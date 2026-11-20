// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import StoreKitTest
import StoreKit
import UIKit
import XCTest
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

final class SupporterTests: XCTestCase {
    @MainActor
    func testInitializeStoreKitTestSession() throws {
        _ = try sessionWithLocalConfiguration()
    }

    @MainActor
    func testStoreKitPurchaseRestoreAndFreshModelReadTheSameEntitlement() async throws {
        let session = try testSession()
        defer { session.clearTransactions() }
        let store = StoreKitSupporter()
        let model = SupporterModel(store: store)
        await model.refreshOffer()
        XCTAssertEqual(model.offer, .init(name: "Supporter", displayPrice: "$2.99"))
        XCTAssertNil(model.supporterCode)

        try await purchase(model)
        let code = try XCTUnwrap(model.supporterCode)
        let result = await Transaction.latest(for: StoreKitSupporter.productID)
        XCTAssertEqual(try result?.payloadValue.environment, .xcode)
        XCTAssertEqual(session.allTransactions().count, 1)
        XCTAssertNil(model.notice)

        let reopened = SupporterModel(store: StoreKitSupporter())
        await reopened.restore()
        XCTAssertEqual(reopened.supporterCode, code)
        XCTAssertNil(reopened.notice)
        await reopened.refreshOffer()
        XCTAssertEqual(reopened.supporterCode, code)
        XCTAssertEqual(session.allTransactions().count, 1)
    }

    @MainActor
    func testStoreKitExpiryRemovesSupporterOnScreenRefresh() async throws {
        let session = try testSession()
        defer { session.clearTransactions() }
        session.timeRate = .oneRenewalEveryTwoSeconds
        let model = SupporterModel(store: StoreKitSupporter())
        model.startObserving()
        await model.refreshOffer()
        try await purchase(model)
        _ = try XCTUnwrap(model.supporterCode)

        let id = try XCTUnwrap(session.allTransactions().first?.identifier)
        try session.disableAutoRenewForTransaction(identifier: id)
        // Use elapsed StoreKit time so the signed transaction expires.
        for _ in 0..<50 where model.supporterCode != nil {
            try await Task.sleep(for: .milliseconds(100))
            await model.refreshEntitlement()
        }
        XCTAssertNil(model.supporterCode)
    }

    @MainActor
    func testStoreKitRefundRemovesVerifiedSupporter() async throws {
        let session = try testSession()
        defer { session.clearTransactions() }
        let model = SupporterModel(store: StoreKitSupporter())
        model.startObserving()
        await model.refreshOffer()
        try await purchase(model)
        _ = try XCTUnwrap(model.supporterCode)

        let id = try XCTUnwrap(session.allTransactions().first?.identifier)
        try session.refundTransaction(identifier: id)
        await waitForNoEntitlement(model)
        XCTAssertNil(model.supporterCode)
    }

    @MainActor
    func testPendingNeverGrantsSupportAndAllowsRetryOrRestore() async {
        let store = FakeSupporterStore()
        let model = SupporterModel(store: store)
        await model.refreshOffer()
        await model.purchase()
        XCTAssertEqual(model.notice, .pending)
        store.purchaseResult = .cancelled
        await model.purchase()
        XCTAssertEqual(store.purchases, 2)
        XCTAssertEqual(model.notice, .pending)
        XCTAssertNil(model.supporterCode)
        await model.restore()
        XCTAssertEqual(store.restores, 1)
        XCTAssertEqual(model.notice, .restoredNothing)

        store.code = "123456"
        await model.refreshEntitlement()
        XCTAssertEqual(model.supporterCode, "123456")
        XCTAssertNil(model.notice)

        store.code = nil
        await model.refreshEntitlement()
        XCTAssertNil(model.supporterCode)
    }

    @MainActor
    func testCancellationAndUnavailableOfferDoNotGrantSupport() async {
        let store = FakeSupporterStore()
        let model = SupporterModel(store: store)
        store.purchaseResult = .cancelled
        await model.refreshOffer()
        await model.purchase()
        XCTAssertNil(model.notice)
        XCTAssertNil(model.supporterCode)

        store.offerError = .network
        await model.refreshOffer()
        await model.purchase()
        XCTAssertNil(model.offer)
        XCTAssertEqual(model.offerFailure, .network)
        XCTAssertEqual(store.purchases, 1)

        await model.restore()
        XCTAssertEqual(store.restores, 1)
        XCTAssertEqual(model.notice, .restoredNothing)
    }

    @MainActor
    private func testSession() throws -> SKTestSession {
        let session = try sessionWithLocalConfiguration()
        session.resetToDefaultState()
        session.disableDialogs = true
        // A disconnected test session can silently fall back to the Apple sandbox.
        guard session.disableDialogs else { throw TestSetupError.localStoreUnavailable }
        session.clearTransactions()
        return session
    }

    @MainActor
    private func sessionWithLocalConfiguration() throws -> SKTestSession {
        try SKTestSession(configurationFileNamed: "Supporter")
    }

    private enum TestSetupError: Error {
        case localStoreUnavailable
    }

    @MainActor
    private func purchase(_ model: SupporterModel) async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        await model.purchase { product in try await product.purchase(confirmIn: scene) }
    }

    @MainActor
    private func waitForNoEntitlement(_ model: SupporterModel) async {
        for _ in 0..<50 {
            if model.supporterCode == nil { return }
            try? await Task.sleep(for: .milliseconds(100))
        }
    }
}

@MainActor
private final class FakeSupporterStore: SupporterStore {
    var code: String?
    var offerError: SupporterStoreFailure?
    var purchaseResult: SupporterPurchase = .pending
    private(set) var purchases = 0
    private(set) var restores = 0

    func offer() async throws -> SupporterOffer {
        if let offerError { throw offerError }
        return .init(name: "Supporter", displayPrice: "$2.99")
    }

    func currentSupporterCode() async -> String? { code }

    func purchase(confirm: ConfirmSupporterPurchase) async throws -> SupporterPurchase {
        purchases += 1
        return purchaseResult
    }

    func restore() async throws { restores += 1 }

    func observeChanges(_ refresh: @escaping @MainActor () async -> Void) async { await refresh() }
}
