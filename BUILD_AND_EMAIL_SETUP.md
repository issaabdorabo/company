# Android build and Google Drive backup setup

The app does not use Firebase Authentication, Firestore, Gmail OTP, or Firebase SDK dependencies.

## Google Drive backup authorization

The app uses the Google account selected from the Android device account picker. When cloud backup is first used, Google displays its own consent screen. The app requests only `https://www.googleapis.com/auth/drive.appdata`; it does not request Gmail send/read permissions or full Drive access. Backups are stored in the application's private `appDataFolder`, not in a user-visible Drive folder.

The Android OAuth client in Google Cloud must match the installed APK's application ID and signing certificate SHA-1. During testing, add the Google account to the OAuth test-user list.

Email OTP has been removed. Selecting a Google account and granting Google's Drive authorization is sufficient for the cloud backup feature; no email code is sent or entered.
