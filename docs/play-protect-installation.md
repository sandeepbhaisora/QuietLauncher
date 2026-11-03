# Installing Quiet and Google Play Protect

Google Play Protect may block manually downloaded APKs if they declare
NotificationListenerService. Quiet's full edition registers that service so
Londe can read active notifications with explicit user consent. This is the
permission-driven sideload block described in Google's developer guidance:
https://developers.google.com/android/play-protect/warning-dev-guidance

The build workflow creates two variants with the same Android package ID and,
for main-branch builds, the same persistent signing identity:

- **Quiet-standard-apk:** Recommended for browser/file-manager installation.
  It omits the notification-listener manifest service. Londe cannot check
  WhatsApp or other notifications in this version. It retains local Qwen,
  Supertonic, offline wake, local memory, app opening and messaging drafts.
- **Quiet-full-apk:** Includes the on-request notification listener, but
  Android may block direct downloads under Google Play Protect. A compliant
  app-store distribution, developer review/appeal or other permitted route
  is needed to offer this feature responsibly.

The standard build is compiled a second time after the full APK is saved.
CI verifies its APK signature and compiled manifest for the absence of the
notification-listener capability. This addresses a known permission category,
not a guarantee that an installation is safe or will be accepted by every
security scan. Do not disable Play Protect to force an install.

Only install signed builds from successful main workflows. PR builds use
disposable test signing and cannot normally update existing installations.
An updated package with the same certificate and a higher version code
should preserve local app data without uninstalling.
