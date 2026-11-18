package ru.kardiomonitor.staff.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.kardiomonitor.staff.platform.ui.StaffTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as StaffApplication).container
        setContent {
            StaffTheme {
                val staffViewModel: StaffViewModel = viewModel(factory = StaffViewModelFactory(container))
                StaffNavHost(staffViewModel)
            }
        }
    }
}