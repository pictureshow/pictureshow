// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import StoreKit
import SwiftUI

struct SupporterOffer: Equatable {
    let name: String
    let displayPrice: String
}

enum SupporterPurchase: Equatable { case purchased, pending, cancelled }

typealias ConfirmSupporterPurchase = @MainActor (Product) async throws -> Product.PurchaseResult

enum SupporterStoreFailure: Error, Equatable {
    case paymentsRestricted, productUnavailable, network, other

    init(_ error: Error) {
        switch error {
        case let failure as Self: self = failure
        case Product.PurchaseError.purchaseNotAllowed: self = .paymentsRestricted
        case StoreKitError.notAvailableInStorefront: self = .productUnavailable
        case StoreKitError.networkError, is URLError: self = .network
        default: self = .other
        }
    }

    var message: LocalizedStringKey {
        switch self {
        case .paymentsRestricted: "Purchases are unavailable on this device or Apple Account."
        case .productUnavailable: "The Supporter subscription is not available in the App Store."
        case .network: "Could not reach the App Store. Check your connection and try again later."
        case .other: "Could not complete the App Store request. Try again later."
        }
    }
}

@MainActor
protocol SupporterStore {
    func offer() async throws -> SupporterOffer
    func purchase(confirm: ConfirmSupporterPurchase) async throws -> SupporterPurchase
    func currentSupporterCode() async -> String?
    func restore() async throws
    func observeChanges(_ refresh: @escaping @MainActor () async -> Void) async
}

@MainActor
struct StoreKitSupporter: SupporterStore {
    nonisolated static let productID = "app.pictureshow.supporter.monthly"

    func offer() async throws -> SupporterOffer {
        let product = try await monthlyProduct()
        return SupporterOffer(name: product.displayName, displayPrice: product.displayPrice)
    }

    func purchase(confirm: ConfirmSupporterPurchase) async throws -> SupporterPurchase {
        let product = try await monthlyProduct()
        switch try await confirm(product) {
        case .success(.verified(let transaction)) where transaction.productID == Self.productID:
            await transaction.finish()
            return .purchased
        case .success: throw SupporterStoreFailure.other
        case .pending: return .pending
        case .userCancelled: return .cancelled
        @unknown default: throw SupporterStoreFailure.other
        }
    }

    func currentSupporterCode() async -> String? {
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result, transaction.productID == Self.productID,
                  transaction.revocationDate == nil, !transaction.isUpgraded else { continue }
            // StoreKit includes active and billing-grace-period subscriptions in this sequence.
            return String(transaction.originalID)
        }
        return nil
    }

    func restore() async throws {
        try await AppStore.sync()
    }

    func observeChanges(_ refresh: @escaping @MainActor () async -> Void) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await result in Transaction.updates {
                    guard case .verified(let transaction) = result, transaction.productID == Self.productID else { continue }
                    await refresh()
                    await transaction.finish()
                }
            }
            group.addTask {
                for await status in Product.SubscriptionInfo.Status.updates {
                    guard case .verified(let transaction) = status.transaction, transaction.productID == Self.productID else { continue }
                    await refresh()
                }
            }
        }
    }

    private func monthlyProduct() async throws -> Product {
        guard AppStore.canMakePayments else { throw SupporterStoreFailure.paymentsRestricted }
        guard let product = try await Product.products(for: [Self.productID]).first,
              let period = product.subscription?.subscriptionPeriod,
              period.value == 1, period.unit == .month else {
            throw SupporterStoreFailure.productUnavailable
        }
        return product
    }
}

@MainActor
final class SupporterModel: ObservableObject {
    enum Activity: Equatable { case purchasing, restoring }
    enum Notice: Equatable { case pending, restoredNothing, failed(SupporterStoreFailure) }

    @Published private(set) var offer: SupporterOffer?
    @Published private(set) var offerFailure: SupporterStoreFailure?
    @Published private(set) var supporterCode: String?
    @Published private(set) var activity: Activity?
    @Published private(set) var notice: Notice?
    @Published private(set) var loading = false

    private let store: any SupporterStore
    private var entitlementRead = 0
    private var observationTask: Task<Void, Never>?

    init(store: any SupporterStore) {
        self.store = store
    }

    deinit { observationTask?.cancel() }

    func refreshOffer() async {
        await refreshEntitlement()
        await loadOffer()
    }

    private func loadOffer() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            let result = try await store.offer()
            guard !Task.isCancelled else { return }
            offer = result
            offerFailure = nil
        } catch {
            guard !Task.isCancelled else { return }
            offer = nil
            offerFailure = SupporterStoreFailure(error)
        }
    }

    func purchase(confirm: ConfirmSupporterPurchase = { try await $0.purchase() }) async {
        guard activity == nil, offer != nil, supporterCode == nil else { return }
        let previousNotice = notice == .pending ? notice : nil
        activity = .purchasing
        notice = nil
        defer { activity = nil }
        await refreshEntitlement()
        guard supporterCode == nil else { return }
        do {
            switch try await store.purchase(confirm: confirm) {
            case .purchased:
                await refreshEntitlement()
                if supporterCode == nil { notice = .pending }
            case .pending: if supporterCode == nil { notice = .pending }
            case .cancelled: if supporterCode == nil { notice = previousNotice }
            }
        } catch {
            notice = SupporterStoreFailure.isCancellation(error) ? previousNotice : .failed(.init(error))
        }
    }

    func restore() async {
        guard activity == nil else { return }
        let wasPending = notice == .pending
        activity = .restoring
        notice = nil
        defer { activity = nil }
        do {
            try await store.restore()
            await refreshEntitlement()
            if supporterCode == nil { notice = .restoredNothing }
            await loadOffer()
        } catch {
            notice = SupporterStoreFailure.isCancellation(error) ? (wasPending ? .pending : nil) : .failed(.init(error))
        }
    }

    func startObserving() {
        guard observationTask == nil else { return }
        observationTask = Task { [weak self, store] in
            await store.observeChanges { [weak self] in await self?.refreshEntitlement() }
        }
    }

    func refreshEntitlement() async {
        entitlementRead += 1
        let read = entitlementRead
        let code = await store.currentSupporterCode()
        guard read == entitlementRead, !Task.isCancelled else { return }
        supporterCode = code
        if code != nil && (notice == .pending || notice == .restoredNothing) { notice = nil }
    }
}

private extension SupporterStoreFailure {
    static func isCancellation(_ error: Error) -> Bool {
        if case StoreKitError.userCancelled = error { return true }
        return error is CancellationError
    }
}
