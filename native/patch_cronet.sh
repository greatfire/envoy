#!/bin/bash -x
# see https://chromium.googlesource.com/chromium/src/+/master/components/cronet/build_instructions.md

set -euo pipefail


patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --force <"$PATCH_DIR/0001-Add-envoy_url-to-URLRequestContext.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --force <"$PATCH_DIR/0002-Add-envoy-scheme.patch"

# build cronet with jni and java api
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --force <"$PATCH_DIR/0003-Add-jni-and-android-interface.patch"
# with dns resolve
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --force <"$PATCH_DIR/0004-Add-host-map-rules-for-envoy-scheme.patch"
# disabled cipher suites
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --force <"$PATCH_DIR/0005-Add-disabled-cipher-suites-parameter.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --force <"$PATCH_DIR/0008-Set-host-header-for-http2.patch"

patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --force <"$PATCH_DIR/0010-Disable-external-intent.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --force <"$PATCH_DIR/0011-Add-salt-parameter.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --force <"$PATCH_DIR/0012-Add-socks5-proxy-for-cronet.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --force <"$PATCH_DIR/0013-Add-jni-for-cronet-socks5-proxy.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --force <"$PATCH_DIR/0014-Add-ss-service.patch"
