// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import AppKit
import Foundation
import StoreKit

private let supporterProduct = "app.pictureshow.supporter.monthly"

private struct StoreProduct: Encodable {
    let id: String
    let name: String
    let displayPrice: String
}

private struct StoreEntitlement: Encodable {
    let productId: String
    let active: Bool
    let expirationMillis: Int64?
    let supporterCode: String

    init(_ transaction: Transaction, entitled: Bool? = nil) {
        productId = transaction.productID
        let current = entitled ?? (transaction.expirationDate.map { $0 > Date() } ?? true)
        active = current && transaction.revocationDate == nil && !transaction.isUpgraded
        supporterCode = String(transaction.originalID)
        expirationMillis = transaction.expirationDate.map { Int64($0.timeIntervalSince1970 * 1000) }
    }
}

private struct StoreReply: Encodable {
    var products: [StoreProduct]?
    var entitlements: [StoreEntitlement]?
    var purchase: String?
    var update: StoreEntitlement?
    var error: String?
    var failure: String?
    var opened: Bool?
}

public typealias StoreCallback = @convention(c) (Int64, UnsafePointer<CChar>) -> Void

private func send(_ reply: StoreReply, id: Int64, to callback: StoreCallback) {
    let encoded = (try? JSONEncoder().encode(reply)) ?? Data(#"{"error":"Could not read the App Store response."}"#.utf8)
    String(decoding: encoded, as: UTF8.self).withCString { callback(id, $0) }
}

@_cdecl("ps_storekit_request")
public func storeRequest(_ id: Int64, _ command: Int32, _ product: UnsafePointer<CChar>?, _ callback: StoreCallback) {
    let requested = product.map { String(cString: $0) }
    Task { @MainActor in
        let reply: StoreReply
        do {
            switch command {
            case 1:
                guard AppStore.canMakePayments else {
                    send(StoreReply(error: "Payments are restricted.", failure: "PAYMENTS_RESTRICTED"), id: id, to: callback)
                    return
                }
                let products = try await Product.products(for: [supporterProduct]).filter(isMonthly)
                reply = StoreReply(products: products.map {
                    StoreProduct(id: $0.id, name: $0.displayName, displayPrice: $0.displayPrice)
                })
            case 2:
                guard AppStore.canMakePayments else {
                    send(StoreReply(error: "Payments are restricted.", failure: "PAYMENTS_RESTRICTED"), id: id, to: callback)
                    return
                }
                guard requested == supporterProduct,
                      let product = try await Product.products(for: [supporterProduct]).first(where: isMonthly) else {
                    send(StoreReply(error: "The Supporter subscription is unavailable.", failure: "PRODUCT_UNAVAILABLE"), id: id, to: callback)
                    return
                }
                let result: Product.PurchaseResult
                if #available(macOS 15.2, *), let window = NSApp.keyWindow ?? NSApp.mainWindow {
                    result = try await product.purchase(confirmIn: window)
                } else {
                    result = try await product.purchase()
                }
                switch result {
                case .success(.verified(let transaction)):
                    await transaction.finish()
                    reply = StoreReply(purchase: "PURCHASED")
                case .success(.unverified):
                    reply = StoreReply(error: "The App Store could not verify the purchase.")
                case .pending:
                    reply = StoreReply(purchase: "PENDING")
                case .userCancelled:
                    reply = StoreReply(purchase: "CANCELLED")
                @unknown default:
                    reply = StoreReply(error: "The App Store returned an unknown purchase result.")
                }
            case 3:
                var entitlements: [StoreEntitlement] = []
                for await result in Transaction.currentEntitlements {
                    if case .verified(let transaction) = result, transaction.productID == supporterProduct {
                        let entitlement = StoreEntitlement(transaction, entitled: true)
                        if entitlement.active { entitlements.append(entitlement) }
                    }
                }
                reply = StoreReply(entitlements: entitlements)
            case 4:
                try await AppStore.sync()
                reply = StoreReply()
            case 5:
                let url = URL(string: "https://apps.apple.com/account/subscriptions")!
                reply = StoreReply(opened: NSWorkspace.shared.open(url))
            default:
                reply = StoreReply(error: "This App Store operation is unavailable.")
            }
        } catch {
            let failure: String?
            switch error {
            case Product.PurchaseError.purchaseNotAllowed: failure = "PAYMENTS_RESTRICTED"
            case StoreKitError.notAvailableInStorefront: failure = "PRODUCT_UNAVAILABLE"
            case StoreKitError.networkError, is URLError: failure = "NETWORK"
            case StoreKitError.userCancelled: failure = "CANCELLED"
            default: failure = nil
            }
            reply = StoreReply(error: "The App Store request could not be completed. Try again.", failure: failure)
        }
        send(reply, id: id, to: callback)
    }
}

private func isMonthly(_ product: Product) -> Bool {
    guard let period = product.subscription?.subscriptionPeriod else { return false }
    return period.value == 1 && period.unit == .month
}

@MainActor private var observer: Task<Void, Never>?

@_cdecl("ps_storekit_observe")
public func storeObserve(_ callback: StoreCallback) {
    Task { @MainActor in
        guard observer == nil else { return }
        observer = Task {
            await withTaskGroup(of: Void.self) { group in
                group.addTask {
                    for await result in Transaction.updates {
                        guard case .verified(let transaction) = result, transaction.productID == supporterProduct else { continue }
                        send(StoreReply(update: StoreEntitlement(transaction)), id: 0, to: callback)
                        await transaction.finish()
                    }
                }
                group.addTask {
                    for await status in Product.SubscriptionInfo.Status.updates {
                        guard case .verified(let transaction) = status.transaction, transaction.productID == supporterProduct else { continue }
                        let update = StoreEntitlement(transaction, entitled: status.state == .subscribed || status.state == .inGracePeriod)
                        send(StoreReply(update: update), id: 0, to: callback)
                    }
                }
            }
        }
    }
}

private let folderKey = "Pictureshow.downloadFolder"
private let folderName = "Pictureshow Downloads"

private enum DownloadFolder {
    // A security scope stays live for the process using it. A new folder takes
    // effect on the next launch, after running download workers have stopped.
    static let active: URL? = {
        let preferences = UserDefaults.standard
        guard let bookmark = preferences.data(forKey: folderKey) else { return nil }
        var stale = false
        guard let folder = try? URL(resolvingBookmarkData: bookmark, options: [.withSecurityScope, .withoutUI],
                                    relativeTo: nil, bookmarkDataIsStale: &stale),
              folder.startAccessingSecurityScopedResource() else { return nil }
        guard FileManager.default.fileExists(atPath: folder.path) else {
            folder.stopAccessingSecurityScopedResource()
            return nil
        }
        if stale, let updated = try? folder.bookmarkData(options: .withSecurityScope, includingResourceValuesForKeys: nil, relativeTo: nil) {
            preferences.set(updated, forKey: folderKey)
        }
        return folder
    }()
}

@_cdecl("ps_store_container_data")
public func storeContainerData() -> UnsafeMutablePointer<CChar>? {
    FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        .map { $0.appendingPathComponent("Pictureshow", isDirectory: true).path }
        .flatMap { strdup($0) }
}

@_cdecl("ps_store_download_data")
public func storeDownloadData() -> UnsafeMutablePointer<CChar>? {
    DownloadFolder.active.flatMap { strdup($0.appendingPathComponent(folderName, isDirectory: true).path) }
}

@_cdecl("ps_store_download_has_bookmark")
public func storeDownloadHasBookmark() -> Int32 {
    UserDefaults.standard.data(forKey: folderKey) == nil ? 0 : 1
}

@_cdecl("ps_store_download_use_container")
public func storeDownloadUseContainer() {
    UserDefaults.standard.removeObject(forKey: folderKey)
}

@_cdecl("ps_store_download_choose")
public func storeDownloadChoose() -> Int32 {
    func choose() -> Int32 {
        let panel = NSOpenPanel()
        panel.title = "Choose a folder for Pictureshow downloads"
        panel.prompt = "Choose Folder"
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let folder = panel.url else { return 0 }
        do {
            let bookmark = try folder.bookmarkData(options: .withSecurityScope, includingResourceValuesForKeys: nil, relativeTo: nil)
            UserDefaults.standard.set(bookmark, forKey: folderKey)
            return 1
        } catch { return -1 }
    }
    return Thread.isMainThread ? choose() : DispatchQueue.main.sync { choose() }
}

@_cdecl("ps_store_free")
public func storeFree(_ pointer: UnsafeMutablePointer<CChar>?) {
    free(pointer)
}
