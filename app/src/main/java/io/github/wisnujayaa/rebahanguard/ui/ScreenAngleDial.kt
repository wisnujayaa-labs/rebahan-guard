package io.github.wisnujayaa.rebahanguard.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The phone seen from the side. The glowing edge is the screen; the line shows where the screen
 * faces. The arc is split at the user's threshold into "sky" (sitting, looking down at the
 * phone → screen faces up) and "blanket" (lying, looking up at the phone → screen faces down).
 */
@Composable
fun ScreenAngleDial(
    elevationDeg: Float,
    thresholdDeg: Float,
    alarming: Boolean,
    modifier: Modifier = Modifier,
    /** Prone threshold: the top of the arc above it is a "lying" zone too. NaN = off. */
    proneDeg: Float = Float.NaN,
) {
    val p = Tone.current // captured here: the Canvas lambda below is not composable
    val hasReading = elevationDeg.isFinite()
    val target = if (hasReading) elevationDeg.coerceIn(-90f, 90f) else 0f
    val angle by animateFloatAsState(target, animationSpec = tween(150), label = "screenAngle")
    val inBed = hasReading && (elevationDeg < thresholdDeg || (proneDeg.isFinite() && elevationDeg >= proneDeg))

    val reducedMotion = rememberReducedMotion()
    val pulse by rememberInfiniteTransition(label = "alarm").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "alarmPulse",
    )
    val blanketAlpha = when {
        alarming && !reducedMotion -> pulse
        alarming || inBed -> 0.8f
        else -> 0.38f
    }

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = p.Muted, fontSize = 12.sp)
    val description = if (hasReading) {
        "Layar menghadap ${angle.roundToInt()} derajat, ${if (inBed) "zona rebahan" else "zona aman"}"
    } else {
        "Membaca sensor"
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.55f)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.height / 2f, size.width * 0.6f) - 6.dp.toPx()
            val c = Offset(size.width * 0.31f, size.height / 2f)
            val thr = thresholdDeg.coerceIn(-90f, 90f)
            val box = Size(2 * r, 2 * r)
            val boxTopLeft = Offset(c.x - r, c.y - r)

            // Compose measures arc angles clockwise from 3 o'clock, so elevation e ↦ angle -e.
            drawArc(p.DuskHigh, -90f, 90f - thr, useCenter = true, topLeft = boxTopLeft, size = box)
            if (proneDeg.isFinite()) {
                // Face up steeply in the hand: the tengkurap zone (confirmed by the camera).
                val pr = proneDeg.coerceIn(-90f, 90f)
                drawArc(p.Blanket.copy(alpha = blanketAlpha * 0.6f), -90f, 90f - pr, useCenter = true, topLeft = boxTopLeft, size = box)
            }
            drawArc(
                p.Blanket.copy(alpha = blanketAlpha), -thr, 90f + thr,
                useCenter = true, topLeft = boxTopLeft, size = box,
            )

            // Ticks every 30°, so the angle can be read like a ruler.
            for (e in -90..90 step 30) {
                val dir = direction(e.toFloat())
                drawLine(
                    p.Text.copy(alpha = 0.35f),
                    c + dir * (r - 8.dp.toPx()), c + dir * r,
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // The user's threshold, like a dashed line drawn on the wall.
            val thrDir = direction(thr)
            drawLine(
                p.Lamp, c, c + thrDir * r,
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )

            labelAt(textMeasurer, "langit-langit", c + Offset(10.dp.toPx(), -r + 6.dp.toPx()), labelStyle)
            labelAt(textMeasurer, "lantai", c + Offset(10.dp.toPx(), r - 22.dp.toPx()), labelStyle)
            labelAt(
                textMeasurer, "batas",
                c + thrDir * (r + 4.dp.toPx()) + Offset(0f, -8.dp.toPx()),
                labelStyle.copy(color = p.Lamp),
            )

            if (hasReading) {
                // Where the screen is looking.
                val gaze = direction(angle)
                drawLine(
                    if (inBed) p.Text else p.Mint,
                    c + gaze * 14.dp.toPx(), c + gaze * (r - 12.dp.toPx()),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // The phone, side view: body plus a lit strip on the screen side.
            rotate(degrees = -angle, pivot = c) {
                drawRoundRect(
                    p.Text.copy(alpha = 0.92f),
                    topLeft = Offset(c.x - 6.dp.toPx(), c.y - 34.dp.toPx()),
                    size = Size(12.dp.toPx(), 68.dp.toPx()),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                )
                drawRect(
                    p.Lamp,
                    topLeft = Offset(c.x + 4.dp.toPx(), c.y - 29.dp.toPx()),
                    size = Size(2.5.dp.toPx(), 58.dp.toPx()),
                )
            }
        }

        // Bottom-right: the only corner the threshold label never reaches.
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 4.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                if (hasReading) "${angle.roundToInt()}°" else "—",
                style = MaterialTheme.typography.displaySmall,
                color = p.Text,
            )
            Text(
                when {
                    !hasReading -> "membaca sensor"
                    inBed -> "zona rebahan"
                    else -> "zona aman"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (inBed) p.Blanket else p.Mint,
            )
        }
    }
}

/** Unit vector for a screen elevation, in canvas coordinates (y grows downward). */
private fun direction(elevationDeg: Float): Offset {
    val a = Math.toRadians(-elevationDeg.toDouble())
    return Offset(cos(a).toFloat(), sin(a).toFloat())
}

private fun DrawScope.labelAt(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    topLeft: Offset,
    style: TextStyle,
) {
    drawText(measurer, text, topLeft = topLeft, style = style)
}
