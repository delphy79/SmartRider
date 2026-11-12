package com.hoon.watchtransit.data

import android.content.Context
import android.location.Location

/**
 * 워치 앱(MainActivity)과 타일(TransitTileService) 간 위치 정보 공유 캐시
 */
object TransitLocationCache {
    private const val PREF_NAME = "transit_location_cache"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_TIME = "time"

    // 기본 위치 유효 시간: 10분 (정류장/역 탐색에는 10분 이내 위치로도 충분히 정확함)
    const val DEFAULT_MAX_AGE_MS = 10 * 60 * 1000L

    fun saveLocation(context: Context, location: Location) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val recordTime = if (location.time > 0) location.time else System.currentTimeMillis()
        sp.edit()
            .putFloat(KEY_LAT, location.latitude.toFloat())
            .putFloat(KEY_LON, location.longitude.toFloat())
            .putLong(KEY_TIME, recordTime)
            .apply()
    }

    /**
     * 캐시된 위치 정보 조회 (기본값: 시간 제한 없이 마지막 저장된 위치 반환)
     */
    fun getCachedLocation(context: Context, maxAgeMillis: Long = Long.MAX_VALUE): Location? {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!sp.contains(KEY_LAT) || !sp.contains(KEY_LON)) return null
        val lat = sp.getFloat(KEY_LAT, 0f).toDouble()
        val lon = sp.getFloat(KEY_LON, 0f).toDouble()
        val time = sp.getLong(KEY_TIME, 0L)
        if (lat == 0.0 && lon == 0.0) return null

        if (maxAgeMillis != Long.MAX_VALUE) {
            val now = System.currentTimeMillis()
            if (now - time > maxAgeMillis) {
                return null // 캐시 만료
            }
        }

        return Location("TransitLocationCache").apply {
            latitude = lat
            longitude = lon
            this.time = time
        }
    }

    /**
     * 현재 캐시된 위치가 지정된 시간(기본 10분) 이내의 신선한 데이터인지 확인
     */
    fun isCacheFresh(context: Context, maxAgeMillis: Long = DEFAULT_MAX_AGE_MS): Boolean {
        return getCachedLocation(context, maxAgeMillis) != null
    }
}
