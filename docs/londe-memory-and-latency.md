# Londe: local memory and acknowledgement cues

## Two layers of memory
- **Session context**: up to three successful general Qwen exchanges, in RAM only,
  expiring after eight minutes. Used only for clearly framed follow-ups.
- **Saved memory**: disabled by default. The user enables it under Quiet
  Settings → LOCAL AI → On-device memory. The app then stores only explicit
  "Remember that ..." facts (12, 140 characters max) and *aggregate* broad
  categories for general questions. Data lives in Android app-private preferences
  and is not synced, uploaded, or sent through a ChatGPT fallback.
- Voice controls: "Remember that I prefer short answers", "What do you remember
  about me?", "Forget everything you remember". The UI offers review/clear.
  Do not store notifications, messages, calendar events, or raw questions.
- Sensitive-data screening is deliberately conservative but not a full
  safety classifier. Avoid adding secrets or health data. Local preferences are
  plain app-private preferences, not independently encrypted.

## Listening
- The existing Hey Londe KWS remains default.
- "Londe" alone can be enabled separately in Quiet Settings. It uses extra
  BPE keywords from the same offline weights; no separate model download.
  It is experimental and may have both false positives and missed phrases.
  No wake guarantee for locked or screen-off Samsung devices.

## Responsiveness
- The UI immediately shows THINKING when inference starts.
- If still waiting after 1 second, one quiet, original PCM hum plays locally.
  It doesn't consume neural speech synthesis capacity, call the cloud or claim
  to be checking facts. It is optional and cancellable.
- This reduces *perceived* waiting, not actual LLM compute time. More speed
  requires model, context, quantization, or native runtime optimization.
