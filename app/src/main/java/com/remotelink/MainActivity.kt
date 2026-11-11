package com.remotelink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remotelink.ui.AppViewModelFactory
import com.remotelink.ui.ControllerScreen
import com.remotelink.ui.ControllerViewModel
import com.remotelink.ui.HomeScreen
import com.remotelink.ui.HostScreen
import com.remotelink.ui.HostViewModel
import com.remotelink.ui.SessionScreen
import com.remotelink.ui.theme.RemoteLinkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as RemoteLinkApp).container
        setContent {
            RemoteLinkTheme { AppNav(AppViewModelFactory(container)) }
        }
    }
}

@Composable
private fun AppNav(factory: ViewModelProvider.Factory) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onController = { nav.navigate("controller") },
                onHost = { nav.navigate("host") }
            )
        }
        composable("host") {
            HostScreen(viewModel<HostViewModel>(factory = factory), onBack = { nav.popBackStack() })
        }
        composable("controller") {
            ControllerScreen(
                viewModel<ControllerViewModel>(factory = factory),
                onBack = { nav.popBackStack() },
                onSession = { nav.navigate("session") }
            )
        }
        composable("session") {
            SessionScreen(viewModel<ControllerViewModel>(factory = factory), onExit = { nav.popBackStack() })
        }
    }
}
