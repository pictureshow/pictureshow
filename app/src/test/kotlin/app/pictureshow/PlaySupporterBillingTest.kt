/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlaySupporterBillingTest {
    private fun details(period: String = "P1M", basePlan: String = "monthly", recurrence: Int = 1): ProductDetails {
        val json = """{
          "productId":"supporter","type":"subs","title":"Supporter","name":"Supporter","description":"Fixture",
          "subscriptionOfferDetails":[{
            "basePlanId":"$basePlan","offerIdToken":"fixture-token","offerTags":[],
            "pricingPhases":[{"formattedPrice":"3,49 €","priceAmountMicros":3490000,"priceCurrencyCode":"EUR",
              "billingPeriod":"$period","billingCycleCount":0,"recurrenceMode":$recurrence}]
          }]
        }"""
        return ProductDetails::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance(json)
    }

    @Test fun actualProductDetailsPreservesFormattedPriceAndMonthlyPeriod() {
        val selected = monthlySupporterOffer(details())!!
        assertEquals(SupporterProduct("3,49 €", "P1M"), selected.first)
        assertEquals("fixture-token", selected.second)
    }

    @Test fun otherPeriodsPlansAndPrepaidNeverMasqueradeAsMonthly() {
        assertNull(monthlySupporterOffer(details(period = "P1Y")))
        assertNull(monthlySupporterOffer(details(basePlan = "annual")))
        assertNull(monthlySupporterOffer(details(recurrence = 3)))
    }

    @Test fun realPlayPurchaseMappingDistinguishesPendingPurchasedAndSuspended() {
        fun mapped(state: Int, suspended: Boolean = false) = supporterPurchase(
            Purchase(
                """{"productId":"supporter","purchaseToken":"fixture","orderId":"GPA.fixture",
                "purchaseState":$state,"acknowledged":false,"suspended":$suspended}""",
                "fixture-signature"
            )
        )
        val pending = mapped(4)
        assertTrue(pending.pending)
        assertFalse(pending.purchased)
        val purchased = mapped(0)
        assertTrue(purchased.purchased)
        assertFalse(purchased.pending)
        assertEquals("GPA.fixture", purchased.orderId)
        assertTrue(mapped(0, true).suspended)
    }
}
