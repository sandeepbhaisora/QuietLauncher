# Londe system assistant: first working foundation

This release moves Londe beyond a launcher chat screen. It keeps the existing
Supertonic 3 local voice output and ChatGPT fallback, and adds Android assistant
role registration, local wake-word spotting and narrowly whitelisted voice actions.

## One-time Android setup

1. Install a **main-branch signed** build (PR builds use ephemeral debug keys).
2. Open Quiet Settings → **Set Londe as Android assistant**; approve the system
   role prompt. This replaces the device's currently selected assistant.
3. Settings → **Voice wake: Hey Londe** → Enable; grant microphone permission.
4. Say **Hey Londe**, then issue a command after the assistant console starts.
   To end the interaction, say **stop Londe**.
5. Try `open Spotify`, `focus for 25 minutes`, `what time is it`,
   `explain Kafka consumer groups`.

**Important**: On Samsung Android versions, the OS may refuse microphone
recording by a third-party assistant while locked or in some background states.
The assistant role enables a platform integration, but does **not** guarantee
privileged DSP hotword detection or activation through a powered-off screen.
Check whether the system shows Londe as default assistant, then test screen
on, screen off, battery saver, and lock screen separately.

## Implementation

- Android VoiceInteractionService / VoiceInteractionSessionService invoke the
  existing Londe voice UI from the system assistant affordance.
- `LondeVoiceService` maintains a tiny sherpa-onnx open-vocabulary English
  keyword spotter and captures 16kHz PCM using `OfflineWakePipeline`.
  Keyword `HEY LONDE` is tokenized from the upstream GigaSpeech BPE vocabulary
  during CI. Only the detected phrase triggers command recognition, instead
  of transcribing all ambient speech.
- `LondeDeviceActions` validates local commands using `LondeCommands`,
  `IntentRouter` and `ActionPolicy`, then executes only explicit,
  unambiguous APP and START/STOP_FOCUS steps. Launches wait until after the
  spoken acknowledgement finishes.
- The Londe console remains hands-free while active and responds verbally
  using the existing local Supertonic voice. Unsupported actions still show
  a review UI, not silent app automation.

## Not implemented or guaranteed

- No read access to SMS/WhatsApp contents, contacts, notifications or personal
  files is requested in this change.
- No universal accessibility clicker, banking automation or bypass of security.
- No offline large language model or offline speech-to-text model yet. General
  questions still require the user's authorized ChatGPT connection/internet;
  transcription may use the Android recognizer service.
- English-trained `HEY LONDE` pronunciation and false triggers need device
  tuning; this is real model-backed KWS, not a custom acoustically fine-tuned
  wake-word model.
- Natural Hindi TTS works independently from the wake-word model.

## Developing

Before Gradle run:
```bash
bash scripts/install_local_voice.sh
bash scripts/install_londe_wake.sh
gradle :app:assembleDebug
```
No additional voice subscription or API key is required for TTS or KWS.
Native model weights and runtime are downloaded as part of the build and
bundled into the APK. `README` and model provenance are in the respective
asset directories.
