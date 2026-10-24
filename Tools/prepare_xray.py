import hashlib
import io
from pathlib import Path
import urllib.request
import zipfile

VERSION = "v26.9.9"
SHA256 = "f18625edf2360df8f857d8a2d947f69dd137b31849e7b9b530200d7e5f482766"
URL = f"https://github.com/XTLS/Xray-core/releases/download/{VERSION}/Xray-android-arm64-v8a.zip"


def main():
    root = Path(__file__).resolve().parents[1]
    with urllib.request.urlopen(URL, timeout=120) as response:
        data = response.read(64 * 1024 * 1024)
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise RuntimeError("Xray SHA-256 mismatch")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        executable = archive.read("xray")
    if executable[:4] != b"\x7fELF" or executable[4] != 2 or int.from_bytes(executable[18:20], "little") != 183:
        raise RuntimeError("Expected Android ARM64 ELF executable")
    target = root / "TMessagesProj/src/main/jniLibs/arm64-v8a/libxray.so"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(executable)
    target.chmod(0o755)
    print("Verified Xray", VERSION, "for ARM64")


if __name__ == "__main__":
    main()
