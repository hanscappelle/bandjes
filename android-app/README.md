# Betyre Android app

A small Android Studio project that displays the data from the PHP JSON
service: tyre-size and history-period filters, latest prices, collection
summary metrics, favorite markers, and a simple chart of favorite price
history.

## Run

1. Open `android-app` in Android Studio and let it sync the Gradle project.
2. Run the `app` configuration on an Android device or emulator.
3. On first launch, the app uses `https://betyre.alwaysdata.net/service.php`
   and loads prices automatically. Use the settings button in the top bar to
   change the service URL; a saved custom URL takes precedence. Choose
   **Use default** beside the URL field to clear the custom URL and revert to
   the default service.

The URL is saved locally on the device. Favorites are saved on the device and
are independent of favorites in the web dashboard. They are not sent to or
loaded from the service.
The app requires Android 8.0 (API 26) or newer and does not trigger price
collection.
Use **Feature requests and bug reports** in settings to open the public
[GitHub repository](https://github.com/hanscappelle/betyre).

## Download

The latest debug APK is
[`releases/betyre-1.0-debug.apk`](releases/betyre-1.0-debug.apk). Dated APKs are
kept in `releases` for version history: the November 11, 2026 build is
[`releases/betyre-1.0-debug-2026-11-11.apk`](releases/betyre-1.0-debug-2026-11-11.apk),
and the November 12, 2026 build is
[`releases/betyre-1.0-debug-2026-11-12.apk`](releases/betyre-1.0-debug-2026-11-12.apk).
APKs are signed with the standard Android debug key and are intended for
sideloading and testing, not for Google Play distribution.

Choose a **Default tyre size** in settings to open the app on that size. If no
default is selected, the app uses the service's first available size.
Use the **Dark mode** switch in settings to enable the dark color scheme; the
choice is saved on the device.
Tap a tyre tile to open its product page, or long-press it to toggle its
favorite status. Turn on **Favorites only** above the latest-price list to
show only favorite tyres; it combines with the text filter. Favorites and
their price history use the app's local selections. Results are ordered by
latest price, lowest first.

In settings, enable **Notify on price drops** to schedule local alerts for
tyres. **Limit notifications to favourites** is on by default; turn it off to
check all tyres instead. Changes to the percentage drop threshold and the
frequency (once, twice, or four times per day) are saved automatically. The app
compares each monitored tyre's latest price with its price at the previous
check; the first check establishes a baseline and does not alert.
Checks require network access, and Android may delay them for battery
management, so they are not exact-time alarms. Android 13 and newer require
notification permission. Open **Alert status** in settings to see the last
check result and price baselines for monitored tyres, refresh that status, or
send a test notification.

## Build requirements

- JDK 17.
- Android SDK platform 35.

The Gradle wrapper is included, so Gradle does not need to be installed
globally. From the `android-app` directory, build and optionally install to a
connected device with:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

In VS Code, open the `android-app` folder and run these commands in the
integrated terminal. Android Studio is optional; it can install SDK components
and create emulators, but is not required for command-line builds.

The endpoint must use HTTPS. The app intentionally does not enable cleartext
HTTP traffic. As an alternative to the public service endpoint you can also deploy the `dashboard-php-public/service-php` to your own server/setup and update the URL in app configuration. 
