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
import com.fixcraft.yume.i18n.appStrings

private data class ComponentEntry(
    val title: String,
    val scope: String,
    val description: String,
    val license: String,
)

@Composable
fun OpenSourceComponentsScreen() {
    val strings = appStrings()
    // Order: pieces that are part of every Yume implementation first,
    // then anything that only ships in this Android client.
    val components = listOf(
        ComponentEntry(
            title = strings.componentBasefwxTitle,
            scope = strings.componentScopeAllImpls,
            description = strings.componentBasefwxUsage,
            license = strings.componentBasefwxLicense,
        ),
        ComponentEntry(
            title = strings.componentLiboqsTitle,
            scope = strings.componentScopeAllImpls,
            description = strings.componentLiboqsUsage,
            license = strings.componentLiboqsLicense,
        ),
        ComponentEntry(
            title = strings.componentHevTunnelTitle,
            scope = strings.componentScopeAndroidOnly,
            description = strings.componentHevTunnelUsage,
            license = strings.componentHevTunnelLicense,
        ),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = strings.openSourceComponentsSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(items = components, key = { it.title }) { entry ->
            ComponentCard(entry)
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
                text = entry.scope,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
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
