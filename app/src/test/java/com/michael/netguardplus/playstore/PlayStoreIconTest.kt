package com.michael.netguardplus.playstore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.michael.netguardplus.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.experimental.categories.Category
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val ICON = "w512dp-h512dp-mdpi"

@RunWith(RobolectricTestRunner::class)
@Category(PlayStoreScreenshotTests::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlayStoreIconTest {

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  @Test(timeout = 120_000)
  @Config(qualifiers = ICON)
  fun app_icon_512() {
    capturePlayStoreImage("app-icon-512.png") {
      PlayStoreIconContent()
    }
  }
}

@Composable
private fun PlayStoreIconContent() {
  MyApplicationTheme(dynamicColor = false) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.linearGradient(
            colors = listOf(
              Color(0xFF07131F),
              Color(0xFF12324B),
              Color(0xFF1A1642),
            ),
            start = Offset.Zero,
            end = Offset.Infinite,
          ),
        ),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
          brush = Brush.radialGradient(
            colors = listOf(Color(0x662DE2FF), Color.Transparent),
            center = Offset(size.width * 0.28f, size.height * 0.32f),
            radius = size.width * 0.62f,
          ),
        )
        drawCircle(
          brush = Brush.radialGradient(
            colors = listOf(Color(0x665B6DFF), Color.Transparent),
            center = Offset(size.width * 0.76f, size.height * 0.68f),
            radius = size.width * 0.58f,
          ),
        )

        drawRoundRect(
          color = Color(0x26000000),
          topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
          size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.76f),
          cornerRadius = CornerRadius(size.width * 0.08f),
        )

        val shield = Path().apply {
          moveTo(size.width * 0.50f, size.height * 0.13f)
          cubicTo(size.width * 0.67f, size.height * 0.20f, size.width * 0.79f, size.height * 0.22f, size.width * 0.84f, size.height * 0.24f)
          lineTo(size.width * 0.84f, size.height * 0.49f)
          cubicTo(size.width * 0.84f, size.height * 0.68f, size.width * 0.71f, size.height * 0.82f, size.width * 0.50f, size.height * 0.90f)
          cubicTo(size.width * 0.29f, size.height * 0.82f, size.width * 0.16f, size.height * 0.68f, size.width * 0.16f, size.height * 0.49f)
          lineTo(size.width * 0.16f, size.height * 0.24f)
          cubicTo(size.width * 0.21f, size.height * 0.22f, size.width * 0.33f, size.height * 0.20f, size.width * 0.50f, size.height * 0.13f)
          close()
        }
        drawPath(
          path = shield,
          brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFEAF8FF), Color(0xFF5BC6FF), Color(0xFF6256FF)),
          ),
          alpha = 0.22f,
        )
        drawPath(
          path = shield,
          brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xFF54D8FF), Color(0xFF8B6CFF)),
          ),
          style = Stroke(width = size.width * 0.035f),
          alpha = 0.82f,
        )

        val pulse = Path().apply {
          moveTo(size.width * 0.08f, size.height * 0.53f)
          lineTo(size.width * 0.25f, size.height * 0.53f)
          lineTo(size.width * 0.31f, size.height * 0.42f)
          lineTo(size.width * 0.40f, size.height * 0.68f)
          lineTo(size.width * 0.50f, size.height * 0.29f)
          lineTo(size.width * 0.61f, size.height * 0.64f)
          lineTo(size.width * 0.69f, size.height * 0.53f)
          lineTo(size.width * 0.92f, size.height * 0.53f)
        }
        drawPath(
          path = pulse,
          color = Color(0x66000000),
          style = Stroke(width = size.width * 0.065f, cap = StrokeCap.Round),
        )
        drawPath(
          path = pulse,
          brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFF35E6FF), Color(0xFFEAFBFF), Color(0xFF8A6CFF)),
          ),
          style = Stroke(width = size.width * 0.043f, cap = StrokeCap.Round),
        )

        drawCircle(
          color = Color.White,
          radius = size.width * 0.032f,
          center = Offset(size.width * 0.50f, size.height * 0.29f),
          alpha = 0.95f,
        )
      }
    }
  }
}
