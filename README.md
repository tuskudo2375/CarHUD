# CarHUD

CarHUD contains two Android apps that exchange navigation, notification, and quick-reply messages over Bluetooth Classic RFCOMM.

- `:companion` runs on the phone. It forwards Google Maps navigation notifications and other notifications, and sends quick replies through notification `RemoteInput` actions.
- `:headunit` runs on the display. It shows navigation and notifications and offers two quick-reply buttons.

Both modules use the shared RFCOMM UUID `fa87c0d0-afac-11de-8a39-0800200c9a66` and newline-delimited UTF-8 JSON.

## Build on GitHub

GitHub Actions builds both debug APKs on every push to `main`, and can also be started from the **Actions** tab with **Build Android APKs → Run workflow**. Download the `CarHUD-APKs` artifact from a completed run. The artifact contains:

- `CarHUD-Companion.apk`
- `CarHUD-HeadUnit.apk`

## Build locally

Install JDK 17, Android SDK platform 34 and build tools 34.0.0, and Gradle 8.2.1. Then run:

```sh
gradle :companion:assembleDebug :headunit:assembleDebug
```
