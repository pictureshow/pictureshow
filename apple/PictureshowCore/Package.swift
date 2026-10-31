// swift-tools-version: 5.9
// SPDX-License-Identifier: MPL-2.0
import PackageDescription

let package = Package(
    name: "PictureshowCore",
    platforms: [.iOS(.v17), .tvOS(.v17), .macOS(.v14)],
    products: [.library(name: "PictureshowCore", targets: ["PictureshowCore"])],
    targets: [.target(name: "PictureshowCore"), .testTarget(name: "PictureshowCoreTests", dependencies: ["PictureshowCore"])]
)
