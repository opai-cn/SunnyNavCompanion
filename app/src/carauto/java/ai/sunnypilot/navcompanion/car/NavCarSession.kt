package ai.sunnypilot.navcompanion.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * CarApp session. One session exists per active Android Auto connection. We hold a single
 * NavCarScreen in memory; templates are rebuilt from the shared NavViewModel every frame.
 */
class NavCarSession : Session() {
  override fun onCreateScreen(intent: Intent): Screen = NavCarScreen(carContext)
}
