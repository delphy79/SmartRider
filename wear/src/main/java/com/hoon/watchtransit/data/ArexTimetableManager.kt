package com.hoon.watchtransit.data

import android.content.Context
import android.util.Log
import com.hoon.watchtransit.model.ArrivalInfo
import com.hoon.watchtransit.model.TransitTarget
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 공항철도(AREX) 공식 열차운행시각표 CSV 관리 및 도착 예정 시각 계산기
 */
object ArexTimetableManager {
    private const val TAG = "ArexTimetable"

    private val timetableCache = ConcurrentHashMap<String, TimetableData>()

    private val AREX_STATIONS = setOf(
        "인천공항2터미널", "인천공항1터미널", "공항화물청사", "운서", "영종",
        "청라국제도시", "검암", "계양", "김포공항", "마곡나루",
        "디지털미디어시티", "홍대입구", "공덕", "서울"
    )

    // 직통열차(A1xxx)가 실제로 승하차 정차하는 역
    private val EXPRESS_STOP_STATIONS = setOf(
        "인천공항2터미널", "인천공항1터미널", "서울"
    )

    private val STATION_ALIASES = mapOf(
        "서울역" to "서울",
        "서울" to "서울",
        "인천공항" to "인천공항1터미널",
        "인천국제공항" to "인천공항1터미널",
        "인천공항1터미널" to "인천공항1터미널",
        "인천공항2터미널" to "인천공항2터미널",
        "청라" to "청라국제도시",
        "청라국제도시" to "청라국제도시",
        "DMC" to "디지털미디어시티",
        "디지털미디어시티" to "디지털미디어시티",
        "홍대" to "홍대입구",
        "홍대입구" to "홍대입구",
        "공덕" to "공덕",
        "마곡나루" to "마곡나루",
        "김포공항" to "김포공항",
        "계양" to "계양",
        "검암" to "검암",
        "영종" to "영종",
        "운서" to "운서",
        "공항화물청사" to "공항화물청사"
    )

    data class TrainScheduleRow(
        val startStation: String,
        val endStation: String,
        val trainNo: String,
        val stopTimes: Map<String, String> // canonicalStationName -> "HH:mm:ss"
    )

    data class TimetableData(
        val stations: List<String>,
        val rows: List<TrainScheduleRow>
    )

    fun isArexStation(stationName: String): Boolean {
        val clean = canonicalStationName(stationName)
        return AREX_STATIONS.contains(clean)
    }

    fun isArex(target: TransitTarget): Boolean {
        if (target.subCode.contains("공항철도") || target.subCode.contains("공항")) return true
        val clean = canonicalStationName(target.name)
        // 공항철도 전용 역이거나 타 호선 미지정 공항철도 역
        val arexOnlyStations = setOf(
            "청라국제도시", "영종", "운서", "공항화물청사", "인천공항1터미널", "인천공항2터미널"
        )
        return arexOnlyStations.contains(clean)
    }

    fun canonicalStationName(rawName: String): String {
        val clean = rawName.removeSuffix("역").trim()
        return STATION_ALIASES[clean] ?: clean
    }

    /**
     * 특정 공항철도 역의 다음 도착 열차 목록 (시간순 상위 2개) 반환
     */
    fun getArrivals(context: Context, target: TransitTarget): List<ArrivalInfo> {
        val lineName = "공항철도"
        val targetStation = canonicalStationName(target.name)
        val filter = target.directionOrRoute.trim()

        val now = LocalTime.now()
        val today = LocalDate.now()
        val isHoliday = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY

        val upFile = if (isHoliday) "arex_holiday_up.csv" else "arex_weekday_up.csv"
        val downFile = if (isHoliday) "arex_holiday_down.csv" else "arex_weekday_down.csv"

        val upData = loadTimetable(context, upFile)
        val downData = loadTimetable(context, downFile)

        val nowSec = toDaySeconds(now)
        val candidatePairs = mutableListOf<Pair<Int, ArrivalInfo>>()

        // 상행(서울 방면) vs 하행(인천공항 방면) 필터 판단
        val isUpFilter = filter.contains("상행") ||
                filter.contains("서울") || filter.contains("홍대") ||
                filter.contains("공덕") || filter.contains("DMC") || filter.contains("디지털미디어시티")
        val isDownFilter = filter.contains("하행") ||
                filter.contains("공항") || filter.contains("인천") ||
                filter.contains("검암") || filter.contains("운서") || filter.contains("청라")

        // 시발/종착역인 서울역에서는 인천공항 방면(downData)만 출발하고, 인천공항2터미널역에서는 서울 방면(upData)만 출발함
        val checkUp = when {
            targetStation == "서울" -> false
            targetStation == "인천공항2터미널" -> true
            filter.isEmpty() || filter == "양방향" -> true
            isUpFilter -> true
            isDownFilter -> false
            else -> true
        }

        val checkDown = when {
            targetStation == "서울" -> true
            targetStation == "인천공항2터미널" -> false
            filter.isEmpty() || filter == "양방향" -> true
            isDownFilter -> true
            isUpFilter -> false
            else -> true
        }

        val effectiveFilter = if (targetStation == "서울" || targetStation == "인천공항2터미널") "" else filter

        if (checkUp && upData != null) {
            candidatePairs.addAll(findUpcomingTrains(upData, targetStation, "상행", lineName, nowSec, effectiveFilter))
        }
        if (checkDown && downData != null) {
            candidatePairs.addAll(findUpcomingTrains(downData, targetStation, "하행", lineName, nowSec, effectiveFilter))
        }

        val sorted = candidatePairs.sortedBy { it.first }
        if (sorted.isNotEmpty()) {
            return sorted.take(2).map { it.second }
        }

        // 당일 남은 열차가 없는 경우 첫차 안내
        val firstTrain = findFirstTrain(upData, downData, targetStation, lineName, checkUp, checkDown)
        if (firstTrain != null) {
            return listOf(firstTrain)
        }

        return listOf(
            ArrivalInfo(
                lineOrBusName = lineName,
                destination = "운행 종료",
                arrivalTime = "운행 종료 [시간표]",
                isSoon = false,
                remainSeconds = 0
            )
        )
    }

    private fun findUpcomingTrains(
        data: TimetableData,
        station: String,
        directionName: String,
        lineName: String,
        nowSec: Int,
        filter: String
    ): List<Pair<Int, ArrivalInfo>> {
        val results = mutableListOf<Pair<Int, ArrivalInfo>>()

        for (row in data.rows) {
            // 당역 종착 열차는 탑승 대상이 아니므로 제외
            if (canonicalStationName(row.endStation) == station) {
                continue
            }

            val isExpress = row.trainNo.startsWith("A1")
            // 직통열차(A1xxx)는 서울역, 인천공항1터미널, 인천공항2터미널 외의 중간역은 무정차 통과하므로 제외
            if (isExpress && !EXPRESS_STOP_STATIONS.contains(station)) {
                continue
            }

            val tStr = row.stopTimes[station] ?: continue
            val tSec = parseTimeToDaySeconds(tStr) ?: continue

            if (tSec < nowSec) continue // 이미 지난 열차

            val baseDest = if (row.endStation.isNotEmpty()) "${row.endStation}행" else "${directionName}방면"
            val destTitle = if (isExpress) "$baseDest (직통)" else baseDest

            // 필터링 적용
            if (filter.isNotEmpty() && filter != "양방향") {
                val matchesDir = filter.contains(directionName)
                val matchesDest = destTitle.contains(filter) || filter.contains(row.endStation)
                if (!matchesDir && !matchesDest) {
                    continue
                }
            }

            val diffSeconds = tSec - nowSec
            val formattedTime = formatToHhMmSs(tStr)
            val detailText = calculateTrainPositionDetail(row, station, tSec, nowSec)

            results.add(
                Pair(
                    diffSeconds,
                    ArrivalInfo(
                        lineOrBusName = lineName,
                        destination = destTitle,
                        arrivalTime = formattedTime,
                        isSoon = diffSeconds in 0..120,
                        remainSeconds = diffSeconds,
                        statusDetail = detailText
                    )
                )
            )
        }

        return results
    }

    /**
     * 현재 시각(nowSec)과 시간표상의 다른 역 도착/출발 시각들을 비교하여
     * 해당 열차가 현재 몇 번째 전역(어느 역)에 있는지 계산
     * 단, 조회 역이 출발역이거나 아직 출발역 출발 시각 전인 경우 "출발 대기 (출발역명)" 표출
     */
    private fun calculateTrainPositionDetail(
        row: TrainScheduleRow,
        targetStation: String,
        targetSec: Int,
        nowSec: Int
    ): String {
        if (canonicalStationName(row.startStation) == targetStation) {
            return if (targetSec - nowSec <= 60) "당역 도착 ($targetStation)" else "출발 대기 ($targetStation)"
        }

        val routeStops = row.stopTimes.mapNotNull { (stn, timeStr) ->
            val sec = parseTimeToDaySeconds(timeStr) ?: return@mapNotNull null
            if (sec <= targetSec) Pair(stn, sec) else null
        }.sortedBy { it.second }

        val targetIdx = routeStops.indexOfLast { it.first == targetStation }
        if (targetIdx <= 0) {
            return if (targetSec - nowSec <= 60) "당역 도착 ($targetStation)" else "출발 대기 ($targetStation)"
        }

        if (targetSec - nowSec <= 20) {
            return "당역 진입 ($targetStation)"
        }

        // 아직 출발역에서 출발하기 전인 경우
        if (nowSec < routeStops[0].second) {
            val startStn = routeStops[0].first
            return "출발 대기 ($startStn)"
        }

        var currentIdx = 0
        for (i in 0 until targetIdx) {
            if (routeStops[i].second <= nowSec) {
                currentIdx = i
            } else {
                break
            }
        }
        val hops = targetIdx - currentIdx
        val currentStn = routeStops[currentIdx].first
        return "[$hops]번째 전역 ($currentStn)"
    }

    private fun findFirstTrain(
        upData: TimetableData?,
        downData: TimetableData?,
        station: String,
        lineName: String,
        checkUp: Boolean,
        checkDown: Boolean
    ): ArrivalInfo? {
        val allRows = mutableListOf<Pair<String, TrainScheduleRow>>()
        if (checkUp && upData != null) {
            upData.rows.forEach { allRows.add(Pair("상행", it)) }
        }
        if (checkDown && downData != null) {
            downData.rows.forEach { allRows.add(Pair("하행", it)) }
        }

        var earliestTimeSec = Int.MAX_VALUE
        var earliestInfo: ArrivalInfo? = null

        for ((dir, row) in allRows) {
            if (canonicalStationName(row.endStation) == station) {
                continue
            }
            val isExpress = row.trainNo.startsWith("A1")
            if (isExpress && !EXPRESS_STOP_STATIONS.contains(station)) {
                continue
            }

            val tStr = row.stopTimes[station] ?: continue
            val tSec = parseTimeToDaySeconds(tStr) ?: continue
            if (tSec < earliestTimeSec) {
                earliestTimeSec = tSec
                val baseDest = if (row.endStation.isNotEmpty()) "${row.endStation}행" else "${dir}방면"
                val destTitle = if (isExpress) "$baseDest (직통)" else baseDest
                val formattedTime = formatToHhMmSs(tStr)
                earliestInfo = ArrivalInfo(
                    lineOrBusName = lineName,
                    destination = destTitle,
                    arrivalTime = "첫차 $formattedTime [시간표]",
                    isSoon = false,
                    remainSeconds = 0
                )
            }
        }
        return earliestInfo
    }

    private fun formatToHhMmSs(timeStr: String): String {
        val parts = timeStr.trim().split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val s = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun loadTimetable(context: Context, filename: String): TimetableData? {
        timetableCache[filename]?.let { return it }

        return runCatching {
            val assetPath = "timetable/$filename"
            context.assets.open(assetPath).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    val lines = reader.readLines().filter { it.isNotBlank() }
                    if (lines.isEmpty()) return@use null

                    val headerCols = lines[0].split(",").map { it.trim() }
                    val stationCols = mutableListOf<Pair<Int, String>>()
                    for (i in 3 until headerCols.size) {
                        val colName = headerCols[i]
                        if (colName.isNotEmpty()) {
                            stationCols.add(Pair(i, canonicalStationName(colName)))
                        }
                    }

                    val rows = mutableListOf<TrainScheduleRow>()
                    for (lineIdx in 1 until lines.size) {
                        val parts = lines[lineIdx].split(",").map { it.trim() }
                        if (parts.size < 3) continue
                        val start = parts[0]
                        val end = parts[1]
                        val trainNo = parts[2]

                        val stopTimes = mutableMapOf<String, String>()
                        for ((colIdx, stationName) in stationCols) {
                            if (colIdx < parts.size) {
                                val timeVal = parts[colIdx]
                                if (timeVal.isNotEmpty() && timeVal.contains(":")) {
                                    stopTimes[stationName] = timeVal
                                }
                            }
                        }
                        rows.add(TrainScheduleRow(start, end, trainNo, stopTimes))
                    }

                    val data = TimetableData(stationCols.map { it.second }, rows)
                    timetableCache[filename] = data
                    Log.d(TAG, "Loaded timetable: $filename (${rows.size} trains, ${stationCols.size} stations)")
                    data
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to load timetable $filename: ${e.message}", e)
        }.getOrNull()
    }

    private fun toDaySeconds(time: LocalTime): Int {
        var h = time.hour
        if (h < 4) {
            h += 24
        }
        return h * 3600 + time.minute * 60 + time.second
    }

    private fun parseTimeToDaySeconds(timeStr: String): Int? {
        val parts = timeStr.trim().split(":")
        if (parts.size < 2) return null
        var h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
        if (h < 4) {
            h += 24
        }
        return h * 3600 + m * 60 + s
    }
}
