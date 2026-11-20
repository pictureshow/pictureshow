/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MacStoreKitTest {
    @Test fun dispatchesProductsPurchaseEntitlementsAndVerifiedUpdatesThroughTheBridge() = runTest {
        val native = FakeStoreKitNative()
        val bridge: StoreKitBridge = MacStoreKit(native)

        assertEquals(listOf(StoreProduct(MAC_SUPPORTER_PRODUCT, "Supporter", "$2.99")), bridge.products())
        assertEquals(StorePurchase.PENDING, bridge.purchase(MAC_SUPPORTER_PRODUCT))
        val entitlement = StoreEntitlement(MAC_SUPPORTER_PRODUCT, true, 123000L)
        assertEquals(listOf(entitlement), bridge.currentEntitlements())
        native.emit("""{"update":{"productId":"$MAC_SUPPORTER_PRODUCT","active":false,"expirationMillis":123000}}""")
        assertEquals(entitlement.copy(active = false), bridge.transactionUpdates.first())
    }

    @Test fun rejectsUnsupportedProductsAndReportsStoreFailures() = runTest {
        val native = FakeStoreKitNative()
        val bridge: StoreKitBridge = MacStoreKit(native)

        assertFailsWith<IllegalArgumentException> { bridge.purchase("unrelated.product") }
        assertNull(native.lastRequestedProduct)
        native.overrideReply = """{"error":"The App Store is unavailable."}"""
        assertEquals("The App Store is unavailable.", assertFailsWith<IllegalStateException> { bridge.products() }.message)
    }

    private class FakeStoreKitNative : StoreKitNative {
        private lateinit var observer: StoreKitCallback
        var lastRequestedProduct: String? = null
        var overrideReply: String? = null

        override fun ps_storekit_request(id: Long, command: Int, product: String?, callback: StoreKitCallback) {
            lastRequestedProduct = product
            val reply = overrideReply ?: when (command) {
                1 -> """{"products":[{"id":"$MAC_SUPPORTER_PRODUCT","name":"Supporter","displayPrice":"$2.99"}]}"""
                2 -> """{"purchase":"PENDING"}"""
                3 -> """{"entitlements":[{"productId":"$MAC_SUPPORTER_PRODUCT","active":true,"expirationMillis":123000}]}"""
                else -> error("Unexpected command")
            }
            callback.invoke(id, reply)
        }

        override fun ps_storekit_observe(callback: StoreKitCallback) {
            observer = callback
        }

        fun emit(reply: String) = observer.invoke(0, reply)
    }
}
