// swift-tools-version:5.9
//
// EtdmNetIOS — reference iOS transport for the etdmnet protocol.
//
// This Swift Package implements the *same* WebRTC DataChannel + signaling
// protocol that the JVM and Android transports speak, so an iOS peer can
// join a lobby alongside a Pixel and a Mac and they all see each other.
//
// It depends on Google's WebRTC binary distribution. We pin a known-good
// version; bumping is safe as long as the public WebRTC API doesn't shift.

import PackageDescription

let package = Package(
    name: "EtdmNetIOS",
    platforms: [.iOS(.v14), .macOS(.v12)],
    products: [
        .library(name: "EtdmNetIOS", targets: ["EtdmNetIOS"]),
    ],
    dependencies: [
        // Google's WebRTC binary distribution maintained by stasel.
        // https://github.com/stasel/WebRTC
        .package(url: "https://github.com/stasel/WebRTC.git", from: "125.0.0"),
    ],
    targets: [
        .target(
            name: "EtdmNetIOS",
            dependencies: [
                .product(name: "WebRTC", package: "WebRTC"),
            ],
            path: "Sources/EtdmNetIOS"
        ),
        .testTarget(
            name: "EtdmNetIOSTests",
            dependencies: ["EtdmNetIOS"],
            path: "Tests/EtdmNetIOSTests"
        ),
    ]
)
