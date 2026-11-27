package com.papershift.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.papershift.core.RotationSettings
import com.papershift.core.RotationState
import com.papershift.core.RotationStore
import com.papershift.core.ShiftOutcome
import com.papershift.core.ShuffleState
import com.papershift.core.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Stored at files/datastore/papershift.preferences_pb (allowlisted in TC-BING-02).
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "papershift")

/** All app data lives here: settings plus small worker state (PRD §4.1). No image bytes. */
class DataStoreRotationStore(context: Context) : RotationStore {
    private val store = context.applicationContext.dataStore

    override val settings: Flow<RotationSettings> = store.data.map(::readSettings).distinctUntilChanged()
    override val state: Flow<RotationState> = store.data.map(::readState).distinctUntilChanged()

    override suspend fun updateSettings(transform: (RotationSettings) -> RotationSettings): RotationSettings {
        var result = RotationSettings()
        store.edit { prefs ->
            result = transform(readSettings(prefs))
            writeSettings(prefs, result)
        }
        return result
    }

    override suspend fun updateState(transform: (RotationState) -> RotationState): RotationState {
        var result = RotationState()
        store.edit { prefs ->
            result = transform(readState(prefs))
            writeState(prefs, result)
        }
        return result
    }

    private fun readSettings(p: Preferences) = RotationSettings(
        source = p[SOURCE]?.let { runCatching { Source.valueOf(it) }.getOrNull() } ?: Source.Gallery,
        folderUri = p[FOLDER_URI],
        folderName = p[FOLDER_NAME],
        rotationEnabled = p[ROTATION_ENABLED] ?: false,
        pauseOnBatterySaver = p[PAUSE_SAVER] ?: true,
        pauseOnLowBattery = p[PAUSE_LOW] ?: true,
        unmeteredOnly = p[UNMETERED_ONLY] ?: true,
    )

    private fun writeSettings(p: MutablePreferences, s: RotationSettings) {
        p[SOURCE] = s.source.name
        p.putOrRemove(FOLDER_URI, s.folderUri)
        p.putOrRemove(FOLDER_NAME, s.folderName)
        p[ROTATION_ENABLED] = s.rotationEnabled
        p[PAUSE_SAVER] = s.pauseOnBatterySaver
        p[PAUSE_LOW] = s.pauseOnLowBattery
        p[UNMETERED_ONLY] = s.unmeteredOnly
    }

    private fun readState(p: Preferences): RotationState {
        val shuffle = p[SHUFFLE_SEED]?.let { seed ->
            ShuffleState(
                seed = seed,
                position = p[SHUFFLE_POSITION] ?: 0,
                fingerprint = p[SHUFFLE_FINGERPRINT].orEmpty(),
                excludeFirst = p[SHUFFLE_EXCLUDE_FIRST],
                lastAppliedId = p[LAST_APPLIED_ID],
            )
        }
        return RotationState(
            shuffle = shuffle,
            bingAppliedDates = p[BING_DATES]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            currentBingDate = p[BING_CURRENT],
            credit = p[CREDIT],
            creditLink = p[CREDIT_LINK],
            lastOutcome = p[LAST_OUTCOME]?.let { runCatching { ShiftOutcome.valueOf(it) }.getOrNull() },
            lastOutcomeAt = p[LAST_OUTCOME_AT] ?: 0L,
        )
    }

    private fun writeState(p: MutablePreferences, s: RotationState) {
        val shuffle = s.shuffle
        if (shuffle == null) {
            listOf(SHUFFLE_SEED, SHUFFLE_POSITION, SHUFFLE_FINGERPRINT, SHUFFLE_EXCLUDE_FIRST, LAST_APPLIED_ID).forEach { p.remove(it) }
        } else {
            p[SHUFFLE_SEED] = shuffle.seed
            p[SHUFFLE_POSITION] = shuffle.position
            p[SHUFFLE_FINGERPRINT] = shuffle.fingerprint
            p.putOrRemove(SHUFFLE_EXCLUDE_FIRST, shuffle.excludeFirst)
            p.putOrRemove(LAST_APPLIED_ID, shuffle.lastAppliedId)
        }
        p[BING_DATES] = s.bingAppliedDates.joinToString(",")
        p.putOrRemove(BING_CURRENT, s.currentBingDate)
        p.putOrRemove(CREDIT, s.credit)
        p.putOrRemove(CREDIT_LINK, s.creditLink)
        p.putOrRemove(LAST_OUTCOME, s.lastOutcome?.name)
        p[LAST_OUTCOME_AT] = s.lastOutcomeAt
    }

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        val SOURCE = stringPreferencesKey("source")
        val FOLDER_URI = stringPreferencesKey("folder_uri")
        val FOLDER_NAME = stringPreferencesKey("folder_name")
        val ROTATION_ENABLED = booleanPreferencesKey("rotation_enabled")
        val PAUSE_SAVER = booleanPreferencesKey("pause_battery_saver")
        val PAUSE_LOW = booleanPreferencesKey("pause_low_battery")
        val UNMETERED_ONLY = booleanPreferencesKey("unmetered_only")

        val SHUFFLE_SEED = longPreferencesKey("shuffle_seed")
        val SHUFFLE_POSITION = intPreferencesKey("shuffle_position")
        val SHUFFLE_FINGERPRINT = stringPreferencesKey("shuffle_fingerprint")
        val SHUFFLE_EXCLUDE_FIRST = stringPreferencesKey("shuffle_exclude_first")
        val LAST_APPLIED_ID = stringPreferencesKey("last_applied_id")
        val BING_DATES = stringPreferencesKey("bing_applied_dates")
        val BING_CURRENT = stringPreferencesKey("bing_current_date")
        val CREDIT = stringPreferencesKey("credit")
        val CREDIT_LINK = stringPreferencesKey("credit_link")
        val LAST_OUTCOME = stringPreferencesKey("last_outcome")
        val LAST_OUTCOME_AT = longPreferencesKey("last_outcome_at")
    }
}
