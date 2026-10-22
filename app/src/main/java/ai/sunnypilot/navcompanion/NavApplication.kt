package ai.sunnypilot.navcompanion

import android.app.Application
import ai.sunnypilot.navcompanion.ui.NavViewModel
import org.maplibre.android.MapLibre

class NavApplication : Application() {
  /** Singleton ViewModel shared by both the phone Activity and the CarApp service. */
  lateinit var viewModel: NavViewModel
    private set

  override fun onCreate() {
    super.onCreate()
    MapLibre.getInstance(this)
    viewModel = NavViewModel(this)
  }
}
