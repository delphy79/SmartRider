package com.hoon.watchtransit.service

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.hoon.watchtransit.data.TransitWatchStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TransitDataListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var storage: TransitWatchStorage

    override fun onCreate() {
        super.onCreate()
        storage = TransitWatchStorage(applicationContext)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/transit_config") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val targetsJson = dataMap.getString("targets_json") ?: "[]"
                Log.d("WatchTransitService", "스마트폰으로부터 설정값 수신 완료: $targetsJson")

                scope.launch {
                    storage.saveTargetsJson(targetsJson)
                }
            }
        }
    }
}