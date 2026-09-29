# Voice Enrollment/Verification Fix — Changes in this build

File changed: `app/src/main/java/com/soltini/app/security/VoiceUnlockActivity.kt`

## Root cause found
`VoiceProfilesSection.kt` launches `VoiceUnlockActivity` (enroll or verify mode)
without ever checking the RECORD_AUDIO runtime permission. If that permission
is missing or was revoked, `AudioRecord` fails to initialize and the old code
silently returned `null`, which the UI always reported as:
"Audio too quiet or short. Please repeat phrase X."
This is misleading — the mic never even got a chance to record — and matches
exactly the "the system just doesn't work" symptom.

## What was changed
1. Added a `hasMicPermission` state check (`ContextCompat.checkSelfPermission`)
   when the screen opens.
2. Added a proper `ActivityResultContracts.RequestPermission()` launcher.
   If mic permission is missing, the action button now says
   "Grant Microphone Permission" and requests it — instead of silently failing.
3. Replaced the old `ByteArray?` return type of `recordAudioBuffer()` with a
   `sealed class RecordAudioOutcome` (`Success`, `PermissionMissing`,
   `MicUnavailable`, `TooShort`) so the UI shows the REAL reason a recording
   failed instead of collapsing everything into "audio too quiet".
4. Enrollment flow now also distinguishes "sample rejected by acoustic
   analysis (too quiet / no speech)" from "finalize failed (< 3 valid
   samples)" — previously both gave a generic message.
5. Same permission-guard applied to the verification ("Authenticate Voice")
   button before calling `VoiceProfileManager.recordAndIdentify()`.

## What was NOT changed (and why you should know)
The actual speaker-recognition engine in `VoiceProfileManager.kt` is a
hand-rolled 34-dimension acoustic feature vector (Mel-band energies +
spectral centroid/spread/flux + zero-crossing rate) compared with cosine
similarity, threshold 0.74. This is NOT a neural speaker-embedding model
(no x-vector/ECAPA/d-vector network). It can work reasonably for a single
enrolled owner in a quiet room, but:
- It is much weaker at telling different speakers apart than production
  voice-ID systems (Google Voice Match, Alexa etc. use trained neural nets).
- Expect more false accepts/rejects in noisy environments or with similar-
  sounding voices (e.g. family members).
- There is no anti-spoofing / replay-attack detection at all — a recording
  of the enrolled user's voice would likely pass.

I did not rewrite this into a real ML model because that requires bundling
a trained embedding model (e.g. TFLite ECAPA-TDNN) which is a much bigger
task than a bug fix — happy to help build that separately if you want it.

## IMPORTANT — I could not build/run this project
This sandbox has no Android SDK and no internet access, so I could not run
`./gradlew build` to confirm this compiles cleanly end-to-end or to catch
every possible error in the ~100+ other files in this project. Please:
1. Open this folder in Android Studio.
2. Let Gradle sync (this will download dependencies — needs internet).
3. Build once (Build > Make Project) and fix anything Android Studio flags,
   before doing a full run.
If you hit specific compiler errors after that, paste them to me and I'll
fix them directly — that's much faster than me guessing blind.
