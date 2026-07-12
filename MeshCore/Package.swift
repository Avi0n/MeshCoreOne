// swift-tools-version: 6.2
import PackageDescription

let package = Package(
  name: "MeshCore",
  platforms: [
    .iOS(.v18),
    .macOS(.v15)
  ],
  products: [
    .library(
      name: "MeshCore",
      targets: ["MeshCore"]
    ),
    .library(
      name: "MeshCoreTestSupport",
      targets: ["MeshCoreTestSupport"]
    )
  ],
  dependencies: [
    .package(url: "https://github.com/apple/swift-docc-plugin", from: "1.4.0")
  ],
  targets: [
    .target(
      name: "MeshCore"
    ),
    .target(
      name: "MeshCoreTestSupport",
      path: "Tests/MeshCoreTestSupport"
    ),
    .testTarget(
      name: "MeshCoreTests",
      dependencies: ["MeshCore", "MeshCoreTestSupport"]
    )
  ]
)
