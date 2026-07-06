package com.astrostack.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.astrostack.app.ui.CameraScreen
import com.astrostack.app.ui.GalleryScreen
import com.astrostack.app.ui.StackingScreen

sealed class Screen(val route: String) {
    data object Camera : Screen("camera")
    data object Gallery : Screen("gallery")
    data object Stacking : Screen("stacking/{sessionId}") {
        fun createRoute(sessionId: Long) = "stacking/$sessionId"
    }
}

@Composable
fun AstroStackNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Camera.route
    ) {
        composable(Screen.Camera.route) {
            CameraScreen(
                onNavigateToGallery = { navController.navigate(Screen.Gallery.route) },
                onNavigateToStacking = { sessionId ->
                    navController.navigate(Screen.Stacking.createRoute(sessionId))
                }
            )
        }

        composable(Screen.Gallery.route) {
            GalleryScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToStacking = { sessionId ->
                    navController.navigate(Screen.Stacking.createRoute(sessionId))
                }
            )
        }

        composable(
            route = Screen.Stacking.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: return@composable
            StackingScreen(
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
