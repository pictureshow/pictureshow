/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** The activity and focus regression use the same route. */
internal fun NavGraphBuilder.supporterScreen(nav: NavHostController, controller: SupporterController? = null) {
    composable("supporter") {
        val context = LocalContext.current
        val owner = controller ?: (context.applicationContext as PictureshowApplication).supporter
        val state by owner.state.collectAsStateWithLifecycle()
        LaunchedEffect(owner) { owner.refresh() }
        SupporterScreen(state, onPurchase = { context.activity()?.let(owner::purchase) }, onBack = { nav.popBackStack() })
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

internal fun supporterManagementIntent() = Intent(
    Intent.ACTION_VIEW,
    "https://play.google.com/store/account/subscriptions?sku=supporter&package=app.pictureshow".toUri()
)

@Composable
internal fun SupporterScreen(state: SupporterState, onPurchase: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val management = remember { supporterManagementIntent() }
    var canManage by remember { mutableStateOf(management.resolveActivity(context.packageManager) != null) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF10131C)).verticalScroll(rememberScrollState()).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // TalkBack on TV moves by spatial search, scoring candidates by their horizontal center as well as distance.
        // A short line (the price) between wide ones lost to the line after it, so reading lines span the full width;
        // left-aligned text without a background looks the same.
        // Unavailable still says what supporting is (the approved Offer copy, unchanged) and why this TV can't
        // subscribe, in a focused panel so the remote lands somewhere visible. Remote Back leaves; there is no
        // alternative payment route.
        if (state is SupporterState.Unavailable) {
            Text(
                stringResource(R.string.supporter_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.fillMaxWidth().semantics {
                    heading()
                }
            )
            Text(stringResource(R.string.supporter_description), Modifier.fillMaxWidth())
            Text(stringResource(R.string.supporter_no_guarantee), Modifier.fillMaxWidth())
            UnavailableReason(stringResource(state.reason.message()), Modifier.focusRequester(first))
        } else {
            Text(
                stringResource(R.string.supporter_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.fillMaxWidth().semantics {
                    heading()
                }
            )
            when (state) {
                SupporterState.Loading -> Text(stringResource(R.string.supporter_loading), Modifier.focusRequester(first).focusable())
                is SupporterState.Offer -> {
                    Text(stringResource(R.string.supporter_description), Modifier.fillMaxWidth())
                    Text(stringResource(R.string.supporter_no_guarantee), Modifier.fillMaxWidth())
                    // Only the P1M recurring base plan is accepted at the ProductDetails boundary.
                    Text(
                        stringResource(R.string.supporter_price_month, state.product.price),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.supporter_renewal), Modifier.fillMaxWidth())
                    Button(onClick = onPurchase, modifier = Modifier.focusRequester(first).testTag("supporter:purchase")) {
                        Text(stringResource(R.string.supporter_subscribe))
                    }
                }
                SupporterState.Pending -> Text(stringResource(R.string.supporter_pending), Modifier.focusRequester(first).focusable())
                is SupporterState.Active -> {
                    Text(stringResource(R.string.supporter_thanks), Modifier.fillMaxWidth())
                    Text(stringResource(R.string.supporter_code_label), Modifier.fillMaxWidth())
                    Text(
                        state.orderId ?: stringResource(R.string.supporter_code_unavailable),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.fillMaxWidth().focusRequester(first).focusable().testTag("supporter:code")
                    )
                    Text(stringResource(R.string.supporter_request), Modifier.fillMaxWidth())
                    Text(stringResource(R.string.supporter_no_guarantee), Modifier.fillMaxWidth())
                    if (canManage) {
                        Button(onClick = {
                            try {
                                context.startActivity(management)
                            } catch (_: ActivityNotFoundException) {
                                canManage = false
                            } catch (_: SecurityException) {
                                canManage = false
                            }
                        }, modifier = Modifier.testTag("supporter:manage")) {
                            Text(stringResource(R.string.supporter_manage))
                        }
                    } else {
                        Text(stringResource(R.string.supporter_manage_fallback), modifier = Modifier.fillMaxWidth().focusable())
                    }
                }
                is SupporterState.Unavailable -> Unit
            }
            Button(onClick = onBack, modifier = Modifier.testTag("supporter:back")) { Text(stringResource(R.string.supporter_back)) }
        }
    }
    LaunchedEffect(state::class) { first.requestFocus() }
}

@Composable
private fun UnavailableReason(text: String, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    // The same focus outline as the About screen's readable panels; nothing here is pressable.
    Text(
        text,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.focusable()
            .border(2.dp, if (focused) Color.White else Color(0xFF747A8E), RoundedCornerShape(8.dp))
            .padding(16.dp).testTag("supporter:unavailable")
    )
}

private fun BillingFailure.message(): Int = when (this) {
    BillingFailure.Unavailable -> R.string.supporter_billing_unavailable
    BillingFailure.Network -> R.string.supporter_network_unavailable
    BillingFailure.ProductMissing -> R.string.supporter_product_unavailable
    BillingFailure.Other -> R.string.supporter_error
}
