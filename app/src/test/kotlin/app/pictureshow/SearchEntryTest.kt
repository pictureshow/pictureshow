/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.pictureshow.core.Account
import app.pictureshow.core.AccountSnapshot
import app.pictureshow.core.SEARCH_QUERY_LIMIT
import app.pictureshow.core.Server
import app.pictureshow.core.User
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
class SearchEntryTest {
    private val account = Account(Server("server", "Test", "https://media.example.test/base", "12"), User("user", "Viewer"), "token")
    private val actions = if (BuildConfig.FIRE_TV) listOf(Intent.ACTION_SEARCH) else listOf(Intent.ACTION_SEARCH, ASSISTANT_SEARCH_ACTION)

    @Test
    fun supportedActionsTrimCapAndPreserveSpecialCharactersWhileIgnoringEmptyOrUnrelatedIntents() {
        actions.forEach { action ->
            val intent = Intent(action).putExtra(SearchManager.QUERY, "  cloud \"orb\" & # 日本語  ")
            assertEquals("cloud \"orb\" & # 日本語", parseSearchIntent(intent))
            assertEquals(SEARCH_QUERY_LIMIT, parseSearchIntent(Intent(action).putExtra(SearchManager.QUERY, "x".repeat(500)))!!.length)
            assertNull(parseSearchIntent(Intent(action).putExtra(SearchManager.QUERY, "  \n ")))
            assertNull(parseSearchIntent(Intent(action)))
        }
        assertNull(parseSearchIntent(Intent(Intent.ACTION_VIEW).putExtra(SearchManager.QUERY, "cloud")))
    }

    @Test
    fun manifestRegistersStoreAppropriateSearchActionsOnTheExportedMainActivity() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        actions.forEach { action ->
            val matches = app.packageManager.queryIntentActivities(Intent(action).setPackage(app.packageName), 0)
            assertTrue(matches.any { it.activityInfo.name == MainActivity::class.java.name && it.activityInfo.exported })
        }
        if (BuildConfig.FIRE_TV) {
            val google = Intent(ASSISTANT_SEARCH_ACTION).setPackage(app.packageName).putExtra(SearchManager.QUERY, "cloud")
            assertFalse(app.packageManager.queryIntentActivities(google, 0).any { it.activityInfo.name == MainActivity::class.java.name })
            assertNull(parseSearchIntent(google))
        }
    }

    @Test
    fun signedInIntentsOpenEncodedSearchWithKeyboardClosedAndOtherSessionsUseNormalStartup() {
        val query = "cloud \"orb\" & # 日本語"
        val snapshot = AccountSnapshot(listOf(account), account.key)
        val route = searchIntentDestination(snapshot, query)
        assertEquals("search", route.substringBefore('?'))
        val uri = Uri.parse("pictureshow://$route")
        assertEquals(query, uri.getQueryParameter("query"))
        assertEquals("false", uri.getQueryParameter("keyboard"))
        assertEquals("servers", searchIntentDestination(AccountSnapshot(), query))
        assertEquals("accounts", searchIntentDestination(snapshot.copy(currentKey = null), query))
        assertEquals(
            accountDestination(snapshot.copy(accounts = listOf(account.copy(expired = true)))),
            searchIntentDestination(snapshot.copy(accounts = listOf(account.copy(expired = true))), query)
        )
    }
}
