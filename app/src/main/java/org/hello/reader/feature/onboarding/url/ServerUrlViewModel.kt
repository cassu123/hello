package org.hello.reader.feature.onboarding.url

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.hello.reader.app.appContainer
import org.hello.reader.core.data.ConnectionResult
import org.hello.reader.core.data.ServerConnectionRepository
import org.hello.reader.core.network.ServerUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.hello.reader.core.data.ConnectionError

sealed interface ServerStatus {
    data object Idle : ServerStatus
    data class InvalidInput(val reason: ServerUrl.Reason) : ServerStatus
    data class Checking(val baseUrl: String) : ServerStatus
    data class Online(val baseUrl: String, val version: String?, val isCleartext: Boolean) : ServerStatus
    data class Unreachable(val baseUrl: String, val error: ConnectionError) : ServerStatus
}

data class ServerUrlUiState(
    val input: String = "",
    val status: ServerStatus = ServerStatus.Idle,
) {
    val canContinue: Boolean get() = status is ServerStatus.Online
}

/**
 * Pings `/api/health` while the user types (debounced) so the connectivity
 * indicator is live by the time they finish entering the address.
 */
class ServerUrlViewModel(
    private val repository: ServerConnectionRepository,
    private val debounceMs: Long = DEBOUNCE_MS,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerUrlUiState())
    val state: StateFlow<ServerUrlUiState> = _state.asStateFlow()

    private var checkJob: Job? = null

    fun onInputChange(input: String) {
        _state.update { it.copy(input = input) }
        scheduleCheck(input, debounce = true)
    }

    fun retry() = scheduleCheck(_state.value.input, debounce = false)

    private fun scheduleCheck(input: String, debounce: Boolean) {
        checkJob?.cancel()
        if (input.isBlank()) {
            _state.update { it.copy(status = ServerStatus.Idle) }
            return
        }
        val baseUrl = when (val result = ServerUrl.normalize(input)) {
            is ServerUrl.Result.Invalid -> {
                // Don't nag about a half-typed address; surface the reason once typing settles.
                checkJob = viewModelScope.launch {
                    if (debounce) delay(debounceMs)
                    _state.update { it.copy(status = ServerStatus.InvalidInput(result.reason)) }
                }
                return
            }
            is ServerUrl.Result.Valid -> result.baseUrl
        }
        checkJob = viewModelScope.launch {
            if (debounce) delay(debounceMs)
            _state.update { it.copy(status = ServerStatus.Checking(baseUrl)) }
            val status = when (val health = repository.checkHealth(baseUrl)) {
                is ConnectionResult.Success ->
                    ServerStatus.Online(baseUrl, health.value.version, ServerUrl.isCleartext(baseUrl))
                is ConnectionResult.Failure -> ServerStatus.Unreachable(baseUrl, health.error)
            }
            _state.update { it.copy(status = status) }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 600L

        val Factory = viewModelFactory {
            initializer { ServerUrlViewModel(appContainer.serverConnectionRepository) }
        }
    }
}
