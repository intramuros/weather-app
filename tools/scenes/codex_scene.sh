#!/usr/bin/env bash
# Draws one scene in a style with the Codex CLI's image generation, from the
# matching pixel-art picture:
#
#   tools/scenes/codex_scene.sh <style> <scene> <out-folder> [reference.png ...]
#
# The prompt is tools/scenes/codex-common.txt, then tools/scenes/<style>/style.txt,
# then the scene's line from tools/scenes/anime/PROMPTS.md (weather and outfit).
# The references are finished pictures in the style, so the girl stays the same;
# the first scene of a new style is made without any. Writes <out-folder>/<scene>.png
# and skips scenes that are already there, so a failed batch can just be rerun.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
style=$1 slug=$2 out=$3
shift 3
[ -s "$out/$slug.png" ] && { echo "skip $style/$slug"; exit 0; }
line=$(sed -nE "s/^\| \`$slug\.png\` \| (.*) \|$/\1/p" "$ROOT/tools/scenes/anime/PROMPTS.md")
[ -n "$line" ] || { echo "no scene called $slug"; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
{
  cat "$ROOT/tools/scenes/codex-common.txt"; echo
  cat "$ROOT/tools/scenes/$style/style.txt"; echo
  echo "$line"; echo
  echo "Use your image generation tool once for this single image, then save it in the current directory as $slug.png. Reply with just the path."
} > "$work/prompt.txt"
images=(-i "$ROOT/app/src/main/assets/scenes/pixel-art/$slug.webp")
# Absolute paths: Codex runs in a scratch folder, where a relative path sends it searching.
for reference in "$@"; do images+=(-i "$(realpath "$reference")"); done

mkdir -p "$out"
for _ in 1 2; do
  codex exec --sandbox workspace-write --skip-git-repo-check -C "$work" "${images[@]}" - < "$work/prompt.txt" > "$work/log.txt" 2>&1
  if [ -s "$work/$slug.png" ]; then cp "$work/$slug.png" "$out/"; echo "ok $style/$slug"; exit 0; fi
done
tail -20 "$work/log.txt"
echo "FAILED $style/$slug"
exit 1
