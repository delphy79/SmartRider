package com.hoon.watchtransit.data

import android.content.Context
import com.hoon.watchtransit.model.ArrivalInfo
import com.hoon.watchtransit.model.TransitTarget
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 인천도시철도(1호선, 2호선) 및 내부 DB(timetable_trains/timetable_stops)에 수록된 지하철 노선
 * (서울 1~9호선 전 구간, 수인분당선, 경의중앙선, 경춘선/ITX-청춘, 서해선 등)의
 * 공식 열차운행시각표를 내부 SQLite DB(SubwayLocalDatabase)에서 조회하여 도착 예정 시각 계산
 */
object IncheonTimetableManager {

    private val STATION_ALIASES = mapOf(
        // 인천 1·2호선
        "석바위" to "석바위",
        "석바위시장" to "석바위",
        "주안국가" to "주안국가",
        "주안국가산단" to "주안국가",
        "서부여성" to "서부여성",
        "서부여성회관" to "서부여성",
        "가정중앙" to "가정중앙",
        "가정중앙시장" to "가정중앙",
        "아시아드" to "아시아드",
        "아시아드경기장" to "아시아드",

        // 1~9호선 / 광역전철 공통 별칭
        "서울역" to "서울",
        "지하서울" to "서울",
        "지하서" to "서울",
        "4서울" to "서울",
        "불암산" to "당고개",
        "별가람" to "별내별가람",
        "이수" to "총신대입구",
        "총신대입구(이수)" to "총신대입구",
        "총신대" to "총신대입구",
        "동작(현충원)" to "동작",
        "과천청" to "정부과천청사",
        "과천청사" to "정부과천청사",
        "경마공" to "경마공원",
        "평촌동" to "평촌",
        "신길온" to "신길온천",
        "능길" to "신길온천",
        "한성대" to "한성대입구",
        "성신여" to "성신여대입구",
        "숙대입" to "숙대입구",
        "미아4" to "미아사거리",
        "4동운" to "동대문역사문화공원",
        "동역사" to "동대문역사문화공원",
        "4동대" to "동대문",
        "1동대" to "동대문",
        "1종로" to "종로3가",
        "종로5" to "종로5가",
        "1지청" to "청량리",
        "가산디" to "가산디지털단지",
        "금천구" to "금천구청",
        "성균관" to "성균관대",
        "쌍용(나사렛대)" to "쌍용",
        "쌍용나" to "쌍용",
        "온양온" to "온양온천",
        "평지제" to "평택지제",
        "지제" to "평택지제",
        "동두중" to "동두천중앙",
        "백마고" to "백마고지",

        // 수인분당선
        "신인천" to "인천",
        "신수원" to "수원",
        "수원시" to "수원시청",
        "매탄권" to "매탄권선",
        "로데오" to "압구정로데오",
        "강남구" to "강남구청",
        "대모산" to "대모산입구",
        "구룡역" to "구룡",
        "남동인" to "남동인더스파크",
        "인천논" to "인천논현",
        "소래포" to "소래포구",

        // 경의중앙선 / 경춘선 / 서해선
        "디엠시" to "디지털미디어시티",
        "DMC" to "디지털미디어시티",
        "홍대입" to "홍대입구",
        "효창공" to "효창공원앞",
        "효창공원" to "효창공원앞",
        "항공대" to "한국항공대",
        "화전" to "한국항공대",
        "1양원" to "양원",
        "1양정" to "양정",
        "평내호" to "평내호평",
        "신김포" to "김포공항",
        "부천종" to "부천종합운동장",
        "신소사" to "소사",
        "시흥대" to "시흥대야",
        "신신천" to "신천",
        "신신현" to "신현",
        "시흥청" to "시흥시청",
        "시흥능" to "시흥능곡",
        "신초지" to "초지"
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

    private fun canonicalStationName(rawName: String, lineCode: String = ""): String {
        val clean = rawName.replace(Regex("""\(.*?\)"""), "").removeSuffix("역").trim()
        // 7호선 '이수'는 '이수' 그대로 유지 (4호선만 '총신대입구'로 매핑)
        if (lineCode == "7" && clean == "이수") {
            return "이수"
        }
        return STATION_ALIASES[rawName.trim()]
            ?: STATION_ALIASES[clean]
            ?: clean
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
     * 특정 역의 다음 도착 열차 목록 (시간순 상위 2개) 반환
     */
    fun getArrivalsByLineCode(
        context: Context,
        lineCode: String,
        lineName: String,
        target: TransitTarget
    ): List<ArrivalInfo> {
        val targetStation = canonicalStationName(target.name, lineCode)
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

        // 상행/내선 조회 여부 판단
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

    private fun formatDestinationTitle(
        row: SubwayLocalDatabase.TrainScheduleEntity,
        directionName: String
    ): String {
        val expressTag = when {
            row.lineCode == "GYEONGCHUN" && row.trainNo.startsWith("S2") -> " (ITX-청춘)"
            row.isExpress -> " (급행)"
            else -> ""
        }
        if (row.lineCode == "2" && canonicalStationName(row.startStation, "2") == canonicalStationName(row.endStation, "2")) {
            val circleLabel = if (row.direction == "UP") "내선순환" else "외선순환"
            return "$circleLabel (${row.endStation}행)"
        }
        return if (row.endStation.isNotEmpty()) "${row.endStation}행$expressTag" else "${directionName}방면$expressTag"
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
            val stops = row.stops
            if (stops.size < 2) continue

            // 종착 정차(마지막 인덱스)를 제외한 승차 가능 정차역 중 현재 시각 이후 가장 빠른 정차 탐색
            var matchedIdx = -1
            for (i in 0 until stops.size - 1) {
                val s = stops[i]
                if (!s.isStopping) continue
                if (canonicalStationName(s.stationName, row.lineCode) == station && s.daySeconds >= nowSec) {
                    matchedIdx = i
                    break
                }
            }
            if (matchedIdx < 0) continue

            val matchedStop = stops[matchedIdx]
            val tStr = matchedStop.arrivalTime
            val tSec = matchedStop.daySeconds
            val destTitle = formatDestinationTitle(row, directionName)

            // 필터링 적용
            if (filter.isNotEmpty() && filter != "양방향") {
                val filterCanon = canonicalStationName(filter.removeSuffix("행").removeSuffix("방면").trim(), row.lineCode)
                val matchesDir = filter.contains(directionName) ||
                        (row.lineCode == "2" && ((row.direction == "UP" && filter.contains("내선")) || (row.direction == "DOWN" && filter.contains("외선"))))
                val matchesDest = destTitle.contains(filter) ||
                        filter.contains(row.endStation) ||
                        (filterCanon.isNotEmpty() && canonicalStationName(row.endStation, row.lineCode) == filterCanon)
                if (!matchesDir && !matchesDest) {
                    continue
                }
            }

            val diffSeconds = tSec - nowSec
            val formattedTime = formatToHhMmSs(tStr)
            val detailText = calculateTrainPositionDetail(row, matchedIdx, station, tSec, nowSec)

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
     * 현재 시각(nowSec)과 시간표상의 이전 경로 역 시각들을 비교하여
     * 해당 열차가 현재 몇 번째 전역(어느 역)에 있는지 계산
     * 단, 조회 역이 출발역이거나 아직 출발역 출발 시각 전인 경우 "출발 대기 (출발역명)" 표출
     */
    private fun calculateTrainPositionDetail(
        row: SubwayLocalDatabase.TrainScheduleEntity,
        targetIdx: Int,
        targetStation: String,
        targetSec: Int,
        nowSec: Int
    ): String {
        val stops = row.stops
        if (targetIdx <= 0) {
            return if (targetSec - nowSec <= 60) "당역 도착 ($targetStation)" else "출발 대기 ($targetStation)"
        }

        if (targetSec - nowSec <= 20) {
            return "당역 진입 ($targetStation)"
        }

        // 아직 출발역에서 출발하기 전인 경우
        val originStop = stops[0]
        if (nowSec < originStop.daySeconds) {
            return "출발 대기 (${originStop.stationName})"
        }

        // 출발역 이후 ~ 도착역 이전: 현재 시각 이하의 가장 최근 통과/정차역 탐색
        var currentIdx = 0
        for (i in 0 until targetIdx) {
            if (stops[i].daySeconds <= nowSec) {
                currentIdx = i
            } else {
                break
            }
        }
        val hops = targetIdx - currentIdx
        val currentStn = stops[currentIdx].stationName
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
            val stops = row.stops
            if (stops.size < 2) continue

            var matchedStop: SubwayLocalDatabase.TrainStopEntity? = null
            for (i in 0 until stops.size - 1) {
                val s = stops[i]
                if (s.isStopping && canonicalStationName(s.stationName, row.lineCode) == station) {
                    matchedStop = s
                    break
                }
            }
            val stop = matchedStop ?: continue
            val tSec = stop.daySeconds
            if (tSec < earliestTimeSec) {
                earliestTimeSec = tSec
                val destTitle = formatDestinationTitle(row, dir)
                val formattedTime = formatToHhMmSs(stop.arrivalTime)
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
}
