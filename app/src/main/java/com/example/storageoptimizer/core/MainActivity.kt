package com.example.storageoptimizer.core

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.example.storageoptimizer.navigation.AppNavGraph
import com.example.storageoptimizer.ui.theme.StorageOptimizerTheme

// ── V7: MainActivity is now just the entry point.
//   All UI lives in HomeScreen / GalleryScreen.
//   All navigation is handled by AppNavGraph.
//   All scanning/hashing logic lives in ImageEngine.
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            StorageOptimizerTheme {
                val navController = rememberNavController()
                AppNavGraph(navController = navController)
            }
        }
    }
}