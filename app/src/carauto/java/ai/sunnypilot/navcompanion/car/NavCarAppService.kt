package ai.sunnypilot.navcompanion.car

import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto / Android Automotive OS entry point.
 *
 * androidx.car.app 1.7.0 removed the [androidx.car.app.annotations.CarApp] annotation — the
 * category is declared in AndroidManifest.xml (see carauto/src/AndroidManifest.xml), and this
 * service only needs to implement [createHostValidator] and [onCreateSession].
 */
class NavCarAppService : CarAppService() {
  override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

  override fun onCreateSession(): Session = NavCarSession()
}
