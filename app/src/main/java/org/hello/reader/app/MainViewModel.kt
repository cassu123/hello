package org.hello.reader.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface StartState {
        data object Loading : StartState
        data object Ready : StartState
    }

    private val _startState = MutableStateFlow<StartState>(StartState.Loading)
    val startState: StateFlow<StartState> = _startState.asStateFlow()

    init {
        viewModelScope.launch {
            // First vault access opens the Keystore-backed prefs: keep it off the main thread.
            withContext(Dispatchers.IO) { container.vault }
            _startState.value = StartState.Ready
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { MainViewModel(appContainer) }
        }
    }
}
