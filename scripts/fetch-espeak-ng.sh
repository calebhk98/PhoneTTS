#!/bin/sh
#
# Fetches the espeak-ng source tree the :app native build (app/src/main/cpp/CMakeLists.txt)
# compiles against. Not run automatically by any Gradle task, and not a git submodule, because
# this environment cannot reach network during automated work (see docs/espeak-ng-integration.md)
# — run this by hand once, on a machine with network access and the Android SDK/NDK installed.
#
# Pinned version: espeak-ng 1.52.0 (tag confirmed against github.com/espeak-ng/espeak-ng/tags —
# the "Current Version: 1.52" the research doc named, released Dec 2024). Bump the tag below
# deliberately, not casually — CMakeLists.txt's flag choices (USE_ASYNC/USE_KLATT/... OFF) and
# espeak_jni.cpp's API usage were written against this release; a newer tag may need re-checking.
#
# Usage:
#   sh scripts/fetch-espeak-ng.sh
#
set -e

ESPEAK_NG_TAG="1.52.0"
REPO_ROOT=$(git rev-parse --show-toplevel)
DEST="$REPO_ROOT/app/src/main/cpp/espeak-ng"

if [ -d "$DEST/.git" ]; then
    echo "fetch-espeak-ng: $DEST already exists -- remove it first to re-fetch."
    exit 0
fi

echo "fetch-espeak-ng: cloning espeak-ng @ $ESPEAK_NG_TAG into $DEST ..."
git clone --branch "$ESPEAK_NG_TAG" --depth 1 \
    https://github.com/espeak-ng/espeak-ng.git \
    "$DEST"

echo "fetch-espeak-ng: done. app/src/main/cpp/CMakeLists.txt will now build the real engine"
echo "(previously it built a stub — see that file's guard). Next: build the language/phoneme"
echo "data and copy it into app/src/main/assets/espeak-ng-data/ -- see"
echo "docs/espeak-ng-integration.md for that step."
