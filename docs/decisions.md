# Accepted trade-offs

Choices that can look like bugs or omissions. Each was decided on purpose; change one only with a concrete reason.

## Dictation
- **Finalizing can't be cancelled by a tap.** Stalls end through the STT and cleanup timeouts instead.
- **Focus loss cancels only while Listening.** Once stopped, a session always finishes.
- **No retry or skip for cleanup.** Any failure inserts the raw transcript with a notice.
- **The previous clipboard isn't restored** after a paste fallback.
- **The cleaned text is never shown in the panel**, only the STT transcript.

## Audio
- **VAD assumes no provider bills a session that got no audio.** Soniox real-time is the exception (wall-clock).
- **Keep-alive zeros are real audio and are billed** (~1 % of the withheld silence). Pings alone don't stop
  servers from ending an idle stream.
- **The VAD hangover isn't scaled by Speed.** A live model gets 1650 / speed ms of tail.
- **Sonic runs on the main thread** (measured < 0.3 ms per chunk on a desktop JVM; phone cost unmeasured). Moving
  it would need per-chunk thread hops.
- **Recording rate is per session, not resampled in Kotlin**, except a 16 kHz copy for the VAD.

## STT providers
- **All OpenAI realtime models stream**, even those without live text: less wait at stop.
- **OpenAI and Deepgram don't reconnect**; only the Gateway does (once). Every Gateway `error` part is retried
  once, since error names can't be told apart.
- **The Gateway's send-drain timeout is terminal**: a stuck uplink won't improve with a full resend.
- **The Gateway key isn't sent as a WebSocket subprotocol** (the server may echo it). Header auth works today.
- **`gatewaySttLive` can go stale** if the catalog retags a model, until the model is re-saved.
- **Soniox's mode reaches the warm-up only at the next settings change** when it comes from the catalogue.
- **A typed model id not in a loaded catalogue** gets no refetch (Soniox: strict hints omitted; Gateway: live).
- **Deepgram Flux (`/v2/listen`) is out of scope.**

## On device
- **With Skip Silence off, per-phrase models show text only every ~20 s** (the segment cap) and the rest at stop.
- **Nemotron at Speed >= ~1.6x with Skip Silence on**: a phrase's last words show only when the next phrase
  starts or at stop. The final text is complete.
- **The STT timeout is ignored**: a native decode can't be aborted, so a short timeout only throws text away.
- **Downloads have no WorkManager or notification.** They stop when the process dies and resume on the next tap.
- **Starting a download deletes other models' partial files**, so an abandoned partial can't hold ~670 MB.
- **No migration for the default model change to Nemotron** (unreleased branch).

## Privacy
- **Filler-word language detection may pass the transcript to the system TextClassifier** (on-device, no
  network, but outside Iris's process), for every provider incl. On device.
- **The language setting is trusted as evidence** even where the provider treats it as a hint or ignores it.
- **Provider options are stored unencrypted**; the UI warns not to put keys there.
- **Favicons for custom providers are fetched from Google's favicon service** by registrable domain.

## Statistics
- **Time Saved assumes 38 WPM typing** and counts whitespace-separated words, so CJK is undercounted.
- **A filler-only on-device dictation still records its sample**, though it ends as "No speech detected".
- **A speed change mid-dictation skews that one sample.**
- **A deleted custom provider's stats stay** under its last name until Reset.
- **Billed audio ignores** keep-alive zeros, padding and per-request minimums.

## UI
- **Small icon buttons and text links (licence, dontkillmyapp.com) are below the 48 dp touch guideline** (dense design;
  they sit edge to edge).
- **No confirmation on deletes** (prompt, provider, model, statistics reset).
- **Sub-pages open fresh**: their saved state is dropped once back on Home.
- **Release builds are arm64-v8a only**: 32-bit devices can't install the app.
- **The Battery row checks only Android's own battery setting.** OEM task killers (HyperOS autostart, Samsung
  sleeping apps, ...) can't be read through public APIs, so the row links to dontkillmyapp.com instead. There is
  no "Allow" dialog (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`): Play allows it only for a few app types; Setup opens
  App info. Only Unrestricted counts as OK, so a fresh install (Optimized by default) shows the row in the
  failure colour.
- **No bubble blur below Android 13.** The blur needs a bigger transparent window around the bubble, and only
  Android 13+ can stop that margin from catching taps meant for the app below (`setTouchableRegion`).

## Backup
- **Restore replaces everything**: keys and settings not in the backup are removed. No confirmation (as deletes).
- **Statistics and downloaded on-device models aren't in the backup**; a restored on-device model may need a download.
- **The bubble position applies the next time the service starts** (read once at connect).
- **PBKDF2-SHA256 with 600,000 iterations** (OWASP); a few seconds on slow phones is accepted. No minimum password
  length; the password can't be recovered.
- **Unknown setting keys in a backup are kept**, not dropped (dropping needs a hand-kept registry of every key).
- **A failure during the write phase can leave a partly restored state** (at most keyless providers, never an
  orphaned key); restoring again fixes it.
- **The password field's text can't be wiped from memory** (Compose holds a String); only the CharArray copy is.
- **Recreating the activity mid-operation** (rotation, dark-mode switch) shows no result: a backup is cancelled and
  its file deleted, a restore still completes. The password must be entered again and a restore file picked
  again (neither is saved).

## Custom Words
- **Custom words are sent to cloud STT and cleanup providers** (often personal names). They are never logged; only
  a count of dropped Deepgram keyterms is. Deepgram gets `redact_usage=true` with them, so its usage records
  show `[REDACTED]`. The master toggle is off by default.
- **The fuzzy correction has false positives** ("nation" -> "Notion", "James" -> "Jamius", "cloud" -> "Claude",
  "sonic" -> "Soniox", "market" -> "Marketo" when those are in the list). It is stricter than Handy's (phonetic
  factor 0.5, fuzzy only for keys of 5+ characters, a fuzzy span yields to a closer match inside it, plurals such
  as "Teslas" are kept), and "Correct Similar-Sounding Words" turns it off.
- **Custom words that are also common words are capitalised everywhere** when that switch is on ("go" -> "Go",
  "rust" -> "Rust", "notion" -> "Notion"). Only all-caps acronyms under 5 letters ("US", "IT", "AI") leave an
  all-lowercase word as is ("let us know"); "Us" still becomes "US".
- **That switch off means no in-app change at all**, not even the exact casing fix. STT biasing and the cleanup
  glossary still apply.
- **Live (partial) text isn't corrected**, only the final transcript.
- **On device and Vercel AI Gateway STT get no biasing** (sherpa-onnx hotwords are a follow-up; Gateway forwarding
  is unverified), only the correction and the cleanup glossary.
- **Deepgram nova-2 isn't biased** (its legacy `keywords` has another syntax); Nova-3 only (`keyterm`).
- **Deepgram terms over the token budget are dropped per request** (estimated, 450 of Deepgram's 500 tokens; the
  rest of the list in order).
- **OpenAI: `gpt-realtime-whisper` and `gpt-4o-transcribe-diarize` get no words** (no prompt for them per the docs);
  `whisper-1` reads only the last 224 tokens of its prompt.
- **Only 1-3 word, ASCII-only matches are corrected**; accented, non-Latin and non-ASCII custom words are still sent
  to providers but never corrected in-app. A hyphenated custom word transcribed as one token ("gpt-5")
  is not corrected either (the hyphen ends the run; "gpt 5" is).
- **An over-cap list in a restored backup is cut silently** (entries over 60 characters dropped, then the first
  100 kept); typing over the caps in the UI shows an error and isn't saved.
