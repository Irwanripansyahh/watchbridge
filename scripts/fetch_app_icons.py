#!/usr/bin/env python3
"""
Downloads the App Store icons of popular iPhone apps into the APK's assets, so the
watch can show them right away, without ever needing internet for these apps.

Apps not listed here still get their real icon: the watch downloads it the first
time that app sends a notification (see AppIconResolver.kt).

To add an app, append its bundle ID below and re-run:
    python3 scripts/fetch_app_icons.py

Find a bundle ID with:
    https://itunes.apple.com/search?term=<app name>&country=id&entity=software
"""
import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request

ICON_SIZE = 128
OUT_DIR = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/app_icons"
STOREFRONTS = ["id", "us"]

BUNDLE_IDS = [
    # Apple
    "com.apple.MobileSMS",           # Messages
    "com.apple.mobilephone",         # Phone
    "com.apple.facetime",
    "com.apple.mobilemail",          # Mail
    "com.apple.mobilecal",           # Calendar
    "com.apple.reminders",
    "com.apple.mobiletimer",         # Clock
    "com.apple.Passbook",            # Wallet
    "com.apple.Health",
    "com.apple.Fitness",
    "com.apple.findmy",
    "com.apple.Music",
    "com.apple.podcasts",
    "com.apple.mobileslideshow",     # Photos
    "com.apple.weather",
    "com.apple.Home",

    # Chat & social
    "net.whatsapp.WhatsApp",
    "net.whatsapp.WhatsAppSMB",      # WhatsApp Business
    "ph.telegra.Telegraph",          # Telegram
    "com.burbn.instagram",
    "com.burbn.barcelona",           # Threads
    "com.facebook.Facebook",
    "com.facebook.Messenger",
    "com.atebits.Tweetie2",          # X
    "com.ss.iphone.ugc.Ame",         # TikTok (Asia store)
    "com.zhiliaoapp.musically",      # TikTok (US store)
    "jp.naver.line",
    "org.whispersystems.signal",
    "com.toyopagroup.picaboo",       # Snapchat
    "com.hammerandchisel.discord",
    "com.linkedin.LinkedIn",
    "pinterest",
    "com.reddit.Reddit",

    # Work & productivity
    "com.google.Gmail",
    "com.microsoft.Office.Outlook",
    "com.google.calendar",
    "com.tinyspeck.chatlyio",        # Slack
    "com.microsoft.skype.teams",
    "us.zoom.videomeetings",
    "com.google.Tachyon",            # Google Meet
    "com.google.Drive",
    "com.google.photos",

    # Google & media
    "com.google.ios.youtube",
    "com.google.ios.youtubemusic",
    "com.google.Maps",
    "com.google.GoogleMobile",       # Google
    "com.google.chrome.ios",
    "com.spotify.client",
    "com.netflix.Netflix",

    # Caller ID
    "com.truesoftware.TrueCallerOther",
    "com.kontakt.getcontact",

    # Indonesia: transport & shopping
    "com.go-jek.ios",                # Gojek
    "com.grabtaxi.iphone",           # Grab
    "com.taxsee.Taxsee",             # Maxim
    "ru.inDriver-ru",                # inDrive
    "com.beeasy.shopee.id",          # Shopee
    "com.tokopedia.Tokopedia",
    "com.LazadaSEA.Lazada",
    "com.blibli.mobile",
    "com.traveloka.traveloka",
    "com.alfamart.alfagift",

    # Indonesia: e-wallets & banks
    "com.go-jek.gopay",              # GoPay
    "id.dana.app",                   # DANA
    "ovo.id",                        # OVO
    "com.shopeepay.id",              # ShopeePay
    "com.bca.bcamobile",             # BCA mobile
    "com.bca.mybca.omni",            # myBCA
    "com.bcadigital.blu",            # blu by BCA Digital
    "id.bmri.livin",                 # Livin' by Mandiri
    "id.co.bri.newbrimobile",        # BRImo
    "id.bni.wondr",                  # wondr by BNI
    "co.id.bankbsi.superapp",        # BYOND by BSI
    "com.cimbniaga.CIMB",            # OCTO by CIMB Niaga
    "com.jago.digitalBanking",       # Bank Jago
    "id.co.bankbkemobile.digitalbank",  # SeaBank
    "com.btpn.jenius.dc",            # Jenius
]


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=20) as response:
        return json.load(response)


def artwork_url(bundle_id):
    for country in STOREFRONTS:
        query = urllib.parse.urlencode({"bundleId": bundle_id, "country": country})
        results = fetch_json(f"https://itunes.apple.com/lookup?{query}")["results"]
        if results:
            url = results[0].get("artworkUrl100") or results[0].get("artworkUrl60")
            if url:
                # "…/100x100bb.jpg" → ask the CDN for the size and format we want
                return re.sub(r"/\d+x\d+bb\.\w+$", f"/{ICON_SIZE}x{ICON_SIZE}bb.webp", url)
    return None


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    failed = []

    for bundle_id in BUNDLE_IDS:
        url = artwork_url(bundle_id)
        if url is None:
            failed.append(bundle_id)
            print(f"  not found  {bundle_id}")
            continue
        with urllib.request.urlopen(url, timeout=20) as response:
            (OUT_DIR / f"{bundle_id}.webp").write_bytes(response.read())
        print(f"  ok         {bundle_id}")

    print(f"\n{len(BUNDLE_IDS) - len(failed)}/{len(BUNDLE_IDS)} icons saved to {OUT_DIR}")
    if failed:
        print("Not on the App Store: " + ", ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
