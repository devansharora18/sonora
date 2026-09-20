package dev.sonora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.sonora.ui.SonoraApp
import dev.sonora.ui.theme.SonoraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SonoraTheme {
                SonoraApp()
            }
        }
    }
}
