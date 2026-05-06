package com.example.storageoptimizer.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.storageoptimizer.data.ImageRepository
import com.example.storageoptimizer.data.MainViewModel
import com.example.storageoptimizer.data.local.AppDatabase
import com.example.storageoptimizer.ui.theme.gallery.GalleryScreen
import com.example.storageoptimizer.ui.theme.home.HomeScreen
import com.example.storageoptimizer.ui.files.FilesScreen
import com.example.storageoptimizer.ui.theme.apps.AppsScreen

@Composable
fun AppNavGraph(navController: NavHostController) {

    val context = LocalContext.current

    val db = remember {
        AppDatabase.getInstance(context)
    }
    val repository = remember(db) {
        ImageRepository(db.imageDao())
    }
    val prefs = remember {
        context.getSharedPreferences("storage_optimizer_prefs", Context.MODE_PRIVATE)
    }

    val fileDao = remember(db) { db.fileDao() }
    val appDao  = remember(db) { db.appDao()  }

    val viewModel: MainViewModel = viewModel(
        factory = MainViewModel.Factory(repository, fileDao, appDao, prefs)
    )

    NavHost(
        navController    = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel          = viewModel,
                onReviewClick      = {
                    if (viewModel.hasData()) navController.navigate(Routes.GALLERY)
                },
                onFilesReviewClick = { navController.navigate(Routes.FILES) },
                onAppsReviewClick  = { navController.navigate(Routes.APPS)  }
            )
        }

        composable(Routes.GALLERY) {
            GalleryScreen(
                viewModel      = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.FILES) {
            FilesScreen(
                viewModel      = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.APPS) {
            AppsScreen(
                viewModel      = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}