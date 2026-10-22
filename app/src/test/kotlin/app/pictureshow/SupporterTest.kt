/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

internal class FakeSupporterBilling : SupporterBilling {
    override var onPurchases: (BillingReply<List<SupporterPurchase>>) -> Unit = {}
    var connection: BillingReply<Unit> = BillingReply.Ok(Unit)
    var offer: BillingReply<SupporterProduct> = BillingReply.Ok(SupporterProduct("€3.19", "P1M"))
    var owned: BillingReply<List<SupporterPurchase>> = BillingReply.Ok(emptyList())
    var acknowledgement: BillingReply<Unit> = BillingReply.Ok(Unit)
    val acknowledgements = mutableListOf<String>()
    var launches = 0
    var launchResult: BillingReply<Unit> = BillingReply.Ok(Unit)
    override suspend fun connect() = connection
    override suspend fun product() = offer
    override suspend fun purchases() = owned
    override suspend fun acknowledge(token: String): BillingReply<Unit> {
        acknowledgements += token
        return acknowledgement
    }
    override fun launch(activity: Activity): BillingReply<Unit> {
        launches++
        return launchResult
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SupporterTest {
    private fun check(
        block: suspend kotlinx.coroutines.test.TestScope.(FakeSupporterBilling, SupporterStore, SupporterController) -> Unit
    ) = runTest {
        val directory = Files.createTempDirectory("supporter-test").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val data = PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("settings.preferences_pb") }
        val billing = FakeSupporterBilling()
        val store = SupporterStore(data)
        try {
            block(billing, store, SupporterController(billing, store, scope))
        } finally {
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            directory.deleteRecursively()
        }
    }
    private fun purchase(acknowledged: Boolean = false) =
        SupporterPurchase(listOf("supporter"), "token", "GPA.fixture", true, acknowledged = acknowledged)

    @Test fun pendingGrantsNothing() = check { billing, store, controller ->
        billing.owned = BillingReply.Ok(listOf(purchase().copy(purchased = false, pending = true)))
        controller.refresh()
        assertEquals(SupporterState.Pending, controller.state.value)
        assertEquals(SupporterStatus(), store.read())
        assertTrue(billing.acknowledgements.isEmpty())
    }

    @Test fun purchasedUnacknowledgedIsAcknowledgedOnceAndGrantedAcrossDuplicateUpdates() = check { billing, store, controller ->
        billing.owned = BillingReply.Ok(listOf(purchase()))
        controller.refresh()
        billing.onPurchases(billing.owned)
        billing.onPurchases(billing.owned)
        advanceUntilIdle()
        assertEquals(SupporterState.Active("GPA.fixture"), controller.state.value)
        assertEquals(SupporterStatus(true, "GPA.fixture"), store.read())
        assertEquals(listOf("token"), billing.acknowledgements)
    }

    @Test fun acknowledgedPurchaseIsNotAcknowledgedAgain() = check { billing, store, controller ->
        billing.owned = BillingReply.Ok(listOf(purchase(true)))
        controller.refresh()
        assertEquals(SupporterStatus(true, "GPA.fixture"), store.read())
        assertTrue(billing.acknowledgements.isEmpty())
    }

    @Test fun emptyStartupQueryRemovesPersistedSupporter() = check { _, store, controller ->
        store.write(SupporterStatus(true, "old-order"))
        controller.refresh()
        assertEquals(SupporterStatus(), store.read())
        assertIs<SupporterState.Offer>(controller.state.value)
    }

    @Test fun productNotFoundShowsUnavailable() = check { billing, _, controller ->
        billing.offer = BillingReply.Failed(BillingFailure.ProductMissing)
        controller.refresh()
        assertEquals(SupporterState.Unavailable(BillingFailure.ProductMissing), controller.state.value)
    }

    @Test fun billingUnavailableShowsUnavailable() = check { billing, _, controller ->
        billing.connection = BillingReply.Failed(BillingFailure.Unavailable)
        controller.refresh()
        assertEquals(SupporterState.Unavailable(BillingFailure.Unavailable), controller.state.value)
    }

    @Test fun priceComesUnchangedFromProductDetailsBoundary() = check { billing, _, controller ->
        billing.offer = BillingReply.Ok(SupporterProduct("¥487", "P1M"))
        controller.refresh()
        assertEquals("¥487", assertIs<SupporterState.Offer>(controller.state.value).product.price)
    }

    @Test fun unrelatedAndSuspendedPurchasesGrantNothing() = check { billing, store, controller ->
        billing.owned = BillingReply.Ok(listOf(purchase().copy(products = listOf("other")), purchase().copy(suspended = true)))
        controller.refresh()
        assertIs<SupporterState.Offer>(controller.state.value)
        assertEquals(SupporterStatus(), store.read())
        assertTrue(billing.acknowledgements.isEmpty())
    }

    @Test fun failedAcknowledgementIsRetriedOnNextForegroundQuery() = check { billing, store, controller ->
        billing.owned = BillingReply.Ok(listOf(purchase()))
        billing.acknowledgement = BillingReply.Failed(BillingFailure.Network)
        controller.refresh()
        assertEquals(SupporterStatus(true, "GPA.fixture"), store.read())
        billing.acknowledgement = BillingReply.Ok(Unit)
        controller.refresh()
        assertEquals(listOf("token", "token"), billing.acknowledgements)
    }

    @Test fun networkErrorDoesNotEraseSavedStatusOrLeaveLoading() = check { billing, store, controller ->
        store.write(SupporterStatus(true, "old-order"))
        billing.owned = BillingReply.Failed(BillingFailure.Network)
        controller.refresh()
        assertEquals(SupporterState.Unavailable(BillingFailure.Network), controller.state.value)
        assertEquals(SupporterStatus(true, "old-order"), store.read())
    }
}
