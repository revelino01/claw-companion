@echo off
setlocal enabledelayedexpansion

set "BASE_URL=http://127.0.0.1:18790"
if defined AI_COMPANION_URL set "BASE_URL=%AI_COMPANION_URL%"

set "SCRIPT_DIR=%~dp0"
if exist "%SCRIPT_DIR%companion.py" (
    where python >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        python "%SCRIPT_DIR%companion.py" %*
        exit /b %ERRORLEVEL%
    )
    where py >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        py -3 "%SCRIPT_DIR%companion.py" %*
        exit /b %ERRORLEVEL%
    )
)

:: Fallback to built-in curl.exe if python is not installed
set "CMD=%~1"
if "%CMD%"=="" goto usage

if /i "%CMD%"=="health" (
    curl.exe -s "%BASE_URL%/health"
    echo.
    exit /b 0
)
if /i "%CMD%"=="device" (
    curl.exe -s "%BASE_URL%/device"
    echo.
    exit /b 0
)
if /i "%CMD%"=="ui" (
    set "PRUNE=true"
    if not "%~2"=="" set "PRUNE=%~2"
    curl.exe -s "%BASE_URL%/ui?prune=!PRUNE!"
    echo.
    exit /b 0
)
if /i "%CMD%"=="tap" (
    if "%~3"=="" (
        echo Usage: companion tap ^<x^> ^<y^>
        exit /b 1
    )
    curl.exe -s -X POST "%BASE_URL%/tap" -H "Content-Type: application/json" -d "{\"x\":%~2,\"y\":%~3}"
    echo.
    exit /b 0
)
if /i "%CMD%"=="swipe" (
    set "DUR=300"
    if not "%~6"=="" set "DUR=%~6"
    curl.exe -s -X POST "%BASE_URL%/swipe" -H "Content-Type: application/json" -d "{\"x1\":%~2,\"y1\":%~3,\"x2\":%~4,\"y2\":%~5,\"duration\":!DUR!}"
    echo.
    exit /b 0
)
if /i "%CMD%"=="scroll" (
    curl.exe -s -X POST "%BASE_URL%/scroll" -H "Content-Type: application/json" -d "{\"direction\":\"%~2\"}"
    echo.
    exit /b 0
)
if /i "%CMD%"=="press" (
    curl.exe -s -X POST "%BASE_URL%/press" -H "Content-Type: application/json" -d "{\"key\":\"%~2\"}"
    echo.
    exit /b 0
)
if /i "%CMD%"=="type" (
    if "%~3"=="" (
        curl.exe -s -X POST "%BASE_URL%/type" -H "Content-Type: application/json" -d "{\"text\":\"%~2\"}"
    ) else (
        curl.exe -s -X POST "%BASE_URL%/type" -H "Content-Type: application/json" -d "{\"text\":\"%~2\",\"viewId\":\"%~3\"}"
    )
    echo.
    exit /b 0
)
if /i "%CMD%"=="launch" (
    curl.exe -s -X POST "%BASE_URL%/intent" -H "Content-Type: application/json" -d "{\"package\":\"%~2\"}"
    echo.
    exit /b 0
)
if /i "%CMD%"=="screenshot" (
    set "OUT=screenshot.jpg"
    if not "%~2"=="" set "OUT=%~2"
    curl.exe -s "%BASE_URL%/screenshot" -o "!OUT!"
    echo Screenshot saved to !OUT!
    exit /b 0
)
if /i "%CMD%"=="notifications" (
    curl.exe -s "%BASE_URL%/notifications"
    echo.
    exit /b 0
)
if /i "%CMD%"=="clipboard" (
    if "%~2"=="" (
        curl.exe -s "%BASE_URL%/clipboard"
    ) else (
        curl.exe -s -X POST "%BASE_URL%/clipboard" -H "Content-Type: application/json" -d "{\"text\":\"%~2\"}"
    )
    echo.
    exit /b 0
)
if /i "%CMD%"=="stop" (
    curl.exe -s -X POST "%BASE_URL%/stop"
    echo.
    exit /b 0
)

:usage
echo AI Companion CLI (Windows CMD)
echo.
echo Usage: companion ^<command^> [args...]
echo Commands: health, device, ui [prune], tap ^<x^> ^<y^>, swipe ^<x1^> ^<y1^> ^<x2^> ^<y2^>, scroll ^<dir^>, press ^<key^>, type ^<text^>, launch ^<pkg^>, screenshot [file], notifications, clipboard [text], stop
exit /b 1
