package ai.sunnypilot.navcompanion

import android.app.Application
import org.maplibre.android.MapLibre

class NavApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    MapLibre.getInstance(this)
  }
}
