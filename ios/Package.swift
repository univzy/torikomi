// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TorikomiCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "TorikomiCore", targets: ["TorikomiCore"])],
    targets: [
        .target(name: "TorikomiCore", path: "TorikomiCore", resources: [.process("Resources")]),
        .testTarget(name: "TorikomiCoreTests", dependencies: ["TorikomiCore"], path: "Tests")
    ]
)
