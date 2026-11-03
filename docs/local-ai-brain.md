# Quiet OS · Real local AI brain (first test build)

The first Layla-inspired local brain uses an **actual** on-device GGUF model and
native llama.cpp via the free `dev.ffmpegkit-maintained:llama-android:0.1.1` AAR.
No inference keys, server or paid API. The Supertonic Hindi neural speech engine
and Android default assistant/wake integrations are preserved.

## Setup and test

1. Install a **main-branch signed** Quiet APK to update an existing install.
   Pull-request builds use disposable signing keys.
2. Quiet → Settings → **On-device AI brain · Qwen3** → Download / Retry.
   The ~484 MB model downloads using Android DownloadManager over Wi-Fi only.
3. Once the notification completes, tap the same Settings row → **Verify**.
   SHA-256 checks the entire file in the background. It must say **Ready**.
4. Open **Londe assistant** (no need for the wake word on the first test).
   Ask *"Explain binary search in one sentence"* or *"Namaste, kya haal hai?"*.
   A successful reply says **OFFLINE QWEN3** and displays measured tokens/second.
5. Turn off Wi-Fi and mobile data; ask another general question.
   Londe should respond locally. Then try *"What's the weather today?"*;
   it should require the existing internet-connected ChatGPT fallback.
6. Test *"open Spotify"* or *"focus for 25 minutes"*. These must still use
   validated local Android actions. Model-suggested commands must open the
   existing action-review UI, **never** execute silently.
7. If the local model is too slow or inaccurate, tap Settings →
   **On-device AI brain · Qwen3** → Disable local AI. The current ChatGPT
   fallback is unaffected. Optional **Remove model** frees storage; restart
   Quiet first if its native model is already loaded.

## Components

- Qwen3 0.6B Q4_K_M GGUF ~484 MB, sourced from
  https://huggingface.co/gvij/qwen3-0.6b-gguf
- Pinned file SHA-256:
  `3479875d3e4c726f7a20b2181f5e1536aefe9925f284f9ae9997a39a7e0d8dc9`
- Native runtime from Maven Central:
  `dev.ffmpegkit-maintained:llama-android:0.1.1`, with Kotlin coroutines.
- Quantized inference is CPU-only; no remote API is invoked by LocalBrain.
- Local first after deterministic device facts/actions; ChatGPT only for
  insufficient answers, errors and time-sensitive questions.
- Qwen3 (original Qwen model) is licensed under Apache-2.0; quantized
  file publisher retains the model license. Review third-party notices.

## Current limits

This version focuses on proving **real local language inference on Samsung**,
not claiming a full OS replacement. A 0.6B model is small and can produce
incorrect/awkward answers, especially in mixed Hindi-English; it is not an
authoritative confidence classifier. Uncertainty should route to ChatGPT but
hallucinations cannot always be detected. Cloud fallback requires existing
authorized ChatGPT access and network connectivity. Android speech-to-text may
still rely on a system/provider recognizer, and screen-off wake is limited by
Samsung's security model. Local conversation context is per assistant session,
not a cross-restart memory store yet.
