package org.hello.reader.feature.onboarding.qr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.hello.reader.app.appContainer
import org.hello.reader.core.data.ConnectionResult
import org.hello.reader.core.data.ServerConnectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.hello.reader.core.data.ConnectionError

sealed interface QrScanState {
    /** [rejectedCode] is set after a QR code that isn't a Hello connect code. */
    data class Scanning(val rejectedCode: Boolean = false) : QrScanState
    data class Connecting(val baseUrl: String) : QrScanState
    data class Failed(val error: ConnectionError) : QrScanState
    data object Connected : QrScanState
}

class QrScanViewModel(private val repository: ServerConnectionRepository) : ViewModel() {

    private val _state = MutableStateFlow<QrScanState>(QrScanState.Scanning())
    val state: StateFlow<QrScanState> = _state.asStateFlow()

    fun onPayload(payload: QrConnectPayload) {
        if (_state.value !is QrScanState.Scanning) return
        _state.value = QrScanState.Connecting(payload.baseUrl)
        viewModelScope.launch {
            _state.value = when (val result = repository.connectWithToken(payload.baseUrl, payload.token)) {
                is ConnectionResult.Success -> QrScanState.Connected
                is ConnectionResult.Failure -> QrScanState.Failed(result.error)
            }
        }
    }

    fun onInvalidCode() {
        if (_state.value is QrScanState.Scanning) {
            _state.value = QrScanState.Scanning(rejectedCode = true)
        }
    }

    fun scanAgain() {
        _state.value = QrScanState.Scanning()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { QrScanViewModel(appContainer.serverConnectionRepository) }
        }
    }
}
