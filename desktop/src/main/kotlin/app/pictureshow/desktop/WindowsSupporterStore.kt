/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Partner Center product ID (InAppOfferToken). Configure the monthly USD 2.99 price there. */
internal const val WINDOWS_SUPPORTER_PRODUCT = "supporter.monthly"

@Serializable
private data class WindowsStoreReply(
    val products: List<StoreProduct>? = null,
    val entitlements: List<StoreEntitlement>? = null,
    val purchase: StorePurchase? = null,
    val error: String? = null
)

internal class WindowsSupporterStore(private val transport: suspend (Int, String) -> String) : StoreKitBridge {
    private val updates = MutableSharedFlow<StoreEntitlement>(extraBufferCapacity = 1)
    override val transactionUpdates = updates.asSharedFlow()

    override suspend fun products() = request(0).products.orEmpty()

    override suspend fun purchase(productId: String): StorePurchase {
        require(productId == WINDOWS_SUPPORTER_PRODUCT)
        val result = checkNotNull(request(1).purchase)
        if (result == StorePurchase.PURCHASED) {
            // An account may take a moment to receive its new license. The caller
            // can also refresh on its next visit using currentEntitlements().
            currentEntitlements().forEach { updates.tryEmit(it) }
        }
        return result
    }

    override suspend fun currentEntitlements() = request(2).entitlements.orEmpty()

    private suspend fun request(command: Int) =
        Json.decodeFromString<WindowsStoreReply>(transport(command, WINDOWS_SUPPORTER_PRODUCT)).also {
            it.error?.let { message -> error(message) }
        }
}

internal fun interface WindowsStoreReplyCallback : Callback {
    fun invoke(reply: WString)
}

internal interface WindowsStoreNative : Library {
    fun ps_windows_store_has_identity(): Int
    fun ps_windows_store_request(command: Int, owner: Pointer, product: WString, callback: WindowsStoreReplyCallback)
}

private val windowsStoreNative: WindowsStoreNative? by lazy {
    if (Platform.isWindows()) System.getProperty("pictureshow.windows.store.library")
        ?.let { Native.load(it, WindowsStoreNative::class.java) }
    else null
}

internal fun windowsStorePackaged() = windowsStoreNative?.ps_windows_store_has_identity() == 1

/** Call with the real, realized Pictureshow window from the shared Supporter screen. */
internal fun windowsSupporterStore(owner: Window): StoreKitBridge? {
    val native = windowsStoreNative?.takeIf { it.ps_windows_store_has_identity() != 0 } ?: return null
    val ownerHandle = Native.getWindowPointer(owner)
    return WindowsSupporterStore { command, product ->
        withContext(Dispatchers.IO) {
            val response = AtomicReference<String>()
            val callback = WindowsStoreReplyCallback { response.set(it.toString()) }
            native.ps_windows_store_request(command, ownerHandle, WString(product), callback)
            checkNotNull(response.get()) { "The Microsoft Store did not return a result." }
        }
    }
}
