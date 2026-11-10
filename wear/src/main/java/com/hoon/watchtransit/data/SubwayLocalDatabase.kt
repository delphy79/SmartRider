package com.hoon.watchtransit.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.hoon.watchtransit.network.SubwayTimetableItem
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 지하철 노선별 역간 소요시간 및 전체 노선/역 시간표를 관리하는 내부 SQLite 데이터베이스 매니저
 * - assets/database/subway_transit.db 초기 시드 DB를 앱 내부 DB 경로(databases/subway_transit.db)로 복사하여 사용
 * - 서울 1~9호선(코레일 직결·연장 전 구간 포함), 인천 1·2호선, 공항철도, 수인분당선, 경의중앙선,
 *   경춘선(ITX-청춘 포함), 서해선 및 향후 추가될 모든 노선/역 시간표를 통합 수용
 */
object SubwayLocalDatabase {
    private const val TAG = "SubwayLocalDB"
    private const val DB_NAME = "subway_transit.db"
    private const val ASSET_DB_PATH = "database/$DB_NAME"
    private const val DB_VERSION = 3

    data class TravelTimeEntity(
        val seq: Int,
        val lineCode: String,
        val stationName: String,
        val travelSeconds: Int,
        val distanceKm: Double = 0.0,
        val totalDistanceKm: Double = 0.0
    )

    data class GraphEdgeEntity(
        val lineCode: String,
        val fromStation: String,
        val toStation: String,
        val travelSeconds: Int
    )

    data class TrainStopEntity(
        val stationName: String,
        val stopSeq: Int,
        val arrivalTime: String,
        val daySeconds: Int,
        val isStopping: Boolean
    )

    data class TrainScheduleEntity(
        val trainId: Long,
        val lineCode: String,
        val lineName: String,
        val dayType: String,
        val direction: String,
        val trainNo: String,
        val startStation: String,
        val endStation: String,
        val isExpress: Boolean,
        val stops: List<TrainStopEntity>
    ) {
        // 정차역 시각 맵 (종착역 하차 전용 정차를 제외한 승차 가능 정차역 기준)
        val boardingStopTimes: Map<String, String> by lazy {
            val boardable = if (stops.size > 1) stops.subList(0, stops.size - 1) else stops
            boardable.filter { it.isStopping }.associate { it.stationName to it.arrivalTime }
        }
        // 통과 시각 포함 전체 경로 역 시각 맵 (현재 위치 추적용)
        val allRouteStopTimes: Map<String, String> by lazy {
            stops.associate { it.stationName to it.arrivalTime }
        }
    }

    @Volatile
    private var database: SQLiteDatabase? = null

    // (lineCode|dayType|direction) -> List<TrainScheduleEntity> 메모리 캐시
    private val scheduleCache = ConcurrentHashMap<String, List<TrainScheduleEntity>>()

    fun getDatabase(context: Context): SQLiteDatabase {
        database?.takeIf { it.isOpen }?.let { return it }
        synchronized(this) {
            database?.takeIf { it.isOpen }?.let { return it }

            val appContext = context.applicationContext
            val dbFile = appContext.getDatabasePath(DB_NAME)
            ensureDatabaseFile(appContext, dbFile)

            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
            )
            ensureSchema(db)
            database = db
            return db
        }
    }

    private fun ensureDatabaseFile(context: Context, dbFile: File) {
        var needsCopy = !dbFile.exists() || dbFile.length() == 0L
        if (!needsCopy) {
            runCatching {
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { existingDb ->
                    val currentVer = existingDb.version
                    val travelCnt = existingDb.rawQuery("SELECT COUNT(*) FROM subway_travel_times", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    }
                    if (currentVer < DB_VERSION || travelCnt == 0) {
                        needsCopy = true
                    }
                }
            }.onFailure {
                needsCopy = true
            }
        }

        if (needsCopy) {
            runCatching {
                dbFile.parentFile?.mkdirs()
                context.assets.open(ASSET_DB_PATH).use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                scheduleCache.clear()
                Log.d(TAG, "Copied seed database (v$DB_VERSION) from assets to ${dbFile.absolutePath}")
            }.onFailure { e ->
                Log.e(TAG, "Failed to copy seed database from assets: ${e.message}", e)
            }
        }
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS subway_travel_times (
                seq INTEGER PRIMARY KEY,
                line_code TEXT NOT NULL,
                station_name TEXT NOT NULL,
                travel_seconds INTEGER NOT NULL,
                distance_km REAL NOT NULL DEFAULT 0,
                total_distance_km REAL NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_travel_times_line ON subway_travel_times(line_code, seq)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS subway_graph_edges (
                line_code TEXT NOT NULL,
                from_station TEXT NOT NULL,
                to_station TEXT NOT NULL,
                travel_seconds INTEGER NOT NULL,
                PRIMARY KEY (line_code, from_station, to_station)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS timetable_trains (
                train_id INTEGER PRIMARY KEY AUTOINCREMENT,
                line_code TEXT NOT NULL,
                line_name TEXT NOT NULL,
                day_type TEXT NOT NULL,
                direction TEXT NOT NULL,
                train_no TEXT NOT NULL,
                start_station TEXT NOT NULL,
                end_station TEXT NOT NULL,
                is_express INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trains_lookup ON timetable_trains(line_code, day_type, direction)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS timetable_stops (
                train_id INTEGER NOT NULL,
                stop_seq INTEGER NOT NULL,
                station_name TEXT NOT NULL,
                arrival_time TEXT NOT NULL,
                day_seconds INTEGER NOT NULL,
                is_stopping INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (train_id, stop_seq)
            ) WITHOUT ROWID
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS station_static_timetables (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                station_cd TEXT NOT NULL,
                station_name TEXT NOT NULL,
                line_code TEXT NOT NULL,
                day_type TEXT NOT NULL,
                inout_tag TEXT NOT NULL,
                left_time TEXT NOT NULL,
                arrive_time TEXT NOT NULL,
                day_seconds INTEGER NOT NULL,
                start_station TEXT NOT NULL,
                end_station TEXT NOT NULL,
                express_yn TEXT NOT NULL DEFAULT 'G'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_static_tt_cd ON station_static_timetables(station_cd, day_type, inout_tag, day_seconds)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_static_tt_stn ON station_static_timetables(line_code, station_name, day_type, inout_tag, day_seconds)")

        if (db.version < DB_VERSION) {
            db.version = DB_VERSION
        }
    }

    /**
     * 역간 소요시간 순차 데이터 조회
     */
    fun getTravelTimeRows(context: Context): List<TravelTimeEntity> = runCatching {
        val db = getDatabase(context)
        val result = mutableListOf<TravelTimeEntity>()
        db.rawQuery(
            "SELECT seq, line_code, station_name, travel_seconds, distance_km, total_distance_km FROM subway_travel_times ORDER BY seq ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    TravelTimeEntity(
                        seq = cursor.getInt(0),
                        lineCode = cursor.getString(1),
                        stationName = cursor.getString(2),
                        travelSeconds = cursor.getInt(3),
                        distanceKm = cursor.getDouble(4),
                        totalDistanceKm = cursor.getDouble(5)
                    )
                )
            }
        }
        result
    }.getOrElse { e ->
        Log.e(TAG, "Failed to query subway_travel_times: ${e.message}", e)
        emptyList()
    }

    /**
     * 서울 1~9호선 전 구간 및 코레일·광역전철(수인분당선, 경의중앙선, 경춘선, 서해선 등)의
     * 역간 인접 그래프 간선(edge) 및 소요시간(초) 목록 조회
     */
    fun getGraphEdges(context: Context): List<GraphEdgeEntity> = runCatching {
        val db = getDatabase(context)
        val result = mutableListOf<GraphEdgeEntity>()
        db.rawQuery(
            "SELECT line_code, from_station, to_station, travel_seconds FROM subway_graph_edges",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    GraphEdgeEntity(
                        lineCode = cursor.getString(0),
                        fromStation = cursor.getString(1),
                        toStation = cursor.getString(2),
                        travelSeconds = cursor.getInt(3)
                    )
                )
            }
        }
        result
    }.getOrElse { e ->
        Log.e(TAG, "Failed to query subway_graph_edges: ${e.message}", e)
        emptyList()
    }

    /**
     * 특정 노선(lineCode), 요일(dayType: WEEKDAY/SATURDAY/HOLIDAY), 방향(direction: UP/DOWN)의 열차 편성 및 정차 시각 목록 조회
     * SATURDAY 데이터가 별도로 등록되지 않은 노선은 HOLIDAY 데이터를 자동으로 폴백 조회함
     */
    fun getTrainSchedules(
        context: Context,
        lineCode: String,
        dayType: String,
        direction: String
    ): List<TrainScheduleEntity> {
        val cacheKey = "$lineCode|$dayType|$direction"
        scheduleCache[cacheKey]?.let { return it }

        return runCatching {
            val db = getDatabase(context)
            var list = queryTrainSchedulesInternal(db, lineCode, dayType, direction)
            if (list.isEmpty() && dayType == "SATURDAY") {
                list = queryTrainSchedulesInternal(db, lineCode, "HOLIDAY", direction)
            }
            if (list.isNotEmpty()) {
                scheduleCache[cacheKey] = list
            }
            list
        }.getOrElse { e ->
            Log.e(TAG, "Failed to load train schedules ($cacheKey): ${e.message}", e)
            emptyList()
        }
    }

    private fun queryTrainSchedulesInternal(
        db: SQLiteDatabase,
        lineCode: String,
        dayType: String,
        direction: String
    ): List<TrainScheduleEntity> {
        val trainsMap = LinkedHashMap<Long, MutableTrainBuilder>()

        db.rawQuery(
            """
            SELECT train_id, line_code, line_name, day_type, direction, train_no, start_station, end_station, is_express
            FROM timetable_trains
            WHERE line_code = ? AND day_type = ? AND direction = ?
            ORDER BY train_id ASC
            """.trimIndent(),
            arrayOf(lineCode, dayType, direction)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                trainsMap[id] = MutableTrainBuilder(
                    trainId = id,
                    lineCode = cursor.getString(1),
                    lineName = cursor.getString(2),
                    dayType = cursor.getString(3),
                    direction = cursor.getString(4),
                    trainNo = cursor.getString(5),
                    startStation = cursor.getString(6),
                    endStation = cursor.getString(7),
                    isExpress = cursor.getInt(8) != 0
                )
            }
        }

        if (trainsMap.isEmpty()) return emptyList()

        db.rawQuery(
            """
            SELECT s.train_id, s.station_name, s.stop_seq, s.arrival_time, s.day_seconds, s.is_stopping
            FROM timetable_stops s
            INNER JOIN timetable_trains t ON s.train_id = t.train_id
            WHERE t.line_code = ? AND t.day_type = ? AND t.direction = ?
            ORDER BY s.train_id ASC, s.stop_seq ASC
            """.trimIndent(),
            arrayOf(lineCode, dayType, direction)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val trainId = cursor.getLong(0)
                val builder = trainsMap[trainId] ?: continue
                builder.stops.add(
                    TrainStopEntity(
                        stationName = cursor.getString(1),
                        stopSeq = cursor.getInt(2),
                        arrivalTime = cursor.getString(3),
                        daySeconds = cursor.getInt(4),
                        isStopping = cursor.getInt(5) != 0
                    )
                )
            }
        }

        return trainsMap.values.map { it.build() }
    }

    private class MutableTrainBuilder(
        val trainId: Long,
        val lineCode: String,
        val lineName: String,
        val dayType: String,
        val direction: String,
        val trainNo: String,
        val startStation: String,
        val endStation: String,
        val isExpress: Boolean,
        val stops: MutableList<TrainStopEntity> = mutableListOf()
    ) {
        fun build() = TrainScheduleEntity(
            trainId = trainId,
            lineCode = lineCode,
            lineName = lineName,
            dayType = dayType,
            direction = direction,
            trainNo = trainNo,
            startStation = startStation,
            endStation = endStation,
            isExpress = isExpress,
            stops = stops
        )
    }

    /**
     * 해당 노선(lineCode)의 편성 시간표가 DB(timetable_trains)에 존재하는지 확인
     */
    fun hasLineTimetable(context: Context, lineCode: String): Boolean = runCatching {
        if (lineCode.isBlank()) return false
        val db = getDatabase(context)
        db.rawQuery(
            "SELECT 1 FROM timetable_trains WHERE line_code = ? LIMIT 1",
            arrayOf(lineCode)
        ).use { it.moveToFirst() }
    }.getOrDefault(false)

    /**
     * 정적 역별 시간표(서울 열린데이터광장 API 또는 차후 추가될 타 노선 역별 시간표)를 내부 DB에 저장
     */
    fun saveStationStaticTimetable(
        context: Context,
        stationCd: String,
        stationName: String,
        lineCode: String,
        dayType: String,
        inoutTag: String,
        items: List<SubwayTimetableItem>
    ) {
        if (items.isEmpty()) return
        runCatching {
            val db = getDatabase(context)
            db.beginTransaction()
            try {
                db.delete(
                    "station_static_timetables",
                    "station_cd = ? AND day_type = ? AND inout_tag = ?",
                    arrayOf(stationCd, dayType, inoutTag)
                )
                for (item in items) {
                    val timeStr = item.leftTime.ifEmpty { item.arriveTime }
                    val parts = timeStr.split(":").mapNotNull { it.toIntOrNull() }
                    if (parts.size < 2) continue
                    val daySec = parts[0] * 3600 + parts[1] * 60 + (parts.getOrNull(2) ?: 0)

                    val cv = ContentValues().apply {
                        put("station_cd", stationCd)
                        put("station_name", stationName.ifBlank { item.stationNm.removeSuffix("역").trim() })
                        put("line_code", lineCode.ifBlank { item.lineNum })
                        put("day_type", dayType)
                        put("inout_tag", inoutTag)
                        put("left_time", item.leftTime)
                        put("arrive_time", item.arriveTime)
                        put("day_seconds", daySec)
                        put("start_station", item.subwaySname)
                        put("end_station", item.subwayEname)
                        put("express_yn", item.expressYn)
                    }
                    db.insert("station_static_timetables", null, cv)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to save station static timetable ($stationCd): ${e.message}", e)
        }
    }

    /**
     * 내부 DB에 저장된 역별 정적 시간표 조회 (역 코드 또는 노선+역명 기준)
     */
    fun getStationStaticTimetable(
        context: Context,
        stationCd: String,
        stationName: String,
        lineCode: String,
        dayType: String,
        inoutTag: String
    ): List<SubwayTimetableItem> = runCatching {
        val db = getDatabase(context)
        val result = mutableListOf<SubwayTimetableItem>()
        val cleanStn = stationName.removeSuffix("역").trim()

        val cursor = if (stationCd.isNotBlank()) {
            db.rawQuery(
                """
                SELECT station_cd, station_name, line_code, left_time, arrive_time, start_station, end_station, inout_tag, express_yn
                FROM station_static_timetables
                WHERE (station_cd = ? OR (line_code = ? AND station_name = ?))
                  AND day_type = ? AND inout_tag = ?
                ORDER BY day_seconds ASC
                """.trimIndent(),
                arrayOf(stationCd, lineCode, cleanStn, dayType, inoutTag)
            )
        } else {
            db.rawQuery(
                """
                SELECT station_cd, station_name, line_code, left_time, arrive_time, start_station, end_station, inout_tag, express_yn
                FROM station_static_timetables
                WHERE line_code = ? AND station_name = ? AND day_type = ? AND inout_tag = ?
                ORDER BY day_seconds ASC
                """.trimIndent(),
                arrayOf(lineCode, cleanStn, dayType, inoutTag)
            )
        }

        cursor.use { c ->
            while (c.moveToNext()) {
                result.add(
                    SubwayTimetableItem(
                        stationCd = c.getString(0),
                        stationNm = c.getString(1),
                        lineNum = c.getString(2),
                        leftTime = c.getString(3),
                        arriveTime = c.getString(4),
                        subwaySname = c.getString(5),
                        subwayEname = c.getString(6),
                        inoutTag = c.getString(7),
                        expressYn = c.getString(8)
                    )
                )
            }
        }
        result
    }.getOrElse { e ->
        Log.e(TAG, "Failed to query station static timetable ($stationCd/$stationName): ${e.message}", e)
        emptyList()
    }
}
