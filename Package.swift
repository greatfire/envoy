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
        .package(url: "https://github.com/greatfire/SwiftyCurl", from: "0.4.2"),
    ],
    targets: [
        .binaryTarget(
            name: "IEnvoyProxy",
            url: "https://github.com/greatfire/IEnvoyProxy/releases/download/e3.4.0/IEnvoyProxy.xcframework.zip",
            // swift package compute-checksum IEnvoyProxy.xcframework.zip
            checksum: "6ac6a7e3284f1e41441fd2c36c1f42dc3c7c711f5e15a9d03eac030c0de9409d"),
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
