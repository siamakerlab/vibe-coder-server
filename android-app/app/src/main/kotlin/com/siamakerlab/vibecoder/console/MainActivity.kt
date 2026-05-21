package com.siamakerlab.vibecoder.console

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.siamakerlab.vibecoder.console.data.local.AppPreferences
import com.siamakerlab.vibecoder.console.ui.artifact.ArtifactScreen
import com.siamakerlab.vibecoder.console.ui.build.BuildScreen
import com.siamakerlab.vibecoder.console.ui.connect.ConnectScreen
import com.siamakerlab.vibecoder.console.ui.console.ConsoleViewModel
import com.siamakerlab.vibecoder.console.ui.console.ProjectConsoleScreen
import com.siamakerlab.vibecoder.console.ui.dashboard.DashboardScreen
import com.siamakerlab.vibecoder.console.ui.environment.EnvironmentScreen
import com.siamakerlab.vibecoder.console.ui.files.FileTransferScreen
import com.siamakerlab.vibecoder.console.ui.git.GitScreen
import com.siamakerlab.vibecoder.console.ui.log.LogScreen
import com.siamakerlab.vibecoder.console.ui.nav.Routes
import com.siamakerlab.vibecoder.console.ui.projects.ProjectListScreen
import com.siamakerlab.vibecoder.console.ui.projects.ProjectRegisterScreen
import com.siamakerlab.vibecoder.console.ui.theme.VibeCoderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VibeCoderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(prefs)
                }
            }
        }
    }
}

@Composable
fun AppNavHost(prefs: AppPreferences) {
    val nav = rememberNavController()

    // Wait for DataStore to deliver the persisted session before deciding the start
    // destination. NavHost only honours `startDestination` on first composition, so
    // we must hold rendering until the first emission — otherwise a returning user
    // briefly sees CONNECT and gets stuck there (NavHost ignores later changes).
    val session by produceState<AppPreferences.Session?>(initialValue = null) {
        prefs.session.collect { value = it }
    }
    val current = session ?: return    // splash: empty frame until first emission

    val startDestination = if (current.token.isNullOrBlank()) Routes.CONNECT else Routes.DASHBOARD

    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.CONNECT) {
            ConnectScreen(
                onSuccess = {
                    nav.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.CONNECT) { inclusive = true }
                    }
                },
                vm = hiltViewModel(),
            )
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onOpenEnvironment = { nav.navigate(Routes.ENVIRONMENT) },
                onOpenProjects = { nav.navigate(Routes.PROJECT_LIST) },
                onLogout = {
                    nav.navigate(Routes.CONNECT) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
                vm = hiltViewModel(),
            )
        }
        composable(Routes.ENVIRONMENT) {
            EnvironmentScreen(onBack = { nav.popBackStack() }, vm = hiltViewModel())
        }
        composable(Routes.PROJECT_LIST) {
            ProjectListScreen(
                onRegister = { nav.navigate(Routes.PROJECT_REGISTER) },
                onOpen = { id -> nav.navigate(Routes.console(id)) },
                onBack = { nav.popBackStack() },
                vm = hiltViewModel(),
            )
        }
        composable(Routes.PROJECT_REGISTER) {
            ProjectRegisterScreen(
                onRegistered = { id ->
                    nav.popBackStack()
                    nav.navigate(Routes.console(id))
                },
                onBack = { nav.popBackStack() },
                vm = hiltViewModel(),
            )
        }
        composable(
            Routes.CONSOLE,
            arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString(Routes.ARG_PROJECT_ID) ?: return@composable
            val vm: ConsoleViewModel = hiltViewModel()
            ProjectConsoleScreen(
                projectId = projectId,
                onBack = { nav.popBackStack() },
                onOpenBuild = { nav.navigate(Routes.builds(projectId)) },
                onOpenGit = { nav.navigate(Routes.git(projectId)) },
                onOpenFiles = { nav.navigate(Routes.files(projectId)) },
                onOpenArtifacts = { nav.navigate(Routes.artifacts(projectId)) },
                onDeleteProject = {
                    vm.deleteProject {
                        nav.popBackStack(Routes.PROJECT_LIST, inclusive = false)
                    }
                },
                vm = vm,
            )
        }
        composable(
            Routes.BUILD_LOG,
            arguments = listOf(
                navArgument(Routes.ARG_PROJECT_ID) { type = NavType.StringType },
                navArgument(Routes.ARG_BUILD_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val projectId = entry.arguments?.getString(Routes.ARG_PROJECT_ID) ?: return@composable
            val buildId = entry.arguments?.getString(Routes.ARG_BUILD_ID) ?: return@composable
            LogScreen(projectId = projectId, buildId = buildId,
                onBack = { nav.popBackStack() }, vm = hiltViewModel())
        }
        composable(
            Routes.BUILDS,
            arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString(Routes.ARG_PROJECT_ID) ?: return@composable
            BuildScreen(projectId = projectId,
                onOpenLog = { buildId -> nav.navigate(Routes.buildLog(projectId, buildId)) },
                onOpenArtifacts = { nav.navigate(Routes.artifacts(projectId)) },
                onBack = { nav.popBackStack() },
                vm = hiltViewModel())
        }
        composable(
            Routes.ARTIFACTS,
            arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString(Routes.ARG_PROJECT_ID) ?: return@composable
            ArtifactScreen(projectId = projectId,
                onBack = { nav.popBackStack() },
                vm = hiltViewModel())
        }
        composable(
            Routes.GIT,
            arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString(Routes.ARG_PROJECT_ID) ?: return@composable
            GitScreen(projectId = projectId, onBack = { nav.popBackStack() }, vm = hiltViewModel())
        }
        composable(
            Routes.FILES,
            arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString(Routes.ARG_PROJECT_ID) ?: return@composable
            FileTransferScreen(projectId = projectId, onBack = { nav.popBackStack() }, vm = hiltViewModel())
        }
    }
}
