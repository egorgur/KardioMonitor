package ru.kardiomonitor.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kardiomonitor.MainViewModel
import ru.kardiomonitor.ui.DashboardActions
import ru.kardiomonitor.ui.DashboardScreen
import ru.kardiomonitor.ui.KardioTheme
import ru.kardiomonitor.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as KardioApplication
                MainViewModel(app, app.container.bandManager)
            }
        }
    }

    private val blePermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) // BLE-сканирование до Android 12
        }

    private fun hasPermissions() = blePermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Приложение свёрнуто — закрываем соединение: данные заберёт фоновая синхронизация,
     * а постоянное соединение тратит батарею браслета. Не трогаем, пока идёт измерение пульса
     * в реальном времени (например, тренировка с заблокированным экраном).
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !viewModel.state.value.band.liveHeartRateOn) viewModel.disconnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KardioTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted -> if (granted.values.all { it }) viewModel.connect() }

                val connect = {
                    if (hasPermissions()) viewModel.connect() else permissionLauncher.launch(blePermissions)
                }

                Scaffold(Modifier.fillMaxSize()) { padding ->
                    val config = state.config
                    if (config == null) {
                        SetupScreen(onSave = viewModel::saveConfig, modifier = Modifier.padding(padding))
                    } else {
                        DashboardScreen(
                            state = state,
                            actions = DashboardActions(
                                connect = connect,
                                disconnect = viewModel::disconnect,
                                sync = viewModel::sync,
                                toggleHeartRate = viewModel::toggleLiveHeartRate,
                                setHeartRateInterval = viewModel::setHeartRateInterval,
                                setBackgroundSync = viewModel::setBackgroundSync,
                                showDay = viewModel::showDay,
                                forgetBand = viewModel::forgetBand,
                                dismissError = viewModel::dismissError,
                            ),
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }
}
