# Londe: Qwen first, ChatGPT only on fallback

General questions now follow this strict order:

1. Trusted device facts (time/date/focus) and explicitly authorized local actions are served directly; no model needed.
2. When the verified local Qwen GGUF model is installed and enabled, **Qwen is invoked for every general question**. No keyword prefilter skips it, including for weather/news questions.
3. **After** Qwen returns, Quiet validates the generated answer. Incomplete/invalid output, refusals, missing results and requests for live information require fallback. No tiny model can reliably detect its own hallucinations, so quality still needs testing.
4. If fallback is needed, check Android's validated internet connectivity and ChatGPT authorization **before** making a network request.
5. With no internet, show: "Qwen tried locally but couldn't produce a reliable answer. Your phone is offline, so I didn't contact ChatGPT." Never read a raw OpenAI connection exception aloud.
6. With internet and authorization, the existing ChatGPT client handles the question. If that fails, show a short failure message, without exposing tokens or internal API text.

The same order applies in **Londe voice console** and **Quiet Home**. Existing Supertonic speech and download/verification of Qwen remain unchanged.

### Manual tests

- Airplane mode, with verified Qwen enabled: ask "Explain a Java HashMap". Expect `OFFLINE QWEN3`, no internet use.
- Airplane mode: ask "What's the latest AI news today?" Expect Qwen attempt followed by a local **offline fallback unavailable** message, not a network exception.
- Internet enabled: same live news question should attempt Qwen first and then ChatGPT.
- Force invalid Qwen output: expect fallback, not a generated raw JSON or hallucinated OpenAI networking issue.
- Turn off local Qwen in Settings: normal existing online mode remains available.
- Verify local time, focus and installed app commands still work without invoking Qwen.
