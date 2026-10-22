/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

class SupporterPersistenceTest {
    @Test fun statusAndOrderSurviveReopenAndRevocationRemovesOrder() = runTest {
        val directory = Files.createTempDirectory("supporter-reopen").toFile()
        val file = directory.resolve("settings.preferences_pb")
        var scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            var data = PreferenceDataStoreFactory.create(scope = scope) { file }
            SupporterStore(data).write(SupporterStatus(true, "GPA.fixture"))
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            scope = CoroutineScope(coroutineContext + SupervisorJob())
            data = PreferenceDataStoreFactory.create(scope = scope) { file }
            val reopened = SupporterStore(data)
            assertEquals(SupporterStatus(true, "GPA.fixture"), reopened.read())
            SupporterController(FakeSupporterBilling(), reopened, scope).refresh()
            assertEquals(SupporterStatus(), reopened.read())
        } finally {
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            directory.deleteRecursively()
        }
    }
}
