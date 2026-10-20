# Prepare for Google Play

First validate the debug build on the S24 Ultra using [the device checklist](S24_ULTRA.md). Publication is a separate step in your Play Console account; building an APK or bundle does not publish the app.

## Configure the production app

Choose the final application ID before the first Play upload; it becomes the app's permanent Play identity. Use your own Firebase project, enable Google authentication, deploy the per-user Firestore rules, and test login and progress on the release build. Register the release upload certificate and, once Play App Signing is configured, the **Play app signing certificate SHA-1** in Firebase. Re-download configuration when the console requires it.

Verify the current [Google Play target API requirement](https://support.google.com/googleplay/android-developer/answer/11926878) before submission. A build that installs on the S24 Ultra can still target an API level too old for a new Play listing. Update and test the target SDK as required.

Review the Vosk engine and English model licenses and retain their notices in the distribution. Recheck any added dependencies and artwork. Use original app branding and describe the app as an independent arithmetic trainer.

## Create a signed bundle

In Android Studio, choose **Build → Generate Signed App Bundle / APK → Android App Bundle**. Create and securely back up a release upload keystore outside the checkout. Keep passwords and signing keys out of source control and build logs. Enable Play App Signing and retain access to the upload key and Play Console account.

Increase `versionCode` for every uploaded update and set an appropriate user-visible `versionName`. A normal bundle task is:

```bash
./gradlew testDebugUnitTest lintDebug bundleRelease
```

Configure release signing first; a bundle produced without it is not upload-ready. Inspect the merged release manifest, installed size, native-library compatibility, and requested permissions. Run the exact signed build on the phone, then use Play's internal testing track so Google login is tested with Play's app signing certificate too.

## Privacy, accounts, and store forms

Publish an accessible privacy-policy URL using an edited version of [PRIVACY.md](../PRIVACY.md), with your developer identity, contact details, retention policy, and deletion instructions. Complete Play's Data safety form from the released app's actual behavior and current definitions. Do not label the entire app “no data collected” if Firebase authentication and progress sync send account identifiers or session data off the device.

Microphone audio is processed on the phone. Google authentication and optional Firestore sync use network services. Review Firebase's current disclosures and every SDK in the final build; do not infer advertising or analytics collection simply from a Google login, or assume none without reviewing the SDK configuration.

Apps offering account creation must meet [Google Play's account-deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111), including an in-app deletion path and a working external deletion-request URL. Verify the app's deletion controls, cloud-session removal, Firebase user removal, offline/error behavior, and recent-sign-in prompts. Supply a real external deletion-request mechanism before publishing. Signing out alone does not delete an account or cloud history.

**Release blocker:** implement trusted backend deletion cleanup and token revocation. The prototype currently retains immutable deletion markers keyed by Firebase UID/random session UUID to prevent stale-device uploads from restoring deleted results. They contain no training metrics, but complete metadata erasure and a bounded retention policy are not implemented. See [Firebase retention details](FIREBASE.md). Test deletion concurrently with another signed-in device and with a timed-out upload, then document the actual retention policy and external deletion-request process.

## Test and submit

- Complete the content-rating, target-audience, ads, app-access, and Data safety declarations accurately.
- Upload original screenshots, an icon, feature artwork, a description, and a support contact.
- Check Play's pre-launch report, crashes, ANRs, accessibility, microphone permission flow, and native-library/page-size compatibility for the included Vosk/JNA libraries.
- Test interrupted sessions, screen locking, denied permission, airplane mode, account switching, deletion, and reinstall/restore behavior.
- Follow any closed-testing requirement shown for your developer account. Such requirements depend on account type and enrollment date.
- Promote a tested signed bundle from internal testing toward production only when the device behavior and store requirements are satisfied.

Voice accuracy and submission latency require real-device measurements. Do not promise a specific latency or flawless treadmill recognition in the listing until supported by repeatable tests.
