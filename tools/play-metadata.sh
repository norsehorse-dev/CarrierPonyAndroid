#!/usr/bin/env bash
# Assemble the Play upload set in build/play-metadata from two sources:
#   fastlane/metadata/android/<locale>/  listing text and changelogs, shared
#                                        with F-Droid (which shows the raw
#                                        screenshots in the same folder)
#   fastlane/play/<locale>/              what Play gets instead: title.txt
#                                        (Play's longer title) and the framed
#                                        phoneScreenshots
set -euo pipefail
cd "$(dirname "$0")/.."
src=fastlane/metadata/android
play=fastlane/play
out=build/play-metadata
rm -rf "$out"
for dir in "$src"/*/; do
  locale=$(basename "$dir")
  mkdir -p "$out/$locale/images"
  for f in short_description.txt full_description.txt; do
    [ -f "$src/$locale/$f" ] && cp "$src/$locale/$f" "$out/$locale/$f"
  done
  if [ -d "$src/$locale/images" ]; then
    find "$src/$locale/images" -maxdepth 1 -type f -name '*.png' -exec cp {} "$out/$locale/images/" \;
  fi
  if [ -f "$play/$locale/title.txt" ]; then
    cp "$play/$locale/title.txt" "$out/$locale/title.txt"
  fi
  if [ -d "$play/$locale/images/phoneScreenshots" ]; then
    mkdir -p "$out/$locale/images/phoneScreenshots"
    cp "$play/$locale/images/phoneScreenshots"/*.png "$out/$locale/images/phoneScreenshots/"
  fi
done
echo "Play metadata assembled in $out"
