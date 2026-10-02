#!/usr/bin/env bash
# WatchBridge installer for macOS / Linux — a step-by-step guide.
# Usage: put this script next to watchbridge-X.Y.Z.apk, then run:  ./install.sh

set -uo pipefail

PACKAGE="com.watchbridge"
RELEASES_URL="https://github.com/Irwanripansyahh/watchbridge/releases/latest"
TOTAL_STEPS=6
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Output helpers -----------------------------------------------------------

if [ -t 1 ]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; RESET=$'\033[0m'
else
  BOLD=""; DIM=""; GREEN=""; YELLOW=""; RED=""; RESET=""
fi

say()   { echo "  $*"; }
ok()    { echo "  ${GREEN}✓${RESET} $*"; }
warn()  { echo "  ${YELLOW}!${RESET} $*"; }
fail()  { echo "  ${RED}✗${RESET} $*"; }

step() {
  echo
  echo "${BOLD}━━━ Step $1 of $TOTAL_STEPS · $2 ━━━${RESET}"
  echo
}

wait_enter() {
  echo
  read -r -p "  ${DIM}Press Enter when you're done...${RESET} " _
}

ask_yes_no() {  # ask_yes_no "Question?" → returns 0 for yes
  local answer
  read -r -p "  $1 [y/N] " answer
  [[ "$answer" =~ ^[Yy] ]]
}

quit() {
  echo
  say "Installer stopped. You can run it again any time:  ./install.sh"
  exit 1
}

# --- Welcome ------------------------------------------------------------------

clear 2>/dev/null || true
echo
echo "${BOLD}  WatchBridge installer${RESET}"
echo "  ${DIM}Phone notifications, calls and music controls on your Galaxy Watch${RESET}"
echo
say "This guide installs WatchBridge on your watch, one step at a time."
say "It takes about 3 minutes. You'll need:"
say "  • your watch, charged, with Wi-Fi on"
say "  • this computer on the ${BOLD}same Wi-Fi network${RESET} as the watch"
say "  • your iPhone nearby (for the last step)"
say
say "Type ${BOLD}q${RESET} at any question to stop."

# --- Checks -------------------------------------------------------------------

APK="$(ls -1 "$SCRIPT_DIR"/watchbridge-*.apk 2>/dev/null | tail -1 || true)"
echo
if [ -z "$APK" ]; then
  fail "Couldn't find watchbridge-*.apk next to this script."
  say "Download it from: $RELEASES_URL"
  say "and put it in the same folder as install.sh, then run this again."
  exit 1
fi
ok "Found $(basename "$APK")"

if ! command -v adb >/dev/null 2>&1; then
  fail "'adb' (Android platform-tools) isn't installed. It's what talks to the watch."
  if [[ "$(uname)" == "Darwin" ]] && command -v brew >/dev/null 2>&1; then
    if ask_yes_no "Install it now with Homebrew?"; then
      brew install --cask android-platform-tools || { fail "Homebrew couldn't install it."; exit 1; }
    else
      say "Install it with:  brew install --cask android-platform-tools"
      exit 1
    fi
  elif [[ "$(uname)" == "Darwin" ]]; then
    say "Install Homebrew (https://brew.sh), then run:  brew install --cask android-platform-tools"
    exit 1
  else
    say "Install it with:  sudo apt install adb"
    say "or download it from: https://developer.android.com/tools/releases/platform-tools"
    exit 1
  fi
fi
ok "adb is installed"

# A watch that's already connected over Wi-Fi lets us skip pairing
CONN_ADDR="$(adb devices | awk 'NR>1 && $2=="device" {print $1}' | head -1)"
if [ -n "$CONN_ADDR" ]; then
  echo
  ok "A device is already connected: $CONN_ADDR"
  if ask_yes_no "Is that your watch? Skip ahead to installing?"; then
    SKIP_PAIRING=1
  else
    CONN_ADDR=""
  fi
fi

if [ -z "${SKIP_PAIRING:-}" ]; then

# --- Step 1: Developer options ------------------------------------------------

step 1 "Turn on Developer options"
say "On your watch:"
say "  1. Open ${BOLD}Settings${RESET}"
say "  2. Go to ${BOLD}About watch → Software information${RESET}"
say "  3. Tap ${BOLD}Software version${RESET} about 7 times,"
say "     until you see \"Developer mode turned on\"."
wait_enter

# --- Step 2: Wireless debugging -----------------------------------------------

step 2 "Turn on Wireless debugging"
say "Still on the watch:"
say "  1. Go back to ${BOLD}Settings${RESET} and open ${BOLD}Developer options${RESET}"
say "     (at the bottom of the list)"
say "  2. Turn on ${BOLD}ADB debugging${RESET} (confirm if asked)"
say "  3. Turn on ${BOLD}Wireless debugging${RESET} (allow it on this network if asked)"
say
say "${DIM}Make sure the watch and this computer are on the same Wi-Fi.${RESET}"
wait_enter

# --- Step 3: Pair -------------------------------------------------------------

step 3 "Pair this computer with the watch"
say "On the watch, open ${BOLD}Wireless debugging${RESET} and tap ${BOLD}Pair new device${RESET}."
say "It shows a ${BOLD}6-digit code${RESET} and an ${BOLD}IP address & port${RESET}, e.g. 192.168.1.23:37123."
say "${DIM}Keep that screen open while you type them here.${RESET}"

while true; do
  echo
  read -r -p "  IP address & port from the pairing screen: " PAIR_ADDR
  [[ "$PAIR_ADDR" == "q" ]] && quit
  if [[ ! "$PAIR_ADDR" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]{2,5}$ ]]; then
    warn "That doesn't look right. Type it like 192.168.1.23:37123"
    continue
  fi
  read -r -p "  6-digit pairing code: " PAIR_CODE
  [[ "$PAIR_CODE" == "q" ]] && quit
  if [[ ! "$PAIR_CODE" =~ ^[0-9]{6}$ ]]; then
    warn "The code is 6 digits, e.g. 482915"
    continue
  fi

  say "Pairing..."
  PAIR_OUTPUT="$(adb pair "$PAIR_ADDR" "$PAIR_CODE" 2>&1)"
  if [[ "$PAIR_OUTPUT" == *[Ss]uccess* ]]; then
    ok "Paired with the watch"
    break
  fi
  fail "Pairing didn't work. Common reasons:"
  say "  • the code expired — tap Pair new device again for a fresh one"
  say "  • the port changed — use the one shown on the current pairing screen"
  say "  • the watch and computer aren't on the same Wi-Fi"
  ask_yes_no "Try again?" || quit
done

# --- Step 4: Connect ----------------------------------------------------------

step 4 "Connect to the watch"
say "On the watch, go back one screen to the main ${BOLD}Wireless debugging${RESET} page."
say "Under ${BOLD}IP address & port${RESET} you'll see an address like 192.168.1.23:41567."
say "${DIM}Its port is different from the pairing one — that's expected.${RESET}"

while true; do
  echo
  read -r -p "  IP address & port from the main Wireless debugging page: " CONN_ADDR
  [[ "$CONN_ADDR" == "q" ]] && quit
  if [[ ! "$CONN_ADDR" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]{2,5}$ ]]; then
    warn "That doesn't look right. Type it like 192.168.1.23:41567"
    continue
  fi

  say "Connecting..."
  adb connect "$CONN_ADDR" >/dev/null 2>&1
  if [[ "$(adb -s "$CONN_ADDR" get-state 2>/dev/null)" == "device" ]]; then
    ok "Connected to the watch"
    break
  fi
  fail "Couldn't connect. Check that:"
  say "  • you used the address from the main Wireless debugging page (not the pairing one)"
  say "  • the watch screen is on and Wireless debugging is still enabled"
  ask_yes_no "Try again?" || quit
done

fi  # SKIP_PAIRING

# --- Step 5: Install ----------------------------------------------------------

step 5 "Install WatchBridge"
say "Installing $(basename "$APK") on the watch..."
INSTALL_OUTPUT="$(adb -s "$CONN_ADDR" install -r "$APK" 2>&1)"

if [[ "$INSTALL_OUTPUT" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* || "$INSTALL_OUTPUT" == *"signatures do not match"* ]]; then
  echo
  warn "A different build of WatchBridge is already on the watch (signed with another key),"
  say "  so this one can't be installed over it. Removing it first fixes that;"
  say "  you'll pair your iPhone again afterwards."
  if ask_yes_no "Remove the old WatchBridge and install this one?"; then
    adb -s "$CONN_ADDR" uninstall "$PACKAGE" >/dev/null 2>&1
    INSTALL_OUTPUT="$(adb -s "$CONN_ADDR" install "$APK" 2>&1)"
  else
    quit
  fi
fi

if [[ "$INSTALL_OUTPUT" != *Success* ]]; then
  fail "Installing didn't work:"
  echo "$INSTALL_OUTPUT" | sed 's/^/    /'
  say "Keep the watch screen on and try running the installer again."
  exit 1
fi
ok "WatchBridge is installed"

# Lets WatchBridge install its own updates later (Settings → Updates).
# Wear OS has no on-watch screen for this permission.
adb -s "$CONN_ADDR" shell appops set "$PACKAGE" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 \
  && ok "Future updates can be installed from the watch itself"

# --- Step 6: Open and pair with the iPhone ------------------------------------

step 6 "Open WatchBridge and pair your iPhone"
adb -s "$CONN_ADDR" shell am start -n "$PACKAGE/.MainActivity" >/dev/null 2>&1 \
  && ok "WatchBridge is now open on the watch"
say
say "On the ${BOLD}watch${RESET}:"
say "  1. Tap ${BOLD}Get Started${RESET} and ${BOLD}Allow${RESET} each permission it asks for"
say "  2. Tap ${BOLD}Connect${RESET} → ${BOLD}Start Pairing${RESET}"
say
say "On the ${BOLD}iPhone${RESET}:"
say "  3. Open ${BOLD}Settings → Bluetooth${RESET} and tap ${BOLD}WatchBridge${RESET}, then ${BOLD}Pair${RESET}"
say "  4. Tap the ${BOLD}(i)${RESET} next to WatchBridge and turn on ${BOLD}Share System Notifications${RESET}"
say
say "The watch shows ${BOLD}Connected${RESET} when it's done."
wait_enter

# --- Done ---------------------------------------------------------------------

echo
echo "${BOLD}${GREEN}  All set! 🎉${RESET}"
echo
say "A few tips:"
say "  • Add the ${BOLD}Phone connection${RESET} and ${BOLD}Music Control${RESET} tiles: on the watch,"
say "    press and hold a tile, tap + and pick them"
say "  • New versions install from the watch: ${BOLD}WatchBridge → Settings → Updates${RESET}"
say "  • You can turn ${BOLD}Wireless debugging${RESET} off again to save battery"
echo
