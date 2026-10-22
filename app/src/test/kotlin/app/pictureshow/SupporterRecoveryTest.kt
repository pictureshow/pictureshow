/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Activity
import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SupporterRecoveryTest {
    private fun check(
        block: suspend kotlinx.coroutines.test.TestScope.(FakeSupporterBilling, SupporterStore, SupporterController, Activity) -> Unit
    ) = runTest {
        val directory = Files.createTempDirectory("supporter-recovery").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val data = PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("settings.preferences_pb") }
        val billing = FakeSupporterBilling()
        val store = SupporterStore(data)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            block(billing, store, SupporterController(billing, store, scope), activity.get())
        } finally {
            activity.pause().stop().destroy()
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            directory.deleteRecursively()
        }
    }
    private val purchased = SupporterPurchase(listOf("supporter"), "token", "GPA.fixture", true)

    @Test fun failedPurchaseCallbackRestoresAndAcknowledgesOwnedSubscription() = check { billing, store, controller, _ ->
        controller.refresh()
        billing.owned = BillingReply.Ok(listOf(purchased))
        billing.onPurchases(BillingReply.Failed(BillingFailure.Other))
        advanceUntilIdle()
        assertEquals(SupporterStatus(true, "GPA.fixture"), store.read())
        assertEquals(SupporterState.Active("GPA.fixture"), controller.state.value)
        assertEquals(listOf("token"), billing.acknowledgements)
    }

    @Test fun cancelledPurchaseCallbackRestoresOfferAndAllowsAnotherLaunch() = check { billing, _, controller, activity ->
        controller.refresh()
        controller.purchase(activity)
        controller.purchase(activity)
        assertEquals(1, billing.launches)
        billing.onPurchases(BillingReply.Cancelled)
        advanceUntilIdle()
        assertIs<SupporterState.Offer>(controller.state.value)
        controller.purchase(activity)
        assertEquals(2, billing.launches)
    }

    @Test fun immediatelyCancelledLaunchCanBeRetried() = check { billing, _, controller, activity ->
        controller.refresh()
        billing.launchResult = BillingReply.Cancelled
        controller.purchase(activity)
        controller.purchase(activity)
        assertEquals(2, billing.launches)
        assertIs<SupporterState.Offer>(controller.state.value)
    }

    @Test fun immediateLaunchErrorRestoresExistingSubscription() = check { billing, store, controller, activity ->
        controller.refresh()
        billing.owned = BillingReply.Ok(listOf(purchased))
        billing.launchResult = BillingReply.Failed(BillingFailure.Other)
        controller.purchase(activity)
        advanceUntilIdle()
        assertEquals(SupporterStatus(true, "GPA.fixture"), store.read())
        assertEquals(listOf("token"), billing.acknowledgements)
    }

    @Test fun purchaseUpdateGrantsAndAcknowledgesWithoutRequiringCatalogAvailability() = check { billing, store, controller, _ ->
        billing.offer = BillingReply.Failed(BillingFailure.ProductMissing)
        controller.refresh()
        billing.onPurchases(BillingReply.Ok(listOf(purchased)))
        advanceUntilIdle()
        assertEquals(SupporterStatus(true, "GPA.fixture"), store.read())
        assertEquals(listOf("token"), billing.acknowledgements)
    }
}
