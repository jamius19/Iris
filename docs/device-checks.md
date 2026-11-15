# Device checks

Manual checks on a real phone (ideally more than one OEM). Nothing here is covered by unit tests, and the emulator
mic is silent, so every real-voice item is still unverified. Run the relevant group after touching that area.

## Capture and insertion
- Mic capture works from the bubble while another app is in front. If silenced (`isClientSilenced`), add a
  `microphone` foreground service started from the bubble tap.
- Insertion in a TextView `EditText`, a Compose `TextField` and a WebView `<input>` / `<textarea>`, with the cursor
  mid-text and with a selection: spacing and final cursor position are right.
- A field that rejects `ACTION_SET_TEXT` gets the text via clipboard + paste.
- Empty WhatsApp / Gmail box: no placeholder ("Message") in the inserted text.
- Keyboard-only field (ChatGPT's chat box right after a fresh launch): bubble shows, text is inserted.
- Changing field during cleanup: text is copied with "field changed".
- Sideloaded APK: the accessibility toggle needs App info > ⋮ > "Allow restricted settings".

## Overlay
- Rotation while Listening and Finalizing: bubble re-snaps, panel follows, session isn't lost.
- Waveform -> ✕ morph and pill slide are smooth on both edges; dark mode colours update while shown.
- Dragging from the pill never stops, dragging from the ✕ never cancels; tapping the gap stops.
- Near the IME / bottom the pill opens upward, and flips without a flicker when the IME shows or hides.
- Re-show after focus loss mid-Finalizing restores spinner and panel without replayed animations.
- Panel sliders apply live in another app; at 90 % width the panel stays on screen on both sides and in landscape.
- TalkBack reads "Start / Cancel / Stop dictation" and double-tap works on the ✕ and the pill.
- Show / hide: the bubble fades in blurred -> sharp and out sharp -> blurred, no hard edge, also with the pill out
  and on both edges; rapid focus changes reverse smoothly. Android 12 and below: fade + zoom only.
- Android 13+: taps ~16 dp beside the bubble and pill reach the app below; the circle sits where it did before;
  landscape with the keyboard up and the bubble near the top shows the whole pill.
- The waveform draws in on show and on return to rest (Done, error, ✕), un-draws on hide; it, the glyphs and the
  pill look centred and proportionate in light and dark.
- Leaving a field while Listening, Finalizing or on a tick / !: the bubble fades out as it looked. Returning
  mid-fade shows the current state, no stale ✕.

## VAD and Speed (real voice)
- First and last words aren't clipped, incl. speaking right after the tap and stopping right after the last word.
- A 10 s pause is dropped (`VAD: sent X ms of Y ms`). A pause >= 60 s on mobile data still gives a transcript on
  every live model. Silence only = instant "No speech detected" with no commit / `audio-done` / upload.
- 1.5x and 2.0x on a live and an after-stop model per provider: transcript correct, live text still streams, log
  `Speed Xx: sent A ms as B ms` with B ≈ A / speed. The waveform stays smooth at 2.0x.
- A dictation under 2 s at 2.0x with an upload model is transcribed (WAV padding path).
- The provider's usage page shows fewer audio seconds at 2.0x.

## Cloud STT
- OpenAI: an upload model (id outside the realtime list) shows no panel, a spinner after stop, then inserts. A
  2-3 min dictation on mobile data finishes within the timeout.
- Gateway: default model plus a 16 kHz one (Grok chunks don't duplicate, MAI / Gemini partials don't double). A
  network drop up to ~10 s reconnects and gives a complete transcript, also at 2.0x; a second drop shows an error.
  A 10 min after-stop dictation (VAD off) succeeds; on a 413, reuse `splitAtPauses`.
- Deepgram: `nova-3` with `en` and with a blank language (mixed speech). Console usage of a VAD-gapped session ≈
  the sent audio, not wall time. "Disable Realtime Streaming" is billed as pre-recorded.
- Soniox: `stt-rt-v6` with hints "en, bn"; no 408 on a long pause. `stt-async-v6` leaves no files or
  transcriptions in the console, also after a poll timeout. EU region with an EU key works; a US key on EU fails.
  App start offline, then a dictation online: the catalogue is fetched at session start.
- A bad key on each provider shows the key message.

## On device
- Each model downloads and transcribes a real voice. A download over mobile data that drops resumes.
- Per-phrase models: text appears as you pause; a phrase over 20 s is split without lost words.
- Whisper with `de` and with a blank language.
- Nemotron: live text while recording; decode faster than real time on a mid-range phone (`Decoded X ms of audio
  in Y ms`, Y < X); the first word after the tap isn't clipped with Skip Silence off; last words kept at stop; a
  5+ min dictation; ✕ then the bubble again at once starts a new stream with no hang.
- Parakeet load time on a mid-range phone, and Finalizing stays within the deadline.
- Memory (`adb shell dumpsys meminfo dev.jamius.iris`) after preload and after the Unload Model delay
  (`Released <id>`). A changed delay applies mid-countdown; "Never" keeps it loaded.
- Deleting or switching the model, or a memory trim, during a dictation waits for the session.
- An R8 release build on arm64 loads the recognizers and the VAD.

## Cleanup and filler words
- OpenRouter: default model, a non-OpenAI model and a reasoning level. 402 / 503 show as notices with the raw text
  inserted. Provider options are accepted on both providers; an impossible routing shows the error notice.
- Custom providers: Groq (`https://api.groq.com/openai/v1`) and OpenAI (`https://api.openai.com/v1`). Send JSON
  Schema off works with a server that rejects `response_format`. The favicon shows, or the cloud when offline.
- Filler words: "um, uh, hmm" removed with `en` set. With a blank language, English "um" is removed and Portuguese
  / German "um" is kept. The system TextClassifier answers from the accessibility service's process, under 1 s.

## Settings and statistics
- Light and dark pages match the Figma PNGs. Page slides and predictive back run to the end with no early cut.
- Autosave on blur, Done, back, home and rotation; invalid values keep the error and aren't saved.
- Dropdown panels open above the IME and never under the nav bar. Large font scale keeps controls unclipped.
- TalkBack reads dropdowns, switches, sliders (name + value), icon buttons and a saved key ("API Key, Saved").
- Statistics match the logs: Speed ≈ sent audio ÷ decode time, Transcription Time ≈ median `stop->transcript`,
  cleanup p90 ≈ the high end of `cleanup X ms`. "No speech detected" and ✕ add nothing. Stats survive a restart.
- Battery Usage row: set Unrestricted, Optimized and Restricted in App info, return to Home: the row shows each
  (Unrestricted in blue, no Setup; the others in the failure colour with helper and Setup). On HyperOS, check
  "No restrictions" reads as Unrestricted.
- Battery Setup opens Iris's App info on stock Android (emulator) and on HyperOS; the helper's path matches.
- "Phone keeps turning Iris off?" opens the browser at the phone maker's dontkillmyapp.com page (the home page on
  an unlisted maker, e.g. the emulator if its maker isn't Google). TalkBack reads the row's title and mode
  ("Battery Usage", "Optimized"), "Set up Battery Usage" on Setup, and the link as a link that opens on double-tap.
- Footer update check: opening the app shows "Version x · Checking for update", then "Up to date" (also
  while the repo has no release), or "Update available" in the failure colour once a release with a higher
  `v<versionName>` tag exists; tapping it opens the release page in the browser. Offline: only the version.
- Footer re-check: tapping "Up to date" shows "Checking for update", then the new result; TalkBack reads it as a
  button with "Check for update".

## Backup and restore
- Create Backup to local storage and to Drive; the file name is `iris-backup-<date>.irisbackup` (no `.bin` added).
  Cancelling either picker shows nothing (a cancelled restore picker keeps any earlier selection).
- Create Backup is disabled until its password is entered. Restore starts with only Choose Backup File; picking a
  file shows its name, a password field and Restore Backup (disabled until a password is entered).
- Success clears the backup password; a restore resets the card to Choose Backup File with its password cleared.
  A wrong restore password keeps the file and the password for a retry.
- Restore that file after uninstall + reinstall: every page matches, every key works (one dictation per keyed
  provider, one cleanup), custom providers' favicons and model lists load, the Soniox catalogue reloads.
- Restore removes a key and a custom provider added after the backup; statistics and downloaded models stay.
- Wrong password, a truncated copy and a non-Iris file (e.g. a photo) each show their message and change nothing.
- Backup / restore time on a mid-range phone (KDF); buttons disabled while running; leaving the page mid-restore
  still completes it.
- Rotation with the backup picker open: the password is gone, the result asks for it again, no empty backup file is
  left. Rotation with the restore picker open: the picked file is shown, its password must be entered.
- Rotation after picking a restore file: the card is back to Choose Backup File (nothing is saved).
- Rotation while Creating… / Restoring…: no message; a backup leaves no file, a restore still completes.
- TalkBack: both "Password" fields, "Show Password" / "Hide Password", the selected file name, buttons and outcome
  text are read.

## Custom Words (real voice)
- A name the provider normally misspells comes out right on OpenAI (`gpt-live-transcribe` and `whisper-1`),
  Deepgram `nova-3` streaming and upload, Soniox `stt-rt-v6` and async.
- Deepgram with 100 long phrases doesn't fail (token budget; the log shows the dropped count); nova-2 still works.
- With Correct Similar-Sounding Words on, an on-device dictation gets the fuzzy fix ("sonics" -> "Soniox" with
  Soniox in the list); with it off (the default) the text is left as transcribed.
- AI Cleanup keeps a custom spelling.
- Master off sends nothing (provider logs / usage show no keyterms, keywords, prompt or context).
- Settings: Post Processing -> Custom Words, back (button and predictive gesture) returns to Post Processing, then
  Home; the card title and page header read "Custom Words (Beta)". Correct Similar-Sounding Words sits in the
  master toggle's card under a separator, off on a fresh install, dimmed but still switchable with the master off.
- Add Word: plus and keyboard Done add the trimmed word to the end of the list and clear the field (keyboard stays
  open after Done); a blank or case-insensitive duplicate adds nothing; each row's trash deletes it; the list
  survives leaving the page and an app restart.
- Add Word errors: over 60 characters, or a 101st word, shows the error and adds nothing; typing clears it.
- Supported model (OpenAI `gpt-live-transcribe`, Deepgram `nova-3`, Soniox) with the toggle on and words: the
  Transcription Provider shows "Custom words feature is on, so additional charges might apply."; gone with the
  toggle off or no words; the Custom Words card has no note.
- Unsupported model (on device, Vercel AI Gateway, Deepgram `nova-2`, OpenAI `gpt-realtime-whisper`): no
  Transcription note; the Custom Words card shows "Not sent to this model. Only the correction and LLM cleanup
  apply." with the correction and AI Cleanup on, "...Only the correction applies." with cleanup off, "...Only the
  LLM cleanup applies." with the correction off, "Not sent to this model, so custom words have no effect." with
  both off; the card still opens.
- Post Processing with the prompt field focused and edited, tap the Custom Words card: the edit is saved and the
  keyboard closes.
- `whisper-1` with Skip silence off and one short word dictated: the "Glossary:" prompt is not returned as the
  transcript.
- A 5 min dictation with 100 custom words: no visible stall at stop (the correction runs on the main thread).
- R8 release build on an arm64 phone launches and corrects (stdlib calls in `CustomWords.kt`, `Kt__*` rule).
