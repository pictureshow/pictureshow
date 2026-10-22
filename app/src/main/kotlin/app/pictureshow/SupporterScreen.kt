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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
        // Unavailable has one explanation and remote Back, with no alternative payment route.
        if (state is SupporterState.Unavailable) {
            Text(stringResource(state.reason.message()), Modifier.focusRequester(first).focusable())
        } else {
            Text(stringResource(R.string.supporter_title), style = MaterialTheme.typography.headlineLarge)
            when (state) {
                SupporterState.Loading -> Text(stringResource(R.string.supporter_loading), Modifier.focusRequester(first).focusable())
                is SupporterState.Offer -> {
                    Text(stringResource(R.string.supporter_description))
                    Text(stringResource(R.string.supporter_no_guarantee))
                    // Only the P1M recurring base plan is accepted at the ProductDetails boundary.
                    Text(
                        stringResource(R.string.supporter_price_month, state.product.price),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(stringResource(R.string.supporter_renewal))
                    Button(onClick = onPurchase, modifier = Modifier.focusRequester(first).testTag("supporter:purchase")) {
                        Text(stringResource(R.string.supporter_subscribe))
                    }
                }
                SupporterState.Pending -> Text(stringResource(R.string.supporter_pending), Modifier.focusRequester(first).focusable())
                is SupporterState.Active -> {
                    Text(stringResource(R.string.supporter_thanks))
                    Text(stringResource(R.string.supporter_code_label))
                    Text(
                        state.orderId ?: stringResource(R.string.supporter_code_unavailable),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.focusRequester(first).focusable().testTag("supporter:code")
                    )
                    Text(stringResource(R.string.supporter_request))
                    Text(stringResource(R.string.supporter_no_guarantee))
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
                        Text(stringResource(R.string.supporter_manage_fallback), modifier = Modifier.focusable())
                    }
                }
                is SupporterState.Unavailable -> Unit
            }
            Button(onClick = onBack, modifier = Modifier.testTag("supporter:back")) { Text(stringResource(R.string.supporter_back)) }
        }
    }
    LaunchedEffect(state::class) { first.requestFocus() }
}

private fun BillingFailure.message(): Int = when (this) {
    BillingFailure.Unavailable -> R.string.supporter_billing_unavailable
    BillingFailure.Network -> R.string.supporter_network_unavailable
    BillingFailure.ProductMissing -> R.string.supporter_product_unavailable
    BillingFailure.Other -> R.string.supporter_error
}
