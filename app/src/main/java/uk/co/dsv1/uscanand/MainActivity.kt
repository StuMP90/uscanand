package uk.co.dsv1.uscanand

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import uk.co.dsv1.uscanand.ui.CropScreen
import uk.co.dsv1.uscanand.ui.DocumentScreen
import uk.co.dsv1.uscanand.ui.LibraryScreen
import uk.co.dsv1.uscanand.ui.PageScreen
import uk.co.dsv1.uscanand.ui.SettingsScreen
import uk.co.dsv1.uscanand.ui.theme.UScanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UScanTheme {
                AppNavHost()
            }
        }
    }
}

@Composable
private fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onOpenDocument = { nav.navigate("document/$it") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("document/{docId}") { entry ->
            val docId = entry.arguments?.getString("docId").orEmpty()
            DocumentScreen(
                docId = docId,
                onBack = nav.backFrom(entry),
                onOpenPage = { pageId -> nav.navigate("page/$docId/$pageId") },
            )
        }
        composable("page/{docId}/{pageId}") { entry ->
            val docId = entry.arguments?.getString("docId").orEmpty()
            val pageId = entry.arguments?.getString("pageId").orEmpty()
            PageScreen(
                docId = docId,
                pageId = pageId,
                onBack = nav.backFrom(entry),
                onCrop = { nav.navigate("crop/$docId/$pageId") },
            )
        }
        composable("crop/{docId}/{pageId}") { entry ->
            CropScreen(
                docId = entry.arguments?.getString("docId").orEmpty(),
                pageId = entry.arguments?.getString("pageId").orEmpty(),
                onDone = nav.backFrom(entry),
            )
        }
        composable("settings") { entry ->
            SettingsScreen(onBack = nav.backFrom(entry))
        }
    }
}

/** Pops [entry] only while it is the resumed destination, so repeated calls never pop further. */
private fun NavHostController.backFrom(entry: NavBackStackEntry): () -> Unit = {
    if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) popBackStack()
}
