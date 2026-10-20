# Google sign-in and private progress sync

The app works without Firebase: sessions are stored on the phone and voice recognition stays on the phone. Google sign-in becomes available after connecting your own Firebase project. No production credentials are supplied in this repository.

1. Create a project at https://console.firebase.google.com/ and add an Android app with package name `com.zetarun.app` (or the final application ID if you change it).
2. Register your development signing fingerprints. From the repository run `./gradlew :app:signingReport` and copy the debug variant's SHA-1 **and** SHA-256 into the Firebase Android app settings. Each developer's debug keystore differs. Add release/Play App Signing fingerprints later for distributed builds.
3. Under **Authentication → Sign-in method**, enable **Google** and select a support email. A Google Web OAuth client will be created. The app uses its Web client ID through Android Credential Manager; do not use an Android OAuth client ID for this field.
4. Under **Firestore Database**, create a database in production mode and choose a region appropriate for your users. Deploy the supplied owner-only `firestore.rules` before syncing real results. You can paste the file into the Firestore rules editor and publish, or use `firebase deploy --only firestore:rules --project YOUR_PROJECT_ID` after authenticating the Firebase CLI. Do not deploy open test-mode rules.
5. Download the updated `google-services.json` from your Firebase Android app settings and place it at `app/google-services.json`. The Android build reads it and enables the configuration; rebuild/install the APK. Keep your personal project configuration out of source control. A Firebase client configuration is not an admin credential, but repository defaults should not point every user at a shared database.
6. On the phone, add a Google account in Android settings, open the app, and choose **Sign in with Google**. The selected account's name/email appear in the account area. If sign-in fails, first check the installed build's package ID, its signing fingerprints, Google provider status, and Web client ID.

Cloud integration needs real credentials and a phone with Google Play services. An offline build or local unit tests cannot verify this end to end. Verify sign-in, sync, sign-out/account switching, and deletion in your Firebase project before release.

## What is stored and synchronized

Firestore documents live at `users/{firebaseUid}/sessions/{sessionUuid}`. Each stores start/end time, planned and elapsed duration, score, attempt count, average response time, best streak, selected arithmetic operations, completion status, and input mode (`voice`, `keypad`, or `mixed`). Only the authenticated owner can read or write these documents under the supplied rules. No recordings, recognized speech, or question transcripts are stored or uploaded.

Sessions are written locally first. Failed cloud sync retains local results and reports the error; **Retry sync** repeats an idempotent merge by session UUID. Cloud sessions are downloaded on sign-in and explicit sync. Concurrent device writes are separate documents. This is not a continuously streaming cloud history listener.

Guest results move atomically into the first account used to sign in and are removed from the guest bucket. Each account has a separate local cache. Signing out hides that account's results; signing in as another user does not copy the former account's history. Android backup is disabled for application data so cached history is not silently restored into a different installation.

The app's account controls use `exportJson()`, `deleteAllProgress()`, and `deleteAccount()`. Cloud deletion needs connectivity. Once deletion intent is saved locally, the affected rounds stay hidden and cannot be uploaded again. Pending progress deletions retry on the next sync. A failure before intent is captured leaves the existing history intact and asks you to reconnect. New rounds made after a progress-deletion snapshot have different UUIDs and remain independent.

Account deletion removes this app's Firebase Authentication account and aggregate session documents, not the user's Google account. A durable account-deletion marker blocks new uploads and prevents another device with a still-valid token from recreating results. While account deletion is pending, saving new rounds for that account is blocked. Firebase can require a recent sign-in; if prompted, sign out/in and use **Delete account** again to finish. A failed deletion may require a retry after partial deletion.

**Prototype retention limitation:** deletion retains random session IDs with a `deleted: true` flag at `users/{uid}/deletedSessions/{sessionUuid}`, and an account-deletion flag keyed by Firebase UID at `deletedAccounts/{uid}`. These minimal markers contain no names, email, scores, times, audio, or transcripts. Rules make them immutable so a stale offline device or a network write completing after a timeout cannot restore deleted results. The app currently has no trusted backend to revoke all outstanding tokens and then safely remove these markers; it does not provide complete metadata erasure. Before Play Store publication, implement and test backend account cleanup with a defined retention period, document that retention in the public policy, and provide a public deletion-request page.

## Release configuration

For Play distribution register the **Play App Signing** SHA fingerprints in Firebase, then download the updated client configuration and build a signed release. Debug, local release, upload key, and Play app-signing certificates can be different. Keep keystores and admin/service-account credentials outside the repository. Restrict your Firebase API key to the intended Android package/signing certificates where supported, and consider Firebase App Check with Play Integrity after you have a working internal testing build. App Check is not enabled in this prototype; enable enforcement only after configuring a supported provider and testing it.

Use Firebase usage/billing alerts. Owner-only rules restrict access but do not by themselves impose per-user quota or stop an authenticated user from submitting fabricated scores; these records are personal training history, not a trusted public leaderboard.
