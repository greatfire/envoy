// swift-tools-version: 6.0
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "GreatfireEnvoy",
    platforms: [.iOS(.v13), .macOS(.v12)],
    products: [
        .library(name: "GreatfireEnvoy", targets: ["GreatfireEnvoy"]),
    ],
    dependencies: [
        .package(url: "https://github.com/greatfire/SwiftyCurl", from: "0.4.1"),
    ],
    targets: [
        .binaryTarget(
            name: "IEnvoyProxy",
            url: "https://github.com/stevenmcdonald/IEnvoyProxy/releases/download/e3.3.0/IEnvoyProxy.xcframework.zip",
            // swift package compute-checksum IEnvoyProxy.xcframework.zip
            checksum: "7b8cddc3d332c2b64dfd40cf63a79fc21b76600aab53891c588f3169cfc0aae9"),
        .target(
            name: "GreatfireEnvoy",
            dependencies: [
                "IEnvoyProxy",
                .product(name: "SwiftyCurl", package: "SwiftyCurl"),
            ],
            path: "apple",
            exclude: ["Example", "Tests"],
            cSettings: [.define("USE_CURL", to: "1")],
            swiftSettings: [.define("USE_CURL")],
            // Needed for IEnvoyProxy, but not allowed in `.binaryTarget`. Hrgrml. But works this way.
            linkerSettings: [.linkedLibrary("resolv")]),
        .testTarget(
            name: "GreatfireEnvoyTests",
            dependencies: ["GreatfireEnvoy"],
            path: "apple",
            exclude: ["Example", "Sources"]),
    ],
    swiftLanguageModes: [.v5]
)
