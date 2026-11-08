package com.fixcraft.yume.ui

import android.icu.text.BreakIterator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.R
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.theme.statusPalette
import com.fixcraft.yume.vpn.VpnPhase
import java.util.Locale
import kotlin.math.ceil

/**
 * A card with a title, at most one short visible line in [detail], and its
 * content. A longer explanation goes in [info], behind a button beside the
 * title, so a screen reads as values rather than paragraphs.
 */
@Composable
internal fun SectionCard(
    title: String,
    detail: String? = null,
    info: String? = null,
    content: @Composable () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CardTitle(title = title, info = info) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/** A heading over a list of rows, with an explanation and the section's add action at its end. */
@Composable
internal fun SectionHeader(title: String, info: String? = null, action: @Composable () -> Unit = {}) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (!info.isNullOrBlank()) InfoButton(title = title, text = info)
        }
        action()
    }
}

/** A card's title, with the info button at its end when there is an explanation. */
@Composable
internal fun CardTitle(title: String, info: String?, text: @Composable () -> Unit) {
    if (info.isNullOrBlank()) {
        text()
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) { text() }
        InfoButton(title = title, text = info)
    }
}

/** A button that shows [text] in a dialog titled [title]. */
@Composable
internal fun InfoButton(title: String, text: String) {
    val strings = appStrings()
    var open by rememberSaveable { mutableStateOf(false) }
    // Drawn in a 32 dp box so the title row keeps the height of its text.
    // Compose still extends the touch target to the minimum 48 dp.
    IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_info_action),
            contentDescription = strings.moreAbout(title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            modifier = Modifier.appDialogWidth(),
            title = { Text(title) },
            text = {
                Text(
                    text = text,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(strings.close) } },
            properties = appDialogProperties,
        )
    }
}

/**
 * A label and its value, beside each other while they fit and stacked once they do not,
 * as with large text. A value that is an identifier is set in a fixed-width face.
 */
@Composable
internal fun FactRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val valueStyle = if (monospace) bodyStyle.copy(fontFamily = FontFamily.Monospace) else bodyStyle
    val locale = LocalConfiguration.current.locales[0]
    val measurer = rememberTextMeasurer()
    val labelWidths = remember(measurer, label, labelStyle) { measurer.measure(label, labelStyle).multiParagraph.intrinsics }
    val valueWidths = remember(measurer, value, valueStyle) { measurer.measure(value, valueStyle).multiParagraph.intrinsics }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = 16.dp
        val layout = factLayout(
            width = constraints.maxWidth,
            gap = with(LocalDensity.current) { gap.roundToPx() },
            labelWidth = ceil(labelWidths.maxIntrinsicWidth).toInt(),
            valueWidth = ceil(valueWidths.maxIntrinsicWidth).toInt(),
            valueWrapsCleanly = { column ->
                val wrapped = measurer.measure(value, valueStyle, constraints = Constraints(maxWidth = column))
                breaksAtOpportunities(value, wrapped, locale)
            },
        )
        if (layout == FactLayout.Stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = label, style = labelStyle, color = labelColor)
                Text(text = value, style = valueStyle, color = valueColor)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.Top,
            ) {
                val columns = layout == FactLayout.Columns
                Text(
                    text = label,
                    style = labelStyle,
                    color = labelColor,
                    modifier = Modifier.weight(if (columns) FACT_LABEL_SHARE else 1f),
                )
                Text(
                    text = value,
                    style = valueStyle,
                    color = valueColor,
                    textAlign = TextAlign.End,
                    modifier = if (columns) Modifier.weight(1f - FACT_LABEL_SHARE) else Modifier,
                )
            }
        }
    }
}

private const val FACT_LABEL_SHARE = 0.42f

internal enum class FactLayout {
    /** Both on one line at their full widths, the value at the end. */
    Line,

    /** The label in its share of the width and the value, which may wrap, in the rest. */
    Columns,

    /** The label above the value. */
    Stacked,
}

/**
 * How a fact fits [width] pixels with [gap] between label and value. Widths are each
 * text's width on one line, and [valueWrapsCleanly] says whether the value, wrapped at
 * a column's width, breaks only where its text may break. Both share one line when they
 * fit. Otherwise the value wraps in its column while the label stays on one line and no
 * word of the value is forced apart, or else it goes under its label.
 */
internal fun factLayout(
    width: Int,
    gap: Int,
    labelWidth: Int,
    valueWidth: Int,
    valueWrapsCleanly: (columnWidth: Int) -> Boolean,
): FactLayout {
    val shared = width - gap
    // The row rounds weighted widths, so each column is counted a pixel short.
    val labelColumn = (shared * FACT_LABEL_SHARE).toInt() - 1
    val valueColumn = (shared * (1f - FACT_LABEL_SHARE)).toInt() - 1
    return when {
        labelWidth + valueWidth <= shared -> FactLayout.Line
        labelWidth <= labelColumn && valueWrapsCleanly(valueColumn) -> FactLayout.Columns
        else -> FactLayout.Stacked
    }
}

/**
 * Whether each line of [layout] ends where [text] may break. The platform splits a word
 * that does not fit at any character, and its notion of a word differs from the text's
 * intrinsic widths (it kept "0.3.0-dev1-agent" whole and then cut it), so the wrapped
 * layout itself is checked.
 */
private fun breaksAtOpportunities(text: String, layout: TextLayoutResult, locale: Locale): Boolean {
    val breaks = BreakIterator.getLineInstance(locale).apply { setText(text) }
    return (0 until layout.lineCount - 1).all { breaks.isBoundary(layout.getLineEnd(it)) }
}

@Composable
internal fun StatusMessage(
    headline: String?,
    detail: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (headline != null) {
            Text(
                text = headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

internal data class StatusTone(
    val container: Color,
    val content: Color,
)

@Composable
internal fun phaseTone(phase: VpnPhase): StatusTone {
    val status = statusPalette()
    val colors = when (phase) {
        VpnPhase.Error -> status.failed
        VpnPhase.Connected -> status.protected
        VpnPhase.Connecting, VpnPhase.Reconnecting, VpnPhase.PermissionRequired -> status.working
        else -> return StatusTone(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    return StatusTone(colors.container, colors.content)
}
