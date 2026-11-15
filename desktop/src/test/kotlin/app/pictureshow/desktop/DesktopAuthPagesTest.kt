/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import app.pictureshow.core.*
import app.pictureshow.ui.PictureshowTheme
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DesktopAuthPagesTest {
    private class Recorder : AuthActions {
        val calls = mutableListOf<String>()
        override fun discover() { calls += "discover" }
        override fun connect(address: String) { calls += "connect:$address" }
        override fun signIn(username: String, password: String) { calls += "signIn:$username:$password" }
        override fun quickConnect() { calls += "quickConnect" }
        override fun clearError() {}
        override fun accounts() { calls += "accounts" }
        override fun selectAccount(key: String) { calls += "select:$key" }
        override fun addAccountOn(server: Server) { calls += "addAccountOn:${server.id}" }
        override fun addServer() { calls += "addServer" }
        override fun signOut() { calls += "signOut" }
        override fun back() { calls += "back" }
        fun actions() = calls.filterNot { it == "discover" }
    }
    private val home = Server("s1", "Home Media", "http://jf.invalid", "10.11.0")
    private val cabin = Server("s2", "Cabin", "http://cabin.invalid", "10.11.0")
    private fun account(server: Server, id: String, name: String, token: String = "t-$id") = Account(server, User(id, name), token)

    private fun ComposeUiTest.typeInto(tag: String, text: String) {
        onNodeWithTag(tag).performClick()
        onNodeWithTag(tag).performTextInput(text)
    }
    private fun ComposeUiTest.pressReturn(tag: String) = onNodeWithTag(tag).performKeyInput { pressKey(Key.Enter) }

    @Test fun returnInPasswordSignsIn() = runComposeUiTest {
        val r = Recorder()
        setContent { PictureshowTheme { SignInForm(DesktopState(route = DesktopRoute.SignIn, server = home), r) } }
        typeInto("username", "mei")
        // D-3: Return in Username moves to Password rather than doing nothing.
        pressReturn("username")
        onNodeWithTag("password").assertIsFocused()
        onNodeWithTag("password").performTextInput("hunter2")
        pressReturn("password")
        assertEquals(listOf("signIn:mei:hunter2"), r.actions())
    }

    @Test fun theButtonSaysSigningInOnlyWhileThisFormsRequestRuns() = runComposeUiTest {
        // Too quick to screenshot against the demo server, so pinned here.
        var state by mutableStateOf(DesktopState(route = DesktopRoute.SignIn, server = home))
        setContent { PictureshowTheme { SignInForm(state, Recorder()) } }
        state = state.copy(busy = true)
        waitForIdle()
        onNodeWithText("Signing in…").assertDoesNotExist()
        state = state.copy(busy = false)
        typeInto("username", "mei")
        typeInto("password", "hunter2")
        pressReturn("password")
        state = state.copy(busy = true)
        waitForIdle()
        onNodeWithTag("sign-in").assertTextEquals("Signing in…")
        state = state.copy(busy = false)
        waitForIdle()
        onNodeWithText("Signing in…").assertDoesNotExist()
        onNodeWithTag("sign-in").assertTextEquals("Sign in")
    }

    @Test fun aBlankSignInStartsInUsernameEvenIfPasswordWasLastFocused() = runComposeUiTest {
        // The shell restores each page's last focus; after an earlier sign-in that was Password (design review, shots/07).
        DesktopUiProbe.lastFocus[""] = "password"
        setContent { PictureshowTheme { SignInForm(DesktopState(route = DesktopRoute.SignIn, server = home), Recorder()) } }
        onNodeWithTag("username").assertIsFocused()
        assertNull(DesktopUiProbe.lastFocus[""])
    }

    @Test fun accountsStartOnAnAccountRowNotOnRemove() = runComposeUiTest {
        val mei = account(home, "m", "ux-mei")
        DesktopUiProbe.lastFocus[""] = "remove:${mei.key}"
        setContent { PictureshowTheme { AccountsList(DesktopState(route = DesktopRoute.Accounts, accounts = listOf(mei), rememberedServers = listOf(home)), Recorder()) {} } }
        waitForIdle()
        assertEquals("account:${mei.key}", DesktopUiProbe.lastFocus[""])
        onNodeWithText("Saved on this computer").assertExists()
        // The shell focuses lastFocus through the probe's requester; it must land on the row itself, not on Remove inside it.
        runOnIdle { DesktopUiProbe.requestFocus.getValue("account:${mei.key}").requestFocus() }
        waitForIdle()
        onNodeWithTag("remove:${mei.key}").assertIsNotFocused()
        assertEquals(true, DesktopUiProbe.focus["account:${mei.key}"])
        assertEquals(false, DesktopUiProbe.focus["remove:${mei.key}"])
    }

    @Test fun returnWithoutUsernameSaysWhatIsMissing() = runComposeUiTest {
        val r = Recorder()
        setContent { PictureshowTheme { SignInForm(DesktopState(route = DesktopRoute.SignIn, server = home), r) } }
        typeInto("password", "hunter2")
        pressReturn("password")
        assertEquals(emptyList(), r.actions())
        onNodeWithText("Enter your username.").assertExists()
        onNodeWithTag("username").assertIsFocused()
    }

    @Test fun returnInAddressConnects() = runComposeUiTest {
        val r = Recorder()
        setContent { PictureshowTheme { ServerForm(DesktopState(route = DesktopRoute.Servers), r) } }
        onNodeWithTag("server-address").performClick()
        pressReturn("server-address")
        assertEquals(emptyList(), r.actions())
        onNodeWithText("Enter your server's address.").assertExists()
        typeInto("server-address", "jf.invalid:8096")
        pressReturn("server-address")
        assertEquals(listOf("connect:jf.invalid:8096"), r.actions())
    }

    @Test fun rejectedPasswordUsesTheAuth04Copy() = runComposeUiTest {
        setContent { PictureshowTheme { SignInForm(DesktopState(route = DesktopRoute.SignIn, server = home, error = "Sign-in was refused.", errorKind = ErrorKind.INVALID_CREDENTIALS), Recorder()) } }
        onNodeWithText(WRONG_CREDENTIALS_COPY).assertExists()
        onNodeWithTag("password").assertIsFocused()
    }

    @Test fun fieldsComeBeforeTheUserListAndAPickIsNamed() = runComposeUiTest {
        val r = Recorder()
        val users = (1..10).map { User("u$it", "viewer$it") }
        setContent { PictureshowTheme { SignInForm(DesktopState(route = DesktopRoute.SignIn, server = home, publicUsers = users), r) } }
        val passwordTop = onNodeWithTag("password").fetchSemanticsNode().boundsInRoot.top
        assertTrue(onNodeWithTag("user:u1").fetchSemanticsNode().boundsInRoot.top > passwordTop, "the user list follows the fields")
        onNodeWithTag("user:u8").assertExists()
        onNodeWithTag("user:u9").assertDoesNotExist()
        onNodeWithText("Show all 10 users").performClick()
        onNodeWithTag("user:u9").assertIsFocused()
        onNodeWithTag("user:u2").performClick()
        onNodeWithTag("user:u2").assertIsSelected()
        onNodeWithTag("signing-in-as").assertExists()
        onAllNodesWithText("viewer2", useUnmergedTree = true).assertCountEquals(3) // the field, the chip and "Signing in as"
        onNodeWithTag("password").assertIsFocused()
        onNodeWithTag("password").performTextInput("pw")
        pressReturn("password")
        assertEquals(listOf("signIn:viewer2:pw"), r.actions())
        onNodeWithText("Not you?").performClick()
        onNodeWithTag("signing-in-as").assertDoesNotExist()
        onNodeWithTag("username").assertIsFocused()
    }

    @Test fun chooserAddsAnAccountOnASavedServerAndSignsOut() = runComposeUiTest {
        val r = Recorder()
        val mei = account(home, "m", "ux-mei")
        val tom = account(cabin, "t", "ux-tom")
        setContent { PictureshowTheme { AccountsList(DesktopState(route = DesktopRoute.Accounts, account = mei, accounts = listOf(mei, tom), rememberedServers = listOf(home, cabin)), r) { r.calls += "remove:${it.key}" } } }
        onNodeWithText("Signed in as ux-mei on Home Media.").assertExists()
        onNodeWithText("Watching now").assertExists()
        onNodeWithText("Saved on this computer").assertExists()
        onNodeWithText("Sign in another user on Home Media").performClick()
        onNodeWithText("Sign in another user on Cabin").performClick()
        onNodeWithText("Add a server").performClick()
        onNodeWithTag("accounts-sign-out").performClick()
        assertEquals(listOf("addAccountOn:s1", "addAccountOn:s2", "addServer", "signOut"), r.actions())
        onNodeWithTag("initials:${mei.key}", useUnmergedTree = true).assertTextEquals("UM")
        onNodeWithTag("initials:${tom.key}", useUnmergedTree = true).assertTextEquals("UT")
    }

    @Test fun signedOutChooserHasNoSignOut() = runComposeUiTest {
        val mei = account(home, "m", "ux-mei")
        setContent { PictureshowTheme { AccountsList(DesktopState(route = DesktopRoute.Accounts, accounts = listOf(mei), rememberedServers = listOf(home)), Recorder()) {} } }
        onNodeWithTag("accounts-sign-out").assertDoesNotExist()
        onNodeWithText("Sign in another user on Home Media").assertExists()
    }

    @Test fun removeWordIsVisibleAtRestAndWithRowOrButtonFocus() = runComposeUiTest {
        val r = Recorder()
        val mei = account(home, "m", "ux-mei")
        setContent { PictureshowTheme { AccountsList(DesktopState(route = DesktopRoute.Accounts, accounts = listOf(mei), rememberedServers = listOf(home)), r) { r.calls += "remove:${it.key}" } } }
        onNodeWithTag("remove-label:${mei.key}", useUnmergedTree = true).assertTextEquals("Remove")
        onNodeWithTag("remove:${mei.key}").requestFocus()
        onNodeWithTag("remove-label:${mei.key}", useUnmergedTree = true).assertTextEquals("Remove")
        onNodeWithTag("account:${mei.key}").requestFocus()
        onNodeWithTag("remove-label:${mei.key}", useUnmergedTree = true).assertTextEquals("Remove")
        onNodeWithTag("account:${mei.key}").performMouseInput { moveTo(center) }
        onNodeWithTag("remove-label:${mei.key}", useUnmergedTree = true).assertExists()
        onNodeWithTag("remove:${mei.key}").performClick()
        assertEquals(listOf("remove:${mei.key}"), r.actions())
    }

    @Test fun initialsTellSimilarNamesApart() {
        assertEquals("UT", accountInitials("ux-tom"))
        assertEquals("MC", accountInitials("Mei Chen"))
        assertEquals("A", accountInitials("alex"))
        assertEquals("JD", accountInitials("j.d"))
        assertEquals("?", accountInitials("  "))
        assertEquals("http://jf.invalid/Users/a%20b/Images/Primary?maxWidth=96&maxHeight=96&quality=90", userImageUrl(home.copy(url = "http://jf.invalid/"), User("a b", "x")))
    }

    @Test fun qualityCopyAndCaptionsWrap() = runComposeUiTest {
        setContent { PictureshowTheme { Box(Modifier.width(360.dp)) { SettingRow("Video quality", VIDEO_QUALITY_CAPTION) {} } } }
        val one = onNodeWithTag("caption:Video quality", useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        one.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
        assertTrue(layout.single().lineCount > 1, "the caption wraps instead of ending in an ellipsis")
        assertFalse(layout.single().hasVisualOverflow)
        assertEquals("Original (no conversion unless needed)", VIDEO_QUALITY_ORIGINAL)
    }

    @Test fun qualityMenuFitsItsLongestChoiceOnOneLine() = runComposeUiTest {
        setContent { PictureshowTheme { Choice("Video quality", listOf(0 to VIDEO_QUALITY_ORIGINAL, 4 to "4 Mbps"), 0, "quality", buttonValue = "Original", buttonWidth = 180.dp, fixedWidth = true, menuMinWidth = 340.dp) {} } }
        onNodeWithTag("quality").performClick()
        val item = onNodeWithText(VIDEO_QUALITY_ORIGINAL, useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        item.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
        assertEquals(1, layout.single().lineCount)
    }
}
