# Quiet v0.7 — optional AI gateway

Quiet runs local commands without any server. To enable **ASK AI**, you must run your own gateway and configure it under Quiet **Settings → Configure AI gateway**.

## Run the reference gateway

Use Node 20+ on a machine you control:

```sh
export OPENAI_API_KEY="your-openai-api-key"
export QUIET_GATEWAY_TOKEN="$(openssl rand -hex 32)"
node gateway/server.mjs
```

The server defaults to `127.0.0.1:8787`. It cannot be reached directly from a phone on another device. Deploy it behind a real HTTPS reverse proxy, with valid TLS, at a path like `https://yourdomain.example/plan`. Configure that URL and the **gateway token** on your phone. Never put `OPENAI_API_KEY` into the app or commit it to GitHub.

The gateway uses the OpenAI Responses API with structured JSON output (default model `gpt-4.1-mini`, configurable using `OPENAI_MODEL`). Your API account may incur charges. The implementation is a reference for personal experimentation, not a production service. Add robust rate limiting, monitoring, spending caps, persistent authentication, and your chosen hosting before exposing it publicly.

## Privacy and safety

- Only the **specific command** typed into the Home bar is sent after tapping ASK AI and confirming. It is then forwarded to the model provider. No app list, notes, screen time, or workout history is uploaded.
- All model output is treated as untrusted. Android validates the allowed actions, time bounds, equipment values, existing non-hidden app names, and the maximum of one screen-opening action.
- A second dialog previews the returned plan; nothing executes until you approve.
- The gateway token is encrypted with Android Keystore; the provider's API key only lives on the server. This is not end-to-end secrecy against an untrusted server.
- The example's in-memory rate limit is for single-process use only.
- If the network, token, or model is unavailable, local commands continue to work. The app never automatically uploads a failed local command.
- Voice recognition still depends on the phone's Android speech provider, which might be online.

This is model-backed **intent planning**, not a general chatbot, background agent, or unrestricted phone controller.
