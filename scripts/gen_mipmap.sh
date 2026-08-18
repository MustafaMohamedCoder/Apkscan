#!/bin/sh
# توليد كل مجلدات mipmap وأيقوناتها
BASE=/home/ubuntu/android_app/app/src/main
ICON=/tmp/icon.png

for spec in xxxhdpi:192 xxhdpi:144 xhdpi:96 hdpi:72 mdpi:48; do
  name=${spec%%:*}
  size=${spec##*:}
  d="$BASE/res/mipmap-$name"
  mkdir -p "$d"
  python3 - "$ICON" "$size" "$d" <<'EOF'
import sys
from PIL import Image
icon_path, size_s, outdir = sys.argv[1], sys.argv[2], sys.argv[3]
size = int(size_s)
img = Image.open(icon_path).convert("RGBA").resize((int(size*1.4), int(size*1.4)), Image.LANCZOS)
bg = Image.new("RGBA", (size*2, size*2), (6, 37, 44, 255))
bg.paste(img, ((size*2 - img.width)//2, (size*2 - img.height)//2), img)
for png in ("ic_launcher.png", "ic_launcher_round.png"):
    bg.save(f"{outdir}/{png}")
EOF
done

mkdir -p "$BASE/res/mipmap-anydpi-v26"
cat > "$BASE/res/mipmap-anydpi-v26/ic_launcher.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_bg"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
EOF
cp "$BASE/res/mipmap-anydpi-v26/ic_launcher.xml" "$BASE/res/mipmap-anydpi-v26/ic_launcher_round.xml"
echo "mipmap generation complete"
