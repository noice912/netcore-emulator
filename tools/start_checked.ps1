<#
.SYNOPSIS
    Checked start-up of the netcore-emulator engine: port pre-flight, schema validation, launch,
    readiness wait, binding verification, Netty event-loop audit and start-up log scan.

.DESCRIPTION
    1. Reads the expected ports from config.json.
    2. Pre-flight: refuses to start if any expected port is already bound and names the owner.
    3. Builds the distribution with gradlew installDist if it is missing (or always with -Rebuild).
    4. Validates the schema directory with core.tools.SchemaCheck.
    5. Starts the engine detached with stdout/stderr captured under logs\ (stdin is an empty file,
       so the engine's console sees end-of-input and keeps running until it is signalled).
    6. Waits for the admin API, then verifies every enabled listener through GET /status AND
       through the operating system's socket table for the engine's process id.
    7. Audits the start-up log for bind failures, errors and warnings; compares the allocated
       Netty event loops with the configured/expected count.
    8. Writes logs\start_checked-<timestamp>.txt.

    Exit codes: 0 healthy, 2 port collision, 3 engine did not become ready, 4 a required listener
    is not bound, 5 schema check failed, 6 build failed.

.PARAMETER ConfigPath       config.json to start with (default: <repo>\config.json)
.PARAMETER InstallDir       installed distribution (default: <repo>\build\install\netcore-emulator)
.PARAMETER TimeoutSeconds   how long to wait for readiness (default 30)
.PARAMETER Rebuild          run gradlew installDist even if the distribution exists
.PARAMETER SkipSchemaCheck  do not validate the schema directory first
.PARAMETER StopAfterCheck   stop the engine again after a successful check (POST /shutdown)

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\start_checked.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\start_checked.ps1 -Rebuild -StopAfterCheck
#>
[CmdletBinding()]
param(
    [string]$ConfigPath = '',
    [string]$InstallDir = '',
    [int]$TimeoutSeconds = 30,
    [switch]$Rebuild,
    [switch]$SkipSchemaCheck,
    [switch]$StopAfterCheck
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ($ConfigPath -eq '') { $ConfigPath = Join-Path $root 'config.json' }
if ($InstallDir -eq '') { $InstallDir = Join-Path $root 'build\install\netcore-emulator' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$reportPath = Join-Path $logDir "start_checked-$stamp.txt"
$script:report = New-Object System.Collections.Generic.List[string]
$script:missing = $false

function Say([string]$text) {
    $script:report.Add($text)
    Write-Host $text
}

function Finish([int]$code) {
    Say ("exit code  : {0}" -f $code)
    $script:report | Set-Content -Path $reportPath -Encoding utf8
    Write-Host "report     : $reportPath"
    exit $code
}

function Get-Setting($object, [string[]]$path, $default) {
    $current = $object
    foreach ($segment in $path) {
        if ($null -eq $current) { return $default }
        $prop = $current.PSObject.Properties[$segment]
        if ($null -eq $prop) { return $default }
        $current = $prop.Value
    }
    if ($null -eq $current) { return $default }
    return $current
}

function Describe-Owner([int]$processId) {
    try {
        $p = Get-Process -Id $processId -ErrorAction Stop
        return ("pid {0} ({1})" -f $processId, $p.ProcessName)
    } catch {
        return "pid $processId"
    }
}

Say "netcore-emulator checked start  $stamp"
Say "repository : $root"
Say "config     : $ConfigPath"
Say "install    : $InstallDir"

# ---- 1. configuration and expected ports -------------------------------------------------------
if (-not (Test-Path -LiteralPath $ConfigPath)) { Say "FAIL: config file not found"; Finish 3 }
$cfg = Get-Content -Raw -LiteralPath $ConfigPath | ConvertFrom-Json
$tcpEnabled   = [bool](Get-Setting $cfg @('network', 'dispatch', 'enabled') $true)
$tcpPort      = [int] (Get-Setting $cfg @('network', 'dispatch', 'port') 22101)
$udpEnabled   = [bool](Get-Setting $cfg @('network', 'gateway', 'enabled') $true)
$udpPort      = [int] (Get-Setting $cfg @('network', 'gateway', 'port') 22102)
$adminEnabled = [bool](Get-Setting $cfg @('admin', 'enabled') $true)
$adminBind    = [string](Get-Setting $cfg @('admin', 'bindAddress') '127.0.0.1')
$adminPort    = [int] (Get-Setting $cfg @('admin', 'port') 22180)
$workerCfg    = [int] (Get-Setting $cfg @('engine', 'workerThreads') 0)
$schemaDir    = Join-Path $root ([string](Get-Setting $cfg @('schemas', 'directory') 'schemas'))
Say ("expected   : TCP dispatch enabled={0} port={1} | UDP gateway enabled={2} port={3} | admin enabled={4} {5}:{6}" -f `
    $tcpEnabled, $tcpPort, $udpEnabled, $udpPort, $adminEnabled, $adminBind, $adminPort)

# ---- 2. pre-flight port collision scan ---------------------------------------------------------
$collision = $false
if ($tcpEnabled -and $tcpPort -gt 0) {
    $owners = @(Get-NetTCPConnection -LocalPort $tcpPort -State Listen -ErrorAction SilentlyContinue)
    if ($owners.Count -gt 0) {
        $collision = $true
        Say ("COLLISION  : TCP {0} is already listening, owner {1}" -f $tcpPort, (Describe-Owner $owners[0].OwningProcess))
    } else {
        Say ("pre-flight : TCP {0} free" -f $tcpPort)
    }
}
if ($udpEnabled -and $udpPort -gt 0) {
    $owners = @(Get-NetUDPEndpoint -LocalPort $udpPort -ErrorAction SilentlyContinue)
    if ($owners.Count -gt 0) {
        $collision = $true
        Say ("COLLISION  : UDP {0} is already bound, owner {1}" -f $udpPort, (Describe-Owner $owners[0].OwningProcess))
    } else {
        Say ("pre-flight : UDP {0} free" -f $udpPort)
    }
}
if ($adminEnabled -and $adminPort -gt 0) {
    $owners = @(Get-NetTCPConnection -LocalPort $adminPort -State Listen -ErrorAction SilentlyContinue)
    if ($owners.Count -gt 0) {
        $collision = $true
        Say ("COLLISION  : admin TCP {0} is already listening, owner {1}" -f $adminPort, (Describe-Owner $owners[0].OwningProcess))
    } else {
        Say ("pre-flight : admin TCP {0} free" -f $adminPort)
    }
}
if ($collision) {
    Say "FAIL: resolve the port collision (stop the owning process or change config.json) and re-run"
    Finish 2
}

# ---- 3. distribution -----------------------------------------------------------------------------
$libDir = Join-Path $InstallDir 'lib'
$jar = $null
if (Test-Path -LiteralPath $libDir) {
    $jar = Get-ChildItem -Path $libDir -Filter 'netcore-emulator-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
}
if ($Rebuild -or $null -eq $jar) {
    Say "build      : gradlew installDist"
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & (Join-Path $root 'gradlew.bat') installDist -q --console=plain 2>&1 | ForEach-Object { Say ("  gradle   : {0}" -f $_) }
    $buildCode = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($buildCode -ne 0) { Say "FAIL: build failed with exit code $buildCode"; Finish 6 }
    $jar = Get-ChildItem -Path $libDir -Filter 'netcore-emulator-*.jar' | Select-Object -First 1
}
Say ("jar        : {0} ({1:N1} KB, built {2})" -f $jar.Name, ($jar.Length / 1KB), $jar.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))
$classpath = Join-Path $libDir '*'
$javaCmd = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $candidate) { $javaCmd = $candidate }
}
if ($null -eq $javaCmd) { $javaCmd = (Get-Command java -ErrorAction Stop).Source }
Say "java       : $javaCmd"

# ---- 4. schema validation ------------------------------------------------------------------------
if (-not $SkipSchemaCheck) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $checkOutput = & $javaCmd -cp $classpath core.tools.SchemaCheck $schemaDir 2>&1
    $checkCode = $LASTEXITCODE
    $ErrorActionPreference = $previous
    foreach ($line in $checkOutput) { Say ("  schema   : {0}" -f $line) }
    if ($checkCode -ne 0) { Say "FAIL: schema check failed"; Finish 5 }
}

# ---- 5. launch -----------------------------------------------------------------------------------
$stdoutPath = Join-Path $logDir "engine-$stamp.out"
$stderrPath = Join-Path $logDir "engine-$stamp.err"
$stdinPath  = Join-Path $logDir 'empty-stdin.txt'
if (-not (Test-Path -LiteralPath $stdinPath)) { New-Item -ItemType File -Path $stdinPath | Out-Null }
$arguments = @('-cp', ('"' + $classpath + '"'), 'core.engine.ServerEngine', ('"' + $ConfigPath + '"'))
$proc = Start-Process -FilePath $javaCmd -ArgumentList $arguments -WorkingDirectory $root `
    -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -RedirectStandardInput $stdinPath `
    -NoNewWindow -PassThru
Say ("launch     : pid {0} at {1}" -f $proc.Id, (Get-Date -Format 'HH:mm:ss.fff'))

# ---- 6. readiness --------------------------------------------------------------------------------
$status = $null
$statusUrl = "http://{0}:{1}/status" -f $adminBind, $adminPort
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    if ($proc.HasExited) { break }
    if ($adminEnabled) {
        try {
            $status = Invoke-RestMethod -Uri $statusUrl -TimeoutSec 2
            break
        } catch {
            Start-Sleep -Milliseconds 250
        }
    } else {
        $bound = @(Get-NetTCPConnection -OwningProcess $proc.Id -State Listen -ErrorAction SilentlyContinue)
        if ($bound.Count -gt 0) { break }
        Start-Sleep -Milliseconds 250
    }
}
if ($proc.HasExited) {
    Say ("FAIL: engine exited early with code {0}" -f $proc.ExitCode)
    if (Test-Path -LiteralPath $stderrPath) { Get-Content -LiteralPath $stderrPath -Tail 20 | ForEach-Object { Say "  stderr   : $_" } }
    if (Test-Path -LiteralPath $stdoutPath) { Get-Content -LiteralPath $stdoutPath -Tail 20 | ForEach-Object { Say "  stdout   : $_" } }
    Finish 3
}
if ($adminEnabled -and $null -eq $status) {
    Say "FAIL: admin API did not answer within $TimeoutSeconds s (engine still running as pid $($proc.Id))"
    Finish 3
}
Say ("ready      : {0:N1} s after launch" -f ((Get-Date) - $proc.StartTime).TotalSeconds)

# ---- 7. binding verification ---------------------------------------------------------------------
$osTcp = @(Get-NetTCPConnection -OwningProcess $proc.Id -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty LocalPort | Sort-Object -Unique)
$osUdp = @(Get-NetUDPEndpoint -OwningProcess $proc.Id -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty LocalPort | Sort-Object -Unique)
Say ("os sockets : TCP listening [{0}]  UDP bound [{1}]" -f ($osTcp -join ','), ($osUdp -join ','))

function Check-Listener([string]$name, [bool]$enabled, [int]$expectedPort, $view, [int[]]$osPorts) {
    if (-not $enabled) {
        Say ("{0,-11}: disabled by configuration" -f $name)
        return
    }
    $running = $false
    $port = -1
    if ($null -ne $view) {
        $running = [bool]$view.running
        $port = [int]$view.port
    }
    $osSeen = ($osPorts -contains $port)
    $ok = $running -and $osSeen -and ($expectedPort -eq 0 -or $port -eq $expectedPort)
    $verdict = 'MISSING'
    if ($ok) { $verdict = 'BOUND  ' }
    Say ("{0,-11}: {1} engine-running={2} port={3} expected={4} os-socket={5}" -f $name, $verdict, $running, $port, $expectedPort, $osSeen)
    if (-not $ok) { $script:missing = $true }
}

if ($null -ne $status) {
    Check-Listener 'TCP dispatch' $tcpEnabled $tcpPort $status.dispatch $osTcp
    Check-Listener 'UDP gateway' $udpEnabled $udpPort $status.gateway $osUdp
    Check-Listener 'admin API' $adminEnabled $adminPort $status.admin $osTcp
    $j = $status.jvm
    $expectedWorkers = 2 * [int]$j.availableProcessors
    if ($workerCfg -gt 0) { $expectedWorkers = $workerCfg }
    $loopVerdict = 'ANOMALY'
    if ([int]$j.workerLoops -eq $expectedWorkers -and [int]$j.bossLoops -eq 1) { $loopVerdict = 'OK' }
    Say ("event loops: boss={0} workers={1} expected boss=1 workers={2} -> {3}" -f $j.bossLoops, $j.workerLoops, $expectedWorkers, $loopVerdict)
    Say ("jvm        : heap {0:N1} MiB used / {1:N1} committed / {2:N1} max, non-heap {3:N1} MiB, netty direct {4:N2} MiB, threads {5}, cores {6}" -f `
        ($j.heapUsedBytes / 1MB), ($j.heapCommittedBytes / 1MB), ($j.heapMaxBytes / 1MB), ($j.nonHeapUsedBytes / 1MB), ($j.nettyDirectBytes / 1MB), $j.threadCount, $j.availableProcessors)
    Say ("engine     : {0} v{1}, registry {2} opcode(s), schemas {3} packet(s)/{4} rule(s), tick {5} tps, plugins {6}" -f `
        $status.name, $status.version, $status.registeredOpcodes, $status.schemas.packets, $status.schemas.rules, $status.tick.ticksPerSecond, @($status.plugins).Count)
} else {
    if ($tcpEnabled) {
        $ok = ($osTcp -contains $tcpPort)
        $verdict = 'MISSING'
        if ($ok) { $verdict = 'BOUND' }
        Say ("TCP dispatch: {0} (os socket table only; admin API disabled)" -f $verdict)
        if (-not $ok) { $script:missing = $true }
    }
    if ($udpEnabled) {
        $ok = ($osUdp -contains $udpPort)
        $verdict = 'MISSING'
        if ($ok) { $verdict = 'BOUND' }
        Say ("UDP gateway : {0} (os socket table only; admin API disabled)" -f $verdict)
        if (-not $ok) { $script:missing = $true }
    }
}

# ---- 8. start-up log audit -----------------------------------------------------------------------
Start-Sleep -Milliseconds 500
$logLines = @()
if (Test-Path -LiteralPath $stdoutPath) { $logLines += @(Get-Content -LiteralPath $stdoutPath) }
if (Test-Path -LiteralPath $stderrPath) { $logLines += @(Get-Content -LiteralPath $stderrPath) }
$bindFailures = @($logLines | Where-Object { $_ -match 'could not bind|Address already in use|BindException' })
$errorLines   = @($logLines | Where-Object { $_ -match ' ERROR ' })
$warnLines    = @($logLines | Where-Object { $_ -match ' WARN ' })
$loopLine  = $logLines | Where-Object { $_ -match 'event loops allocated' } | Select-Object -First 1
$readyLine = $logLines | Where-Object { $_ -match ' ready: ' } | Select-Object -First 1
Say ("log scan   : {0} line(s); bind failures {1}, errors {2}, warnings {3}" -f $logLines.Count, $bindFailures.Count, $errorLines.Count, $warnLines.Count)
if ($loopLine)  { Say ("           : {0}" -f ($loopLine -replace '^.*? - ', '')) }
if ($readyLine) { Say ("           : {0}" -f ($readyLine -replace '^.*? - ', '')) }
foreach ($line in @($bindFailures + $errorLines + $warnLines | Select-Object -First 20)) { Say ("  flagged  : {0}" -f $line) }
Say ("log files  : {0}" -f $stdoutPath)

# ---- result --------------------------------------------------------------------------------------
if ($script:missing -or $bindFailures.Count -gt 0) {
    Say "RESULT     : UNHEALTHY - a required listener is not bound (engine left running as pid $($proc.Id))"
    Finish 4
}
Say ("RESULT     : HEALTHY - engine pid {0} is serving" -f $proc.Id)
if ($StopAfterCheck) {
    if ($adminEnabled) {
        try { Invoke-RestMethod -Method Post -Uri ("http://{0}:{1}/shutdown" -f $adminBind, $adminPort) -TimeoutSec 5 | Out-Null } catch { }
    } else {
        Stop-Process -Id $proc.Id -Force
    }
    $proc.WaitForExit(20000) | Out-Null
    Say ("stopped    : exit code {0}" -f $proc.ExitCode)
}
Finish 0
