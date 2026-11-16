// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "PictureshowTransport", platforms: [.iOS(.v17), .tvOS(.v17), .macOS(.v14)], products: [.library(name: "PictureshowTransport", targets: ["PictureshowTransport"])], targets: [.target(name: "PictureshowTransport"), .testTarget(name: "PictureshowTransportTests", dependencies: ["PictureshowTransport"])])
