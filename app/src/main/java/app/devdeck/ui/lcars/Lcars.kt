package app.devdeck.ui.lcars

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.devdeck.ui.theme.Condensed
import app.devdeck.ui.theme.Lcars

/**
 * The characteristic "elbow": a vertical column bending into a horizontal bar with a rounded outer
 * corner and a concave inner corner. [flipVertical] draws the bottom variant.
 */
@Composable
fun Elbow(
    color: Color, columnWidth: Dp, barHeight: Dp, modifier: Modifier = Modifier,
    outerRadius: Dp = 28.dp, innerRadius: Dp = 14.dp, flipVertical: Boolean = false,
) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cw = columnWidth.toPx(); val bh = barHeight.toPx()
        val ro = outerRadius.toPx().coerceAtMost(minOf(cw, h)); val ri = innerRadius.toPx()
        val p = Path().apply {
            moveTo(0f, ro)
            arcTo(Rect(0f, 0f, 2 * ro, 2 * ro), 180f, 90f, false)
            lineTo(w, 0f)
            lineTo(w, bh)
            lineTo(cw + ri, bh)
            arcTo(Rect(cw, bh, cw + 2 * ri, bh + 2 * ri), 270f, -90f, false)
            lineTo(cw, h)
            lineTo(0f, h)
            close()
        }
        if (flipVertical) {
            scale(1f, -1f) { drawPath(p, color) }
        } else drawPath(p, color)
    }
}

/** Horizontal bar made of coloured segments, optionally with a rounded end cap and a label. */
@Composable
fun SegmentBar(height: Dp, modifier: Modifier = Modifier, label: String? = null, colors: List<Color> = listOf(Lcars.Orange, Lcars.Lavender, Lcars.Peach)) {
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(3f).fillMaxHeight().background(colors[0]))
        if (label != null) {
            Text(
                label.uppercase(), color = Lcars.Orange, fontFamily = Condensed, fontWeight = FontWeight.Medium,
                fontSize = (height.value * 0.95f).coerceIn(11f, 22f).sp, letterSpacing = 2.sp, maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        Box(Modifier.width(36.dp).fillMaxHeight().background(colors[1]))
        Box(Modifier.width(18.dp).fillMaxHeight().background(colors[2 % colors.size]))
        Box(Modifier.width(28.dp).fillMaxHeight().clip(RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)).background(colors[0]))
    }
}

/** A navigation block: coloured rectangle with the label in black capitals at the bottom right. */
@Composable
fun LcarsBlock(
    label: String, color: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(0.dp), align: Alignment = Alignment.BottomEnd,
) {
    Box(
        modifier
            .clip(shape)
            .background(if (selected) Color.White else color)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = align,
    ) {
        Text(
            label.uppercase(), color = Color.Black, fontFamily = Condensed, fontWeight = FontWeight.Medium,
            fontSize = 14.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Clip, textAlign = TextAlign.End,
        )
    }
}

/** Pill-shaped button with rounded ends. */
@Composable
fun LcarsPill(label: String, color: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    LcarsBlock(label, color, selected, onClick, modifier, RoundedCornerShape(50), Alignment.Center)

@Composable
fun Gap(size: Dp = 4.dp) = Spacer(Modifier.height(size).width(size))

/** Thin full-width bar used as a section divider. */
@Composable
fun LcarsDivider(color: Color = Lcars.Lavender, modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(color))
