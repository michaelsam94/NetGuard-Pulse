package com.michael.netguardplus.playstore

import org.junit.Rule
import org.junit.experimental.categories.Category
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w360dp-h640dp-xxhdpi"
private const val TABLET = "w800dp-h1280dp-xhdpi"

@RunWith(RobolectricTestRunner::class)
@Category(PlayStoreScreenshotTests::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlayStoreScreenshotTest {

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  @Test(timeout = 120_000)
  @Config(qualifiers = PHONE)
  fun phone_01_dashboard() {
    capturePlayStoreImage("phone/01_dashboard.png") {
      PlayStoreScreenshotFrame(
        scene = PlayStoreScene.Overview,
        state = PlayStoreTestFixtures.dashboardState,
      )
    }
  }

  @Test(timeout = 120_000)
  @Config(qualifiers = PHONE)
  fun phone_02_dns() {
    capturePlayStoreImage("phone/02_dns.png") {
      PlayStoreScreenshotFrame(
        scene = PlayStoreScene.Dns,
        state = PlayStoreTestFixtures.dnsState,
      )
    }
  }

  @Test(timeout = 120_000)
  @Config(qualifiers = PHONE)
  fun phone_03_configure_alert() {
    capturePlayStoreImage("phone/03_configure_alert.png") {
      PlayStoreAlertNetworkOverlay(state = PlayStoreTestFixtures.alertsState)
    }
  }

  @Test(timeout = 120_000)
  @Config(qualifiers = PHONE)
  fun phone_04_hotspot() {
    capturePlayStoreImage("phone/04_hotspot.png") {
      PlayStoreScreenshotFrame(
        scene = PlayStoreScene.Hotspot,
        state = PlayStoreTestFixtures.hotspotState,
      )
    }
  }

  @Test(timeout = 120_000)
  @Config(qualifiers = TABLET)
  fun tablet_01_dashboard() {
    capturePlayStoreImage("tablet/01_dashboard.png") {
      PlayStoreScreenshotFrame(
        scene = PlayStoreScene.Overview,
        state = PlayStoreTestFixtures.dashboardState,
      )
    }
  }

  @Test(timeout = 120_000)
  @Config(qualifiers = TABLET)
  fun tablet_02_dns() {
    capturePlayStoreImage("tablet/02_dns.png") {
      PlayStoreScreenshotFrame(
        scene = PlayStoreScene.Dns,
        state = PlayStoreTestFixtures.dnsState,
      )
    }
  }

  @Test(timeout = 120_000)
  @Config(qualifiers = TABLET)
  fun tablet_03_configure_alert() {
    capturePlayStoreImage("tablet/03_configure_alert.png") {
      PlayStoreAlertNetworkOverlay(state = PlayStoreTestFixtures.alertsState)
    }
  }

  @Test(timeout = 120_000)
  @Config(qualifiers = TABLET)
  fun tablet_04_hotspot() {
    capturePlayStoreImage("tablet/04_hotspot.png") {
      PlayStoreScreenshotFrame(
        scene = PlayStoreScene.Hotspot,
        state = PlayStoreTestFixtures.hotspotState,
      )
    }
  }
}
