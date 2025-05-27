# Envoy for Apple Platforms CHANGELOG

## 0.3.1

- Updated `SwiftyCurl`/`libcurl` dependency.
- Updated `IEnvoyProxy` dependency.
- Minor improvements.

## 0.3.0

- Added `libcurl` support: Read all about it in the [README](README.md).
- Updated `IEnvoyProxy` dependency, and hence Snowflake and Lyrebird to their latest versions.
- Uses `OSLog` now, where available to avoid leaking any sensitive information via `print` statements.

## 0.2.0

- Improve connect test: Allow other status codes. Introduced a `Test` object for this.
- ATTENTION: Minor API change!

## 0.1.0

- Initial release.
