/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class WindowsSupporterStoreTest {
    private class FakeStore {
        val commands = mutableListOf<Int>()
        var purchaseResult = "PURCHASED"
        val store = WindowsSupporterStore { command, offer ->
            assertEquals("supporter.monthly", offer)
            commands += command
            when (command) {
                0 -> """{"products":[{"id":"supporter.monthly","name":"Supporter","displayPrice":"USD 2.99"}]}"""
                1 -> """{"purchase":"$purchaseResult"}"""
                2 -> """{"entitlements":[{"productId":"supporter.monthly","active":true,"expirationMillis":12345}]}"""
                else -> error("Unexpected Store command $command")
            }
        }
    }

    @Test fun successfulPurchaseRefreshesTheStoreLicense() = runTest {
        val fake = FakeStore()
        assertEquals("USD 2.99", fake.store.products().single().displayPrice)
        assertEquals(StorePurchase.PURCHASED, fake.store.purchase(WINDOWS_SUPPORTER_PRODUCT))
        assertEquals(listOf(0, 1, 2), fake.commands)
        assertEquals(StoreEntitlement(WINDOWS_SUPPORTER_PRODUCT, true, 12345L), fake.store.currentEntitlements().single())
    }

    @Test fun cancellationAndUnexpectedProductsDoNotRefreshTheStoreLicense() = runTest {
        val fake = FakeStore().apply { purchaseResult = "CANCELLED" }
        assertFailsWith<IllegalArgumentException> { fake.store.purchase("another-product") }
        assertEquals(StorePurchase.CANCELLED, fake.store.purchase(WINDOWS_SUPPORTER_PRODUCT))
        assertEquals(listOf(1), fake.commands)
    }
}
