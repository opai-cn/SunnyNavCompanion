package ai.sunnypilot.navcompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import ai.sunnypilot.navcompanion.ui.MainScreen
import ai.sunnypilot.navcompanion.ui.NavViewModel
import ai.sunnypilot.navcompanion.ui.SunnyNavTheme

class MainActivity : ComponentActivity() {
  private val navViewModel: NavViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    setContent {
      SunnyNavTheme {
        MainScreen(navViewModel)
      }
    }
  }
}
