package com.thegalaxybyte.momentum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.thegalaxybyte.momentum.ui.AppRoot
import com.thegalaxybyte.momentum.ui.MomentumTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MomentumTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Fresh watch data, weekly re-calibration, then sync — each step best-effort.
        val c = container
        lifecycleScope.launch {
            runCatching { c.health.importRecent() }
            runCatching { c.plan.ensureWeeklyUpdates() }
            c.sync.sync()
        }
    }
}
