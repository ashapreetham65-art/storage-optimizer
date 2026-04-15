package com.example.storageoptimizer.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.storageoptimizer.data.ScanViewModel
import com.example.storageoptimizer.ui.theme.gallery.GalleryScreen
import com.example.storageoptimizer.ui.theme.home.HomeScreen

@Composable
fun AppNavGraph(navController: NavHostController) {

    // Single ViewModel instance shared across both screens.
    // viewModel() with no owner defaults to the NavBackStackEntry's closest
    // ViewModelStoreOwner — using the NavController's parent activity scope
    // ensures the same instance is returned for both destinations.
    val scanViewModel: ScanViewModel = viewModel()

    NavHost(
        navController    = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                scanViewModel = scanViewModel,
                onReviewClick = { navController.navigate(Routes.GALLERY) }
            )
        }

        composable(Routes.GALLERY) {
            GalleryScreen(
                scanViewModel  = scanViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}