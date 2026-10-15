package dev.abhay.ferblink

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/**
 * One screen, Material 3 Expressive: the main switch, the computer's status, its address and key,
 * a request to try, and recent requests; before an address and key are saved, only how to connect.
 * Other explanations are kept out, except first-use hints ([Hint]), each shown once.
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
                LinkScreen(viewModel)
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
private fun LinkScreen(viewModel: LinkViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingJev by rememberSaveable { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        // As Ferb's settings: the page on surfaceContainer, rows on surface.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Ferb Link") },
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
                // Not set up: only how to connect. The switch, Try and Recent come once it is.
                item { ConnectCard(onEnterByHand = { editing = true }, onPaste = viewModel::offerPairing) }
                return@LazyColumn
            }
            item { MainSwitch(state.enabled, viewModel::setEnabled) }
            if (Hint.Switch in state.hints) item { HintLine(Hint.Switch, viewModel::dismissHint) }
            item { Spacer(Modifier.size(10.dp)) }
            item { StatusCard(state, viewModel) }
            // One status hint at a time: voice (or text only), then Unload.
            Hint.forStatus(state.health).firstOrNull { it in state.hints }?.let { hint -> item { HintLine(hint, viewModel::dismissHint) } }
            section("Computer")
            item {
                SegmentedListItem(
                    onClick = { editing = true },
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                    colors = rowColors(),
                    supportingContent = { Text(state.serverUrl.ifBlank { "Not set" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                ) { Text("Address") }
            }
            item {
                SegmentedListItem(
                    onClick = { editing = true },
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                    colors = rowColors(),
                    supportingContent = { Text(if (state.apiKey.isBlank()) "Not set" else "Set") },
                ) { Text("API key") }
            }
            jevSection(state.jev, viewModel, onEdit = { editingJev = true })
            section("Try")
            if (Hint.Try in state.hints) item { HintLine(Hint.Try, viewModel::dismissHint) }
            item { TryRow(state, viewModel::tryRequest) }
            if (recent.isNotEmpty()) {
                section("Recent")
                itemsIndexed(recent, key = { _, entry -> entry.at.toString() + entry.request }) { index, entry ->
                    RecentRow(entry, index, recent.size)
                }
            }
        }
    }
    if (editing) ServerDialog(state, viewModel, onDone = { editing = false })
    if (editingJev) JevDialog(state.jev, viewModel, onDone = { editingJev = false })
    state.pairing?.let { PairDialog(it, replacing = state.replacing, viewModel) }
    state.pairingProblem?.let { problem ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPairing,
            title = { Text("This pairing link doesn't work") },
            text = { Text("$problem. On your computer, run mise run server:pair again.") },
            confirmButton = { TextButton(onClick = viewModel::dismissPairing) { Text("OK") } },
        )
    }
}

/** First launch: how to connect, in three steps. */
@Composable
private fun ConnectCard(onEnterByHand: () -> Unit, onPaste: (String) -> Unit) {
    val uris = LocalUriHandler.current
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect to your computer", style = MaterialTheme.typography.titleLarge)
            Text("Ferb Link sends Ferb's requests to a model on your own computer. Until it's connected, Ferb answers on the phone.", style = MaterialTheme.typography.bodyMedium)
            listOf(
                "Set up the server on your computer (the quick start in Ferb Link's README).",
                "Turn on Tailscale on this phone, on the same tailnet as the computer.",
                "On the computer, run mise run server:pair and scan its QR code with this phone's camera. If the camera only shows the link, copy it and tap Paste link. Or, with the phone on USB, run mise run companion:pair.",
            ).forEachIndexed { i, step ->
                Row {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onPaste(clipboardText(context)) }) { Text("Paste link") }
                TextButton(onClick = onEnterByHand) { Text("Enter by hand") }
                TextButton(onClick = { uris.openUri(SETUP_GUIDE) }) { Text("Guide") }
            }
        }
    }
}

private const val SETUP_GUIDE = "https://github.com/kshatriya-abhay/ferb-link#quick-start"

/** A pairing link arrived. Any app can open one, so the computer's name is shown in full first. */
@Composable
private fun PairDialog(offer: Pairing.Offer, replacing: String?, viewModel: LinkViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissPairing,
        title = { Text("Connect to this computer?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(offer.host, style = MaterialTheme.typography.titleMedium)
                Text("Ferb's requests will go to it. Connect only if it's your own computer and you just ran server:pair or companion:pair.")
                replacing?.let { Text("This replaces $it.", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::acceptPairing) { Text("Connect") } },
        dismissButton = { TextButton(onClick = viewModel::dismissPairing) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rowColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surface)

private fun LazyListScope.section(title: String) = item {
    Text(
        title,
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
            Text("Answer Ferb's requests", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

/** The computer at a glance: connected or why not, the model and its state, voice, and Unload. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard(state: LinkViewModel.State, viewModel: LinkViewModel) {
    val health = state.health
    val colors = MaterialTheme.colorScheme
    val (container, icon, title, detail) = when {
        !state.enabled -> Status(colors.surfaceContainerHigh, Icons.Filled.Info, "Turned off", "Ferb answers on the phone")
        state.serverUrl.isBlank() || state.apiKey.isBlank() -> Status(colors.surfaceContainerHigh, Icons.Filled.Info, "Not set up", null)
        health == null -> Status(colors.surfaceContainerHigh, Icons.Filled.Info, "Checking", null)
        health.ok -> Status(colors.primaryContainer, Icons.Filled.CheckCircle, "Connected", health.model)
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
                    Icon(Icons.Filled.Refresh, contentDescription = "Check again")
                }
            }
            if (health != null && health.ok && health.state != null) {
                Row(Modifier.padding(top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(health.state.words.replaceFirstChar { it.uppercase() })
                    if (health.audio == true) {
                        Spacer(Modifier.width(8.dp))
                        Icon(painterResource(R.drawable.ic_mic), contentDescription = "Takes voice", modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.unloading) LoadingIndicator(Modifier.size(40.dp))
                    else if (health.state == ModelState.Loaded || health.state == ModelState.Asleep) {
                        FilledTonalButton(onClick = viewModel::unload) { Text("Unload") }
                    }
                }
            }
            if (state.enabled && health != null && !health.ok) {
                Guidance.of(health.failure, state.vpnActive)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 42.dp, top = 8.dp, end = 12.dp))
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
            Text(hint.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onDismiss(hint) }) { Text("Got it") }
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
 * Jev, TypeSafe's cloud model, as a second opinion on Ferb's tool choice: off by default, with what
 * leaves the phone said plainly, the key, the spend against its cap, and a Test.
 */
private fun LazyListScope.jevSection(jev: LinkViewModel.Jev, viewModel: LinkViewModel, onEdit: () -> Unit) {
    section("Jev (TypeSafe)")
    item {
        val status = when {
            jev.capped -> "Off: it reached its spending limit"
            !jev.keySet -> "Off. Needs a TypeSafe key"
            jev.on -> "On: Ferb asks Jev when it's unsure"
            else -> "Off"
        }
        SegmentedListItem(
            onClick = { if (jev.keySet && !jev.capped || jev.on) viewModel.setJevOn(!jev.on) else onEdit() },
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 4),
            colors = rowColors(),
            supportingContent = { Text(status) },
            trailingContent = { Switch(checked = jev.on, onCheckedChange = null) },
        ) { Text("Second opinion from Jev") }
    }
    item {
        SegmentedListItem(
            onClick = onEdit,
            shapes = ListItemDefaults.segmentedShapes(index = 1, count = 4),
            colors = rowColors(),
            supportingContent = { Text(if (jev.keySet) "Set" else "Not set") },
        ) { Text("TypeSafe key") }
    }
    item {
        SegmentedListItem(
            onClick = onEdit,
            shapes = ListItemDefaults.segmentedShapes(index = 2, count = 4),
            colors = rowColors(),
            supportingContent = { Text("${dollars(jev.spentUsd)} of ${dollars(jev.capUsd)}. At the limit Jev turns itself off") },
        ) { Text("Spending limit") }
    }
    item {
        SegmentedListItem(
            onClick = { if (jev.keySet && !jev.testing) viewModel.testJev() },
            shapes = ListItemDefaults.segmentedShapes(index = 3, count = 4),
            colors = rowColors(),
            supportingContent = { Text(if (jev.testing) "Asking Jev…" else jev.tested ?: "One small request (about \$0.00002), counted in the spending") },
        ) { Text("Test") }
    }
    item {
        val uris = LocalUriHandler.current
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "When it's on and Ferb is unsure, what you type or say to Ferb, and the names and descriptions of Ferb's tools, " +
                    "go to TypeSafe's servers in the US. Not your contacts, notes or other app data. TypeSafe doesn't train on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { uris.openUri(TYPESAFE_PRIVACY) }, contentPadding = PaddingValues(0.dp)) { Text("TypeSafe's privacy policy") }
        }
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
        title = { Text("Jev (TypeSafe)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (jev.keySet) "New TypeSafe key (empty keeps the saved one)" else "TypeSafe key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cap,
                    onValueChange = { cap = it },
                    label = { Text("Spending limit, in dollars") },
                    singleLine = true,
                    isError = capValue == null,
                    supportingText = { Text("Spent so far: ${dollars(jev.spentUsd)}. This phone's count; it never resets.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.saveJev(key.trim().ifEmpty { null }, capValue!!); onDone() }, enabled = capValue != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** The address and key, edited together; saving checks them. */
@Composable
private fun ServerDialog(state: LinkViewModel.State, viewModel: LinkViewModel, onDone: () -> Unit) {
    var showKey by rememberSaveable { mutableStateOf(false) }
    val urlProblem = state.serverUrl.takeIf { it.isNotBlank() }?.let { LinkSettings.urlProblem(it) }
    AlertDialog(
        onDismissRequest = { viewModel.cancelEdit(); onDone() },
        title = { Text("Computer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = viewModel::editUrl,
                    label = { Text("Address") },
                    placeholder = { Text("http://computer.tailnet.ts.net") },
                    singleLine = true,
                    isError = urlProblem != null,
                    supportingText = urlProblem?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::editKey,
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.save(); onDone() },
                enabled = state.serverUrl.isNotBlank() && state.apiKey.isNotBlank() && urlProblem == null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { viewModel.cancelEdit(); onDone() }) { Text("Cancel") } },
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
                else IconButton(onClick = send, enabled = canSend) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        state.tried?.let { (_, reply) -> ReplyBlock(reply, expandedAtFirst = false) }
    }
}

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

private fun replySummary(reply: Reply): String = reply.error
    ?: reply.calls.joinToString("\n") { it.signature() }.ifEmpty { "No call: " + reply.text.ifBlank { "(nothing)" } }

private fun replyDetails(reply: Reply): String = reply.errorDetail
    ?: "%s · %d ms · weakest %.2f · first %.2f · mean %.2f".format(reply.model, reply.ms, reply.confidence, reply.first, reply.mean)

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
        transcript != null -> if (transcript.text != transcript.heard) transcript.text else "Written down"
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
            { Icon(painterResource(R.drawable.ic_mic), contentDescription = "Recording") }
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
