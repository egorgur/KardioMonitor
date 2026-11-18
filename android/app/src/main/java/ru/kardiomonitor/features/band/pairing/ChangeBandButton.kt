package ru.kardiomonitor.features.band.pairing

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.domain.BandRepository

/**
 * Вторая точка входа слайса «Привязка браслета»: «Сменить браслет» на главном экране.
 * Отвязывает браслет — приложение возвращается к [PairingScreen].
 */
@Composable
fun ChangeBandButton(repository: BandRepository, connection: BandConnection, modifier: Modifier = Modifier) {
    val viewModel = viewModel { ChangeBandViewModel(repository, connection) }
    TextButton(onClick = viewModel::forget, modifier = modifier) { Text("Сменить браслет") }
}

internal class ChangeBandViewModel(
    private val repository: BandRepository,
    private val connection: BandConnection,
) : ViewModel() {
    fun forget() {
        connection.disconnect()
        repository.forget()
    }
}
