// swift-tools-version: 5.10
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "Envoy",
    platforms: [.iOS(.v13), .macOS(.v11)],
    products: [
        .library(name: "Envoy", targets: ["Envoy"]),
    ],
    targets: [
        .binaryTarget(
            name: "IEnvoyProxy",
            url: "https://github.com/stevenmcdonald/IEnvoyProxy/releases/download/e2.0.1/IEnvoyProxy.xcframework.zip",
            // swift package compute-checksum IEnvoyProxy.xcframework.zip
            checksum: "338e4e9d142efd0886284fcc6427646ea5b252de61dc12e15fcf308878fda194"),
        .target(
            name: "Envoy",
            dependencies: ["IEnvoyProxy"],
            path: "apple",
            exclude: ["Example"],
            // Needed for IEnvoyProxy, but not allowed in `.binaryTarget`. Hrgrml. But works this way.
            linkerSettings: [.linkedLibrary("resolv")]),
        .testTarget(
            name: "EnvoyTests",
            dependencies: ["Envoy"],
            path: "apple",
            exclude: ["Example"]),
    ],
    swiftLanguageVersions: [.v5]
)
