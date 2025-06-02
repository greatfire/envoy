#!/bin/bash

set -euo pipefail

TARGET=android
OUTPUT=Emissary.aar

cd "$(dirname "$0")" || exit 1

# go get golang.org/x/mobile/bind
# go install golang.org/x/mobile/cmd/gomobile@latest

# gomobile init

MACOSX_DEPLOYMENT_TARGET=11.0 gomobile bind -target=$TARGET \
    -ldflags="-s -w -checklinkname=0" -o "$OUTPUT" \
    -iosversion=12.0 -androidapi=21 -v -tags=netcgo -trimpath
