/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.pictureshow.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class MacSupporterPageTest {
    @Test fun storeBuildPurchasesWaitsForVerificationRestoresAndManagesWhileRevocationRemovesThanks() = runComposeUiTest {
        val bridge = FakeStoreKit()
        setContent { MaterialTheme { DesktopSupporterPage(bridge) } }
        onNodeWithText("$2.99 / month").assertExists()
        onNodeWithText("Become a supporter").performClick()
        onNodeWithText("Waiting for the App Store to confirm your payment.").assertExists()
        onNodeWithText("Thank you for supporting Pictureshow!").assertDoesNotExist()
        onNodeWithText("Become a supporter").assertIsEnabled()
        assertEquals(listOf(MAC_SUPPORTER_PRODUCT), bridge.purchases)

        runOnIdle { bridge.update(active = true) }
        onNodeWithText("Thank you for supporting Pictureshow!").assertExists()
        onNodeWithText("123456").assertExists()
        onNodeWithText("Manage or cancel in the App Store").performClick()
        waitForIdle()
        assertEquals(1, bridge.managementRequests)

        runOnIdle { bridge.update(active = false) }
        onNodeWithText("Thank you for supporting Pictureshow!").assertDoesNotExist()
        onNodeWithText("Become a supporter").assertExists()
        onNodeWithText("Restore Purchases").performClick()
        onNodeWithText("Thank you for supporting Pictureshow!").assertExists()
        assertEquals(1, bridge.restores)
    }

    @Test fun noBridgeKeepsTheNonStoreInformationScreen() = runComposeUiTest {
        setContent { MaterialTheme { DesktopSupporterPage() } }
        onNodeWithText(DESKTOP_SUPPORTER_NO_PURCHASE).assertExists()
        onNodeWithText("Become a supporter").assertDoesNotExist()
        onNodeWithText("Restore Purchases").assertDoesNotExist()
    }

    private class FakeStoreKit : StoreKitBridge {
        private var entitlement = StoreEntitlement(MAC_SUPPORTER_PRODUCT, false, supporterCode = "123456")
        override val transactionUpdates = MutableSharedFlow<StoreEntitlement>(replay = 1)
        val purchases = mutableListOf<String>()
        var managementRequests = 0
            private set
        var restores = 0
            private set

        override suspend fun products() = listOf(StoreProduct(MAC_SUPPORTER_PRODUCT, "Supporter", "$2.99"))
        override suspend fun currentEntitlements() = listOf(entitlement)

        override suspend fun purchase(productId: String): StorePurchase {
            purchases += productId
            return StorePurchase.PENDING
        }

        override suspend fun manage(): Boolean {
            managementRequests++
            return true
        }

        override suspend fun restore() {
            restores++
            entitlement = entitlement.copy(active = true)
        }

        fun update(active: Boolean) {
            entitlement = entitlement.copy(active = active)
            check(transactionUpdates.tryEmit(entitlement))
        }
    }
}
