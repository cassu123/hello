package org.hello.reader.feature.onboarding.switcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.hello.reader.app.appContainer
import org.hello.reader.core.model.ServerProfile
import org.hello.reader.core.security.SecurityVault
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ServerSwitcherViewModel(private val vault: SecurityVault) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> = vault.profiles
    val activeProfile: StateFlow<ServerProfile?> = vault.activeProfile

    fun select(profileId: String) {
        if (vault.activeProfile.value?.id == profileId) return
        viewModelScope.launch { vault.setActiveProfile(profileId) }
    }

    fun rename(profileId: String, newName: String) {
        viewModelScope.launch { vault.renameProfile(profileId, newName) }
    }

    fun delete(profileId: String) {
        viewModelScope.launch { vault.deleteProfile(profileId) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ServerSwitcherViewModel(appContainer.vault) }
        }
    }
}
