#!/bin/bash
# [Building old revisions](https://chromium.googlesource.com/chromium/src.git/+/master/docs/building_old_revisions.md)
# [Working with Release Branches - The Chromium Projects](https://www.chromium.org/developers/how-tos/get-the-code/working-with-release-branches)
# [Issue 440273002: Reland: Add --no-history option to fetch and gclient for shallow clones. - Code Review](https://codereview.chromium.org/440273002/)

set -euo pipefail
export DEPOT_TOOLS_UPDATE=0
CHROMIUM_SRC_ROOT=${CHROMIUM_SRC_ROOT:-/root/chromium/src}
DEPOT_TOOLS_ROOT=${DEPOT_TOOLS_ROOT:-/root/depot_tools}
export PATH="$DEPOT_TOOLS_ROOT:$PATH"
TAG=${1:-108.0.5359.243}

cd "$CHROMIUM_SRC_ROOT" || exit 1

# check chrome://about or http://omahaproxy.appspot.com
git fetch origin "refs/tags/$TAG:refs/tags/$TAG" --no-tags --verbose
git checkout -B "$TAG" "tags/$TAG"

COMMIT_DATE=$(git log -n 1 --pretty=format:%ci)
# 2020-01-06 04:54:58 +0000

cd "$DEPOT_TOOLS_ROOT" || exit 2
git checkout main && git pull && git checkout "$(git rev-list -n 1 --before="$COMMIT_DATE" main)"

cd "$CHROMIUM_SRC_ROOT" || exit 3
git clean -ffd # --dry-run

# NOT: local change will be resets
# will prompt for licenses, https://bugs.chromium.org/p/chromium/issues/detail?id=552442
gclient sync --reset --with_branch_heads --nohooks # --delete_unversioned_trees --force --with_tags
# Will prompt for package installation
build/install-build-deps.sh
gclient runhooks
