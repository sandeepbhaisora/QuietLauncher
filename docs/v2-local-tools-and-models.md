# Quiet 2.0 — Safe on-device tools and selectable Qwen

## This iteration implements

The local model may suggest a **typed request**, rather than claim that it read
device data. The Android tool executor remains authoritative. The current
native tool registry has these allowlisted operations:

| Tool | Execution |
| --- | --- |
| `get_battery` | Read actual Android battery percentage/charging state |
| `get_time` | Read the Android device clock |
| `get_focus_status` | Read Quiet focus timer preference |
| `get_volume` | Read Android media volume, without changing it |
| `get_screen_time` | Read approximate foreground app usage with Usage Access |
| `list_apps` | Show a sample of launchable installed apps without opening them |
| `open_app` | Only if the app name appears in user's explicit open/launch request, then passes to existing Android app/action review |

Direct battery queries bypass Qwen to reduce latency. Existing deterministic
notification/calendar/WhatsApp draft routing stays in place with its permission
and user-review policies. Model-suggested shell, send, delete, click, network,
or arbitrary API access is not allowed. The tool executor does not give Android
Context or credentials to Qwen. Tool results are spoken/displayed locally, not
fed back through an online LLM.

The new tool policy is deliberately constrained: it isn't a generic external
MCP server or unrestricted automation interface. Expanding it requires
dedicated schema validation, permission controls, user confirmations, and tests
for each additional tool.

## Models

- **Qwen3 0.6B Q4_K_M** (existing): ~484 MB; remains the default.
- **Qwen3 1.7B Q4_K_M** (new, optional): ~1.28 GB (source:
  https://huggingface.co/Antigma/Qwen3-1.7B-GGUF/blob/main/qwen3-1.7b-q4_k_m.gguf).
  Verified using the file's SHA-256
  `a7f6720f68f4a4567ebf7e3257041dd0b72077b518efe56890aec3516b59b9de`.

Select Quiet Settings → On-device brain → Choose AI model. Download via
user-approved Wi-Fi DownloadManager, then verify before inference. The two
models are kept as separate files and tracked separately. Switching an
already-loaded model requires fully restarting Quiet; the app refuses an unsafe
hot switch. No new model weights are built into the APK.

## Testing on a real phone

1. Ask `Londe, what is my battery percentage?`; it should report Android
   percentage and charging status without waiting for Qwen.
2. Ask `What is a battery?` — answer as general knowledge, not the phone's
   battery level.
3. Ask `Open Spotify` — allow only installed matching app and existing review.
   Ask an unrelated question; it should never launch an app.
4. Select the optional 1.7B model, download, verify, restart Quiet if model was
   previously loaded, and compare the same five Hindi and English prompts.
5. With Wi-Fi disabled, verify both model inference and local battery tool
   succeed. Keep unrelated cloud fallback behind spoken authorization.
6. Record model startup time, tokens/sec, device RAM pressure, TTS time,
   and whether the UI responds reliably to wake/listening.

This build is not a Quiet 2.0 release candidate until it passes actual
device acceptance tests.

## Full-feature Play bundle safety
The standard APK temporarily removes notification access from the manifest. The release-bundle workflow now restores the original full manifest and reruns bundle tasks, so the Play release retains notification access.
