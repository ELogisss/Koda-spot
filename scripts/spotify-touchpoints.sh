#!/usr/bin/env bash
# Lists the upstream files this fork has touched, so the Spotify hook surface
# stays small and reviewable after every upstream merge (plan section 3b).
#
# Every edit to an upstream file should be a small hook that delegates into
# Spotify code. Files under spotify-core/ and anything with "spotify" in its
# name are ours by definition and are not counted.
#
# Usage: scripts/spotify-touchpoints.sh [base-ref]
#   base-ref defaults to upstream/beta.
#
# Uncommitted changes are not included: commit first, then run it.

set -euo pipefail

base="${1:-upstream/beta}"

if ! git rev-parse --verify --quiet "$base" >/dev/null; then
    echo "No such ref: $base (fetch it first: git fetch upstream)" >&2
    exit 2
fi

git diff --stat "$base"...HEAD -- . ':!spotify-core' ':!*spotify*'
