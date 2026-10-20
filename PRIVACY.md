# ZetaRun privacy information

This document describes the intended data flow of the app in this repository. Before publishing, the app owner must turn it into a public policy with a developer name, support contact, effective date, actual retention practices, and an external account-deletion request URL. It is not yet a complete production privacy policy.

## Voice input

When microphone permission is granted and a voice session is active, ZetaRun processes microphone audio locally using Vosk. Audio and recognized phrases are not uploaded to a speech service, retained in session history, or synced to Firebase. The app stops capture when voice input stops or the app leaves the foreground. The installed English recognition model works without an Internet connection.

## Progress on the device

The app retains session metrics locally: a random session identifier, start/end time, selected arithmetic operations, planned duration, elapsed time, correct answers, attempts, mean response time, best streak, completion status, and input mode. This supports history and progress over time without a login.

Uninstalling the app or clearing its storage can remove local-only history. Device backup behavior depends on the released app's Android backup configuration. Export or sync records before changing signing keys or removing the installation.

## Optional Google sign-in and sync

When a Firebase project is configured and you choose Google sign-in, Google and Firebase authenticate the account. The app can display the account's name and email and uses the Firebase user ID to separate cloud progress. Session metrics are stored under that user's Firestore account. Microphone audio and recognized phrases are not part of those records.

Guest history is associated with the first account to which it is migrated and is then removed from the guest bucket. Local signed-in histories are separated by user ID. Signing out does not delete cloud history.

Google/Firebase process data according to their services' terms and privacy policies. The developer operating the Firebase project controls its access rules, region, retention, and account administration. The app does not require an account for local arithmetic practice.

## Your controls

You can deny or revoke microphone permission in Android settings and use manual answers. Stop voice input or leave the app to stop microphone capture. Local data can be removed by clearing the app's storage; this does not remove cloud records.

The app includes progress export, progress deletion, and app-account deletion controls. Cloud deletion needs connectivity. Once progress-deletion intent is saved locally, affected rounds stay hidden and pending deletion retries on sync. App-account deletion can require a fresh Google sign-in and a retry; while it is pending, further saves for that account are blocked. It removes this app's Firebase Authentication account and aggregate training results, without deleting the user's Google account. These cloud flows still require end-to-end testing with the developer's Firebase project.

This prototype retains minimal deletion markers: random deleted session IDs and a flag keyed by Firebase UID. They contain no scores, times, names, email, recordings, or transcripts. They prevent an offline device or a delayed network write from restoring deleted results. The app has no backend retention/cleanup service yet, so deleting an account does not currently erase these markers. Before public release, implement trusted cleanup and token revocation, define and disclose marker retention, and verify complete account deletion. Provide an external deletion-request route in the published policy and Play Console.

The repository does not intentionally include advertising or an analytics event pipeline. The app owner must review the complete dependency set and final service configuration before making public collection or sharing disclosures.
