/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.pictureshow.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreSupporterPageTest {
    @Test fun storeBuildPurchasesWaitsForVerificationRestoresAndManagesWhileRevocationRemovesThanks() = runComposeUiTest {
        val bridge = FakeStoreKit()
        setContent { MaterialTheme { DesktopSupporterPage(bridge) } }
        onNodeWithText("$2.99 / month").assertExists()
        onNodeWithText("Become a supporter").performClick()
        onNodeWithText("Waiting for the App Store to confirm your payment.").assertExists()
        onNodeWithText("Thank you for supporting Pictureshow!").assertDoesNotExist()
        onNodeWithText("Become a supporter").assertDoesNotExist()
        assertEquals(listOf(MAC_SUPPORTER_PRODUCT), bridge.purchases)

        onNodeWithText("Restore purchases").performClick()
        onNodeWithText("No active Supporter subscription was found for this Apple Account.").assertExists()
        onNodeWithText("Become a supporter").performClick()
        onNodeWithText("Waiting for the App Store to confirm your payment.").assertExists()

        runOnIdle { bridge.update(active = true) }
        onNodeWithText("Thank you for supporting Pictureshow!").assertExists()
        onNodeWithText("123456").assertExists()
        onNodeWithText("Manage or cancel in the App Store").performClick()
        waitForIdle()
        assertEquals(1, bridge.managementRequests)

        runOnIdle { bridge.update(active = false) }
        onNodeWithText("Thank you for supporting Pictureshow!").assertDoesNotExist()
        onNodeWithText("Become a supporter").assertExists()
        runOnIdle { bridge.restoreGrantsSupport = true }
        onNodeWithText("Restore purchases").performClick()
        onNodeWithText("Thank you for supporting Pictureshow!").assertExists()
        assertEquals(2, bridge.restores)
    }

    @Test fun directInstallerKeepsTheInformationScreenWithoutAPackageBridge() = runComposeUiTest {
        setContent { MaterialTheme { DesktopSupporterPage(null) } }
        onNodeWithText(DESKTOP_SUPPORTER_NO_PURCHASE).assertExists()
        onNodeWithText("Become a supporter").assertDoesNotExist()
        onNodeWithText("Restore purchases").assertDoesNotExist()
    }

    @Test fun microsoftOfferUsesItsOwnProductIdForDisplayAndPurchase() = runComposeUiTest {
        val purchases = mutableListOf<String>()
        val bridge = WindowsSupporterStore { command, token ->
            when (command) {
                0 -> """{"products":[
                    {"id":"$MAC_SUPPORTER_PRODUCT","name":"Apple offer","displayPrice":"$9.99"},
                    {"id":"$WINDOWS_SUPPORTER_PRODUCT","name":"Microsoft offer","displayPrice":"$2.99"}
                ]}"""
                1 -> {
                    purchases += token
                    """{"purchase":"CANCELLED"}"""
                }
                2 -> """{"entitlements":[{"productId":"$MAC_SUPPORTER_PRODUCT","active":true}]}"""
                else -> error("Unexpected request $command")
            }
        }
        setContent { MaterialTheme { DesktopSupporterPage(bridge) } }
        onNodeWithText("Microsoft offer").assertExists()
        onNodeWithText("$2.99 / month").assertExists()
        onNodeWithText("Apple offer").assertDoesNotExist()
        onNodeWithText(DESKTOP_SUPPORTER_NO_PURCHASE).assertDoesNotExist()
        onNodeWithText("Become a supporter").performClick()
        runOnIdle { assertEquals(listOf("supporter.monthly"), purchases) }
    }

    @Test fun activeMicrosoftEntitlementShowsThanksAndManagementInstructions() = runComposeUiTest {
        val bridge = WindowsSupporterStore { command, _ ->
            when (command) {
                0 -> """{"products":[]}"""
                2 -> """{"entitlements":[{"productId":"supporter.monthly","active":true}]}"""
                else -> error("Unexpected request $command")
            }
        }
        setContent { MaterialTheme { DesktopSupporterPage(bridge) } }
        onNodeWithText("Thank you for supporting Pictureshow!").assertExists()
        onNodeWithText("Become a supporter").assertDoesNotExist()
        onNodeWithText("Manage or cancel in the Microsoft Store").performClick()
        onNodeWithText("To manage or cancel, open the Microsoft Store, then choose your profile, Manage account and devices, and Subscriptions.").assertExists()
    }

    private class FakeStoreKit : StoreKitBridge {
        override val store = SupporterStore.APPLE
        override val supporterProductId = MAC_SUPPORTER_PRODUCT
        private var entitlement = StoreEntitlement(MAC_SUPPORTER_PRODUCT, false, supporterCode = "123456")
        override val transactionUpdates = MutableSharedFlow<StoreEntitlement>(replay = 1)
        val purchases = mutableListOf<String>()
        var managementRequests = 0
            private set
        var restores = 0
            private set
        var restoreGrantsSupport = false

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
            entitlement = entitlement.copy(active = restoreGrantsSupport)
        }

        fun update(active: Boolean) {
            entitlement = entitlement.copy(active = active)
            check(transactionUpdates.tryEmit(entitlement))
        }
    }
}
