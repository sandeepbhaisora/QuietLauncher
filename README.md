# Quiet v0.4 — Adaptive Workspace (prototype)

Open in Quiet can now distinguish shared links, code-like text, Markdown headings, and ordinary text. The Explore view offers contextual actions, code/document section navigation, a local text-file picker, and focused snippets to copy or explicitly share with ChatGPT. No AI model executes in Quiet. No separate API billing; Android’s standard share intent hands information to ChatGPT only when requested.

Important: outlines use basic local heuristics, not an AST parser. A link alone cannot reveal its webpage contents, and reading text files is size-limited. This is a prototype, not a general app wrapper.

# Quiet Launcher — a calmer Android homescreen

Quiet is an **original, native Android launcher** influenced by the idea of a distraction-free phone. It is not affiliated with Ratio / Blloc. The first version is built in Java with Android platform APIs—**no account, adverts, trackers, server, or third-party Android library**.

The project is designed for Samsung Galaxy phones and other Android devices running Android 9+ (including recent Android versions). It has **not been compiled or tested on a physical device in this environment**, because the Android SDK/build tools aren't installed here.

## Features included in v0.1

| Feature | Status | Notes |
| --- | --- | --- |
| Replace default homescreen | Implemented | Android HOME intent + launcher settings shortcut |
| Up to six essential apps | Implemented | Choose actual installed apps |
| Swipe-up app drawer and app search | Implemented | Search by name / category; long press app to manage; web-search fallback |
| Grouped drawers | Implemented | Android app categories + rule-based categorization; collapsible |
| Usage time and most-used apps | Implemented | Requires **Usage Access** permission |
| Focus timer (15–60 min) | Implemented | Lightweight local timer; **doesn't enforce app blocking** |
| Intentional launch prompt | Implemented | Flag distracting apps, require a deliberate choice when launching via Quiet; 5-minute bypass interval isn't a background block |
| Hidden apps | Implemented | Hidden **only from Quiet**, not uninstalled or securely hidden from Android |
| Protected apps | Implemented | Uses device PIN/password/biometric confirmation before launching **from Quiet**; not system-wide protection |
| Android widgets | Implemented | Standard widget picker and host, including compatible Calendar/music/weather widgets |
| Notes | Implemented | Local quick note |
| Notification inbox | Implemented | Requires **Notification Access**; shows currently active notifications and opens originals; not a merged conversation database |
| Offline command input | Implemented | From app search: `focus 30`, `open WhatsApp`, `usage`, `notes` |
| Theme choices | Implemented | Ink, warm paper, true black |
| Calendar shortcut | Implemented | Opens the device calendar |

## Not in this version

- Full cross-app WhatsApp/Instagram/Signal message history and sending messages on their behalf. These apps don't offer general-purpose unrestricted message access to launchers. Android's notification listener offers a partial, permission-based alternative.
- **Enforced screen-time blocking** across all apps or background force-closing. This requires a more involved system-access architecture and still has OS limitations. The built-in *intentional launch prompt* is a gentle nudge, not a hard block.
- Offline wake-word voice assistant (`Hey Focus`) or default assistant role, to be implemented and tested separately (on-device speech model, hotword service, mic notification, power budgeting).
- OS-level lockscreen replacement (Android/One UI limits), icon-pack theming of other apps, app shortcuts, work-profile/private-space integration, and notification quick replies.
- Automatic local weather data (use a compatible standard Android widget).

## Build an APK with GitHub Actions (no local Android Studio needed)

1. Create a GitHub repository and upload the **contents of the extracted `QuietLauncher` folder**, including the `.github` directory (not the ZIP file itself).
2. Open your repo → **Actions** → **Build Quiet APK** → **Run workflow**. A push to `main` also starts the workflow.
3. When complete, open the run → **Artifacts** → download `Quiet-debug-apk`, unzip it and install `app-debug.apk` on your phone.
4. This is a development APK signed with an automatically generated debug key and is suitable for personal testing. Android might ask you to allow installing from your browser / Files app.

This workflow is included but has **not been executed here**; run it to actually verify Android compilation and generate the APK.

## Run on an Android phone

1. Install **Android Studio** on a Mac or PC with Android SDK 35.
2. In Android Studio, open the `QuietLauncher` project folder. If Android Studio asks to set up a Gradle wrapper, let it create one. Use JDK 17 (or compatible JDK).
3. Wait for Gradle sync (requires internet to obtain Android Gradle Plugin 8.9.2) and click **Run** with a device connected by USB / wireless debugging.
4. On the phone open **Settings → Apps → Choose default apps → Home app → Quiet** (menus may vary by Samsung / Android version), or tap **Set Quiet as home** in app settings.
5. For usage stats, enable **Usage Access**; for inbox, enable **Notification access**. Neither is required for basic launching.
6. Configure your six essentials, distracting apps, and theme under Quiet's Settings screen. Long-press an app in the drawer for individual options.

### Build command (after Android SDK + Gradle are installed)

```sh
gradle :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

This archive does **not** contain an APK. Android Studio / Gradle are needed to compile and sign one. No Google services / paid licenses are required for the launcher runtime.

## Files

- `app/src/main/java/dev/quiet/launcher/MainActivity.java` — launch activity, UI, settings and widget hosting
- `app/src/main/java/dev/quiet/launcher/NotificationBridgeService.java` — opt-in notification bridge
- `app/src/main/AndroidManifest.xml` — app entry, launcher role, opt-in usage/notification declarations
- `preview/index.html` — standalone interactive design preview (browser-only), screenshots in `preview/home.png`, `preview/paper.png`, `preview/apps.png`

## Security / privacy design

- No `INTERNET` permission, tracking code or external libraries in the app.
- Usage Access and Notification Access are opt-in.
- Quick note and preferences stored in Android private SharedPreferences.
- Notifications are read live while notification access is enabled, not uploaded or stored remotely.
- Device credential confirmation protects launching apps **through Quiet only**. Apps can still be launched via Android Settings, notifications, deep links, and other surfaces. Do **not** treat it as a security boundary.
- Backups are disabled to avoid accidental syncing of local preferences.

## Testing checklist before daily driving

- Change default home to Quiet and back to One UI Home.
- Test all favourites and category groups; confirm installed apps launch.
- Confirm notification access / usage access prompts and resulting data.
- Add / remove at least one third-party widget.
- Enable protection for one app and verify wrong/cancelled credential attempts don't open it.
- Enable distracting-app prompt and check opening behaviour.
- Confirm status/nav insets and swipe interactions on Samsung Android 16/17.
- Test a rotated screen, accessibility font scaling, gesture navigation, and a long app list.

**License:** This sample source is provided for personal modification. Visual design and implementation are new and do not reproduce Ratio artwork, assets or code.
