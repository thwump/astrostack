// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AstroStack",
    platforms: [
        .iOS(.v17),
        .macOS(.v14)
    ],
    products: [
        .library(
            name: "AstroStackCore",
            targets: ["AstroStackCore"]
        ),
        .library(
            name: "AstroStackApp",
            targets: ["AstroStackApp"]
        ),
        .executable(
            name: "AstroStackCoreTests",
            targets: ["AstroStackCoreTests"]
        )
    ],
    targets: [
        .target(
            name: "AstroStackCore",
            dependencies: [],
            path: "Sources/AstroStackCore"
        ),
        .target(
            name: "AstroStackApp",
            dependencies: ["AstroStackCore"],
            path: "Sources/AstroStackApp"
        ),
        .executableTarget(
            name: "AstroStackCoreTests",
            dependencies: ["AstroStackCore"],
            path: "Tests/AstroStackCoreTests"
        )
    ]
)
