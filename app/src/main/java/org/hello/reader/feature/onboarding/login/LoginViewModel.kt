package org.hello.reader.feature.onboarding.login

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.hello.reader.core.data.ConnectionError

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: ConnectionError? = null,
    val isLoggedIn: Boolean = false,
) {
    val canSubmit: Boolean get() = username.isNotBlank() && password.isNotEmpty() && !isSubmitting
}

class LoginViewModel(
    private val baseUrl: String,
    initialUsername: String?,
    private val repository: ServerConnectionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(username = initialUsername.orEmpty()))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.login(baseUrl, current.username.trim(), current.password)) {
                is ConnectionResult.Success -> _state.update {
                    it.copy(isSubmitting = false, password = "", isLoggedIn = true)
                }
                is ConnectionResult.Failure -> _state.update {
                    it.copy(isSubmitting = false, error = result.error)
                }
            }
        }
    }

    companion object {
        fun factory(baseUrl: String, initialUsername: String?) = viewModelFactory {
            initializer { LoginViewModel(baseUrl, initialUsername, appContainer.serverConnectionRepository) }
        }
    }
}
