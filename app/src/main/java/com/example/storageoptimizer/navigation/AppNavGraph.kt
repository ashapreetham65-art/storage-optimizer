package com.example.storageoptimizer.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.storageoptimizer.data.MainViewModel
import com.example.storageoptimizer.ui.theme.gallery.GalleryScreen
import com.example.storageoptimizer.ui.home.HomeScreen

@Composable
fun AppNavGraph(navController: NavHostController) {

    // viewModel() here is scoped to the Activity's ViewModelStore —
    // same instance is returned for every composable call in this tree,
    // so HomeScreen and GalleryScreen always share the same ViewModel.
    val viewModel: MainViewModel = viewModel()

    NavHost(
        navController    = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel     = viewModel,
                onReviewClick = {
                    // Only navigate if a scan has already produced data.
                    // This prevents the Gallery from showing an empty state
                    // that looks like a bug — user must scan first.
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