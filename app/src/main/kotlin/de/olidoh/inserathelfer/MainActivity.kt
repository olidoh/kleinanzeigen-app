package de.olidoh.inserathelfer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.olidoh.inserathelfer.ui.AppTheme
import de.olidoh.inserathelfer.ui.CaptureScreen
import de.olidoh.inserathelfer.ui.DetailsScreen
import de.olidoh.inserathelfer.ui.EbayLoginScreen
import de.olidoh.inserathelfer.ui.EbayPostScreen
import de.olidoh.inserathelfer.ui.HistoryScreen
import de.olidoh.inserathelfer.ui.PostScreen
import de.olidoh.inserathelfer.ui.ReviewScreen
import de.olidoh.inserathelfer.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val viewModel: ListingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val vm = viewModel
                when (vm.screen) {
                    Screen.SETTINGS -> SettingsScreen(vm)
                    Screen.CAPTURE -> CaptureScreen(vm)
                    Screen.DETAILS -> {
                        BackHandler { vm.screen = Screen.CAPTURE }
                        DetailsScreen(vm)
                    }
                    Screen.REVIEW -> {
                        BackHandler { vm.leaveReview() }
                        ReviewScreen(vm)
                    }
                    Screen.POST -> {
                        BackHandler { vm.screen = Screen.REVIEW }
                        PostScreen(vm)
                    }
                    Screen.EBAY_LOGIN -> {
                        BackHandler { vm.onEbayLoginFinished(false) }
                        EbayLoginScreen(vm)
                    }
                    Screen.EBAY_POST -> {
                        BackHandler { vm.screen = Screen.REVIEW }
                        EbayPostScreen(vm)
                    }
                    Screen.HISTORY -> {
                        BackHandler { vm.screen = Screen.CAPTURE }
                        HistoryScreen(vm)
                    }
                }
            }
        }
    }
}
