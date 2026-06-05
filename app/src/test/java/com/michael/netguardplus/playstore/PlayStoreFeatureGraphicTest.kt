package com.michael.netguardplus.playstore

import org.junit.Rule
import org.junit.experimental.categories.Category
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FEATURE = "w1024dp-h500dp-mdpi"

@RunWith(RobolectricTestRunner::class)
@Category(PlayStoreScreenshotTests::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlayStoreFeatureGraphicTest {

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  @Test(timeout = 120_000)
  @Config(qualifiers = FEATURE)
  fun feature_graphic() {
    capturePlayStoreImage("feature-graphic.png") {
      FeatureGraphicContent()
    }
  }
}
