/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SearchDeadlineTest {
    @Test
    fun httpCategoryTimeoutKeepsPartialStateAndRetryRecoversWithoutLateOverwrite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var stall = true
        var failShows = false
        var attemptedMovies = 0
        var answeredMovies = 0
        val http = HttpClient(MockEngine) {
            engine {
                this.dispatcher = dispatcher
                addHandler {
                    when (it.url.parameters["includeItemTypes"]) {
                        "Movie" -> {
                            attemptedMovies++
                            if (stall) delay(35_000)
                            answeredMovies++
                            respond("""{"Items":[{"Id":"movie","Name":"Cloud Observatory","Type":"Movie"}],"TotalRecordCount":1}""")
                        }
                        "Series" -> if (failShows) {
                            respond("unavailable", HttpStatusCode.ServiceUnavailable)
                        } else {
                            respond("""{"Items":[],"TotalRecordCount":0}""")
                        }
                        else -> respond("""{"Items":[],"TotalRecordCount":0}""")
                    }
                }
            }
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        val account = Account(Server("s", "Test", "https://media.example.test", "12"), User("u", "Viewer"), "token")
        val store = ViewModelStore()
        val vm =
            SearchViewModel(account, CatalogSession(JellyfinClient(http, ClientIdentity("Test TV", "search-test")), accounts), accounts)
        store.put("vm", vm)
        try {
            vm.query("cloud", immediate = true)
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            assertTrue(vm.state.value.completed)
            assertTrue(vm.state.value.someFailed)
            assertFalse(vm.state.value.allFailed)
            assertTrue(vm.state.value.rows.isEmpty())
            assertEquals(0, answeredMovies)
            stall = false
            vm.retry()
            advanceUntilIdle()
            assertEquals("movie", vm.state.value.rows.single().items.single().id)
            assertFalse(vm.state.value.someFailed)
            assertEquals(2, attemptedMovies)
            assertEquals(1, answeredMovies)
            failShows = true
            vm.retry()
            advanceUntilIdle()
            assertEquals("movie", vm.state.value.rows.single().items.single().id)
            assertTrue(vm.state.value.someFailed)
            assertFalse(vm.state.value.allFailed)
        } finally {
            store.clear()
            http.close()
            Dispatchers.resetMain()
        }
    }
}
