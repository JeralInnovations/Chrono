package com.chrono.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.chrono.app.ui.theme.Amber
import com.chrono.app.ui.theme.ClipBlack
import com.chrono.app.ui.theme.ClipRed
import com.chrono.app.ui.theme.Good
import com.chrono.app.ui.theme.PanelHigh

/**
 * A stylized two-port spring-loaded speaker terminal, drawn entirely with
 * Canvas so it scales cleanly. Shows a red (+) and black (–) spring clip
 * with the sensor wires entering from below. When [pulsing] the whole
 * connector breathes with an amber glow; when [verified] the glow locks
 * to steady green.
 */
@Composable
fun TerminalGraphic(
    pulsing: Boolean,
    verified: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.size(260.dp, 190.dp)) {
            val w = size.width
            val h = size.height
            val bodyW = w * 0.82f
            val bodyH = h * 0.52f
            val bodyLeft = (w - bodyW) / 2f
            val bodyTop = h * 0.14f

            // glow behind the block
            if (pulsing || verified) {
                val glow = if (verified) Good else Amber
                val alpha = if (verified) 0.85f else pulse
                drawRoundRect(
                    color = glow.copy(alpha = alpha * 0.5f),
                    topLeft = Offset(bodyLeft - 10f, bodyTop - 10f),
                    size = Size(bodyW + 20f, bodyH + 20f),
                    cornerRadius = CornerRadius(28f, 28f),
                    style = Stroke(width = 10f),
                )
            }

            // terminal body
            drawRoundRect(
                color = PanelHigh,
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyW, bodyH),
                cornerRadius = CornerRadius(20f, 20f),
            )
            // subtle top edge highlight
            drawRoundRect(
                color = Color.White.copy(alpha = 0.06f),
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyW, bodyH * 0.45f),
                cornerRadius = CornerRadius(20f, 20f),
            )

            // the two spring clips
            val clipW = bodyW * 0.30f
            val clipH = bodyH * 0.52f
            val clipTop = bodyTop + bodyH * 0.14f
            val clip1X = bodyLeft + bodyW * 0.12f
            val clip2X = bodyLeft + bodyW * 0.58f
            drawSpringClip(clip1X, clipTop, clipW, clipH, ClipRed)
            drawSpringClip(clip2X, clipTop, clipW, clipH, ClipBlack)

            // wire entry holes under each clip
            val holeY = bodyTop + bodyH * 0.82f
            val hole1 = Offset(clip1X + clipW / 2f, holeY)
            val hole2 = Offset(clip2X + clipW / 2f, holeY)
            for (hole in listOf(hole1, hole2)) {
                drawCircle(Color.Black, radius = bodyH * 0.09f, center = hole)
                drawCircle(
                    Color.White.copy(alpha = 0.12f),
                    radius = bodyH * 0.09f,
                    center = hole,
                    style = Stroke(width = 2.5f),
                )
            }

            // sensor wires curving up into the holes
            drawWire(hole1, ClipRed.copy(red = 1f), w, h, bendLeft = true)
            drawWire(hole2, Color(0xFF3A4552), w, h, bendLeft = false)
        }
    }
}

private fun DrawScope.drawSpringClip(x: Float, y: Float, w: Float, h: Float, color: Color) {
    // lever: a slightly tilted rounded trapezoid, like a pressed spring tab
    rotate(degrees = -6f, pivot = Offset(x + w / 2f, y + h)) {
        val lever = Path().apply {
            moveTo(x + w * 0.12f, y + h)
            lineTo(x + w * 0.22f, y + h * 0.18f)
            quadraticBezierTo(x + w * 0.5f, y - h * 0.10f, x + w * 0.78f, y + h * 0.18f)
            lineTo(x + w * 0.88f, y + h)
            close()
        }
        drawPath(lever, color)
        drawPath(lever, Color.White.copy(alpha = 0.15f), style = Stroke(width = 2f))
        // ridge lines on the lever for grip
        val ridgeY = y + h * 0.35f
        drawLine(
            Color.Black.copy(alpha = 0.35f),
            Offset(x + w * 0.3f, ridgeY),
            Offset(x + w * 0.7f, ridgeY),
            strokeWidth = 3f,
        )
        drawLine(
            Color.Black.copy(alpha = 0.35f),
            Offset(x + w * 0.28f, ridgeY + h * 0.16f),
            Offset(x + w * 0.72f, ridgeY + h * 0.16f),
            strokeWidth = 3f,
        )
    }
}

private fun DrawScope.drawWire(hole: Offset, color: Color, w: Float, h: Float, bendLeft: Boolean) {
    val endX = if (bendLeft) hole.x - w * 0.18f else hole.x + w * 0.18f
    val path = Path().apply {
        moveTo(hole.x, hole.y)
        cubicTo(
            hole.x, hole.y + h * 0.16f,
            endX, hole.y + h * 0.10f,
            endX, h,
        )
    }
    drawPath(path, color, style = Stroke(width = 9f))
    drawPath(path, Color.White.copy(alpha = 0.10f), style = Stroke(width = 3f))
}
