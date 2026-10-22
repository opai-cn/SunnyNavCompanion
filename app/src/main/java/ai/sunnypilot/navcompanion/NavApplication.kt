package ai.sunnypilot.navcompanion

import android.app.Application
import android.util.Log
import ai.sunnypilot.navcompanion.ui.NavViewModel
import org.maplibre.android.MapLibre

class NavApplication : Application() {
  /** Singleton ViewModel shared by both the phone Activity and the CarApp service. */
  lateinit var viewModel: NavViewModel
    private set

  override fun onCreate() {
    super.onCreate()

    // MapLibre init can throw on some devices (missing native libs, corrupt cache, etc.).
    // If it fails, the phone map falls back gracefully; we must NOT let it crash the process.
    runCatching { MapLibre.getInstance(this) }
      .onFailure { Log.e(TAG, "MapLibre init failed", it) }

    // ViewModel construction must also survive Application.onCreate — if something in its init
    // block throws, we still need a usable `viewModel` property or MainActivity will NPE.
    runCatching { NavViewModel(this) }
      .onSuccess { viewModel = it }
      .onFailure { error ->
        Log.e(TAG, "NavViewModel init failed — creating degraded fallback", error)
        // Minimal fallback: skip bridge polling so the user can still open the App.
        viewModel = NavViewModel(this, skipPolling = true)
      }
  }

  companion object {
    private const val TAG = "NavApplication"
  }
}
