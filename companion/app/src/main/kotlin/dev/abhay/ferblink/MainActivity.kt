package dev.abhay.ferblink

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/**
 * One screen, Material 3 Expressive. Before an address and key are saved, only how to connect.
 * Then the main switch, the computer's status, the computer, and recent requests; the test request
 * and Jev wait under Advanced. First-use hints ([Hint]) are shown once each. Words are in
 * res/values/strings.xml.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MainActivity : ComponentActivity() {
    private val viewModel: LinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity has the same intent: its pairing link was offered already.
        if (savedInstanceState == null) offerPairing(intent)
        setContent {
            // minSdk 33, so the wallpaper-based dynamic colours are always available.
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive()) {
                var licences by rememberSaveable { mutableStateOf(false) }
                if (licences) LicencesScreen(onBack = { licences = false })
                else LinkScreen(viewModel, onLicences = { licences = true })
            }
        }
        // No autofill: the API key isn't a login, so password managers have nothing to offer for it,
        // and they'd treat the whole screen as a sign-in form.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        offerPairing(intent)
    }

    /** A ferblink://pair link (server:pair's QR code, companion:pair): asks before saving it. */
    private fun offerPairing(intent: Intent?) {
        val link = intent?.dataString
        if (intent?.action == Intent.ACTION_VIEW && Pairing.isPairingLink(link)) viewModel.offerPairing(link!!)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LinkScreen(viewModel: LinkViewModel, onLicences: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingJev by rememberSaveable { mutableStateOf(false) }
    // Advanced starts open only when something in it is on.
    var advanced by rememberSaveable { mutableStateOf(state.jev.on) }
    val uris = LocalUriHandler.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        // As Ferb's settings: the page on surfaceContainer, rows on surface.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        // adjustResize does nothing when drawing edge to edge, so the list shrinks above the keyboard
        // itself. It has to be the list's size that changes, not its padding: a list keeps the focused
        // field in view only when it gets smaller.
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            if (!state.configured) {
                // Not set up: only how to connect. The switch, Recent and Advanced come once it is.
                item { ConnectCard(onEnterByHand = { editing = true }, onPaste = viewModel::offerPairing) }
                aboutSection(onPrivacy = { uris.openUri(PRIVACY_NOTE) }, onLicences = onLicences)
                return@LazyColumn
            }
            item { MainSwitch(state.enabled, viewModel::setEnabled) }
            if (Hint.Switch in state.hints) item { HintLine(Hint.Switch, viewModel::dismissHint) }
            item { Spacer(Modifier.size(10.dp)) }
            item { StatusCard(state, viewModel) }
            // One status hint at a time: voice (or text only), then Unload.
            Hint.forStatus(state.health).firstOrNull { it in state.hints }?.let { hint -> item { HintLine(hint, viewModel::dismissHint) } }
            section(R.string.section_computer)
            item {
                SegmentedListItem(
                    onClick = { editing = true },
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                    colors = rowColors(),
                    supportingContent = { Text(hostOf(state.serverUrl), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                ) { Text(stringResource(R.string.computer_row)) }
            }
            if (recent.isNotEmpty()) {
                section(R.string.section_recent)
                itemsIndexed(recent, key = { _, entry -> entry.at.toString() + entry.request }) { index, entry ->
                    RecentRow(entry, index, recent.size)
                }
            }
            section(R.string.section_advanced)
            item {
                SegmentedListItem(
                    onClick = { advanced = !advanced },
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                    colors = rowColors(),
                    supportingContent = { Text(stringResource(R.string.advanced_show)) },
                    trailingContent = { Icon(if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                ) { Text(stringResource(R.string.section_advanced)) }
            }
            if (advanced) {
                section(R.string.try_title)
                if (Hint.Try in state.hints) item { HintLine(Hint.Try, viewModel::dismissHint) }
                item { TryRow(state, viewModel::tryRequest) }
                jevSection(state.jev, viewModel, onEdit = { editingJev = true })
            }
            aboutSection(onPrivacy = { uris.openUri(PRIVACY_NOTE) }, onLicences = onLicences)
        }
    }
    if (editing) ServerDialog(state, viewModel, onDone = { editing = false })
    if (editingJev) JevDialog(state.jev, viewModel, onDone = { editingJev = false })
    state.pairing?.let { PairDialog(it, replacing = state.replacing, viewModel) }
    state.pairingProblem?.let { problem ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPairing,
            title = { Text(stringResource(R.string.pair_bad_title)) },
            text = { Text(stringResource(R.string.pair_bad_body, stringResource(problem))) },
            confirmButton = { TextButton(onClick = viewModel::dismissPairing) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/** The computer's name from its address ("my-laptop" from http://my-laptop.tail1234.ts.net). */
private fun hostOf(url: String): String =
    runCatching { java.net.URI(url.trim()).host }.getOrNull()?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: url

/** First launch: how to connect, in two steps. */
@Composable
private fun ConnectCard(onEnterByHand: () -> Unit, onPaste: (String) -> Unit) {
    val uris = LocalUriHandler.current
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.connect_body), style = MaterialTheme.typography.bodyMedium)
            listOf(R.string.connect_step_tailscale, R.string.connect_step_pair).forEachIndexed { i, step ->
                Row {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
                    Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onPaste(clipboardText(context)) }) { Text(stringResource(R.string.connect_paste)) }
                TextButton(onClick = onEnterByHand) { Text(stringResource(R.string.connect_by_hand)) }
            }
            TextButton(onClick = { uris.openUri(SETUP_GUIDE) }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.connect_guide)) }
        }
    }
}

private const val SETUP_GUIDE = "https://github.com/kshatriya-abhay/ferb-link#install-on-your-phone"
private const val PRIVACY_NOTE = "https://github.com/kshatriya-abhay/ferb-link/blob/main/PRIVACY.md"

/** Privacy, the licences and the version: on every screen, last. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun LazyListScope.aboutSection(onPrivacy: () -> Unit, onLicences: () -> Unit) {
    section(R.string.section_about)
    item {
        SegmentedListItem(
            onClick = onPrivacy,
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
            colors = rowColors(),
            supportingContent = { Text(stringResource(R.string.privacy_detail)) },
        ) { Text(stringResource(R.string.privacy)) }
    }
    item {
        SegmentedListItem(
            onClick = onLicences,
            shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
            colors = rowColors(),
            supportingContent = { Text(stringResource(R.string.version, BuildConfig.VERSION_NAME)) },
        ) { Text(stringResource(R.string.licences)) }
    }
}

/** A pairing link arrived. Any app can open one, so the computer's name is shown in full first. */
@Composable
private fun PairDialog(offer: Pairing.Offer, replacing: String?, viewModel: LinkViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissPairing,
        title = { Text(stringResource(R.string.pair_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(offer.host, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.pair_body))
                replacing?.let { Text(stringResource(R.string.pair_replaces, it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::acceptPairing) { Text(stringResource(R.string.pair_connect)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissPairing) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rowColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surface)

internal fun LazyListScope.section(@StringRes title: Int) = item {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** The on/off switch, as a main switch at the top (like Android's Wi-Fi page). */
@Composable
private fun MainSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        checked = enabled,
        onCheckedChange = onChange,
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.main_switch), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

@StringRes
private fun ModelState.label(): Int = when (this) {
    ModelState.Loaded -> R.string.model_loaded
    ModelState.Loading -> R.string.model_loading
    ModelState.Asleep -> R.string.model_asleep
    ModelState.Unloaded -> R.string.model_unloaded
}

/** The computer at a glance: connected or why not, the model and its state, voice, and Unload. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard(state: LinkViewModel.State, viewModel: LinkViewModel) {
    val health = state.health
    val colors = MaterialTheme.colorScheme
    // The error's words come from the contract (ServerClient), shared with Ferb's chat bubbles.
    val (container, icon, title, detail) = when {
        !state.enabled -> Status(colors.surfaceContainerHigh, Icons.Filled.Info, stringResource(R.string.status_off), stringResource(R.string.status_off_detail))
        state.serverUrl.isBlank() || state.apiKey.isBlank() -> Status(colors.surfaceContainerHigh, Icons.Filled.Info, stringResource(R.string.status_not_set_up), null)
        health == null -> Status(colors.surfaceContainerHigh, Icons.Filled.Info, stringResource(R.string.status_checking), null)
        health.ok -> Status(colors.primaryContainer, Icons.Filled.CheckCircle, stringResource(R.string.status_connected), health.model)
        else -> Status(colors.errorContainer, Icons.Filled.Warning, health.short.replaceFirstChar { it.uppercase() }, health.message)
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (state.checking) LoadingIndicator(Modifier.size(48.dp))
                else IconButton(onClick = viewModel::check, enabled = !state.edited && state.serverUrl.isNotBlank()) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.status_check_again))
                }
            }
            if (health != null && health.ok && health.state != null) {
                Row(Modifier.padding(top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(stringResource(health.state.label()))
                    if (health.audio == true) {
                        Spacer(Modifier.width(8.dp))
                        Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.takes_voice), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.unloading) LoadingIndicator(Modifier.size(40.dp))
                    else if (health.state == ModelState.Loaded || health.state == ModelState.Asleep) {
                        FilledTonalButton(onClick = viewModel::unload) { Text(stringResource(R.string.unload)) }
                    }
                }
            }
            if (state.enabled && health != null && !health.ok) {
                Guidance.of(health.failure, state.vpnActive, state.privateDns)?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 42.dp, top = 8.dp, end = 12.dp))
                }
            }
            state.unloadError?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** The clipboard's text, or "" (Android lets only the app in front read it). */
internal fun clipboardText(context: Context): String {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
    return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

/** A first-use hint, until "Got it". */
@Composable
private fun HintLine(hint: Hint, onDismiss: (Hint) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(hint.text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onDismiss(hint) }) { Text(stringResource(R.string.got_it)) }
        }
    }
}

private data class Status(val container: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val detail: String?)

@Composable
private fun Pill(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/**
 * Jev, TypeSafe's cloud model, which picks the tool for Ferb (Ferb's "Use Jev to select the tool"):
 * off by default. Without a key, one row that opens the key dialog; with one, the switch, the key
 * and spend against its cap, and a Test. What leaves the phone is said under it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun LazyListScope.jevSection(jev: LinkViewModel.Jev, viewModel: LinkViewModel, onEdit: () -> Unit) {
    section(R.string.jev_section)
    val rows = if (jev.keySet) 3 else 1
    item {
        val status = when {
            jev.capped -> stringResource(R.string.jev_capped)
            !jev.keySet -> stringResource(R.string.jev_intro)
            jev.on -> stringResource(R.string.jev_on)
            else -> stringResource(R.string.jev_off)
        }
        SegmentedListItem(
            onClick = { if (jev.keySet && !jev.capped || jev.on) viewModel.setJevOn(!jev.on) else onEdit() },
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = rows),
            colors = rowColors(),
            supportingContent = { Text(status) },
            trailingContent = { Switch(checked = jev.on, onCheckedChange = null) },
        ) { Text(stringResource(R.string.jev_switch)) }
    }
    if (jev.keySet) {
        item {
            SegmentedListItem(
                onClick = onEdit,
                shapes = ListItemDefaults.segmentedShapes(index = 1, count = rows),
                colors = rowColors(),
                supportingContent = { Text(stringResource(R.string.jev_spent, dollars(jev.spentUsd), dollars(jev.capUsd))) },
            ) { Text(stringResource(R.string.jev_key_and_limit)) }
        }
        item {
            SegmentedListItem(
                onClick = { if (!jev.testing) viewModel.testJev() },
                shapes = ListItemDefaults.segmentedShapes(index = 2, count = rows),
                colors = rowColors(),
                supportingContent = { Text(if (jev.testing) stringResource(R.string.jev_testing) else jev.tested ?: stringResource(R.string.jev_test_detail)) },
            ) { Text(stringResource(R.string.jev_test)) }
        }
    }
    item { JevDisclosure() }
}

@Composable
private fun JevDisclosure() {
    val uris = LocalUriHandler.current
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.jev_disclosure), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { uris.openUri(TYPESAFE_PRIVACY) }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.jev_privacy)) }
    }
}

/** Small amounts with enough digits to see them move: a request costs about $0.00002. */
private fun dollars(usd: Double) = if (usd >= 0.01 || usd == 0.0) "\$%.2f".format(Locale.ROOT, usd) else "\$%.5f".format(Locale.ROOT, usd)

private const val TYPESAFE_PRIVACY = "https://typesafe.ai/legal/privacy-policy"

/** The TypeSafe key (masked, left as it is when the field stays empty) and the spending limit. */
@Composable
private fun JevDialog(jev: LinkViewModel.Jev, viewModel: LinkViewModel, onDone: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    var cap by rememberSaveable { mutableStateOf("%.2f".format(Locale.ROOT, jev.capUsd)) }
    val capValue = cap.trim().removePrefix("\$").toDoubleOrNull()?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.jev_section)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!jev.keySet) Text(stringResource(R.string.jev_disclosure), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(if (jev.keySet) R.string.jev_new_key else R.string.jev_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cap,
                    onValueChange = { cap = it },
                    label = { Text(stringResource(R.string.jev_limit)) },
                    singleLine = true,
                    isError = capValue == null,
                    supportingText = { Text(stringResource(R.string.jev_limit_detail, dollars(jev.spentUsd))) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.saveJev(key.trim().ifEmpty { null }, capValue!!); onDone() }, enabled = capValue != null) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The address and key, edited together; saving checks them. */
@Composable
private fun ServerDialog(state: LinkViewModel.State, viewModel: LinkViewModel, onDone: () -> Unit) {
    var showKey by rememberSaveable { mutableStateOf(false) }
    val urlProblem = state.serverUrl.takeIf { it.isNotBlank() }?.let { LinkSettings.urlProblem(it) }
    AlertDialog(
        onDismissRequest = { viewModel.cancelEdit(); onDone() },
        title = { Text(stringResource(R.string.computer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = viewModel::editUrl,
                    label = { Text(stringResource(R.string.address)) },
                    placeholder = { Text(stringResource(R.string.address_placeholder)) },
                    singleLine = true,
                    isError = urlProblem != null,
                    supportingText = urlProblem?.let { { Text(stringResource(it)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::editKey,
                    label = { Text(stringResource(R.string.api_key)) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(stringResource(if (showKey) R.string.hide else R.string.show)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.save(); onDone() },
                enabled = state.serverUrl.isNotBlank() && state.apiKey.isNotBlank() && urlProblem == null,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = { viewModel.cancelEdit(); onDone() }) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TryRow(state: LinkViewModel.State, onSend: (String) -> Unit) {
    var request by rememberSaveable { mutableStateOf("") }
    val send = { onSend(request.ifBlank { SAMPLE_REQUEST }) }
    val canSend = !state.trying && !state.edited && state.serverUrl.isNotBlank()
    // While the field has focus, keep the reply under it above the keyboard too: the list on its
    // own only scrolls the field into view. Follows the keyboard as it slides in.
    val block = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused) {
        if (focused) snapshotFlow { ime.getBottom(density) }.collect { block.bringIntoView() }
    }
    Column(Modifier.bringIntoViewRequester(block), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = request,
            onValueChange = { request = it },
            placeholder = { Text(SAMPLE_REQUEST) },
            singleLine = true,
            shape = CircleShape,
            trailingIcon = {
                if (state.trying) LoadingIndicator(Modifier.size(40.dp))
                else IconButton(onClick = send, enabled = canSend) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send)) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        state.tried?.let { (_, reply) -> ReplyBlock(reply, expandedAtFirst = false) }
    }
}

/** What the test request says: English, as Ferb's requests are sent; it's also the placeholder. */
internal const val SAMPLE_REQUEST = "set a timer for 5 minutes"

/** The calls (or the model's words, or why it failed); tap for the timings and confidence. */
@Composable
private fun ReplyBlock(reply: Reply, expandedAtFirst: Boolean) {
    var expanded by remember(reply) { mutableStateOf(expandedAtFirst) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(replySummary(reply), style = MaterialTheme.typography.bodyLarge, color = replyColor(reply.error))
        }
        if (expanded) Text(replyDetails(reply), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun replySummary(reply: Reply): String = reply.error
    ?: reply.calls.joinToString("\n") { it.signature() }.ifEmpty { stringResource(R.string.no_call, reply.text.ifBlank { stringResource(R.string.no_call_nothing) }) }

@Composable
private fun replyDetails(reply: Reply): String = reply.errorDetail
    ?: stringResource(R.string.reply_details, reply.model, reply.ms, reply.confidence, reply.first, reply.mean)

@Composable
private fun replyColor(error: String?) = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

/** One request in a segmented list: what was asked or heard, the answer, and when; tap for details. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecentRow(entry: RecentRequests.Entry, index: Int, count: Int) {
    var expanded by remember(entry) { mutableStateOf(false) }
    val transcript = entry.transcript
    val reply = entry.reply
    val headline = transcript?.heard?.takeIf { it.isNotBlank() } ?: entry.request
    val summary = when {
        reply != null -> replySummary(reply)
        transcript?.error != null -> transcript.error!!
        transcript != null -> if (transcript.text != transcript.heard) transcript.text else stringResource(R.string.recent_written_down)
        else -> ""
    }
    val details = when {
        reply != null -> replyDetails(reply)
        transcript?.errorDetail != null -> transcript.errorDetail!!
        transcript != null -> "%s · %s · %d ms".format(entry.request, transcript.model, transcript.ms)
        else -> ""
    } + " · " + entry.caller
    SegmentedListItem(
        onClick = { expanded = !expanded },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = rowColors(),
        leadingContent = if (transcript != null) {
            { Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.recent_recording)) }
        } else null,
        trailingContent = { Text(DateUtils.getRelativeTimeSpanString(entry.at).toString(), style = MaterialTheme.typography.labelSmall) },
        supportingContent = {
            Column {
                Text(summary, color = replyColor(reply?.error ?: transcript?.error), maxLines = if (expanded) 6 else 1, overflow = TextOverflow.Ellipsis)
                if (expanded) Text(details, style = MaterialTheme.typography.bodySmall)
            }
        },
    ) { Text(headline, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis) }
}
