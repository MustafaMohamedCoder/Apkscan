#!/usr/bin/env python3
"""Generate mipmap icons, notification icon, and foreground resources from a base icon."""
import os
from PIL import Image, ImageDraw

BASE = "/home/ubuntu/android_app/app/src/main"
SRC = "/tmp/icon.png"

def make_icons():
    # Launcher adaptive icon: background circle (teal) + centered icon
    sizes = [("xxxhdpi", 192), ("xxhdpi", 144), ("xhdpi", 96), ("hdpi", 72), ("mdpi", 48)]
    for name, size in sizes:
        d = os.path.join(BASE, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        for png in ("ic_launcher.png", "ic_launcher_round.png"):
            bg = Image.new("RGBA", (size * 2, size * 2), (6, 37, 44, 255))  # dark teal bg
            img = Image.open(SRC).convert("RGBA").resize((int(size*1.4), int(size*1.4)), Image.LANCZOS)
            bg.paste(img, ((size*2 - img.width)//2, (size*2 - img.height)//2), img)
            bg.save(os.path.join(d, png))

    # ic_launcher.xml (adaptive) in mipmap-anydpi
    anydpi = os.path.join(BASE, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                    '    <background android:drawable="@color/ic_bg"/>\n'
                    '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
                    '</adaptive-icon>\n')

    # colors.xml ic_bg
    colors_path = os.path.join(BASE, "res/values/colors.xml")
    if os.path.exists(colors_path):
        colors = open(colors_path).read()
        if "ic_bg" not in colors:
            colors = colors.replace("</resources>", '    <color name="ic_bg">#06252C</color>\n</resources>')
            open(colors_path, "w").write(colors)

if __name__ == "__main__":
    make_icons()
    print("done")
