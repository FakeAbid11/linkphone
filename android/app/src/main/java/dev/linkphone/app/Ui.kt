package dev.linkphone.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One gray ramp for the whole app: matte graphite, white ink, no hue. */
internal object LinkColors {
    val background = Color(0xFF000000)
    val surface = Color(0xFF121212)
    val raised = Color(0xFF1C1C1C)
    val line = Color(0xFF2A2A2A)
    val lineStrong = Color(0xFF3A3A3A)
    val text = Color(0xFFFFFFFF)
    val muted = Color(0xFFB3B3B3)
}

internal val Primary = LinkColors.text
internal val Secondary = LinkColors.muted
internal val MaterialSymbols = FontFamily(Font(R.font.material_symbols_rounded))

internal object Symbols {
    const val smartphone = "\ue7ba"
    const val system_update = "\uf2cd"
    const val search = "\uef7a"
    const val notifications = "\ue7f5"
    const val sms = "\ue625"
    const val photo_library = "\ue413"
    const val contacts = "\ue0ba"
    const val call = "\uf0d4"
    const val folder = "\ue2c7"
    const val link = "\ue250"
    const val qr_code_scanner = "\uf206"
    const val check_circle = "\uf0be"
    const val download = "\uf090"
    const val install_desktop = "\ueb71"
    const val refresh = "\ue5d5"
    const val close = "\ue5cd"
    const val settings = "\ue8b8"
    const val upload = "\uf09b"
    const val volume_up = "\ue050"
    const val stop = "\ue047"
    const val info = "\ue88e"
    const val arrow_downward = "\ue5db"
    const val arrow_upward = "\ue5d8"
    const val chevron_right = "\ue5cc"
}

internal fun linkScheme(): ColorScheme = darkColorScheme(
    primary = LinkColors.text,
    onPrimary = LinkColors.background,
    primaryContainer = LinkColors.raised,
    onPrimaryContainer = LinkColors.text,
    secondary = LinkColors.muted,
    onSecondary = LinkColors.background,
    secondaryContainer = LinkColors.raised,
    onSecondaryContainer = LinkColors.text,
    tertiary = LinkColors.muted,
    onTertiary = LinkColors.background,
    background = LinkColors.background,
    onBackground = LinkColors.text,
    surface = LinkColors.surface,
    onSurface = LinkColors.text,
    surfaceVariant = LinkColors.raised,
    onSurfaceVariant = LinkColors.muted,
    surfaceContainerLowest = LinkColors.background,
    surfaceContainerLow = LinkColors.surface,
    surfaceContainer = LinkColors.surface,
    surfaceContainerHigh = LinkColors.raised,
    surfaceContainerHighest = LinkColors.raised,
    outline = LinkColors.lineStrong,
    outlineVariant = LinkColors.line,
    error = LinkColors.text,
    onError = LinkColors.background,
    errorContainer = LinkColors.raised,
    onErrorContainer = LinkColors.text,
    scrim = LinkColors.background,
    inverseSurface = LinkColors.text,
    inverseOnSurface = LinkColors.background,
    inversePrimary = LinkColors.muted
)

internal val LinkTypography = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
)

internal val LinkShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

internal object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

@Composable
internal fun LinkPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = linkScheme(),
        shapes = LinkShapes,
        typography = LinkTypography,
        content = content
    )
}

@Composable
internal fun Symbol(
    glyph: String,
    size: TextUnit = 20.sp,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null
) {
    Text(
        text = glyph,
        fontFamily = MaterialSymbols,
        fontSize = size,
        lineHeight = size,
        color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint,
        modifier = Modifier.clearAndSetSemantics {
            if (contentDescription != null) this.contentDescription = contentDescription
        }
    )
}

@Composable
internal fun ButtonLabel(glyph: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        Symbol(glyph, 18.sp, LocalContentColor.current)
        Text(label)
    }
}

@Composable
internal fun CardTitle(glyph: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        Symbol(glyph)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun LinkCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            content = content
        )
    }
}

@Composable
internal fun LinkDot(active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(if (active) LinkColors.text else LinkColors.lineStrong)
    )
}

@Composable
internal fun NoticeCard(glyph: String, text: String) {
    LinkCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            Symbol(glyph, 18.sp)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Monochrome failure: a left rule and full-contrast text, never a gray tint. */
@Composable
internal fun ErrorNote(text: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        Box(
            Modifier
                .size(width = 2.dp, height = 16.dp)
                .background(LinkColors.lineStrong)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = LinkColors.text
        )
    }
}

@Composable
internal fun PermissionCard(
    glyph: String,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    primary: Boolean = false,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    LinkCard {
        CardTitle(glyph, title)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (primary) {
                Button(onClick = onAction) { ButtonLabel(glyph, actionLabel) }
            } else {
                OutlinedButton(onClick = onAction) { ButtonLabel(glyph, actionLabel) }
            }
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
        }
    }
}
