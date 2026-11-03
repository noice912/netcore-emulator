<#
.SYNOPSIS
    Safe version migration for the netcore-emulator checkout: integrates a new version from the
    remote without ever losing local, unpushed commits.

.DESCRIPTION
    1. Refuses to run on a dirty working tree unless -AutoStash is given (then stashes, including
       untracked files, and restores at the end).
    2. With -ProtectCommit <hash>: verifies the commit exists and is reachable from HEAD before the
       integration and again after it; aborts and restores if it would be lost. -Rebase is refused
       together with -ProtectCommit because a rebase rewrites the hash (override with -AllowRewrite).
    3. Lists every unpushed commit (HEAD not on the upstream) and creates a backup branch
       backup/pre-upgrade-<stamp> plus a tag pre-upgrade-<stamp> at the current HEAD.
    4. Fetches, then merges the target ref with --no-ff (default) or rebases onto it (-Rebase).
       A conflict aborts the integration and leaves the tree exactly as it was.
    5. Runs gradlew build installDist; with -RollbackOnBuildFailure a failed build resets the branch
       to the backup.

    Exit codes: 0 upgraded, 2 dirty working tree, 3 protected commit missing/unreachable/refused,
    4 integration conflict (restored), 5 build failed, 6 git error.

.PARAMETER Ref                      ref to integrate (default origin/main)
.PARAMETER ProtectCommit            commit hash that must survive the migration
.PARAMETER Rebase                   rebase onto the ref instead of merging it
.PARAMETER AllowRewrite             allow -Rebase together with -ProtectCommit
.PARAMETER AutoStash                stash uncommitted changes and restore them afterwards
.PARAMETER NoBuild                  skip the Gradle build
.PARAMETER RollbackOnBuildFailure   reset to the backup branch if the build fails

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\upgrade_build.ps1 -ProtectCommit 3ecf03f
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\upgrade_build.ps1 -Ref origin/release/2.1 -AutoStash -RollbackOnBuildFailure
#>
[CmdletBinding()]
param(
    [string]$Ref = 'origin/main',
    [string]$ProtectCommit = '',
    [switch]$Rebase,
    [switch]$AllowRewrite,
    [switch]$AutoStash,
    [switch]$NoBuild,
    [switch]$RollbackOnBuildFailure
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$script:LastGitCode = 0

function Say([string]$text) { Write-Host $text }

function Invoke-Git {
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [switch]$AllowFailure)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = & git @Arguments
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    $script:LastGitCode = $code
    if ($code -ne 0 -and -not $AllowFailure) {
        throw ("git {0} failed with exit code {1}" -f ($Arguments -join ' '), $code)
    }
    return $output
}

function Test-Commit([string]$hash) {
    Invoke-Git @('cat-file', '-e', ($hash + '^{commit}')) -AllowFailure | Out-Null
    return ($script:LastGitCode -eq 0)
}

function Test-Ancestor([string]$hash, [string]$of) {
    Invoke-Git @('merge-base', '--is-ancestor', $hash, $of) -AllowFailure | Out-Null
    return ($script:LastGitCode -eq 0)
}

function Restore-Stash([bool]$stashed) {
    if ($stashed) {
        Invoke-Git @('stash', 'pop') -AllowFailure | Out-Null
        if ($script:LastGitCode -eq 0) { Say "stash      : restored" } else { Say "stash      : could not pop automatically; run 'git stash pop' manually" }
    }
}

try {
    Invoke-Git @('rev-parse', '--is-inside-work-tree') | Out-Null
    $branch = @(Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD'))[0]
    $head = @(Invoke-Git @('rev-parse', '--short=12', 'HEAD'))[0]
    Say "upgrade_build $stamp"
    Say "repository : $root"
    Say "branch     : $branch at $head, target $Ref"

    # ---- 1. working tree ---------------------------------------------------------------------
    $dirty = @(Invoke-Git @('status', '--porcelain'))
    $stashed = $false
    if ($dirty.Count -gt 0) {
        if (-not $AutoStash) {
            Say ("FAIL: the working tree has {0} uncommitted change(s); commit them or re-run with -AutoStash" -f $dirty.Count)
            $dirty | ForEach-Object { Say "  $_" }
            exit 2
        }
        Invoke-Git @('stash', 'push', '--include-untracked', '-m', "upgrade_build $stamp") | Out-Null
        $stashed = $true
        Say ("stash      : {0} change(s) stashed" -f $dirty.Count)
    }

    # ---- 2. protected commit -----------------------------------------------------------------
    if ($ProtectCommit -ne '') {
        if (-not (Test-Commit $ProtectCommit)) {
            Say "FAIL: commit $ProtectCommit does not exist in this repository (git cat-file -e); nothing was changed"
            Restore-Stash $stashed
            exit 3
        }
        if (-not (Test-Ancestor $ProtectCommit 'HEAD')) {
            Say "FAIL: commit $ProtectCommit exists but is not reachable from HEAD; check out the branch that contains it first"
            Restore-Stash $stashed
            exit 3
        }
        $full = @(Invoke-Git @('rev-parse', $ProtectCommit))[0]
        Say "protected  : $full is reachable from HEAD"
        if ($Rebase -and -not $AllowRewrite) {
            Say "FAIL: -Rebase rewrites commit hashes, so $ProtectCommit would not survive; use the default merge or pass -AllowRewrite"
            Restore-Stash $stashed
            exit 3
        }
    }

    # ---- 3. unpushed commits and backup ------------------------------------------------------
    $upstream = @(Invoke-Git @('rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}') -AllowFailure)
    if ($script:LastGitCode -eq 0) {
        $unpushed = @(Invoke-Git @('log', '--oneline', '@{u}..HEAD'))
        Say ("unpushed   : {0} commit(s) ahead of {1}" -f $unpushed.Count, $upstream[0])
        $unpushed | ForEach-Object { Say "  $_" }
    } else {
        Say "unpushed   : no upstream configured; every local commit is treated as unpushed"
    }
    $backupBranch = "backup/pre-upgrade-$stamp"
    Invoke-Git @('branch', $backupBranch, 'HEAD') | Out-Null
    Invoke-Git @('tag', "pre-upgrade-$stamp", 'HEAD') | Out-Null
    Say "backup     : branch $backupBranch and tag pre-upgrade-$stamp at $head"

    # ---- 4. fetch and integrate --------------------------------------------------------------
    Invoke-Git @('fetch', '--prune', 'origin') | Out-Null
    if (-not (Test-Commit $Ref)) {
        Say "FAIL: ref $Ref does not exist after fetch"
        Restore-Stash $stashed
        exit 6
    }
    if (Test-Ancestor $Ref 'HEAD') {
        Say "integrate  : HEAD already contains $Ref; nothing to integrate"
    } else {
        $mode = 'merge'
        if ($Rebase) { $mode = 'rebase' }
        Say "integrate  : $mode $Ref"
        if ($Rebase) {
            Invoke-Git @('rebase', $Ref) -AllowFailure | Out-Null
        } else {
            Invoke-Git @('merge', '--no-ff', '--no-edit', $Ref) -AllowFailure | Out-Null
        }
        if ($script:LastGitCode -ne 0) {
            $conflicts = @(Invoke-Git @('diff', '--name-only', '--diff-filter=U') -AllowFailure)
            Say "FAIL: $mode stopped with conflicts in:"
            $conflicts | ForEach-Object { Say "  $_" }
            if ($Rebase) { Invoke-Git @('rebase', '--abort') -AllowFailure | Out-Null } else { Invoke-Git @('merge', '--abort') -AllowFailure | Out-Null }
            Say "restored   : working tree back at $head (backup $backupBranch kept)"
            Restore-Stash $stashed
            exit 4
        }
    }
    $newHead = @(Invoke-Git @('rev-parse', '--short=12', 'HEAD'))[0]
    Say "head now   : $newHead"

    # ---- 5. post-integration check -----------------------------------------------------------
    if ($ProtectCommit -ne '') {
        if (Test-Ancestor $ProtectCommit 'HEAD') {
            Say "protected  : $ProtectCommit is still reachable after the integration"
        } else {
            Say "FAIL: $ProtectCommit is no longer reachable; resetting to $backupBranch"
            Invoke-Git @('reset', '--hard', $backupBranch) | Out-Null
            Restore-Stash $stashed
            exit 3
        }
    }

    # ---- 6. build ----------------------------------------------------------------------------
    if (-not $NoBuild) {
        Say "build      : gradlew build installDist"
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & (Join-Path $root 'gradlew.bat') build installDist --console=plain -q
        $buildCode = $LASTEXITCODE
        $ErrorActionPreference = $previous
        if ($buildCode -ne 0) {
            Say "FAIL: build failed with exit code $buildCode"
            if ($RollbackOnBuildFailure) {
                Invoke-Git @('reset', '--hard', $backupBranch) | Out-Null
                Say "rolled back: branch reset to $backupBranch"
            } else {
                Say "left as integrated; roll back manually with: git reset --hard $backupBranch"
            }
            Restore-Stash $stashed
            exit 5
        }
        Say "build      : OK -> build\install\netcore-emulator"
    }

    Restore-Stash $stashed
    Say "RESULT     : upgraded $head -> $newHead on $branch; backup $backupBranch (delete it once you are satisfied)"
    exit 0
} catch {
    Say ("FAIL: {0}" -f $_.Exception.Message)
    exit 6
}
