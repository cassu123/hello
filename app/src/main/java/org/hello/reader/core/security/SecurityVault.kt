package org.hello.reader.core.security

import android.content.Context
import android.content.SharedPreferences
import org.hello.reader.core.model.ServerProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

interface SecurityVault {
    /** The selected server, or null when no server is configured. Read synchronously by the network layer. */
    val activeProfile: StateFlow<ServerProfile?>

    /** Every stored profile, in insertion order. */
    val profiles: StateFlow<List<ServerProfile>>

    suspend fun getAllProfiles(): List<ServerProfile>

    /**
     * Inserts or replaces (by id) a profile. The saved profile becomes active if
     * it is flagged active, is the first profile, or replaces the active one.
     */
    suspend fun saveProfile(profile: ServerProfile)

    /** @throws IllegalArgumentException if no profile has [profileId]. */
    suspend fun setActiveProfile(profileId: String)

    /**
     * Updates the display name of an existing profile atomically.
     * @throws IllegalArgumentException if no profile has [profileId] or [newName] is blank.
     */
    suspend fun renameProfile(profileId: String, newName: String)

    /** Removes a profile; if it was active, the most recently used remaining profile takes over. */
    suspend fun deleteProfile(profileId: String)

    /** Deactivates all profiles without deleting them, switching the app into local mode. */
    suspend fun clearActiveProfile()
}

/**
 * [SecurityVault] backed by a [SharedPreferences] that the caller supplies
 * already encrypted (see [EncryptedProfileStore.open]). Profiles are kept in
 * memory after the initial load; writes are serialized by a [Mutex] and use
 * `commit()` so a failed disk write surfaces as an [IOException] instead of
 * leaving memory and disk out of sync.
 *
 * Construction reads from [prefs]: create this off the main thread.
 */
class SecurityVaultImpl(
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : SecurityVault {

    /** Production wiring: Keystore-backed `EncryptedSharedPreferences` via [EncryptedProfileStore]. */
    constructor(context: Context) : this(EncryptedProfileStore.open(context))

    // encodeDefaults: ServerProfile's timestamp defaults are evaluated at decode time,
    // so an omitted field would silently be replaced with "now" on the next load.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val writeLock = Mutex()

    private val _profiles = MutableStateFlow(normalize(readFromDisk()))
    override val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow(selectActive(_profiles.value))
    override val activeProfile: StateFlow<ServerProfile?> = _activeProfile.asStateFlow()

    override suspend fun getAllProfiles(): List<ServerProfile> = _profiles.value

    override suspend fun saveProfile(profile: ServerProfile) = mutate { current ->
        val index = current.indexOfFirst { it.id == profile.id }
        val replacesActive = index >= 0 && current[index].isActive
        val makeActive = profile.isActive || current.isEmpty() || replacesActive
        val saved = profile.copy(isActive = makeActive)

        val merged = if (index >= 0) {
            current.toMutableList().apply { this[index] = saved }
        } else {
            current + saved
        }
        if (makeActive) merged.map { it.copy(isActive = it.id == saved.id) } else merged
    }

    override suspend fun setActiveProfile(profileId: String) = mutate { current ->
        require(current.any { it.id == profileId }) { "Unknown server profile: $profileId" }
        val now = clock()
        current.map {
            if (it.id == profileId) it.copy(isActive = true, lastUsedAt = now) else it.copy(isActive = false)
        }
    }

    override suspend fun renameProfile(profileId: String, newName: String) = mutate { current ->
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Profile name must not be blank" }
        require(current.any { it.id == profileId }) { "Unknown server profile: $profileId" }
        current.map {
            if (it.id == profileId) it.copy(name = trimmed) else it
        }
    }

    override suspend fun deleteProfile(profileId: String) = mutate { current ->
        val remaining = current.filter { it.id != profileId }
        if (remaining.isEmpty() || remaining.any { it.isActive }) {
            remaining
        } else {
            val successor = remaining.maxBy { it.lastUsedAt }
            remaining.map { it.copy(isActive = it.id == successor.id) }
        }
    }

    override suspend fun clearActiveProfile() = mutate { current ->
        current.map { it.copy(isActive = false) }
    }

    private suspend fun mutate(transform: (List<ServerProfile>) -> List<ServerProfile>) {
        withContext(ioDispatcher) {
            writeLock.withLock {
                val updated = transform(_profiles.value)
                val written = prefs.edit()
                    .putString(KEY_PROFILES, json.encodeToString(PROFILE_LIST, updated))
                    .commit()
                if (!written) throw IOException("Failed to persist server profiles")
                _profiles.value = updated
                _activeProfile.value = selectActive(updated)
            }
        }
    }

    private fun readFromDisk(): List<ServerProfile> {
        val serialized = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            json.decodeFromString(PROFILE_LIST, serialized)
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            // ServerProfile.init invariants rejected a stored record.
            emptyList()
        }
    }

    /** Repairs a stored list so that exactly one profile is active whenever any exist. */
    private fun normalize(profiles: List<ServerProfile>): List<ServerProfile> {
        if (profiles.isEmpty() || profiles.count { it.isActive } == 1) return profiles
        val keep = (profiles.firstOrNull { it.isActive } ?: profiles.first()).id
        return profiles.map { it.copy(isActive = it.id == keep) }
    }

    private fun selectActive(profiles: List<ServerProfile>): ServerProfile? =
        profiles.firstOrNull { it.isActive }

    private companion object {
        const val KEY_PROFILES = "vault_server_profiles"
        val PROFILE_LIST = ListSerializer(ServerProfile.serializer())
    }
}
