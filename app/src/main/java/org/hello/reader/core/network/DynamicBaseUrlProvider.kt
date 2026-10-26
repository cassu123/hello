package org.hello.reader.core.network

import org.hello.reader.core.model.ServerProfile
import org.hello.reader.core.security.SecurityVault

/**
 * Supplies the currently active server profile to the network layer.
 * Implementations must be thread-safe and non-blocking: this is read on
 * OkHttp dispatcher threads for every request.
 */
fun interface DynamicBaseUrlProvider {
    fun getActiveProfile(): ServerProfile?
}

/** Reads the active profile straight from the vault's in-memory [kotlinx.coroutines.flow.StateFlow]. */
class VaultBaseUrlProvider(private val vault: SecurityVault) : DynamicBaseUrlProvider {
    override fun getActiveProfile(): ServerProfile? = vault.activeProfile.value
}
