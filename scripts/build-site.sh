#!/usr/bin/env bash
# Build the static site for GitHub Pages into site/.
#   BASE=/redes-islenas/ scripts/build-site.sh          # project page (user.github.io/redes-islenas/)
#   BASE=/ scripts/build-site.sh                        # custom domain / user page
#   PIPELINE=1 ...                                      # also regenerate data from the workbook
# The scanned book is never part of the site (not allowed to share it).
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${BASE:-/redes-islenas/}"
OUT=site

if [ "${PIPELINE:-0}" = "1" ]; then
  clojure -M -m redes.pipeline.core
fi
npx shadow-cljs release app

rm -rf "$OUT"
mkdir -p "$OUT/js" "$OUT/css" "$OUT/data" "$OUT/geo"
sed "s|<base href=\"/\">|<base href=\"$BASE\">|" resources/public/index.html > "$OUT/index.html"
# GitHub Pages serves 404.html for unknown paths: the same app handles deep links.
cp "$OUT/index.html" "$OUT/404.html"
cp target/release/js/main.js "$OUT/js/"
cp resources/public/css/*.css "$OUT/css/"
cp resources/public/geo/*.geojson "$OUT/geo/"
cp resources/public/data/{index,payload,flows,stats}.json resources/public/data/redes-islenas.csv "$OUT/data/"
touch "$OUT/.nojekyll"

echo "site/ ready (base $BASE):"
du -sh "$OUT"; du -sh "$OUT"/* | sort -h
