# Londe offline neural speech (PR #10)

Default: **Supertonic 3** via **sherpa-onnx 2.0.1** (JNI, in app).
The real ONNX weights + Android native runtime are downloaded by
`bash scripts/install_local_voice.sh` before the Android build.
Nothing calls a TTS server at runtime; this is not a separate Android TTS engine.

## Build

```bash
bash scripts/install_local_voice.sh
gradle :app:assembleDebug
```

CI performs the same download before assembling the APK. The runtime AAR
is pinned to the upstream release SHA-256. This is an **arm64** APK build.
Supertonic weighs substantially more than the old launcher alone; leave
enough storage for the APK and the model extracted to private app storage.

## Behavior

The app offers Supertonic as the default voice in both Home and Londe.
Choose **Londe voice → Android fallback** to restore legacy Android TTS.
A failed model initialization displays an error; it does not silently
switch to an inferior voice. No additional API keys or subscription.

The model currently chooses Hindi synthesis for Devanagari and a few
Hinglish cues, English synthesis otherwise. This is not a full Hinglish
transliteration engine. Hindi quality and real-time speed on Samsung
must be measured on-device; there is no claim of parity with ChatGPT Voice.
The first speech response may take longer due to asset extraction and load.

## Licensing

- sherpa-onnx runtime: Apache-2.0; https://github.com/k2-fsa/sherpa-onnx
- Supertonic 3 weights: OpenRAIL-M; https://huggingface.co/Supertone/supertonic-3
  (use restrictions and attribution requirements apply)

## Separate work

This PR does not add a screen-off `Hey Londe` wake-word detector, an
offline LLM, or offline ASR. It only replaces voice *output* and retains
PR #10's offline-command/ChatGPT routing logic.
