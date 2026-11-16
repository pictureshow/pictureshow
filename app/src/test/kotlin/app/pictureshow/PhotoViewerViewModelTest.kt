/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** PHOTO-01 against the server's real shape: grid items arrive without ParentId, which only the item's own read has. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoViewerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "token")
    private val album = listOf("a", "b", "c").map { MediaItem(it, it, type = "Photo") }

    private class Photos(private val parent: String?, private val album: List<MediaItem>) :
        CatalogService by CatalogModelsTest.FakeCatalog() {
        val reads = mutableListOf<String>()
        override suspend fun item(account: Account, id: String): ClientResult<MediaItem> {
            reads += "item:$id"
            return ClientResult.Success(MediaItem(id, id, type = "Photo", parentId = parent))
        }
        override suspend fun photos(account: Account, parentId: String, sortBy: String, descending: Boolean): ClientResult<ItemPage> {
            reads += "photos:$parentId:$sortBy:$descending"
            return if (parentId == "album") {
                ClientResult.Success(ItemPage(album, album.size))
            } else {
                ClientResult.Failure(ClientError(ErrorKind.SERVER, "missing"))
            }
        }
    }

    private fun session(service: CatalogService) = CatalogSession(
        service,
        AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
    )

    @BeforeTest
    fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun cleanup() = Dispatchers.resetMain()

    @Test
    fun aGridPhotoWithoutItsParentStillOpensAmongItsAlbum() = runTest(dispatcher) {
        val service = Photos("album", album)
        val vm = PhotoViewerViewModel(account, MediaItem("b", "b", type = "Photo"), "DateCreated", true, session(service))
        advanceUntilIdle()
        assertEquals(listOf("item:b", "photos:album:DateCreated:true"), service.reads)
        assertEquals(PhotoViewerState(album, 1, loading = false), vm.state.value)
    }

    @Test
    fun aKnownParentSkipsTheItemRead() = runTest(dispatcher) {
        val service = Photos("unused", album)
        val vm = PhotoViewerViewModel(account, MediaItem("c", "c", type = "Photo", parentId = "album"), "SortName", false, session(service))
        advanceUntilIdle()
        assertEquals(listOf("photos:album:SortName:false"), service.reads)
        assertEquals(2, vm.state.value.start)
    }

    @Test
    fun aFailedReadShowsJustTheChosenPhoto() = runTest(dispatcher) {
        val chosen = MediaItem("x", "x", type = "Photo")
        val vm = PhotoViewerViewModel(account, chosen, "SortName", false, session(Photos("gone", album)))
        advanceUntilIdle()
        assertEquals(PhotoViewerState(listOf(chosen), 0, loading = false), vm.state.value)
    }
}
