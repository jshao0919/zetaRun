#!/usr/bin/env python3
"""Prepare checksum-pinned offline English ASR and Silero speech detection models.

The release APK is a transport archive; none of its application code is used.
Keeping this official GitHub mirror makes builds independent of the model site's uptime.
"""
import hashlib
import os
from pathlib import Path
import shutil
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
URL = "https://github.com/alphacep/vosk-android-demo/releases/download/2021-04/vosk-android-demo-0.3.23.apk"
SHA256 = "70b354708afbbbe8aa902e94584f39d379fd3104329bc37a37c9f4f42935467c"
TARGET = ROOT / "app/src/main/assets/model-en-us"
CACHE = ROOT / ".model-cache"
VAD_URL = "https://raw.githubusercontent.com/snakers4/silero-vad/5cd7945676eb32225748052e2e6a0580e4686a08/src/silero_vad/data/silero_vad.onnx"
VAD_SHA256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
VAD_TARGET = TARGET.parent / "silero-vad.onnx"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else hashlib.sha256(stream.read()).hexdigest()


def prepare_speech_model():
    marker = TARGET / ".source-sha256"
    if marker.exists() and marker.read_text().strip() == SHA256 and (TARGET / "am/final.mdl").is_file() and "--force" not in sys.argv:
        print("Offline English model already prepared.")
        return
    CACHE.mkdir(exist_ok=True)
    archive = CACHE / "official-vosk-demo.apk"
    if not archive.exists() or digest(archive) != SHA256:
        partial = CACHE / "download.partial"
        print("Downloading the official English speech model (first build only)…")
        try:
            with urllib.request.urlopen(URL, timeout=90) as response, partial.open("wb") as out:
                shutil.copyfileobj(response, out)
            if digest(partial) != SHA256:
                raise RuntimeError("Speech model checksum mismatch; refusing this download.")
            os.replace(partial, archive)
        finally:
            partial.unlink(missing_ok=True)
    stage = CACHE / "model-staging"
    shutil.rmtree(stage, ignore_errors=True)
    stage.mkdir()
    try:
        with zipfile.ZipFile(archive) as zipped:
            for entry in zipped.infolist():
                prefix = "assets/model-en-us/"
                if not entry.filename.startswith(prefix) or entry.is_dir():
                    continue
                relative = Path(entry.filename[len(prefix):])
                if relative.is_absolute() or ".." in relative.parts:
                    raise RuntimeError("Unexpected path in model archive")
                target = stage / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with zipped.open(entry) as source, target.open("wb") as out:
                    shutil.copyfileobj(source, out)
        for required in ("am/final.mdl", "graph/HCLr.fst", "graph/Gr.fst", "conf/model.conf"):
            if not (stage / required).is_file():
                raise RuntimeError(f"Incomplete speech model: {required}")
        (stage / ".source-sha256").write_text(SHA256 + "\n")
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        backup = CACHE / "previous-model"
        shutil.rmtree(backup, ignore_errors=True)
        if TARGET.exists():
            TARGET.rename(backup)
        try:
            stage.rename(TARGET)
        except Exception:
            if backup.exists():
                backup.rename(TARGET)
            raise
        shutil.rmtree(backup, ignore_errors=True)
        print("Offline English model verified and prepared.")
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def prepare_vad_model():
    if VAD_TARGET.is_file() and digest(VAD_TARGET) == VAD_SHA256 and "--force" not in sys.argv:
        print("Offline speech detector already verified and prepared.")
        return
    CACHE.mkdir(exist_ok=True)
    partial = CACHE / "silero-vad.partial"
    print("Downloading the official Silero speech detector…")
    try:
        with urllib.request.urlopen(VAD_URL, timeout=90) as response, partial.open("wb") as out:
            shutil.copyfileobj(response, out)
        if digest(partial) != VAD_SHA256:
            raise RuntimeError("Speech detector checksum mismatch; refusing this download.")
        VAD_TARGET.parent.mkdir(parents=True, exist_ok=True)
        os.replace(partial, VAD_TARGET)
        print("Offline speech detector verified and prepared.")
    finally:
        partial.unlink(missing_ok=True)


def main():
    prepare_speech_model()
    prepare_vad_model()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Model setup failed: {error}", file=sys.stderr)
        sys.exit(1)
