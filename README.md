# MapsBridge

MapsBridge sends a shared destination from a Xiaomi phone to a Samsung Galaxy Note8 over Bluetooth Classic RFCOMM. The Note8 resolves shortened map links and opens the destination in Google Maps turn-by-turn navigation. The companion can also forward notifications and deliver quick replies through Android notification actions.

## Apps

- `:companion` installs on the Xiaomi phone. Choose the paired Note8 in Setup, enable notification access if desired, then use Android's Share menu and select **Dẫn đường trên Note8**.
- `:headunit` installs on the Note8. Grant overlay permission and start the receiver service. It listens for shared destinations and displays quick-reply overlays while Google Maps is open.

The apps use the shared RFCOMM UUID `fa87c0d0-afac-11de-8a39-0800200c9a66` and newline-delimited UTF-8 JSON. They do not depend on Google Play Services or Android Auto SDK.

## Build on GitHub

GitHub Actions builds both debug APKs on pushes to `main`, and can also be run from **Actions → Build MapsBridge APKs → Run workflow**. Download the `MapsBridge-APKs` artifact from the completed run. It contains `MapsBridge-Xiaomi.apk` and `MapsBridge-Note8.apk`.

## Build locally

Install JDK 17, Android SDK platform 35 and build tools 35.0.0, and Gradle 8.7. Run:

```sh
gradle :companion:assembleDebug :headunit:assembleDebug
```
