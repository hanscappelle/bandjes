# Bandjes Android app

A small Android Studio project that displays the data from the PHP JSON
service: tyre-size and history-period filters, latest prices, collection
summary metrics, favorite markers, and a simple chart of favorite price
history.

## Run

1. Deploy the `service-php` directory on a server with HTTPS enabled.
2. Open `android-app` in Android Studio and let it sync the Gradle project.
3. Run the `app` configuration on an Android device or emulator.
4. On first launch, enter the full HTTPS URL to `service.php`, for example
   `https://prices.example.com/service.php`, and tap **Load prices**. The app
   saves the URL and loads it automatically on future launches. Use the
   settings button in the top bar to change the URL later.

The URL is saved locally on the device. To change favorites from the app,
configure `BANDJES_API_TOKEN` on the PHP server and enter the same token in app
settings. The token is encrypted using the Android Keystore. Favorite changes
are shared with the web dashboard. The app requires Android 8.0 (API 26) or
newer and does not trigger price collection.

Choose a **Default tyre size** in settings to open the app on that size. If no
default is selected, the app uses the service's first available size.
Use the **Dark mode** switch in settings to enable the dark color scheme; the
choice is saved on the device.
Tap a tyre tile to open its product page, or long-press it to toggle its
favorite status. Turn on **Favorites only** above the latest-price list to
show only favorite tyres; it combines with the text filter. Results are ordered
by latest price, lowest first.

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
HTTP traffic. For the private NAS CA setup, the app trusts the bundled CA only
for `192.168.1.198`; other hosts continue to use Android's normal system
certificate authorities.
