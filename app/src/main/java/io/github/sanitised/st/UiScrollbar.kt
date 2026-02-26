package io.github.sanitised.st

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

internal fun Modifier.verticalScrollbar(state: ScrollState, color: Color): Modifier =
    drawWithContent {
        drawContent()
        if (state.maxValue > 0 && state.maxValue < Int.MAX_VALUE) {
            val viewport = size.height
            val content = viewport + state.maxValue
            val thumbH = (viewport * viewport / content).coerceAtLeast(48f)
            val thumbY = (state.value.toFloat() / state.maxValue) * (viewport - thumbH)
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width - 8f, thumbY + 2f),
                size = Size(6f, thumbH - 4f),
                cornerRadius = CornerRadius(3f)
            )
        }
    }
