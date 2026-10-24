import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
CACHE = Path(__file__).resolve().parent / ".cache"
GSON_SHA = "57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b"
XRAY_SHA = "1eb9175d0f0a8f8149c9230a7fc5ae66ce332ed20a53155ce61fe62e3f58b7df"


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    jar = CACHE / "gson-2.11.0.jar"
    if not jar.exists(): urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar", jar)
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == GSON_SHA
    java_home = Path(os.environ["JAVA_HOME"]) if "JAVA_HOME" in os.environ else None
    java = str(java_home / "bin/java") if java_home else shutil.which("java")
    javac = str(java_home / "bin/javac") if java_home else shutil.which("javac")
    subprocess.run([javac, "-encoding", "UTF-8", "-cp", str(jar), "-d", str(CACHE),
                    str(ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/vinogram/SubscriptionConfig.java"),
                    str(Path(__file__).with_name("ConfigTest.java"))], check=True)
    checker = [java, "-cp", str(CACHE) + os.pathsep + str(jar), "ConfigTest"]
    subprocess.run(checker, check=True)
    if sys.platform == "win32":
        executable = Path(os.environ.get("XRAY_TEST_EXECUTABLE", r"D:\lilgram\out\Debug\xray\xray.exe"))
        if not executable.exists(): raise RuntimeError("Set XRAY_TEST_EXECUTABLE to the pinned Windows Xray")
    else:
        import io
        import zipfile
        data = urllib.request.urlopen("https://github.com/XTLS/Xray-core/releases/download/v26.9.9/Xray-linux-64.zip", timeout=120).read()
        assert hashlib.sha256(data).hexdigest() == XRAY_SHA
        executable = CACHE / "xray"
        with zipfile.ZipFile(io.BytesIO(data)) as archive: executable.write_bytes(archive.read("xray"))
        executable.chmod(0o755)
    subprocess.run([sys.executable, str(Path(__file__).with_name("engine_check.py")), "--xray", str(executable), "--checker", *checker], check=True)


if __name__ == "__main__":
    main()
