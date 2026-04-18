package com.example.storageoptimizer.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.storageoptimizer.data.ImageRepository
import com.example.storageoptimizer.data.MainViewModel
import com.example.storageoptimizer.data.local.AppDatabase
import com.example.storageoptimizer.ui.theme.gallery.GalleryScreen
import com.example.storageoptimizer.ui.home.HomeScreen

@Composable
fun AppNavGraph(navController: NavHostController) {

    val context = LocalContext.current

    // Build the DB singleton and wire it through to the ViewModel.
    // AppDatabase.getInstance() uses double-checked locking so this is safe
    // to call on every recomposition — it returns the existing instance.
    val db         = AppDatabase.getInstance(context)
    val repository = ImageRepository(db.imageDao())
    val prefs      = context.getSharedPreferences("storage_optimizer_prefs", Context.MODE_PRIVATE)

    // viewModel() scoped to the Activity — same instance returned for every
    // composable destination, so HomeScreen and GalleryScreen share state.
    val viewModel: MainViewModel = viewModel(
        factory = MainViewModel.Factory(repository, prefs)
    )

    NavHost(
        navController    = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel     = viewModel,
                onReviewClick = {
                    if (viewModel.hasData()) {
                        navController.navigate(Routes.GALLERY)
                    }
                }
            )
        }

        composable(Routes.GALLERY) {
            GalleryScreen(
                viewModel      = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}