package com.hoon.watchtransit.data

import android.content.Context
import com.hoon.watchtransit.model.ArrivalInfo
import com.hoon.watchtransit.model.TransitTarget
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 인천도시철도(1호선, 2호선) 및 향후 내부 DB(timetable_trains/timetable_stops)에 추가될 지하철 노선의
 * 공식 열차운행시각표를 내부 SQLite DB(SubwayLocalDatabase)에서 조회하여 도착 예정 시각 계산
 */
object IncheonTimetableManager {

    private val STATION_ALIASES = mapOf(
        "석바위" to "석바위",
        "석바위시장" to "석바위",
        "주안국가" to "주안국가",
        "주안국가산단" to "주안국가",
        "서부여성" to "서부여성",
        "서부여성회관" to "서부여성",
        "가정중앙" to "가정중앙",
        "가정중앙시장" to "가정중앙",
        "아시아드" to "아시아드",
        "아시아드경기장" to "아시아드"
    )

    fun isLine2(target: TransitTarget): Boolean {
        val cleanName = target.name.removeSuffix("역").trim()
        return target.subCode.contains("2호선") || isLine2Station(cleanName)
    }

    private fun isLine2Station(stationName: String): Boolean {
        val line2Stations = setOf(
            "검단오류", "왕길", "검단사거리", "마전", "완정", "독정", "검암", "검바위", "아시아드경기장", "아시아드",
            "서구청", "가정", "가정중앙시장", "가정중앙", "석남", "서부여성회관", "서부여성", "인천가좌", "가재울", "주안국가산단", "주안국가",
            "주안", "시민공원", "석바위시장", "석바위", "인천시청", "석천사거리", "모래내시장", "만수", "남동구청", "인천대공원", "운연"
        )
        return line2Stations.contains(stationName)
    }

    private fun canonicalStationName(rawName: String): String {
        val clean = rawName.removeSuffix("역").trim()
        return STATION_ALIASES[clean] ?: clean
    }

    /**
     * 인천 1·2호선 특정 역의 다음 도착 열차 목록 (시간순 상위 2개) 반환
     */
    fun getArrivals(context: Context, target: TransitTarget): List<ArrivalInfo> {
        val isL2 = isLine2(target)
        val lineName = if (isL2) "인천2호선" else "인천1호선"
        val lineCode = if (isL2) "I2" else "I1"
        return getArrivalsByLineCode(context, lineCode, lineName, target)
    }

    /**
     * 내부 DB(timetable_trains / timetable_stops)에 수록된 임의의 노선(lineCode)에 대해
     * 특정 역의 다음 도착 열차 목록 (시간순 상위 2개) 반환 (차후 추가될 타 지하철 노선 공용)
     */
    fun getArrivalsByLineCode(
        context: Context,
        lineCode: String,
        lineName: String,
        target: TransitTarget
    ): List<ArrivalInfo> {
        val targetStation = canonicalStationName(target.name)
        val filter = target.directionOrRoute.trim()

        val now = LocalTime.now()
        val today = LocalDate.now()
        val dayType = when (today.dayOfWeek) {
            DayOfWeek.SATURDAY -> "SATURDAY"
            DayOfWeek.SUNDAY -> "HOLIDAY"
            else -> "WEEKDAY"
        }

        val upRows = SubwayLocalDatabase.getTrainSchedules(context, lineCode, dayType, "UP")
        val downRows = SubwayLocalDatabase.getTrainSchedules(context, lineCode, dayType, "DOWN")

        if (upRows.isEmpty() && downRows.isEmpty()) {
            return emptyList()
        }

        val nowSec = toDaySeconds(now)
        val candidatePairs = mutableListOf<Pair<Int, ArrivalInfo>>()

        // 상행 조회 여부 판단
        val isUpFilter = filter.contains("상행") || filter.contains("내선") ||
                (lineCode == "I1" && (filter.contains("검단") || filter.contains("계양"))) ||
                (lineCode == "I2" && (filter.contains("검단오류") || filter.contains("서구청") || filter.contains("아시아드")))
        val isDownFilter = filter.contains("하행") || filter.contains("외선") ||
                (lineCode == "I1" && (filter.contains("송도") || filter.contains("동막") || filter.contains("신연수") || filter.contains("예술회관"))) ||
                (lineCode == "I2" && (filter.contains("운연") || filter.contains("인천대공원") || filter.contains("주안")))

        val isNorthTerminal = targetStation == "검단호수공원" || targetStation == "검단오류"
        val isSouthTerminal = targetStation == "송도달빛축제공원" || targetStation == "운연"

        val checkUp = when {
            isNorthTerminal -> false
            isSouthTerminal -> true
            filter.isEmpty() || filter == "양방향" -> true
            isUpFilter -> true
            isDownFilter -> false
            else -> true
        }

        val checkDown = when {
            isNorthTerminal -> true
            isSouthTerminal -> false
            filter.isEmpty() || filter == "양방향" -> true
            isDownFilter -> true
            isUpFilter -> false
            else -> true
        }

        val effectiveFilter = if (isNorthTerminal || isSouthTerminal) "" else filter

        if (checkUp && upRows.isNotEmpty()) {
            candidatePairs.addAll(findUpcomingTrains(upRows, targetStation, "상행", lineName, nowSec, effectiveFilter))
        }
        if (checkDown && downRows.isNotEmpty()) {
            candidatePairs.addAll(findUpcomingTrains(downRows, targetStation, "하행", lineName, nowSec, effectiveFilter))
        }

        val sorted = candidatePairs.sortedBy { it.first }
        if (sorted.isNotEmpty()) {
            return sorted.take(2).map { it.second }
        }

        // 당일 남은 열차가 없는 경우 (운행 종료 또는 익일 첫차)
        val firstTrain = findFirstTrain(upRows, downRows, targetStation, lineName, checkUp, checkDown)
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
        rows: List<SubwayLocalDatabase.TrainScheduleEntity>,
        station: String,
        directionName: String,
        lineName: String,
        nowSec: Int,
        filter: String
    ): List<Pair<Int, ArrivalInfo>> {
        val results = mutableListOf<Pair<Int, ArrivalInfo>>()

        for (row in rows) {
            // 당역 종착 열차는 탑승 대상이 아니므로 제외
            if (canonicalStationName(row.endStation) == station) {
                continue
            }

            val tStr = row.boardingStopTimes[station] ?: continue
            val tSec = parseTimeToDaySeconds(tStr) ?: continue

            if (tSec < nowSec) continue // 이미 지난 열차

            val expressTag = if (row.isExpress) " (급행)" else ""
            val destTitle = if (row.endStation.isNotEmpty()) "${row.endStation}행$expressTag" else "${directionName}방면$expressTag"

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
        row: SubwayLocalDatabase.TrainScheduleEntity,
        targetStation: String,
        targetSec: Int,
        nowSec: Int
    ): String {
        if (canonicalStationName(row.startStation) == targetStation) {
            return if (targetSec - nowSec <= 60) "당역 도착 ($targetStation)" else "출발 대기 ($targetStation)"
        }

        val routeStops = row.allRouteStopTimes.mapNotNull { (stn, timeStr) ->
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

        // 출발역 이후 ~ 도착역 이전: 현재 시각 이하의 가장 최근 정차역 탐색
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
        upRows: List<SubwayLocalDatabase.TrainScheduleEntity>,
        downRows: List<SubwayLocalDatabase.TrainScheduleEntity>,
        station: String,
        lineName: String,
        checkUp: Boolean,
        checkDown: Boolean
    ): ArrivalInfo? {
        val allRows = mutableListOf<Pair<String, SubwayLocalDatabase.TrainScheduleEntity>>()
        if (checkUp) {
            upRows.forEach { allRows.add(Pair("상행", it)) }
        }
        if (checkDown) {
            downRows.forEach { allRows.add(Pair("하행", it)) }
        }

        var earliestTimeSec = Int.MAX_VALUE
        var earliestInfo: ArrivalInfo? = null

        for ((dir, row) in allRows) {
            if (canonicalStationName(row.endStation) == station) {
                continue
            }
            val tStr = row.boardingStopTimes[station] ?: continue
            val tSec = parseTimeToDaySeconds(tStr) ?: continue
            if (tSec < earliestTimeSec) {
                earliestTimeSec = tSec
                val expressTag = if (row.isExpress) " (급행)" else ""
                val destTitle = if (row.endStation.isNotEmpty()) "${row.endStation}행$expressTag" else "${dir}방면$expressTag"
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

    /**
     * 하루 기준 04:00:00 ~ 익일 03:59:59를 연속적인 초 단위로 환산
     */
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
