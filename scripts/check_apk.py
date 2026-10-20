#!/usr/bin/env python3
"""Check bundled offline model and 16 KB ELF load alignment in the actual APK."""
import struct
import sys
import zipfile


def main():
    with zipfile.ZipFile(sys.argv[1]) as apk:
        for path in ("am/final.mdl", "graph/HCLr.fst", "graph/Gr.fst", "conf/model.conf"):
            entry = apk.getinfo("assets/model-en-us/" + path)
            assert entry.file_size > 0, f"Empty model file: {path}"
        libraries = [name for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")]
        assert libraries, "No native speech libraries packaged"
        for abi in ("arm64-v8a", "x86_64"):
            for library in ("libvosk.so", "libjnidispatch.so"):
                assert f"lib/{abi}/{library}" in libraries, f"Missing {abi}/{library}"
        for name in libraries:
            blob = apk.read(name)
            assert blob[:4] == b"\x7fELF" and blob[4] == 2, f"Unexpected native architecture: {name}"
            order = "<" if blob[5] == 1 else ">"
            offset = struct.unpack_from(order + "Q", blob, 32)[0]
            entry_size, count = struct.unpack_from(order + "HH", blob, 54)
            for index in range(count):
                header = struct.unpack_from(order + "IIQQQQQQ", blob, offset + index * entry_size)
                if header[0] == 1:
                    assert header[7] >= 16384, f"{name}: load segment is not 16 KB aligned"
                    assert (header[3] - header[2]) % 16384 == 0, f"{name}: offset mismatch"
            print(f"16 KB ELF alignment OK: {name}")
        print("Offline model and native speech libraries verified in APK.")


if __name__ == "__main__":
    main()
