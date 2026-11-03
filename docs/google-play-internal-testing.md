# QuietLauncher — Google Play internal testing checklist

The GitHub main-branch workflow produces three artifacts:
- **Quiet-Play-internal-AAB**: signed release Android App Bundle with notification listener; upload to Play.
- **Quiet-standard-apk**: direct-install APK without notification reading.
- **Quiet-full-apk**: direct-install APK with notification listening (may trigger sideload block).

## Play Console steps

1. Open https://play.google.com/console and set up a developer account if needed.
2. Create an Android application with package ID `dev.quiet.launcher` and app name Quiet.
3. Enroll in Play App Signing. Preserve the existing GitHub Actions upload-key secrets.
4. Complete required Play declarations accurately: Data safety, Privacy Policy, App Access, content rating, target audience, and other prompts displayed by Play Console.
5. Explain notification access honestly. Android grants the listener access to all visible notifications, not only WhatsApp. Quiet reads active notifications when asked, without sending their content to an LLM. Verify the actual code before filing any declarations.
6. Publish a public Privacy Policy covering microphone, notification access, calendar, usage access, local Qwen, stored local memory, optional ChatGPT online features, and model downloads. A private GitHub document is not a public privacy policy.
7. Open Testing → Internal testing; create and save the tester email list; create a new release; upload `Quiet-Play-internal-AAB` from the successful GitHub Actions *main* run; complete any Play Console warnings and submit.
8. Share Play's opt-in test link with testers, who must join using their listed Google accounts, then install from the Play Store.
9. Test the full permission flow, wake phrase, voice, message reading, and Play-installed updates on a Samsung device. Play testing does not exempt an app from policies or review.

## Signing / existing installations

The old direct APK uses a developer certificate. Play App Signing may use a distinct certificate for delivered APKs. If the certificates differ, Android cannot update the existing installation in place. Quiet has `allowBackup=false`; uninstalling may erase downloaded models, preferences, and local memory. Compare certificate fingerprints and plan preservation before any uninstall. The AAB itself is not installable by tapping it.

## Build readiness and limitations

- Targets Android API 36 for new Play submissions after August 31, 2026.
- App bundle is compiled with AGP 8.10.1 and Gradle 8.11.1 using JDK 17.
- Native architecture remains ARM64 only.
- Changing targetSdk to 36 requires device validation, especially edge-to-edge / navigation bar insets on Android 16/17.
- No automatic publishing is configured. The repo's workflow has no Play Developer API credentials. A developer-account owner must submit the bundle and complete Play Console steps.
- Play can refuse a submission independently of APK signing or notification-listener implementation. Never advise disabling Play Protect.
