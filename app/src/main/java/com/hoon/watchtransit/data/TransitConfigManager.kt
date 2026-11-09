package com.hoon.watchtransit.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hoon.watchtransit.model.TransitCity
import com.hoon.watchtransit.model.TransitTarget
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "transit_settings")

class TransitConfigManager(private val context: Context) {

    private val TARGETS_KEY = stringPreferencesKey("saved_targets_json")
    private val CITIES_KEY = stringPreferencesKey("cached_city_codes_json")
    private val SELECTED_CITY_CODE_KEY = intPreferencesKey("selected_city_code")

    private val dataClient = Wearable.getDataClient(context)

    // 1. 스마트폰 화면에 뿌려줄 저장된 목록 Flow
    val savedTargetsFlow: Flow<List<TransitTarget>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[TARGETS_KEY] ?: "[]"
        runCatching { Json.decodeFromString<List<TransitTarget>>(jsonStr) }
            .getOrDefault(emptyList())
    }

    // 2. 로컬에 저장된 도시코드 목록 Flow
    val cachedCitiesFlow: Flow<List<TransitCity>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[CITIES_KEY] ?: ""
        if (jsonStr.isEmpty()) emptyList()
        else runCatching { Json.decodeFromString<List<TransitCity>>(jsonStr) }.getOrDefault(emptyList())
    }

    // 3. 선택된 도시 코드 Flow
    val selectedCityCodeFlow: Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[SELECTED_CITY_CODE_KEY]
    }

    suspend fun getCachedCities(): List<TransitCity> {
        return cachedCitiesFlow.first()
    }

    suspend fun saveCities(cities: List<TransitCity>) {
        context.dataStore.edit { prefs ->
            prefs[CITIES_KEY] = Json.encodeToString(cities)
        }
    }

    suspend fun saveSelectedCityCode(cityCode: Int) {
        context.dataStore.edit { prefs ->
            prefs[SELECTED_CITY_CODE_KEY] = cityCode
        }
    }

    // 4. 새로운 역/정류장 목록 저장 및 워치 동기화
    suspend fun saveAndSyncTargets(targets: List<TransitTarget>): Result<Unit> {
        return runCatching {
            val jsonStr = Json.encodeToString(targets)

            // 스마트폰 로컬 저장
            context.dataStore.edit { prefs ->
                prefs[TARGETS_KEY] = jsonStr
            }

            // 갤럭시 워치로 즉시 전송 (Data Layer API)
            val putDataReq = PutDataMapRequest.create("/transit_config").apply {
                dataMap.putString("targets_json", jsonStr)
                dataMap.putLong("timestamp", System.currentTimeMillis()) // 변경 감지 강제 트리거
            }.asPutDataRequest().setUrgent()

            dataClient.putDataItem(putDataReq).await()
        }
    }
}
