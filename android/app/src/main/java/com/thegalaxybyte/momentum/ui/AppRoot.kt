package com.thegalaxybyte.momentum.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.data.db.ProfileEntity
import com.thegalaxybyte.momentum.ui.food.AddFoodScreen
import com.thegalaxybyte.momentum.ui.food.FoodScreen
import com.thegalaxybyte.momentum.ui.progress.ProgressScreen
import com.thegalaxybyte.momentum.ui.settings.ProfileScreen
import com.thegalaxybyte.momentum.ui.settings.SettingsScreen
import com.thegalaxybyte.momentum.ui.today.TodayScreen
import com.thegalaxybyte.momentum.ui.train.ExerciseScreen
import com.thegalaxybyte.momentum.ui.train.SessionScreen
import com.thegalaxybyte.momentum.ui.train.TrainScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Today", Icons.Default.Home),
    Tab("food", "Food", Icons.Default.Restaurant),
    Tab("train", "Train", Icons.Default.FitnessCenter),
    Tab("progress", "Progress", Icons.Default.ShowChart),
    Tab("settings", "Settings", Icons.Default.Settings),
)

private object Loading

@Composable
fun AppRoot() {
    val c = LocalContext.current.container
    val profileFlow = remember { c.dao.profile() }
    val profile by profileFlow.collectAsState(initial = Loading as Any?)
    when (profile) {
        Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        null -> ProfileScreen(onboarding = true, onDone = {})
        else -> MainNav(profile as ProfileEntity)
    }
}

fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun MainNav(@Suppress("UNUSED_PARAMETER") profile: ProfileEntity) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.goTab(t.route) },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(pad)) {
            composable("today") { TodayScreen(nav) }
            composable("food") { FoodScreen(nav) }
            composable(
                "food/add/{day}/{meal}",
                arguments = listOf(navArgument("day") { type = NavType.LongType }, navArgument("meal") { type = NavType.StringType }),
            ) { e ->
                AddFoodScreen(nav, e.arguments!!.getLong("day"), e.arguments!!.getString("meal")!!)
            }
            composable("train") { TrainScreen(nav) }
            composable("session/{key}") { e -> SessionScreen(nav, e.arguments!!.getString("key")!!) }
            composable("exercise/{id}") { e -> ExerciseScreen(nav, e.arguments!!.getString("id")!!) }
            composable("progress") { ProgressScreen() }
            composable("settings") { SettingsScreen(nav) }
            composable("profile") { ProfileScreen(onboarding = false, onDone = { nav.popBackStack() }) }
        }
    }
}
