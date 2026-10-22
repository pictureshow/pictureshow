/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Only the auto-renewing monthly base plan is offered; trials/prepaid/promotional offers are not selected. */
internal fun monthlySupporterOffer(details: ProductDetails): Pair<SupporterProduct, String>? {
    if (details.productId != "supporter" || details.productType != BillingClient.ProductType.SUBS) return null
    val offer = details.subscriptionOfferDetails?.firstOrNull {
        it.basePlanId == "monthly" &&
            it.offerId == null &&
            it.pricingPhases.pricingPhaseList.size == 1 &&
            it.pricingPhases.pricingPhaseList.single().let { phase ->
                phase.billingPeriod == "P1M" && phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING
            }
    } ?: return null
    val phase = offer.pricingPhases.pricingPhaseList.single()
    return SupporterProduct(phase.formattedPrice, phase.billingPeriod) to offer.offerToken
}

internal class PlaySupporterBilling(context: Context) : SupporterBilling {
    override var onPurchases: (BillingReply<List<SupporterPurchase>>) -> Unit = {}
    private var details: ProductDetails? = null
    private var offerToken: String? = null
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> onPurchases(result.reply { purchases.orEmpty().map(::supporterPurchase) }) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override suspend fun connect(): BillingReply<Unit> {
        if (client.isReady) return BillingReply.Ok(Unit)
        return suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result.reply { Unit })
                }
                override fun onBillingServiceDisconnected() {
                    if (continuation.isActive) continuation.resume(BillingReply.Failed(BillingFailure.Unavailable))
                }
            })
        }
    }

    override suspend fun product(): BillingReply<SupporterProduct> = suspendCancellableCoroutine { continuation ->
        details = null
        offerToken = null
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder().setProductId("supporter")
                    .setProductType(BillingClient.ProductType.SUBS).build()
            )
        ).build()
        client.queryProductDetailsAsync(params) { result, response ->
            if (continuation.isActive) {
                val product = response.productDetailsList.firstOrNull { monthlySupporterOffer(it) != null }
                val selected = product?.let(::monthlySupporterOffer)
                val reply = if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    result.reply { SupporterProduct("", "") }
                } else if (selected == null) {
                    BillingReply.Failed(BillingFailure.ProductMissing)
                } else {
                    details = product
                    offerToken = selected.second
                    BillingReply.Ok(selected.first)
                }
                continuation.resume(reply)
            }
        }
    }

    override suspend fun purchases(): BillingReply<List<SupporterPurchase>> = suspendCancellableCoroutine { continuation ->
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) {
                result,
                purchases
            ->
            if (continuation.isActive) continuation.resume(result.reply { purchases.map(::supporterPurchase) })
        }
    }

    override suspend fun acknowledge(token: String): BillingReply<Unit> = suspendCancellableCoroutine { continuation ->
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { result ->
            if (continuation.isActive) continuation.resume(result.reply { Unit })
        }
    }

    override fun launch(activity: Activity): BillingReply<Unit> {
        val product = details ?: return BillingReply.Failed(BillingFailure.ProductMissing)
        val token = offerToken ?: return BillingReply.Failed(BillingFailure.ProductMissing)
        return client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).setOfferToken(token).build())
            ).build()
        ).reply { Unit }
    }
}

internal fun supporterPurchase(purchase: Purchase) = SupporterPurchase(
    purchase.products,
    purchase.purchaseToken,
    purchase.orderId,
    purchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED,
    pending = purchase.purchaseState == Purchase.PurchaseState.PENDING,
    acknowledged = purchase.isAcknowledged,
    suspended = purchase.isSuspended
)

private inline fun <T> BillingResult.reply(value: () -> T): BillingReply<T> = when (responseCode) {
    BillingClient.BillingResponseCode.OK -> BillingReply.Ok(value())
    BillingClient.BillingResponseCode.USER_CANCELED -> BillingReply.Cancelled
    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
    BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> BillingReply.Failed(BillingFailure.Unavailable)
    BillingClient.BillingResponseCode.NETWORK_ERROR -> BillingReply.Failed(BillingFailure.Network)
    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> BillingReply.Failed(BillingFailure.ProductMissing)
    else -> BillingReply.Failed(BillingFailure.Other)
}
