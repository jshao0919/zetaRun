#!/usr/bin/env python3
"""Deterministic desktop DSP/VAD/decoder comparison, not an Android acceptance test.

Requires ffmpeg with flite, numpy, onnxruntime, and vosk. Models are explicit CLI
inputs; generated speech/noise stays in memory unless --save-fixtures is supplied.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import math
import platform
import subprocess
import time
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort
import vosk

RATE = 16_000
FRAME = 512
EXPECTED_MODEL_SHA = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
PHRASES = ("one", "two", "six", "four", "eight", "twenty", "one hundred twenty")
VOCABULARY = (
    "zero oh one two three four five six seven eight nine ten eleven twelve thirteen "
    "fourteen fifteen sixteen seventeen eighteen nineteen twenty thirty forty fifty "
    "sixty seventy eighty ninety hundred thousand million and negative minus point skip pause"
).split() + ["[unk]"]


def rms(audio):
    return float(np.sqrt(np.mean(np.square(audio, dtype=np.float64)))) if len(audio) else 0.0


def scaled(audio, target_rms):
    return audio * (target_rms / max(rms(audio), 1e-12))


def high_pass(audio):
    """Causal second-order Butterworth 80 Hz HP, double-precision recurrence, no gain control."""
    omega = 2 * math.pi * 80 / RATE
    cosine, alpha = math.cos(omega), math.sin(omega) / math.sqrt(2)
    a0 = 1 + alpha
    b0, b1 = (1 + cosine) / (2 * a0), -(1 + cosine) / a0
    a1, a2 = -2 * cosine / a0, (1 - alpha) / a0
    out = np.empty(len(audio), np.float32)
    x1 = x2 = y1 = y2 = 0.0
    for index, value in enumerate(audio):
        x = float(value)
        y = b0 * x + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
        out[index] = y
        x2, x1, y2, y1 = x1, x, y1, y
    return np.clip(out, -1, 1)


def noise(rng, count, low=0.0, high=8_000.0, pink=False):
    frequencies = np.fft.rfftfreq(count, 1 / RATE)
    spectrum = np.fft.rfft(rng.normal(size=count))
    spectrum[(frequencies < low) | (frequencies > high)] = 0
    if pink:
        spectrum /= np.sqrt(np.maximum(frequencies, 1))
    return scaled(np.fft.irfft(spectrum, n=count).astype(np.float32), 1.0)


def motor_fan(rng, count, pink):
    t = np.arange(count) / RATE
    rumble = np.sin(2 * math.pi * 50 * t) + 0.2 * np.sin(2 * math.pi * 100 * t)
    fan = noise(rng, count, low=80, high=6_000, pink=pink)
    return scaled(0.7 * scaled(rumble, 1) + 0.7 * fan, 1)


def huff(rng, duration_ms, target_rms=0.11):
    count = round(RATE * duration_ms / 1000)
    # A bandlimited noise burst with smooth attack/decay is only a breath proxy.
    envelope = np.sin(np.linspace(0, math.pi, count)) ** 1.5
    return scaled(noise(rng, count, low=180, high=5_000) * envelope, target_rms)


def synthesize(text, voice):
    result = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
         f"flite=text={text}:voice={voice}", "-ar", str(RATE), "-ac", "1", "-f", "s16le", "pipe:1"],
        check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    audio = np.frombuffer(result.stdout, dtype="<i2").astype(np.float32) / 32768
    nonquiet = np.flatnonzero(np.abs(audio) > max(0.002, np.max(np.abs(audio)) * 0.02))
    if not len(nonquiet):
        raise RuntimeError(f"Flite emitted no speech for {text!r}")
    audio = audio[max(0, nonquiet[0] - 480): min(len(audio), nonquiet[-1] + 481)]
    return np.clip(scaled(audio, 0.09), -0.9, 0.9)


def fixtures(seed, voices):
    rng = np.random.default_rng(seed)
    for voice in voices:
        for phrase in PHRASES:
            speech = synthesize(phrase, voice)
            leading, trailing = RATE, RATE * 2
            clean = np.pad(speech, (leading, trailing))
            span = [1.0, 1.0 + len(speech) / RATE]
            common = {"expected": phrase, "voice": voice, "speech_span_seconds": span}
            yield {**common, "name": f"{voice}/{phrase}/clean", "condition": "clean", "audio": clean}
            for pink in (False, True):
                for snr in (10, 5):
                    background = motor_fan(rng, len(clean), pink)
                    background = scaled(background, rms(speech) / (10 ** (snr / 20)))
                    kind = "pink" if pink else "white"
                    yield {**common, "name": f"{voice}/{phrase}/{kind}-{snr}dB",
                           "condition": f"motor50_fan_{kind}_{snr}dB",
                           "audio": np.clip(clean + background, -1, 1)}
            after = clean.copy()
            for gap_ms, duration_ms in ((150, 80), (500, 300)):
                start = leading + len(speech) + round(RATE * gap_ms / 1000)
                burst = huff(rng, duration_ms)
                after[start:start + len(burst)] += burst
            yield {**common, "name": f"{voice}/{phrase}/breath-after", "condition": "breath_after_answer",
                   "audio": np.clip(after, -1, 1)}
    count = 5 * RATE
    yield {"name": "noise/silence", "condition": "silence", "expected": None, "audio": np.zeros(count, np.float32)}
    t = np.arange(count) / RATE
    yield {"name": "noise/rumble50", "condition": "rumble50_only", "expected": None,
           "audio": scaled(np.sin(2 * math.pi * 50 * t), 0.12)}
    for pink in (False, True):
        kind = "pink" if pink else "white"
        yield {"name": f"noise/fan-{kind}", "condition": f"fan_{kind}_only", "expected": None,
               "audio": scaled(noise(rng, count, 80, 6_000, pink), 0.08)}
        yield {"name": f"noise/motor-fan-{kind}", "condition": f"motor_fan_{kind}_only", "expected": None,
               "audio": scaled(motor_fan(rng, count, pink), 0.12)}
    for duration_ms in (40, 80, 150, 300, 600):
        bursts = np.zeros(count, np.float32)
        for offset in (0.5, 1.7, 3.2):
            burst = huff(rng, duration_ms)
            start = round(offset * RATE)
            bursts[start:start + len(burst)] += burst
        yield {"name": f"noise/breath-{duration_ms}ms", "condition": f"synthetic_huff_{duration_ms}ms",
               "expected": None, "audio": bursts}


class Vad:
    def __init__(self, path, threshold, release, consecutive):
        options = ort.SessionOptions()
        options.intra_op_num_threads = options.inter_op_num_threads = 1
        started = time.perf_counter_ns()
        self.session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
        self.load_ms = (time.perf_counter_ns() - started) / 1e6
        self.threshold, self.release, self.consecutive = threshold, release, consecutive
        self.call_ms = []

    def analyze(self, audio):
        state = np.zeros((2, 1, 128), np.float32)
        context = np.zeros((1, 64), np.float32)
        probabilities, qualified = [], []
        active, consecutive, onsets = False, 0, []
        for offset in range(0, len(audio), FRAME):
            block = np.pad(audio[offset:offset + FRAME], (0, max(0, offset + FRAME - len(audio))))
            samples = np.concatenate([context, block.reshape(1, FRAME)], axis=1).astype(np.float32)
            started = time.perf_counter_ns()
            probability, state = self.session.run(None, {"input": samples, "state": state, "sr": np.array(RATE, np.int64)})
            self.call_ms.append((time.perf_counter_ns() - started) / 1e6)
            context = samples[:, -64:]
            p = float(probability.squeeze())
            consecutive = consecutive + 1 if p >= self.threshold else 0
            if not active and consecutive >= self.consecutive:
                active = True
                onsets.append((offset + FRAME) / RATE)
            if p < self.release:
                active = False
            probabilities.append(p)
            qualified.append(active)
        return {"probabilities": probabilities, "qualified": qualified, "onsets_seconds": onsets}


def rms_baseline(audio):
    floor, values = 0.006, []
    # The prior Android path used 20 ms energy frames, not Silero's 32 ms windows.
    for offset in range(0, len(audio), 320):
        energy = rms(audio[offset:offset + 320])
        voiced = energy > max(0.015, floor * 2.8)
        if not voiced:
            floor = floor * 0.985 + energy * 0.015
        values.append(voiced)
    return any(values)


def numeric(text):
    words = text.split()
    disallowed = {"[unk]", "skip", "pause", "point", "minus", "negative"}
    return bool(words) and words != ["and"] and not (set(words) & disallowed) and all(word in VOCABULARY for word in words)


def normalized(text):
    return " ".join(word for word in text.lower().split() if word != "and")


def decode(model, audio):
    recognizer = vosk.KaldiRecognizer(model, RATE, json.dumps(VOCABULARY))
    recognizer.SetWords(True)
    results = []
    for offset in range(0, len(audio), FRAME):
        pcm = (np.clip(audio[offset:offset + FRAME], -1, 1) * 32767).astype("<i2").tobytes()
        if recognizer.AcceptWaveform(pcm):
            result = json.loads(recognizer.Result())
            if result.get("text"):
                results.append(result)
    final = json.loads(recognizer.FinalResult())
    if final.get("text"):
        results.append(final)
    return results


def candidate_proxy(result, vad, min_confidence):
    """Final-only offline proxy; does not emulate app partials, rearming, or answer matching."""
    words = result.get("result", [])
    if not numeric(result.get("text", "")) or not words or min(w.get("conf", 0) for w in words) < min_confidence:
        return False
    start = max(0, math.floor((words[0]["start"] - 0.15) * RATE / FRAME))
    end = min(len(vad["qualified"]), math.ceil((words[-1]["end"] + 0.15) * RATE / FRAME))
    return any(vad["qualified"][start:end])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vosk-model", required=True, type=Path)
    parser.add_argument("--vad-model", required=True, type=Path)
    parser.add_argument("--output-json", required=True, type=Path)
    parser.add_argument("--save-fixtures", type=Path)
    parser.add_argument("--voices", nargs="+", default=["slt", "kal"])
    parser.add_argument("--seed", type=int, default=20261021)
    parser.add_argument("--threshold", type=float, default=0.6)
    parser.add_argument("--release", type=float, default=0.35)
    parser.add_argument("--consecutive", type=int, default=2)
    parser.add_argument("--min-confidence", type=float, default=0.62)
    args = parser.parse_args()
    digest = hashlib.sha256(args.vad_model.read_bytes()).hexdigest()
    if digest != EXPECTED_MODEL_SHA:
        parser.error(f"Expected pinned Silero v6.2.3 model SHA256 {EXPECTED_MODEL_SHA}; got {digest}")
    vosk.SetLogLevel(-1)
    model = vosk.Model(str(args.vosk_model))
    vad = Vad(args.vad_model, args.threshold, args.release, args.consecutive)
    cases = []
    for fixture in fixtures(args.seed, args.voices):
        raw = fixture.pop("audio").astype(np.float32)
        filtered = high_pass(raw)
        activity = vad.analyze(filtered)
        raw_results, filtered_results = decode(model, raw), decode(model, filtered)
        raw_text = " ".join(result["text"] for result in raw_results)
        filtered_text = " ".join(result["text"] for result in filtered_results)
        candidates = [result["text"] for result in filtered_results if candidate_proxy(result, activity, args.min_confidence)]
        expected = fixture["expected"]
        case = {**fixture, "seconds": len(raw) / RATE, "raw_rms_qualified": rms_baseline(raw),
                "silero_qualified": any(activity["qualified"]), "silero_onsets_seconds": activity["onsets_seconds"],
                "silero_max_probability": max(activity["probabilities"]),
                "silero_qualified_ms": sum(activity["qualified"]) * FRAME / RATE * 1000,
                "raw_vosk_text": raw_text, "filtered_vosk_text": filtered_text,
                "raw_numeric_finals": [r["text"] for r in raw_results if numeric(r["text"])],
                "filtered_final_confidences": [{"text": r["text"], "minimum": min((w.get("conf", 0) for w in r.get("result", [])), default=0)} for r in filtered_results],
                "offline_filtered_final_candidates": candidates,
                "raw_expected_match": None if expected is None else normalized(raw_text) == normalized(expected),
                "filtered_expected_match": None if expected is None else normalized(filtered_text) == normalized(expected),
                "filtered_candidate_expected_match": None if expected is None else len(candidates) == 1 and normalized(candidates[0]) == normalized(expected)}
        if expected:
            speech_end = fixture["speech_span_seconds"][1]
            active_frames = [index for index, active in enumerate(activity["qualified"]) if active]
            case["last_qualified_frame_end_seconds"] = (active_frames[-1] + 1) * FRAME / RATE if active_frames else None
            case["post_speech_reference_windows"] = []
            # These are the actual burst windows in breath_after_answer fixtures; on clean
            # fixtures they inspect identical silent windows to expose endpoint differences.
            for gap_ms, duration_ms in ((150, 80), (500, 300)):
                start = speech_end + gap_ms / 1000
                end = start + duration_ms / 1000
                indices = [i for i in range(len(activity["probabilities"]))
                           if i * FRAME / RATE < end and (i + 1) * FRAME / RATE > start]
                case["post_speech_reference_windows"].append({
                    "start_seconds": start, "end_seconds": end,
                    "maximum_silero_probability": max((activity["probabilities"][i] for i in indices), default=0),
                    "any_qualified_frame": any(activity["qualified"][i] for i in indices),
                })
        if args.save_fixtures:
            args.save_fixtures.mkdir(parents=True, exist_ok=True)
            path = args.save_fixtures / (fixture["name"].replace("/", "_").replace(" ", "-") + ".wav")
            with wave.open(str(path), "wb") as target:
                target.setnchannels(1)
                target.setsampwidth(2)
                target.setframerate(RATE)
                target.writeframes((np.clip(raw, -1, 1) * 32767).astype("<i2").tobytes())
        cases.append(case)
        if expected and not case["filtered_candidate_expected_match"]:
            print(json.dumps({"failed_proxy_case": case["name"], "text": filtered_text,
                              "qualified": case["silero_qualified"], "confidence": case["filtered_final_confidences"]}), flush=True)
    groups = {}
    for case in cases:
        group = groups.setdefault(case["condition"], {"cases": 0, "raw_expected_matches": 0,
            "filtered_expected_matches": 0, "filtered_candidate_matches": 0, "raw_rms_qualified": 0,
            "silero_qualified": 0, "raw_numeric_final_clips": 0, "offline_filtered_candidate_clips": 0})
        group["cases"] += 1
        for source, target in (("raw_expected_match", "raw_expected_matches"), ("filtered_expected_match", "filtered_expected_matches"),
                               ("filtered_candidate_expected_match", "filtered_candidate_matches"), ("raw_rms_qualified", "raw_rms_qualified"),
                               ("silero_qualified", "silero_qualified"), ("raw_numeric_finals", "raw_numeric_final_clips"),
                               ("offline_filtered_final_candidates", "offline_filtered_candidate_clips")):
            group[target] += bool(case[source])
    warm = vad.call_ms[1:]
    report = {"scope": "Desktop synthetic DSP/VAD/Vosk comparison; not full Android acceptance or real treadmill/breath validation",
              "host": {"platform": platform.platform(), "architecture": platform.machine(), "cpu": platform.processor(), "python": platform.python_version()},
              "versions": {name: importlib.metadata.version(name) for name in ("numpy", "onnxruntime", "vosk")},
              "settings": {"sample_rate": RATE, "frame_samples": FRAME, "context_samples": 64,
                  "state_shape": [2, 1, 128], "silero_sha256": digest, "seed": args.seed, "voices": args.voices,
                  "qualify_threshold": args.threshold, "release_threshold": args.release,
                  "consecutive_frames": args.consecutive, "minimum_final_word_confidence": args.min_confidence,
                  "high_pass_hz": 80, "automatic_gain_control": False,
                  "quiet_ms_reference_only": 300, "stable_ms_reference_only": 150},
              "desktop_cpu_vad_timing": {"model_load_ms": vad.load_ms, "first_inference_ms": vad.call_ms[0],
                  "warm_inference_count": len(warm), "warm_p50_ms": float(np.percentile(warm, 50)),
                  "warm_p95_ms": float(np.percentile(warm, 95)), "warm_p99_ms": float(np.percentile(warm, 99))},
              "groups": groups, "cases": cases}
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"groups": groups, "desktop_cpu_vad_timing": report["desktop_cpu_vad_timing"], "report": str(args.output_json)}, indent=2))


if __name__ == "__main__":
    main()
