#!/usr/bin/env bash
# WatchBridge installer for macOS / Linux — a step-by-step guide.
#
# Downloads the latest WatchBridge from GitHub Releases and installs it on the watch.
# Run it straight from GitHub:
#   bash <(curl -fsSL https://raw.githubusercontent.com/Irwanripansyahh/watchbridge/master/scripts/install.sh)
# or download it and run:  ./install.sh
# To install a specific version instead, put its watchbridge-X.Y.Z.apk next to this script.

set -uo pipefail

PACKAGE="com.watchbridge"
REPO="Irwanripansyahh/watchbridge"
RELEASES_URL="https://github.com/$REPO/releases/latest"
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

ask_yes_no() {  # ask_yes_no "Question?" → returns 0 for yes (default no)
  local answer
  read -r -p "  $1 [y/N] " answer
  [[ "$answer" =~ ^[Yy] ]]
}

ask_yes() {  # ask_yes "Question?" → returns 0 for yes (default yes)
  local answer
  read -r -p "  $1 [Y/n] " answer
  [[ ! "$answer" =~ ^[Nn] ]]
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
say "This guide downloads the latest WatchBridge and installs it on your watch,"
say "one step at a time."
say "It takes about 3 minutes. You'll need:"
say "  • your watch, charged, with Wi-Fi on"
say "  • this computer on the ${BOLD}same Wi-Fi network${RESET} as the watch"
say "  • your iPhone nearby (for the last step)"
say
say "Type ${BOLD}q${RESET} at any question to stop."

# --- Checks -------------------------------------------------------------------

# An APK next to the script wins (to install a specific version); otherwise get the latest
APK="$(ls -1 "$SCRIPT_DIR"/watchbridge-*.apk 2>/dev/null | tail -1 || true)"
echo
if [ -n "$APK" ]; then
  ok "Using $(basename "$APK") from this folder"
else
  say "Getting the latest WatchBridge from GitHub..."
  if ! command -v curl >/dev/null 2>&1; then
    fail "'curl' is needed to download WatchBridge."
    say "Or download the .apk from $RELEASES_URL, put it next to this script and run it again."
    exit 1
  fi
  RELEASE_JSON="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null || true)"
  APK_URL="$(printf '%s' "$RELEASE_JSON" \
    | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 \
    | sed 's/.*"\(https[^"]*\)"$/\1/')"
  if [ -z "$APK_URL" ]; then
    fail "Couldn't find WatchBridge on GitHub."
    say "Check the internet connection, or download the .apk from $RELEASES_URL,"
    say "put it next to this script and run it again."
    exit 1
  fi
  APK="$(mktemp -d)/$(basename "$APK_URL")"
  if ! curl -fL --progress-bar -o "$APK" "$APK_URL"; then
    fail "The download didn't finish. Check the internet connection and run this again."
    exit 1
  fi
  ok "Downloaded $(basename "$APK")"
fi

# adb (Android platform-tools) is what talks to the watch. If it's missing, offer Google's
# official download into a WatchBridge folder (no Homebrew, sudo or admin rights needed);
# later runs reuse it.
TOOLS_DIR="$HOME/.watchbridge"
[ -x "$TOOLS_DIR/platform-tools/adb" ] && export PATH="$TOOLS_DIR/platform-tools:$PATH"

if ! command -v adb >/dev/null 2>&1; then
  warn "'adb' (Android platform-tools) isn't installed yet. It's what talks to the watch."
  case "$(uname -s)" in
    Darwin) PT_OS="darwin" ;;
    Linux)  PT_OS="linux" ;;
    *)      PT_OS="" ;;
  esac
  if [ -n "$PT_OS" ] && command -v curl >/dev/null 2>&1 \
    && ask_yes "Download it now from Google (about 10-15 MB)?"; then
    say "${DIM}Saved in $TOOLS_DIR, only used by this installer."
    say "Downloading it means you accept the Android SDK terms: https://developer.android.com/studio/terms${RESET}"
    PT_ZIP="$(mktemp -d)/platform-tools.zip"
    if curl -fL --progress-bar -o "$PT_ZIP" \
      "https://dl.google.com/android/repository/platform-tools-latest-$PT_OS.zip"; then
      mkdir -p "$TOOLS_DIR"
      if command -v unzip >/dev/null 2>&1; then
        unzip -qo "$PT_ZIP" -d "$TOOLS_DIR"
      elif command -v python3 >/dev/null 2>&1; then
        # Python's unzip drops the executable bit
        python3 -m zipfile -e "$PT_ZIP" "$TOOLS_DIR" && chmod +x "$TOOLS_DIR/platform-tools/adb"
      fi
      rm -f "$PT_ZIP"
      [ -x "$TOOLS_DIR/platform-tools/adb" ] && export PATH="$TOOLS_DIR/platform-tools:$PATH"
    fi
  fi
fi

if ! command -v adb >/dev/null 2>&1; then
  fail "adb is still missing. Install it, then run this installer again:"
  if [[ "$(uname)" == "Darwin" ]]; then
    say "  brew install --cask android-platform-tools"
  else
    say "  sudo apt install adb"
  fi
  say "  or download it from: https://developer.android.com/tools/releases/platform-tools"
  exit 1
fi
ok "adb is ready"

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
