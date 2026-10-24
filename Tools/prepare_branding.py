import io
import json
from pathlib import Path
import subprocess

from PIL import Image


DESKTOP_COMMIT = "2a8d64abe899ad6f258490395bea5dc2ef62e0bf"
ANDROID_COMMIT = "0aae959806c57be8ad457980f2a5c6277ff18635"
ROOT = Path(__file__).resolve().parents[1]


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--desktop", type=Path, required=True)
    args = parser.parse_args()

    def released(path):
        return subprocess.check_output(["git", "-C", str(args.desktop), "show", f"{DESKTOP_COMMIT}:{path}"])

    res = ROOT / "TMessagesProj/src/main/res"
    logo = Image.open(io.BytesIO(released("Telegram/Resources/art/icon512.png"))).convert("RGBA")
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        directory = res / f"mipmap-{density}"
        icon = logo.resize((size, size), Image.Resampling.LANCZOS)
        for name in ("ic_launcher.png", "ic_launcher_round.png", "ic_launcher_sa.png"):
            icon.save(directory / name)
        foreground_size = round(size * 108 / 48)
        foreground = Image.new("RGBA", (foreground_size, foreground_size))
        foreground.alpha_composite(icon, ((foreground_size - size) // 2, (foreground_size - size) // 2))
        for name in ("icon_foreground.png", "icon_foreground_sa.png", "icon_foreground_round.png"):
            foreground.save(directory / name)
    nodpi = res / "drawable-nodpi"
    nodpi.mkdir(exist_ok=True)
    (nodpi / "vinogram_logo.png").write_bytes(released("Telegram/Resources/art/icon512.png"))
    (nodpi / "vinogram_banner.png").write_bytes(released("Telegram/Resources/art/intro_banner.png"))
    for name in ("ic_launcher.xml", "ic_launcher_round.xml", "ic_launcher_sa.xml"):
        (res / "mipmap-anydpi-v26" / name).write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/vinogram_launcher_background" />
    <foreground android:drawable="@mipmap/icon_foreground" />
</adaptive-icon>
''', encoding="utf-8")
    (res / "values/vinogram_colors.xml").write_text('''<resources>
    <color name="vinogram_launcher_background">#E8DCFF</color>
</resources>
''', encoding="utf-8")
    assets = ROOT / "TMessagesProj/src/main/assets/vinogram"
    assets.mkdir(exist_ok=True)
    (assets / "Xray-core-LICENSE.txt").write_bytes(released("tools/subscription/licenses/Xray-core-LICENSE"))
    (assets / "desktop-release.json").write_text(json.dumps({
        "desktop_repository": "https://github.com/lilcodder/vinoGram",
        "desktop_release": "Update", "desktop_commit": DESKTOP_COMMIT,
        "android_upstream": "https://github.com/DrKLO/Telegram",
        "android_commit": ANDROID_COMMIT, "xray": "v26.9.9",
        "ported_features": ["branding", "proxy-subscriptions", "automatic-server-selection"],
        "excluded_features": ["expressive-avatars", "unreleased-desktop-changes"],
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (ROOT / "LICENSE.upstream").write_bytes(subprocess.check_output(["git", "-C", str(ROOT), "show", f"{ANDROID_COMMIT}:LICENSE"]))
    (ROOT / "LICENSE").write_bytes(released("LICENSE"))
    print("Imported branding only from desktop release", DESKTOP_COMMIT)


if __name__ == "__main__":
    main()
