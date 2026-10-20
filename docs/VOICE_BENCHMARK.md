# Measure voice input on the S24 Ultra

The goal is to choose the quickest profile that accepts complete answers reliably in your treadmill setup. No physical-device latency or accuracy result is supplied by a successful build or emulator test.

## What to measure

Measure from the **audible end of the complete spoken answer** to the **first visible frame of the next problem**. This is the delay you perceive while using the app. Time spent solving the problem is excluded. In the constant-answer diagnostic below, consecutive problems look identical, so use the first visible **score increment** instead and label that measurement accordingly.

The app's **Approx. endpoint delay** is a diagnostic interval measured after the last energetic audio frame or changed recognition text. It is not an end-to-end measurement: capture buffering, decoder work, callback scheduling, and screen presentation can add time outside it. A final result can also take a different path from the partial-result silence check. The results screen's mean response time includes solving time and cannot substitute for this benchmark.

| Profile | Configured silence check | What to compare |
| --- | --- | --- |
| Fast | 180 ms | Whether shorter pauses cause a prefix to submit before the number is complete. |
| Balanced | 300 ms | Default starting point for ordinary continuous speech. |
| Careful | 450 ms | Whether added pause tolerance reduces premature submissions enough to justify the delay. |

These settings express a tradeoff, not measured latencies or guaranteed minimum/maximum response times. A pause within a number can look like the end of an utterance in any profile.

## A repeatable setup

Record the phone model, Android/One UI version, app version/build, microphone route, phone position, treadmill speed, fan setting, music volume, and profile. Keep the same phone position and speaking volume when comparing profiles. Use your normal accent and natural speech rather than trying to optimize pronunciation for the recognizer.

Use a second phone or camera to film both the screen and your voice, ideally at 60 fps. Avoid screen recording on the test phone because it changes its workload. Frame timestamps give roughly 17 ms resolution at 60 fps; identifying the end of a spoken word is less exact, so report approximate timings rather than millisecond precision. Keep or delete these recordings yourself; the app does not create them.

Allow the model to finish preparing. Do a short warm-up round, then collect a measured run. Keep the phone's battery/power mode and charging state consistent, and note any thermal throttling or unusual heat.

## Run the comparison

1. On **Train**, select only addition. Open the tune button and set both addition ranges to **1–1**. Every question is now `1 + 1`, so solving time and changing vocabulary do not dominate the comparison.
2. Speak “two” once, then wait for the score to increment before starting another answer. Collect **60 attempted answers** per profile. Record any answer that does not advance within two seconds as a missed/delayed attempt; do not silently remove it from the results. A later advance can be noted separately.
3. Repeat Fast, Balanced, and Careful in a different order on a second pass. Start in a quiet room with the phone microphone. Then repeat at your usual treadmill/fan settings. Add your actual earbuds/headset as a separate condition; connection does not prove which microphone Android selected.
4. For correctly accepted complete answers, review the recording and measure the end-of-answer to score-increment interval in the constant-answer test. Use next-problem appearance in normal rounds. Sort these intervals. Report the median and 95th percentile, plus the number of accepted answers used. With 60 accepted samples, the 57th sorted value is the nearest-rank 95th percentile. Missed or premature answers stay in separate counts so faster results cannot hide lower reliability.
5. Restore normal arithmetic settings and run at least one two-minute round with each promising profile. Include short numbers, tens, hundreds, and longer answers. Confirm that the constant-answer result also holds for ordinary play.

An intentionally paced constant-answer round is a diagnostic, not a meaningful progress score. Its records can be exported and then removed with disposable test history if you prefer a clean training history.

## Challenge the edge cases

Use the custom ranges to make a known answer repeat. For example, addition `1–1` plus `1–1` always expects 2; `10–10` plus `10–10` always expects 20. Speak deliberately wrong and ambiguous phrases to check whether they advance the current question.

| Check | What to observe |
| --- | --- |
| Expected 2; say “twenty” or “two hundred” | Do not accept the initial “two” as the complete answer. Repeat with a natural pause and then a deliberately long pause; document the point where the profile splits the phrase. |
| Expected 100/102; say “one hundred” / “one hundred and two” | Complete number is recognized; the spoken prefix does not cause a premature advance. |
| Repeated expected 2 | One “two” scores once; the next question needs a fresh utterance. Replayed or final callbacks must not add a second score. |
| “Two … no, twenty,” unrelated speech, or two answers in one breath | No silent reinterpretation as an intended correct answer; note any accidental advance. Rephrase as one complete answer to recover. |
| Four/“for,” two/“to,” eight/“ate” | Similar-sounding words can be acoustically indistinguishable. Record how incidental conversation affects false accepts; spelling cannot be inferred from identical audio. |
| Coughs, heavy breathing, fan, footsteps, music, nearby speech | Count advances without an intended answer. Audio energy is not proof that the user spoke a number. |
| Say “pause,” then resume; say “skip” | Each command runs once. The paused timer and microphone stay stopped. |
| Switch apps, lock screen, or receive a call | Capture and timer pause; resuming does not submit a stale answer. |
| Deny permission, then use Keypad | A clear permission/error state appears and manual training remains available. |
| Airplane mode | Offline voice and local history still work after model installation. Cloud sync can remain pending. |

Also leave a live test round unanswered for 30 seconds with your usual treadmill noise and music. Any problem advance or score increment is a false accept and should be recorded. Do not use a word such as “pause” in the background soundtrack during this check unless testing command-trigger behavior deliberately.

## Record the result

Use one row per condition and profile. Count **premature** acceptance separately from **false** acceptance: the first clips an intended longer number; the second advances for a wrong answer or background sound.

| Condition / mic / profile | Attempts | Complete answers accepted | Missed or >2 s | Premature accepts | False accepts | Median delay | P95 delay | Notes |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- | --- |
| Quiet / phone / Balanced | — | — | — | — | — | Unmeasured | Unmeasured | — |
| Treadmill + fan / phone / Balanced | — | — | — | — | — | Unmeasured | Unmeasured | — |
| Treadmill + fan / headset / Balanced | — | — | — | — | — | Unmeasured | Unmeasured | — |

Choose a profile using both accuracy and delay. If Fast feels quicker but clips longer numbers, compare Balanced or Careful, then adjust placement or microphone routing before treating the slower profile as a failure. Keep the underlying recordings and conditions if reporting an issue or claiming a performance improvement.
