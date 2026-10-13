package com.fixcraft.yume.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.i18n.appStrings

private data class ComponentEntry(
    val title: String,
    val description: String,
    val license: String,
)

private data class ComponentGroup(
    val heading: String,
    val blurb: String,
    val entries: List<ComponentEntry>,
)

@Composable
fun OpenSourceComponentsScreen() {
    val strings = appStrings()
    val groups = listOf(
        ComponentGroup(
            strings.componentGroupLibrary,
            strings.componentGroupLibraryBlurb,
            GeneratedCredits.nativeComponents.map { ComponentEntry(it.name, strings.message(it.use), it.license) },
        ),
        ComponentGroup(
            strings.componentGroupAndroidOnly,
            strings.componentGroupAndroidOnlyBlurb,
            // Only a build with -PyumeMlKitTranslation=true contains ML Kit.
            (GeneratedCredits.androidComponents +
                GeneratedCredits.mlkitComponents.takeIf { BuildConfig.ML_KIT_TRANSLATION }.orEmpty())
                .map { ComponentEntry(it.name, strings.message(it.use), it.license) },
        ),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                text = strings.openSourceComponentsSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        groups.forEach { group ->
            item(key = "header_${group.heading}") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = group.heading,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = group.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(items = group.entries, key = { "${group.heading}::${it.title}" }) { entry ->
                ComponentCard(entry)
            }
        }
    }
}

@Composable
private fun ComponentCard(entry: ComponentEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(entry.license, style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}
