package tw.bard.carradar.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tw.bard.carradar.core.ActiveCameraHud
import tw.bard.carradar.core.IntervalZone

data class MonitorUiState(
    val isRunning: Boolean = false,
    val isBtMode: Boolean = false,
    val speedKmh: Int = 0,
    val sessionKm: Double = 0.0,
    val activeHud: ActiveCameraHud? = null,
    val activeZone: IntervalZone? = null,
    val lastAlertLabel: String = "",
    val cameraCount: Int = 0,
    val zoneCount: Int = 0
)

object MonitorStateBus {
    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    fun update(transform: (MonitorUiState) -> MonitorUiState) {
        _state.value = transform(_state.value)
    }

    const val ACTION_KILL_TASK = "tw.bard.carradar.ACTION_KILL_TASK"
}
