# MapsBridge

MapsBridge sends a shared destination from the primary phone (Máy Chính) to the secondary device (Máy Phụ) over Bluetooth Classic RFCOMM. Máy Phụ resolves shortened map links and opens the destination in Google Maps turn-by-turn navigation. The companion can also forward notifications and native notification actions.

## Apps

- `:companion` installs on Máy Chính. Choose the paired Máy Phụ in Setup, enable notification access if desired, then use Android's Share menu and select **Dẫn đường trên Máy Phụ**.
- `:headunit` installs on Máy Phụ. It can be selected as the Android home launcher, shows the supplied QR wallpaper and app dock, and starts the Bluetooth receiver service automatically.

The apps use the shared RFCOMM UUID `fa87c0d0-afac-11de-8a39-0800200c9a66` and newline-delimited UTF-8 JSON. They do not depend on Google Play Services or Android Auto SDK.

## Build on GitHub

GitHub Actions builds both debug APKs on pushes to `main`, and can also be run from **Actions → Build MapsBridge APKs → Run workflow**. Download the `MapsBridge-APKs` artifact from the completed run. It contains `MapsBridge-Chinh.apk` and `MapsBridge-Phu.apk`.

## Build locally

Install JDK 17, Android SDK platform 35 and build tools 35.0.0, and Gradle 8.7. Run:

```sh
gradle :companion:assembleDebug :headunit:assembleDebug
```
