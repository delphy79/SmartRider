package com.hoon.watchtransit.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hoon.watchtransit.model.TransitTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

val Context.watchDataStore by preferencesDataStore(name = "watch_transit_settings")

class TransitWatchStorage(private val context: Context) {
    private val TARGETS_KEY = stringPreferencesKey("saved_targets_json")
    private val json = Json { ignoreUnknownKeys = true }

    val savedTargetsFlow: Flow<List<TransitTarget>> = context.watchDataStore.data.map { prefs ->
        val jsonStr = prefs[TARGETS_KEY] ?: "[]"
        runCatching { json.decodeFromString<List<TransitTarget>>(jsonStr) }.getOrDefault(emptyList())
    }

    suspend fun saveTargetsJson(jsonStr: String) {
        context.watchDataStore.edit { prefs ->
            prefs[TARGETS_KEY] = jsonStr
        }
    }
}