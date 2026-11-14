/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LivePage
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveTvService
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LivePlayerMetadataTest {
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("viewer", "Viewer"), "token")
    private val current = LiveProgram("p", "Current program", "c", startDate = "1970-01-01T00:00:00Z", endDate = "1970-01-01T01:00:00Z")

    @Test fun currentProgramOnTheAuthorizedChannelNeedsNoSecondRead() = runTest {
        var reads = 0
        val service = object : LiveTvService by LiveFake() {
            override suspend fun channel(account: Account, id: String): ClientResult<LiveChannel> {
                assertEquals(this@LivePlayerMetadataTest.account, account)
                assertEquals("c", id)
                return ClientResult.Success(LiveChannel(id, "Channel", currentProgram = current))
            }
            override suspend fun programs(
                account: Account,
                channelIds: List<String>,
                startUtc: String,
                endUtc: String,
                startIndex: Int,
                limit: Int
            ): ClientResult<LivePage<LiveProgram>> {
                reads++
                return ClientResult.Success(LivePage())
            }
        }
        assertEquals(current, loadPlayerLiveChannel(service, account, "c", 30_000)?.currentProgram)
        assertEquals(0, reads)
    }

    @Test fun missingCurrentProgramUsesOnlyThisViewersChannelAndCurrentInterval() = runTest {
        val service = object : LiveTvService by LiveFake() {
            override suspend fun channel(account: Account, id: String) = ClientResult.Success(LiveChannel("c", "Channel"))
            override suspend fun programs(
                account: Account,
                channelIds: List<String>,
                startUtc: String,
                endUtc: String,
                startIndex: Int,
                limit: Int
            ): ClientResult<LivePage<LiveProgram>> {
                assertEquals(this@LivePlayerMetadataTest.account, account)
                assertEquals(listOf("c"), channelIds)
                assertEquals("1970-01-01T00:00:30Z", startUtc)
                assertEquals("1970-01-01T00:01:30Z", endUtc)
                return ClientResult.Success(LivePage(listOf(current.copy(channelId = "other"), current.copy(endDate = startUtc), current)))
            }
        }
        assertEquals(current, loadPlayerLiveChannel(service, account, "c", 30_000)?.currentProgram)
    }

    @Test fun aDifferentChannelCannotSupplyThePlayerHeader() = runTest {
        val service = object : LiveTvService by LiveFake() {
            override suspend fun channel(account: Account, id: String) =
                ClientResult.Success(LiveChannel("other", currentProgram = current))
            override suspend fun programs(
                account: Account,
                channelIds: List<String>,
                startUtc: String,
                endUtc: String,
                startIndex: Int,
                limit: Int
            ): ClientResult<LivePage<LiveProgram>> = error("must not read guide for wrong channel")
        }
        assertNull(loadPlayerLiveChannel(service, account, "c", 30_000))
    }
}
