# Voice-first permission before online LLM fallback

When offline Qwen fails, rejects its own answer, or is unavailable, Londe asks
out loud (using the user's configured voice output model):

> I couldn't answer that offline. Shall I use GPT online?

- No network request occurs until a newly recognized, clear **yes** follows
  this specific question. A clear **no**, silence, recognizer failure, unknown
  utterance, or time-out cancels without transmitting the question.
- The approval window is 22 seconds **after the spoken prompt completes**.
- No tap, modal action buttons, remembered consent, or auto fallback. Consent
  is per question and cannot be supplied by an LLM-generated output.
- On Home, Quiet opens its existing Londe voice console to ask and recognize
  the consent reply, avoiding a second competing microphone recognizer.
- The approved cloud request sends only the pending question as conversation
  input; Qwen-provided notes and local-only context remain private.
- Internet access and authorized ChatGPT connection are checked before the
  prompt and again before the cloud request.
- If the microphone/speech model/voice synthesis isn't ready, the cloud
  request remains unapproved.

Real-device testing should cover Hindi ("haan", "nahi"), slow Supertonic
voice finishing, screen-off transitions, speech errors and false positives.
