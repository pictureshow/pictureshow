/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val MAC_SUPPORTER_PRODUCT = "app.pictureshow.supporter.monthly"

@Serializable
internal data class StoreProduct(val id: String, val name: String, val displayPrice: String)

@Serializable
internal data class StoreEntitlement(val productId: String, val active: Boolean, val expirationMillis: Long? = null)

@Serializable
internal enum class StorePurchase { PURCHASED, PENDING, CANCELLED }

internal interface StoreKitBridge {
    suspend fun products(): List<StoreProduct>
    suspend fun purchase(productId: String): StorePurchase
    suspend fun currentEntitlements(): List<StoreEntitlement>
    val transactionUpdates: Flow<StoreEntitlement>
}

@Serializable
private data class StoreReply(
    val products: List<StoreProduct>? = null,
    val entitlements: List<StoreEntitlement>? = null,
    val purchase: StorePurchase? = null,
    val update: StoreEntitlement? = null,
    val error: String? = null
)

internal fun interface StoreKitCallback : Callback {
    fun invoke(id: Long, response: String)
}

internal interface StoreKitNative : Library {
    fun ps_storekit_request(id: Long, command: Int, product: String?, callback: StoreKitCallback)
    fun ps_storekit_observe(callback: StoreKitCallback)
}

private interface StoreStorageNative : Library {
    fun ps_store_container_data(): Pointer?
    fun ps_store_download_data(): Pointer?
    fun ps_store_download_has_bookmark(): Int
    fun ps_store_download_use_container()
    fun ps_store_download_choose(): Int
    fun ps_store_free(pointer: Pointer)
}

internal class MacStoreKit(private val native: StoreKitNative) : StoreKitBridge {
    private val nextId = AtomicLong()
    private val inflight = ConcurrentHashMap<Long, CompletableDeferred<StoreReply>>()
    private val mutableUpdates = MutableSharedFlow<StoreEntitlement>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val transactionUpdates = mutableUpdates.asSharedFlow()

    // StoreKit owns asynchronous calls and can finish one after its coroutine
    // is cancelled. This app-lifetime callback remains live for such replies.
    private val callback = StoreKitCallback { id, response ->
        val decoded = runCatching { Json.decodeFromString<StoreReply>(response) }
        if (id == 0L) {
            decoded.getOrNull()?.update?.let { mutableUpdates.tryEmit(it) }
        } else {
            inflight.remove(id)?.let { request ->
                decoded.fold(request::complete, request::completeExceptionally)
            }
        }
    }

    init {
        native.ps_storekit_observe(callback)
    }

    override suspend fun products() = requireNotNull(send(1).products)

    override suspend fun purchase(productId: String): StorePurchase {
        require(productId == MAC_SUPPORTER_PRODUCT)
        return requireNotNull(send(2, productId).purchase)
    }

    override suspend fun currentEntitlements() = requireNotNull(send(3).entitlements)

    private suspend fun send(command: Int, product: String? = null): StoreReply {
        val id = nextId.incrementAndGet()
        val completion = CompletableDeferred<StoreReply>()
        inflight[id] = completion
        try {
            native.ps_storekit_request(id, command, product, callback)
            return completion.await().also { reply -> reply.error?.let { error(it) } }
        } finally {
            inflight.remove(id)
        }
    }
}

private val storeLibrary = System.getProperty("pictureshow.storekit.library")
internal val packagedStoreKit: StoreKitBridge? by lazy {
    storeLibrary?.let { MacStoreKit(Native.load(it, StoreKitNative::class.java)) }
}

internal class MacStoreStorage private constructor(private val native: StoreStorageNative) {
    private fun path(location: Pointer?): File? = location?.let { pointer ->
        try {
            File(pointer.getString(0, "UTF-8"))
        } finally {
            native.ps_store_free(pointer)
        }
    }

    fun applicationData(): File = requireNotNull(path(native.ps_store_container_data()))
    val downloadFolder: File? by lazy { path(native.ps_store_download_data()) }
    fun hasBookmark(): Boolean = native.ps_store_download_has_bookmark() != 0
    fun useContainer() = native.ps_store_download_use_container()
    fun chooseFolder(): Int = native.ps_store_download_choose()

    companion object {
        val packaged: MacStoreStorage? by lazy {
            storeLibrary?.let { MacStoreStorage(Native.load(it, StoreStorageNative::class.java)) }
        }
    }
}
