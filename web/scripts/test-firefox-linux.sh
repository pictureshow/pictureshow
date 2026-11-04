#!/bin/sh
# Run the unchanged suite in Linux when a host Firefox/audio backend is unavailable.
set -eu
cd "$(dirname "$0")/.."
node tests/generate-media.mjs
web_source=$(pwd)
web_media=$(node -e 'process.stdout.write(require("path").join(require("os").tmpdir(),"pictureshow-web-media"))')
docker run --rm --cpus=2 --memory=2g \
  -v "$web_source:/source:ro" -v "$web_media:/tmp/pictureshow-web-media:ro" \
  mcr.microsoft.com/playwright:v1.62.1-noble sh -ec '
    mkdir /work; cd /work
    cp -R /source/public /source/core /source/src /source/tests /source/*.json /source/*.ts /source/index.html .
    npm ci --ignore-scripts
    npx playwright install firefox
    npx playwright test --project=firefox
  '
