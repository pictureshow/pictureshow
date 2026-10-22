/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal enum class BillingFailure { Unavailable, Network, ProductMissing, Other }
internal sealed interface BillingReply<out T> {
    data class Ok<T>(val value: T) : BillingReply<T>
    data class Failed(val reason: BillingFailure) : BillingReply<Nothing>
    data object Cancelled : BillingReply<Nothing>
}
internal data class SupporterProduct(val price: String, val period: String)
internal data class SupporterPurchase(
    val products: List<String>,
    val token: String,
    val orderId: String?,
    val purchased: Boolean,
    val pending: Boolean = false,
    val acknowledged: Boolean = false,
    val suspended: Boolean = false
)
internal interface SupporterBilling {
    var onPurchases: (BillingReply<List<SupporterPurchase>>) -> Unit
    suspend fun connect(): BillingReply<Unit>
    suspend fun product(): BillingReply<SupporterProduct>
    suspend fun purchases(): BillingReply<List<SupporterPurchase>>
    suspend fun acknowledge(token: String): BillingReply<Unit>
    fun launch(activity: Activity): BillingReply<Unit>
}
internal data class SupporterStatus(val active: Boolean = false, val orderId: String? = null)
internal class SupporterStore(private val preferences: DataStore<Preferences>) {
    suspend fun read(): SupporterStatus {
        val data = preferences.data.first()
        return SupporterStatus(data[ACTIVE] ?: false, data[ORDER])
    }
    suspend fun write(value: SupporterStatus) {
        preferences.edit { data ->
            data[ACTIVE] = value.active
            value.orderId?.let { data[ORDER] = it } ?: data.remove(ORDER)
        }
    }
    private companion object {
        val ACTIVE = booleanPreferencesKey("supporter_active")
        val ORDER = stringPreferencesKey("supporter_order")
    }
}
internal sealed interface SupporterState {
    data object Loading : SupporterState
    data class Offer(val product: SupporterProduct) : SupporterState
    data object Pending : SupporterState
    data class Active(val orderId: String?) : SupporterState
    data class Unavailable(val reason: BillingFailure) : SupporterState
}
internal class SupporterController(
    private val billing: SupporterBilling,
    private val store: SupporterStore,
    private val scope: CoroutineScope
) {
    private val mutable = MutableStateFlow<SupporterState>(SupporterState.Loading)
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private val acknowledged = mutableSetOf<String>()
    private var offer: SupporterProduct? = null
    private var launching = false

    init {
        billing.onPurchases = { result ->
            scope.launch {
                guarded {
                    launching = false
                    when (result) {
                        is BillingReply.Ok -> {
                            if (result.value.any { "supporter" in it.products && (it.purchased || it.pending) && !it.suspended }) {
                                process(result.value)
                            } else {
                                refreshLocked()
                            }
                        }
                        is BillingReply.Failed -> {
                            unavailable(result.reason)
                            refreshLocked()
                        }
                        BillingReply.Cancelled -> refreshLocked()
                    }
                }
            }
        }
    }

    suspend fun refresh() = guarded {
        launching = false
        refreshLocked()
    }

    private suspend fun refreshLocked() {
        when (val connection = billing.connect()) {
            is BillingReply.Failed -> return unavailable(connection.reason)
            BillingReply.Cancelled -> return unavailable(BillingFailure.Other)
            is BillingReply.Ok -> Unit
        }
        // Always restore/revoke before fetching the catalog: an existing supporter still has a code
        // when a new purchase is unavailable in their current region or the product is unpublished.
        when (val owned = billing.purchases()) {
            is BillingReply.Failed -> unavailable(owned.reason)
            BillingReply.Cancelled -> unavailable(BillingFailure.Other)
            is BillingReply.Ok -> process(owned.value)
        }
    }

    private suspend fun process(purchases: List<SupporterPurchase>) {
        val relevant = purchases.filter { "supporter" in it.products && !it.suspended }
        val active = relevant.filter { it.purchased }
        // Local state is only a convenience. Maintainers check the emailed Play order ID in
        // Play Console to honor request priority off-device; spoofing this state gains nothing.
        val status = SupporterStatus(active.isNotEmpty(), active.firstOrNull()?.orderId)
        store.write(status)
        if (status.active) {
            mutable.value = SupporterState.Active(status.orderId)
            active.distinctBy { it.token }.filter { !it.acknowledged && it.token !in acknowledged }.forEach { purchase ->
                if (billing.acknowledge(purchase.token) is BillingReply.Ok) acknowledged += purchase.token
            }
        } else if (relevant.any { it.pending }) {
            mutable.value = SupporterState.Pending
        } else {
            when (val product = billing.product()) {
                is BillingReply.Ok -> {
                    offer = product.value
                    mutable.value = SupporterState.Offer(product.value)
                }
                is BillingReply.Failed -> unavailable(product.reason)
                BillingReply.Cancelled -> unavailable(BillingFailure.Other)
            }
        }
    }

    fun purchase(activity: Activity) {
        if (launching || mutable.value !is SupporterState.Offer || offer == null) return
        launching = true
        val launched = try {
            billing.launch(activity)
        } catch (_: Exception) {
            BillingReply.Failed(BillingFailure.Other)
        }
        when (val result = launched) {
            is BillingReply.Ok -> Unit // Play owns the purchase UI until its callback or foreground restore.
            BillingReply.Cancelled -> launching = false
            is BillingReply.Failed -> {
                launching = false
                unavailable(result.reason)
                // An already-owned response also restores/acknowledges through the normal query.
                scope.launch { refresh() }
            }
        }
    }

    private fun unavailable(reason: BillingFailure) {
        mutable.value = SupporterState.Unavailable(reason)
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        mutex.withLock {
            try {
                withTimeout(20_000) { block() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                unavailable(BillingFailure.Network)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailable(BillingFailure.Other)
            }
        }
    }
}
