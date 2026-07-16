#!/bin/sh
#
# Installs the PhoneTTS git hooks. Run once after cloning:
#   sh scripts/install-hooks.sh
#
set -e

REPO_ROOT=$(git rev-parse --show-toplevel)
HOOK_SRC="$REPO_ROOT/scripts/pre-commit"
HOOK_DST="$REPO_ROOT/.git/hooks/pre-commit"

cp "$HOOK_SRC" "$HOOK_DST"
chmod +x "$HOOK_DST"

echo "Installed pre-commit hook -> $HOOK_DST"
echo "It runs: ktlintCheck + detekt + :core:test on every commit."
