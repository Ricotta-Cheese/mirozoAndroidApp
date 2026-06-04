package kr.mirozo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.mirozo.app.ui.screens.CalendarHomeScreen
import kr.mirozo.app.ui.theme.MyApplicationTheme
import kr.mirozo.app.ui.viewmodel.CalendarViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        val viewModel: CalendarViewModel = viewModel()
        CalendarHomeScreen(viewModel = viewModel)
      }
    }
  }
}

