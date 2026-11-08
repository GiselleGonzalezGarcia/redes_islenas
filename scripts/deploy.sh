#!/usr/bin/env bash
# Build the static site and publish it to the gh-pages branch of origin.
# GitHub Pages: Settings → Pages → Deploy from a branch → gh-pages / (root).
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${BASE:-/redes_islenas/}" scripts/build-site.sh
REMOTE="$(git remote get-url origin)"
cd site
rm -rf .git
git init -q -b gh-pages
git add -A
git commit -q -m "Site build $(date -u +%Y-%m-%dT%H:%MZ)"
git push -f "$REMOTE" gh-pages
rm -rf .git
echo "Published to gh-pages."
