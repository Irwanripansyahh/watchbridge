@echo off
REM WatchBridge installer for Windows.
REM Usage: place this script next to a watchbridge-X.Y.Z.apk and double-click (or run from cmd).

setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "APK="
for %%F in ("%SCRIPT_DIR%watchbridge-*.apk") do (
  if not defined APK set "APK=%%~fF"
)

if not defined APK (
  echo Could not find watchbridge-*.apk next to this script.
  echo Download the APK from https://github.com/Irwanripansyahh/watchbridge/releases/latest
  echo and place it in the same folder as install.bat.
  pause
  exit /b 1
)

where adb >nul 2>nul
if errorlevel 1 (
  echo ERROR: 'adb' is not on your PATH.
  echo Install Android platform-tools from:
  echo   https://developer.android.com/tools/releases/platform-tools
  echo Then add the unzipped folder to your PATH.
  pause
  exit /b 1
)

echo.
echo ==^> WatchBridge installer
echo     APK: %APK%
echo.
echo On your watch, do this first:
echo   1. Settings -^> About watch -^> Software -^> tap 'Software version' 7 times
echo   2. Settings -^> Developer options -^> enable 'Wireless debugging'
echo   3. Tap 'Pair new device' -- you'll see an IP:port and a 6-digit code
echo.

set /p PAIR_ADDR="Pairing IP:port (from 'Pair new device' screen): "
set /p PAIR_CODE="6-digit pairing code: "
adb pair %PAIR_ADDR% %PAIR_CODE%
if errorlevel 1 goto :error

echo.
echo Pairing OK. Now go back one screen so you can see the main 'Wireless debugging' page.
set /p CONN_ADDR="Connect IP:port (from main Wireless debugging screen): "

adb connect %CONN_ADDR%
if errorlevel 1 goto :error

echo.
echo Installing...
adb -s %CONN_ADDR% install -r "%APK%"
if errorlevel 1 goto :error
REM Lets WatchBridge install its own updates (Settings -> Updates); Wear OS has no screen for this
adb -s %CONN_ADDR% shell appops set com.watchbridge REQUEST_INSTALL_PACKAGES allow

echo.
echo Done!
echo Open WatchBridge on your watch, grant Bluetooth permissions, then on your iPhone:
echo   Settings -^> Bluetooth -^> look for 'WatchBridge' and tap to pair.
pause
exit /b 0

:error
echo.
echo Install failed. Check the messages above.
pause
exit /b 1
