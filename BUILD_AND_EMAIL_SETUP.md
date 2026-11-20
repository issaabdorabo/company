# Build and email verification setup

## Android project / APK

This repository is a single-module Android application whose Gradle application module is the repository root. The Android sources are in `src/main`; there is intentionally no `app/` subdirectory. Build from the repository root with:

```bash
gradle --no-daemon clean assembleDebug
```

The expected output is `build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow in `.github/workflows/android-debug.yml` builds this root project and uploads the APK as the `company-debug-apk` artifact.

## Firebase removal

The Android app does not use Firebase Authentication, Firestore, or Firebase SDK dependencies. The Google Services Gradle plugin and Firebase-specific generated configuration files have been removed. Google Drive backup and Gmail OTP continue to use Google OAuth and the Google REST APIs; this is separate from Firebase.

## Gmail OTP / Google Drive prerequisites

The app sends the OTP from the Google account selected on the device to that same account's mailbox, then polls Gmail for the matching reference tag. It does not silently send from an arbitrary third-party sender. For this to work, the Google Cloud project associated with the Android OAuth client must:

1. Have the Gmail API and Google Drive API enabled.
2. Have an OAuth consent screen configured and the selected account authorized as a test user while the app is in testing.
3. Have a valid Android OAuth client configured for application ID `com.aistudio.inventory.essa` and the signing certificate SHA-1 used for the installed build.
4. Request/approve the Gmail send, Gmail read-only, and Drive file scopes used by the app.

The Android client cannot bypass Google's OAuth consent or API restrictions. If the Gmail API is disabled, the OAuth client is misconfigured, the scopes are not approved, or Google blocks the consent flow, sending will fail with a surfaced error rather than falsely reporting success.
