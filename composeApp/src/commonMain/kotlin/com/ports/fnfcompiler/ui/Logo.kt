package com.ports.fnfcompiler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AppLogo(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val center = w / 2f
        val radius = w * 0.46f
        val glow = Color(0xFFC56CF0)
        val soft = Color(0xFFE9C9FF)

        val hexagon = Path().apply {
            for (i in 0 until 6) {
                val angle = (PI / 180.0) * (60 * i - 90)
                val x = center + radius * cos(angle).toFloat()
                val y = center + radius * sin(angle).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(hexagon, Brush.verticalGradient(listOf(Color(0xFF2A1A47), Color(0xFF130C22))))
        drawPath(hexagon, glow, style = Stroke(width = w * 0.045f, join = StrokeJoin.Round))

        val chipStart = w * 0.30f
        val chipSize = w * 0.40f
        drawRoundRect(
            color = glow.copy(alpha = 0.18f),
            topLeft = Offset(chipStart, chipStart),
            size = Size(chipSize, chipSize),
            cornerRadius = CornerRadius(w * 0.06f)
        )
        drawRoundRect(
            color = glow,
            topLeft = Offset(chipStart, chipStart),
            size = Size(chipSize, chipSize),
            cornerRadius = CornerRadius(w * 0.06f),
            style = Stroke(width = w * 0.03f)
        )

        val pin = w * 0.05f
        val pinWidth = w * 0.022f
        for (i in 0 until 3) {
            val offset = chipStart + chipSize * (0.25f + 0.25f * i)
            drawLine(glow, Offset(offset, chipStart - pin), Offset(offset, chipStart), pinWidth, StrokeCap.Round)
            drawLine(glow, Offset(offset, chipStart + chipSize), Offset(offset, chipStart + chipSize + pin), pinWidth, StrokeCap.Round)
            drawLine(glow, Offset(chipStart - pin, offset), Offset(chipStart, offset), pinWidth, StrokeCap.Round)
            drawLine(glow, Offset(chipStart + chipSize, offset), Offset(chipStart + chipSize + pin, offset), pinWidth, StrokeCap.Round)
        }

        val play = Path().apply {
            moveTo(w * 0.43f, w * 0.40f)
            lineTo(w * 0.62f, w * 0.50f)
            lineTo(w * 0.43f, w * 0.60f)
            close()
        }
        drawPath(play, Brush.linearGradient(listOf(soft, glow)))
    }
}
