package ru.kardiomonitor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kardiomonitor.features.band.pairing.PairingScreen
import ru.kardiomonitor.platform.ui.KardioTheme

/** Навигация: браслет не привязан — экран привязки, иначе — главный экран. */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as KardioApplication).container

    /**
     * Приложение свёрнуто — закрываем соединение: данные заберёт фоновая синхронизация,
     * а постоянное соединение тратит батарею браслета. Не закрывается, пока его держит
     * измерение пульса в реальном времени (тренировка с заблокированным экраном), ADR-0003.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) container.bandConnection.closeIfIdle()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KardioTheme {
                val band by container.bandRepository.band.collectAsStateWithLifecycle()
                Scaffold(Modifier.fillMaxSize()) { padding ->
                    if (band == null) {
                        PairingScreen(
                            container.bandRepository,
                            container.clinicEnrollment,
                            Modifier.padding(padding),
                        )
                    } else {
                        DashboardScreen(container, Modifier.padding(padding))
                    }
                }
            }
        }
    }
}
