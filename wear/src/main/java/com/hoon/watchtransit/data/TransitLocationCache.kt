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

    fun saveLocation(context: Context, location: Location) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putFloat(KEY_LAT, location.latitude.toFloat())
            .putFloat(KEY_LON, location.longitude.toFloat())
            .putLong(KEY_TIME, location.time)
            .apply()
    }

    fun getCachedLocation(context: Context): Location? {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!sp.contains(KEY_LAT) || !sp.contains(KEY_LON)) return null
        val lat = sp.getFloat(KEY_LAT, 0f).toDouble()
        val lon = sp.getFloat(KEY_LON, 0f).toDouble()
        val time = sp.getLong(KEY_TIME, 0L)
        if (lat == 0.0 && lon == 0.0) return null

        return Location("TransitLocationCache").apply {
            latitude = lat
            longitude = lon
            this.time = time
        }
    }
}
