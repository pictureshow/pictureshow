/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val SUPPORTER_DESCRIPTION = "Pictureshow is free and open source. Supporters pay monthly to have their feature requests read and considered first."
internal const val SUPPORTER_NO_GUARANTEE = "Requests are not guaranteed to be built or delivered by a particular date."
private const val CHECKING = "Checking the App Store…"
private const val PENDING = "Waiting for the App Store to confirm your payment."
private const val RESTORING = "Restoring purchases…"
private const val PRODUCT_UNAVAILABLE = "The Supporter subscription is not available in the App Store."
private const val FAILURE = "Could not complete the App Store request. Try again later."

@Composable
internal fun MacSupporterPage(bridge: StoreKitBridge) {
    val model = remember(bridge) { MacSupporter(bridge) }
    val scope = rememberCoroutineScope()
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(model, focused) { model.load() }
    LaunchedEffect(model) { model.observe() }

    FormPage("Support Pictureshow", SUPPORTER_DESCRIPTION) {
        Text(SUPPORTER_NO_GUARANTEE, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val entitlement = model.entitlement
        val product = model.product
        if (entitlement != null) {
            Text("Thank you for supporting Pictureshow!", style = MaterialTheme.typography.titleLarge)
            entitlement.supporterCode?.let { code ->
                InfoLine("Your supporter code", code)
                Text("Email info@legitimateapps.com with your request and supporter code.")
            }
            OutlinedButton(
                onClick = { scope.launch { model.manage() } },
                modifier = Modifier.controlFocus().probe("supporter:manage")
            ) { Text("Manage or cancel in the App Store") }
            if (model.showManageInstructions) {
                Text("To manage or cancel, open the App Store, then choose your name, Account Settings, and Subscriptions.")
            }
        } else if (product != null) {
            Text(product.name)
            Text("${product.displayPrice} / month", style = MaterialTheme.typography.headlineSmall)
            Text("Renews monthly until cancelled. Cancel anytime in the App Store.")
            if (model.notice != PENDING) {
                Button(
                    onClick = { scope.launch { model.purchase() } },
                    modifier = Modifier.controlFocus().probe("supporter:purchase"),
                    enabled = model.operation == null && !model.loading
                ) { Text("Become a supporter") }
            }
        } else {
            Text(if (model.loading) CHECKING else model.offerMessage ?: CHECKING)
        }
        (model.operation ?: model.notice)?.let {
            Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        OutlinedButton(
            onClick = { scope.launch { model.restore() } },
            modifier = Modifier.controlFocus().probe("supporter:restore"),
            enabled = model.operation == null
        ) { Text("Restore Purchases") }
        TextButton(onClick = { desktopOpenLink("https://www.apple.com/legal/internet-services/itunes/dev/stdeula/") },
            modifier = Modifier.controlFocus()) { Text("Terms of Use") }
        TextButton(onClick = { desktopOpenLink(PICTURESHOW_PRIVACY_URL) },
            modifier = Modifier.controlFocus()) { Text("Privacy Policy") }
    }
}

private class MacSupporter(private val bridge: StoreKitBridge) {
    var entitlement by mutableStateOf<StoreEntitlement?>(null)
        private set
    var product by mutableStateOf<StoreProduct?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var offerMessage by mutableStateOf<String?>(null)
        private set
    var operation by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var showManageInstructions by mutableStateOf(false)
        private set

    private var entitlementRead = 0

    suspend fun load() {
        if (loading) return
        loading = true
        try {
            refreshEntitlement()
            product = bridge.products().firstOrNull { it.id == MAC_SUPPORTER_PRODUCT }
            offerMessage = if (product == null) PRODUCT_UNAVAILABLE else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            product = null
            offerMessage = failure.messageForSupporter()
        } finally {
            loading = false
        }
    }

    suspend fun purchase() {
        if (operation != null || notice == PENDING || product == null || entitlement != null) return
        runStoreOperation(PENDING) {
            refreshEntitlement()
            if (entitlement == null) {
                when (bridge.purchase(MAC_SUPPORTER_PRODUCT)) {
                    StorePurchase.PURCHASED -> refreshEntitlement()
                    StorePurchase.PENDING -> if (entitlement == null) notice = PENDING
                    StorePurchase.CANCELLED -> Unit
                }
            }
        }
    }

    suspend fun restore() = runStoreOperation(RESTORING) {
        bridge.restore()
        refreshEntitlement()
        if (entitlement == null && notice != PENDING) {
            notice = "No active Supporter subscription was found for this Apple Account."
        }
    }

    suspend fun manage() {
        showManageInstructions = try {
            !bridge.manage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }
    }

    suspend fun observe() {
        bridge.transactionUpdates.collect { refreshEntitlement() }
    }

    private suspend fun refreshEntitlement() {
        val read = ++entitlementRead
        val result = try {
            bridge.currentEntitlements()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Fail closed and avoid using an earlier success after a store read failure.
            emptyList()
        }
        if (read != entitlementRead) return
        entitlement = result.firstOrNull { it.productId == MAC_SUPPORTER_PRODUCT && it.active }
        if (entitlement != null) notice = null
    }

    private suspend fun runStoreOperation(message: String, action: suspend () -> Unit) {
        if (operation != null) return
        operation = message
        if (notice != PENDING) notice = null
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (notice != PENDING) notice = failure.messageForSupporter()
        } finally {
            operation = null
        }
    }

    private fun Exception.messageForSupporter() = when ((this as? StoreRequestException)?.failure) {
        StoreFailure.PAYMENTS_RESTRICTED -> "Purchases are unavailable on this device or Apple Account."
        StoreFailure.PRODUCT_UNAVAILABLE -> PRODUCT_UNAVAILABLE
        StoreFailure.NETWORK -> "Could not reach the App Store. Check your connection and try again later."
        StoreFailure.CANCELLED -> null
        null -> FAILURE
    }
}
