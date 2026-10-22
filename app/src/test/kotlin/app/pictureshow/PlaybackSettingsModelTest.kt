@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.UserConfiguration
import app.pictureshow.core.UserConfigurationService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PlaybackSettingsModelTest {
    @Test
    fun serverChangesAreSerializedPreserveOtherPreferencesAndFailuresLeaveLastSavedValues() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val directory = Files.createTempDirectory("settings-model").toFile()
        val diskScope = CoroutineScope(coroutineContext + SupervisorJob())
        val preferences = PreferenceDataStoreFactory.create(scope = diskScope, produceFile = {
            directory.resolve("settings.preferences_pb")
        })
        val owner = ViewModelStore()
        try {
            val account = Account(Server("s", "Test", "https://media.example.test", "12.0.0"), User("u", "Sam"), "fake")
            val accounts = AccountStore(object : TextPersistence {
                override suspend fun read(): String? = null
                override suspend fun write(value: String) = Unit
            })
            accounts.remember(account)
            val initial =
                UserConfiguration(
                    Json.parseToJsonElement(
                        """{"AudioLanguagePreference":"eng","SubtitleLanguagePreference":"eng","SubtitleMode":"Always","Other":{"keep":true}}"""
                    ).jsonObject
                )
            val writes = mutableListOf<UserConfiguration>()
            val gate = CompletableDeferred<Unit>()
            var reject = false
            val service = object : UserConfigurationService {
                override suspend fun configuration(account: Account) = ClientResult.Success(initial)
                override suspend fun saveConfiguration(account: Account, configuration: UserConfiguration): ClientResult<Unit> {
                    writes += configuration
                    gate.await()
                    return if (reject) ClientResult.Failure(ClientError(ErrorKind.SERVER, "Try again")) else ClientResult.Success(Unit)
                }
            }
            val vm = PlaybackSettingsViewModel(account, PlaybackPreferencesStore(preferences), service, accounts)
            owner.put("settings", vm)
            runCurrent()
            vm.server("AudioLanguagePreference", "jpn")
            runCurrent()
            vm.server("SubtitleMode", "None") // Controls are disabled while the first whole-object write is pending.
            assertEquals(1, writes.size)
            assertEquals("eng", vm.state.value.configuration!!.audioLanguage)
            gate.complete(Unit)
            runCurrent()
            assertEquals("jpn", vm.state.value.configuration!!.audioLanguage)
            vm.server("SubtitleMode", "None")
            runCurrent()
            assertEquals("jpn", writes.last().audioLanguage)
            assertEquals("eng", writes.last().subtitleLanguage)
            assertEquals(initial.fields["Other"], writes.last().fields["Other"])
            reject = true
            vm.server("SubtitleLanguagePreference", "fra")
            runCurrent()
            assertEquals("eng", vm.state.value.configuration!!.subtitleLanguage)
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.saving)
            vm.local { it.copy(passthrough = false, bitrateMbps = 40) }
            advanceUntilIdle()
            assertEquals(40_000_000, vm.state.value.local.deviceSettings().maxStreamingBitrate)
            assertFalse(vm.state.value.local.deviceSettings().passthroughEnabled)
        } finally {
            owner.clear()
            diskScope.cancel()
            diskScope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            directory.deleteRecursively()
            Dispatchers.resetMain()
        }
    }
}
