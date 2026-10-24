#!/usr/bin/env bash
# Download the screenshot-frame fonts into ./fonts next to this script
# (gitignored): Noto Sans and Noto Sans CJK JP/SC, SIL Open Font License.
set -euo pipefail
dir="$(cd "$(dirname "$0")" && pwd)/fonts"
mkdir -p "$dir"
base=https://raw.githubusercontent.com/notofonts
fetch() { [ -s "$dir/$2" ] || curl -fsSL -o "$dir/$2" "$1/$2"; }
fetch "$base/notofonts.github.io/main/fonts/NotoSans/hinted/ttf" NotoSans-Bold.ttf
fetch "$base/noto-cjk/main/Sans/OTF/Japanese" NotoSansCJKjp-Bold.otf
fetch "$base/noto-cjk/main/Sans/OTF/SimplifiedChinese" NotoSansCJKsc-Bold.otf
echo "Fonts in $dir"
