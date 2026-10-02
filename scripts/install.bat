@echo off
REM WatchBridge installer for Windows - a step-by-step guide.
REM Usage: put this script next to watchbridge-X.Y.Z.apk, then double-click it.

setlocal EnableDelayedExpansion

set "PACKAGE=com.watchbridge"
set "RELEASES_URL=https://github.com/Irwanripansyahh/watchbridge/releases/latest"
set "SCRIPT_DIR=%~dp0"
set "TMP_OUT=%TEMP%\watchbridge_install.txt"

cls
echo.
echo   WatchBridge installer
echo   Phone notifications, calls and music controls on your Galaxy Watch
echo.
echo   This guide installs WatchBridge on your watch, one step at a time.
echo   It takes about 3 minutes. You'll need:
echo     - your watch, charged, with Wi-Fi on
echo     - this computer on the SAME Wi-Fi network as the watch
echo     - your iPhone nearby (for the last step)
echo.
echo   Type q at any question to stop.
echo.

REM --- Checks -----------------------------------------------------------------

set "APK="
for %%F in ("%SCRIPT_DIR%watchbridge-*.apk") do set "APK=%%~fF"
if not defined APK (
  echo   [X] Couldn't find watchbridge-*.apk next to this script.
  echo       Download it from: %RELEASES_URL%
  echo       and put it in the same folder as install.bat, then run this again.
  goto :end_fail
)
for %%F in ("%APK%") do echo   [OK] Found %%~nxF

where adb >nul 2>nul
if errorlevel 1 (
  echo   [X] 'adb' ^(Android platform-tools^) isn't installed. It's what talks to the watch.
  echo       1. Download "SDK Platform-Tools for Windows" from:
  echo          https://developer.android.com/tools/releases/platform-tools
  echo       2. Unzip it, e.g. to C:\platform-tools
  echo       3. Add that folder to your PATH ^(or put install.bat and the APK inside it^)
  echo       Then run this installer again.
  goto :end_fail
)
echo   [OK] adb is installed

REM A watch that's already connected over Wi-Fi lets us skip pairing
set "CONN_ADDR="
for /f "skip=1 tokens=1,2" %%A in ('adb devices') do (
  if "%%B"=="device" if not defined CONN_ADDR set "CONN_ADDR=%%A"
)
if defined CONN_ADDR (
  echo.
  echo   [OK] A device is already connected: !CONN_ADDR!
  set "ANSWER="
  set /p "ANSWER=  Is that your watch? Skip ahead to installing? [y/N] "
  if /i "!ANSWER:~0,1!"=="y" goto :install
  set "CONN_ADDR="
)

REM --- Step 1: Developer options ----------------------------------------------

echo.
echo   === Step 1 of 6 - Turn on Developer options ===
echo.
echo   On your watch:
echo     1. Open Settings
echo     2. Go to About watch -^> Software information
echo     3. Tap "Software version" about 7 times,
echo        until you see "Developer mode turned on".
echo.
set /p "_=  Press Enter when you're done... "

REM --- Step 2: Wireless debugging ---------------------------------------------

echo.
echo   === Step 2 of 6 - Turn on Wireless debugging ===
echo.
echo   Still on the watch:
echo     1. Go back to Settings and open Developer options (at the bottom of the list)
echo     2. Turn on ADB debugging (confirm if asked)
echo     3. Turn on Wireless debugging (allow it on this network if asked)
echo.
echo   Make sure the watch and this computer are on the same Wi-Fi.
echo.
set /p "_=  Press Enter when you're done... "

REM --- Step 3: Pair -----------------------------------------------------------

echo.
echo   === Step 3 of 6 - Pair this computer with the watch ===
echo.
echo   On the watch, open Wireless debugging and tap "Pair new device".
echo   It shows a 6-digit code and an IP address ^& port, e.g. 192.168.1.23:37123.
echo   Keep that screen open while you type them here.

:pair
echo.
set "PAIR_ADDR="
set /p "PAIR_ADDR=  IP address & port from the pairing screen: "
if /i "!PAIR_ADDR!"=="q" goto :quit
REM A redirect, not a pipe: pipes run in a new cmd where !variables! aren't expanded
>"%TMP_OUT%" echo(!PAIR_ADDR!
findstr /r /x /c:"[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*:[0-9][0-9]*" "%TMP_OUT%" >nul
if errorlevel 1 (
  echo   [!] That doesn't look right. Type it like 192.168.1.23:37123
  goto :pair
)
set "PAIR_CODE="
set /p "PAIR_CODE=  6-digit pairing code: "
if /i "!PAIR_CODE!"=="q" goto :quit
>"%TMP_OUT%" echo(!PAIR_CODE!
findstr /r /x /c:"[0-9][0-9][0-9][0-9][0-9][0-9]" "%TMP_OUT%" >nul
if errorlevel 1 (
  echo   [!] The code is 6 digits, e.g. 482915
  goto :pair
)

echo   Pairing...
adb pair !PAIR_ADDR! !PAIR_CODE! > "%TMP_OUT%" 2>&1
findstr /i "success" "%TMP_OUT%" >nul
if not errorlevel 1 (
  echo   [OK] Paired with the watch
  goto :connect_intro
)
echo   [X] Pairing didn't work. Common reasons:
echo       - the code expired: tap "Pair new device" again for a fresh one
echo       - the port changed: use the one shown on the current pairing screen
echo       - the watch and computer aren't on the same Wi-Fi
set "ANSWER="
set /p "ANSWER=  Try again? [y/N] "
if /i "!ANSWER:~0,1!"=="y" goto :pair
goto :quit

REM --- Step 4: Connect --------------------------------------------------------

:connect_intro
echo.
echo   === Step 4 of 6 - Connect to the watch ===
echo.
echo   On the watch, go back one screen to the main Wireless debugging page.
echo   Under "IP address & port" you'll see an address like 192.168.1.23:41567.
echo   Its port is different from the pairing one - that's expected.

:connect
echo.
set "CONN_ADDR="
set /p "CONN_ADDR=  IP address & port from the main Wireless debugging page: "
if /i "!CONN_ADDR!"=="q" goto :quit
>"%TMP_OUT%" echo(!CONN_ADDR!
findstr /r /x /c:"[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*:[0-9][0-9]*" "%TMP_OUT%" >nul
if errorlevel 1 (
  echo   [!] That doesn't look right. Type it like 192.168.1.23:41567
  goto :connect
)

echo   Connecting...
adb connect !CONN_ADDR! >nul 2>&1
set "STATE="
for /f %%S in ('adb -s !CONN_ADDR! get-state 2^>nul') do set "STATE=%%S"
if "!STATE!"=="device" (
  echo   [OK] Connected to the watch
  goto :install
)
echo   [X] Couldn't connect. Check that:
echo       - you used the address from the main Wireless debugging page (not the pairing one)
echo       - the watch screen is on and Wireless debugging is still enabled
set "ANSWER="
set /p "ANSWER=  Try again? [y/N] "
if /i "!ANSWER:~0,1!"=="y" goto :connect
goto :quit

REM --- Step 5: Install --------------------------------------------------------

:install
echo.
echo   === Step 5 of 6 - Install WatchBridge ===
echo.
echo   Installing on the watch...
adb -s !CONN_ADDR! install -r "%APK%" > "%TMP_OUT%" 2>&1

findstr /c:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" /c:"signatures do not match" "%TMP_OUT%" >nul
if not errorlevel 1 (
  echo.
  echo   [!] A different build of WatchBridge is already on the watch ^(signed with another key^),
  echo       so this one can't be installed over it. Removing it first fixes that;
  echo       you'll pair your iPhone again afterwards.
  set "ANSWER="
  set /p "ANSWER=  Remove the old WatchBridge and install this one? [y/N] "
  if /i not "!ANSWER:~0,1!"=="y" goto :quit
  adb -s !CONN_ADDR! uninstall %PACKAGE% >nul 2>&1
  adb -s !CONN_ADDR! install "%APK%" > "%TMP_OUT%" 2>&1
)

findstr /c:"Success" "%TMP_OUT%" >nul
if errorlevel 1 (
  echo   [X] Installing didn't work:
  type "%TMP_OUT%"
  echo   Keep the watch screen on and try running the installer again.
  goto :end_fail
)
echo   [OK] WatchBridge is installed

REM Lets WatchBridge install its own updates later (Settings -^> Updates).
REM Wear OS has no on-watch screen for this permission.
adb -s !CONN_ADDR! shell appops set %PACKAGE% REQUEST_INSTALL_PACKAGES allow >nul 2>&1
if not errorlevel 1 echo   [OK] Future updates can be installed from the watch itself

REM --- Step 6: Open and pair with the iPhone ----------------------------------

echo.
echo   === Step 6 of 6 - Open WatchBridge and pair your iPhone ===
echo.
adb -s !CONN_ADDR! shell am start -n %PACKAGE%/.MainActivity >nul 2>&1
if not errorlevel 1 echo   [OK] WatchBridge is now open on the watch
echo.
echo   On the WATCH:
echo     1. Tap "Get Started" and "Allow" each permission it asks for
echo     2. Tap "Connect" -^> "Start Pairing"
echo.
echo   On the iPHONE:
echo     3. Open Settings -^> Bluetooth and tap "WatchBridge", then "Pair"
echo     4. Tap the (i) next to WatchBridge and turn on "Share System Notifications"
echo.
echo   The watch shows "Connected" when it's done.
echo.
set /p "_=  Press Enter when you're done... "

REM --- Done -------------------------------------------------------------------

echo.
echo   All set!
echo.
echo   A few tips:
echo     - Add the "Phone connection" and "Music Control" tiles: on the watch,
echo       press and hold a tile, tap + and pick them
echo     - New versions install from the watch: WatchBridge -^> Settings -^> Updates
echo     - You can turn Wireless debugging off again to save battery
echo.
del "%TMP_OUT%" >nul 2>&1
pause
exit /b 0

:quit
echo.
echo   Installer stopped. You can run it again any time.
del "%TMP_OUT%" >nul 2>&1
pause
exit /b 1

:end_fail
echo.
del "%TMP_OUT%" >nul 2>&1
pause
exit /b 1
