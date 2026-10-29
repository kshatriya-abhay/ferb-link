package dev.abhay.ferblink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * What the APK ships and under which licence: every library on the release runtime classpath is
 * Apache-2.0, as is Ferb Link, so the one licence text (res/raw/apache_2_0.txt, the repo's
 * LICENSE) covers them all. Add a library here, and to the README's Credits, when adding it.
 */
internal object Licences {
    data class Component(val name: String, val url: String)

    val components = listOf(
        Component("Ferb Link", "https://github.com/kshatriya-abhay/ferb-link"),
        Component("AndroidX (Compose, Material 3, Activity, Lifecycle, Core and their dependencies)", "https://developer.android.com/jetpack/androidx"),
        Component("Material Components for Android", "https://github.com/material-components/material-components-android"),
        Component("Material Icons", "https://github.com/google/material-design-icons"),
        Component("Kotlin standard library", "https://github.com/JetBrains/kotlin"),
        Component("Kotlin coroutines", "https://github.com/Kotlin/kotlinx.coroutines"),
        Component("Kotlin serialization", "https://github.com/Kotlin/kotlinx.serialization"),
        Component("Guava ListenableFuture", "https://github.com/google/guava"),
        Component("JetBrains Java Annotations", "https://github.com/JetBrains/java-annotations"),
        Component("JSpecify", "https://github.com/jspecify/jspecify"),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LicencesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val resources = LocalResources.current
    val uris = LocalUriHandler.current
    // The licence in paragraphs, so the list lays out only what's on screen.
    val paragraphs = remember(resources) {
        resources.openRawResource(R.raw.apache_2_0).bufferedReader().use { it.readText() }
            .split(Regex("\n\\s*\n")).map { it.trimEnd() }.filter { it.isNotBlank() }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licences)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.licences_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item {
                Text(stringResource(R.string.licences_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }
            val count = Licences.components.size
            Licences.components.forEachIndexed { index, component ->
                item {
                    SegmentedListItem(
                        onClick = { uris.openUri(component.url) },
                        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
                        colors = rowColors(),
                        supportingContent = { Text(stringResource(R.string.licences_full_text)) },
                    ) { Text(component.name) }
                }
            }
            section(R.string.licences_full_text)
            items(paragraphs) { paragraph ->
                Text(
                    paragraph,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}
