@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem ===========================================================================
rem  launch_client_sandbox.bat
rem  Starts the trimmed client from the isolated asset sandbox and refuses to
rem  touch the live launcher installation.
rem
rem  usage: launch_client_sandbox.bat [--sandbox DIR] [--exe NAME^|PATH] [--workspace DIR]
rem                                   [--require-engine] [--verify-size] [--dry-run]
rem                                   [-- client arguments...]
rem
rem    --sandbox DIR     isolated asset repository (default C:\Genshin_Isolated_Sandbox_7.1)
rem    --exe NAME^|PATH   executable to start; default: the first of GenshinImpact.exe /
rem                      YuanShen.exe found in the sandbox (or in the linked workspace)
rem    --workspace DIR   trimmed client workspace kept outside the sandbox; it is linked INTO
rem                      the sandbox as ^<sandbox^>\client with a directory junction (no copy)
rem                      and launched from there, so the client runs against the sandbox assets
rem    --require-engine  refuse to start unless the emulator admin API answers on 127.0.0.1:22180
rem    --verify-size     require at least 2.5 GB of client files (guards against a half-copied trim)
rem    --dry-run         print every decision, start nothing, create nothing
rem
rem  exit codes: 0 started, 1 bad option, 2 sandbox/workspace missing, 3 executable missing,
rem              4 refused (outside the sandbox or inside a live install), 5 engine not running,
rem              6 junction failed, 7 size check failed
rem ===========================================================================

set "SANDBOX=C:\Genshin_Isolated_Sandbox_7.1"
set "EXE="
set "WORKSPACE="
set "REQUIRE_ENGINE=0"
set "VERIFY_SIZE=0"
set "DRY_RUN=0"
set "ADMIN_URL=http://127.0.0.1:22180/status"
set "CLIENT_ARGS="
set "CLIENT="
set "PF64=%ProgramFiles%"
set "PF86=%ProgramFiles(x86)%"
set "LAPPS=%LocalAppData%\Programs"

rem Option parsing uses labels instead of ( ... ) blocks on purpose: a goto that leaves a
rem parenthesized block confuses cmd.exe's block bookkeeping, and after enough of them a later
rem "exit /b N" inside a block stops propagating N as the process exit code.
:parse
if "%~1"=="" goto :parsed
if /I "%~1"=="--sandbox"        goto :opt_sandbox
if /I "%~1"=="--exe"            goto :opt_exe
if /I "%~1"=="--workspace"      goto :opt_workspace
if /I "%~1"=="--require-engine" goto :opt_require_engine
if /I "%~1"=="--verify-size"    goto :opt_verify_size
if /I "%~1"=="--dry-run"        goto :opt_dry_run
if "%~1"=="--"                  goto :opt_passthrough
echo [FAIL] unknown option: %~1
exit /b 1
:opt_sandbox
set "SANDBOX=%~2"
shift
shift
goto :parse
:opt_exe
set "EXE=%~2"
shift
shift
goto :parse
:opt_workspace
set "WORKSPACE=%~2"
shift
shift
goto :parse
:opt_require_engine
set "REQUIRE_ENGINE=1"
shift
goto :parse
:opt_verify_size
set "VERIFY_SIZE=1"
shift
goto :parse
:opt_dry_run
set "DRY_RUN=1"
shift
goto :parse
:opt_passthrough
shift
:collect
if "%~1"=="" goto :parsed
set "CLIENT_ARGS=!CLIENT_ARGS! %1"
shift
goto :collect
:parsed

rem --- sandbox ----------------------------------------------------------------
if not exist "%SANDBOX%\" (
    echo [FAIL] sandbox not found: %SANDBOX%
    set "RC=2" & goto :die
)
for %%I in ("%SANDBOX%") do set "SANDBOX=%%~fI"
if "%SANDBOX:~-1%"=="\" set "SANDBOX=%SANDBOX:~0,-1%"
set "SEARCH=%SANDBOX%"
set "ALT_ROOT="

rem --- optional workspace junction --------------------------------------------
if defined WORKSPACE (
    if not exist "!WORKSPACE!\" (
        echo [FAIL] workspace not found: !WORKSPACE!
        set "RC=2" & goto :die
    )
    for %%I in ("!WORKSPACE!") do set "WORKSPACE=%%~fI"
    if "!WORKSPACE:~-1!"=="\" set "WORKSPACE=!WORKSPACE:~0,-1!"
    set "LINK=%SANDBOX%\client"
    if exist "!LINK!\" (
        echo [INFO] reusing existing link !LINK!
        set "SEARCH=!LINK!"
    ) else if "%DRY_RUN%"=="1" (
        echo [DRY-RUN] would create junction !LINK! -^> !WORKSPACE!
        set "SEARCH=!WORKSPACE!"
        set "ALT_ROOT=!WORKSPACE!"
    ) else (
        mklink /J "!LINK!" "!WORKSPACE!" >nul
        if errorlevel 1 (
            echo [FAIL] could not create junction !LINK! -^> !WORKSPACE!
            set "RC=6" & goto :die
        )
        echo [OK] linked !LINK! -^> !WORKSPACE!
        set "SEARCH=!LINK!"
    )
)

rem --- executable -------------------------------------------------------------
if defined EXE (
    if exist "!EXE!" (
        set "CLIENT=!EXE!"
    ) else if exist "!SEARCH!\!EXE!" (
        set "CLIENT=!SEARCH!\!EXE!"
    ) else (
        echo [FAIL] executable not found: !EXE! ^(looked in !SEARCH!^)
        set "RC=3" & goto :die
    )
) else (
    for %%E in (GenshinImpact.exe YuanShen.exe) do (
        if not defined CLIENT if exist "!SEARCH!\%%E" set "CLIENT=!SEARCH!\%%E"
    )
    if not defined CLIENT (
        echo [FAIL] no client executable ^(GenshinImpact.exe / YuanShen.exe^) in !SEARCH!
        set "RC=3" & goto :die
    )
)
for %%I in ("!CLIENT!") do (
    set "CLIENT=%%~fI"
    set "CLIENT_DIR=%%~dpI"
)
if "!CLIENT_DIR:~-1!"=="\" set "CLIENT_DIR=!CLIENT_DIR:~0,-1!"

rem --- containment: inside the sandbox, never a live install -----------------
call :inside "!CLIENT!" "%SANDBOX%"
set "ALLOWED=!INSIDE!"
if defined ALT_ROOT if "!ALLOWED!"=="0" (
    call :inside "!CLIENT!" "!ALT_ROOT!"
    set "ALLOWED=!INSIDE!"
)
if "!ALLOWED!"=="0" (
    echo [FAIL] refusing: !CLIENT! is outside the sandbox %SANDBOX%
    set "RC=4" & goto :die
)
for %%P in ("%PF64%" "%PF86%" "%LAPPS%") do (
    if not "%%~P"=="" (
        call :inside "!CLIENT!" "%%~P"
        if "!INSIDE!"=="1" (
            echo [FAIL] refusing: !CLIENT! is inside %%~P ^(live install location^)
            set "RC=4" & goto :die
        )
    )
)
for %%M in (HoYoPlay "Genshin Impact" "miHoYo Launcher") do (
    set "MARK=\%%~M\"
    set "STRIPPED=!CLIENT:%%~M=!"
    if not "!STRIPPED!"=="!CLIENT!" (
        echo [FAIL] refusing: !CLIENT! looks like the live launcher installation ^(contains %%~M^)
        set "RC=4" & goto :die
    )
)

rem --- sanity: data folder, size, engine -------------------------------------
set "DATA_DIR="
for /d %%D in ("!CLIENT_DIR!\*_Data") do set "DATA_DIR=%%~fD"
if not defined DATA_DIR echo [WARN] no *_Data folder next to the executable
if "%VERIFY_SIZE%"=="1" (
    for /f "usebackq delims=" %%S in (`powershell -NoProfile -Command "$s=(Get-ChildItem -LiteralPath '!CLIENT_DIR!' -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum; [int][math]::Floor($s/100MB)"`) do set "SIZE100=%%S"
    if not defined SIZE100 set "SIZE100=0"
    if !SIZE100! LSS 25 (
        echo [FAIL] client workspace holds only ~!SIZE100!00 MB; expected the ~3 GB trimmed build
        set "RC=7" & goto :die
    )
    echo [OK] client workspace size ~!SIZE100!00 MB
)
if "%REQUIRE_ENGINE%"=="1" (
    powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri '%ADMIN_URL%'; if ($r.StatusCode -ne 200) { exit 1 } } catch { exit 1 }" >nul 2>&1
    if errorlevel 1 (
        echo [FAIL] emulator admin API is not answering at %ADMIN_URL%; start the engine first
        set "RC=5" & goto :die
    )
    echo [OK] emulator admin API is up
)

rem --- plan, log, launch ------------------------------------------------------
echo [PLAN] exe     : !CLIENT!
echo [PLAN] cwd     : !CLIENT_DIR!
echo [PLAN] sandbox : %SANDBOX%
echo [PLAN] args    :!CLIENT_ARGS!
set "LOG=%SANDBOX%\launch.log"
>>"%LOG%" echo %DATE% %TIME% exe="!CLIENT!" cwd="!CLIENT_DIR!" dry-run=%DRY_RUN% args=!CLIENT_ARGS!
if "%DRY_RUN%"=="1" (
    echo [DRY-RUN] nothing started
    exit /b 0
)
start "" /D "!CLIENT_DIR!" "!CLIENT!"!CLIENT_ARGS!
if errorlevel 1 (
    echo [FAIL] start command failed
    set "RC=3" & goto :die
)
echo [OK] client started from the sandbox
exit /b 0

rem --- :die -> exits with RC from statement level. An "exit /b N" inside a ( ... ) block
rem whose command line contains quoted parentheses (the powershell one-liners) sets ERRORLEVEL
rem but cmd.exe then returns 0 as the process exit code; leaving the block first avoids that.
:die
exit /b %RC%

rem --- :inside PATH BASE -> sets INSIDE=1 when PATH is BASE or below it ------
rem Walks up the directory chain comparing each parent (always with a trailing
rem backslash) against BASE\. Stops at the drive root: the parent of "C:\" is
rem "C:\" and a bare "C:" would resolve to the current directory, so the walk
rem ends as soon as the next candidate is a drive letter.
rem Every goto here is at statement level, never inside ( ... ), for the reason given above.
:inside
setlocal EnableDelayedExpansion
set "probe=%~1"
set "base=%~2"
if "!base:~-1!"=="\" set "base=!base:~0,-1!"
set "result=0"
:inside_loop
for %%I in ("!probe!") do set "parent=%%~dpI"
if /I "!parent!"=="!base!\" set "result=1"
set "next=!parent:~0,-1!"
if "!result!"=="1" goto :inside_done
if /I "!next!"=="!probe!" goto :inside_done
if "!next:~-1!"==":" goto :inside_done
set "probe=!next!"
goto :inside_loop
:inside_done
endlocal & set "INSIDE=%result%"
exit /b 0
