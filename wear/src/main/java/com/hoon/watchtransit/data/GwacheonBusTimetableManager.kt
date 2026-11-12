package com.hoon.watchtransit.data

import com.hoon.watchtransit.model.ArrivalInfo
import com.hoon.watchtransit.model.TransitTarget
import com.hoon.watchtransit.model.TransitType
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 과천시 주요 마을버스 (1번, 2번, 3번, 5번) 평일 정규 운행 시간표 매니저
 * - 실시간 BIS 정보가 없거나 차고지 대기 중일 때 정밀 출발 시간표 기반 도착 시간 표출
 */
object GwacheonBusTimetableManager {

    // 1번 마을버스 (문원동 종점 출발 기준)
    private val LINE_1_TIMETABLE = listOf(
        "05:20", "05:28", "05:36", "05:44", "05:52",
        "06:00", "06:08", "06:16", "06:24", "06:32", "06:40", "06:48", "06:56",
        "07:04", "07:11", "07:19", "07:25", "07:32", "07:38", "07:45", "07:51", "07:58",
        "08:04", "08:11", "08:17", "08:24", "08:30", "08:37", "08:43", "08:50",
        "09:05", "09:13", "09:21", "09:29", "09:37", "09:45", "09:53",
        "10:01", "10:09", "10:17", "10:25", "10:33", "10:41", "10:49", "10:57",
        "11:01", "11:09", "11:17", "11:25", "11:33", "11:41", "11:49", "11:57",
        "12:01", "12:09", "12:17", "12:25", "12:33", "12:41", "12:49", "12:57",
        "13:05", "13:13", "13:21", "13:29", "13:37", "13:45", "13:55",
        "14:05", "14:15", "14:25", "14:35", "14:45", "14:55",
        "15:05", "15:15", "15:25", "15:35", "15:45", "15:52",
        "16:00", "16:07", "16:15", "16:22", "16:30", "16:37", "16:45", "16:52",
        "17:00", "17:07", "17:15", "17:22", "17:30", "17:37", "17:45", "17:52",
        "18:00", "18:07", "18:15", "18:22", "18:30", "18:37", "18:45", "18:52",
        "19:00", "19:07", "19:15", "19:22", "19:30", "19:37", "19:45", "19:52",
        "20:00", "20:07", "20:15", "20:23", "20:31", "20:39", "20:47", "20:55",
        "21:03", "21:11", "21:19", "21:27", "21:35", "21:43", "21:51", "21:59",
        "22:07", "22:15", "22:23", "22:32", "22:41", "22:50",
        "23:00", "23:10", "23:20", "23:30"
    )

    // 2번 마을버스 (문원동 종점 출발 기준)
    private val LINE_2_TIMETABLE = listOf(
        "05:40",
        "06:00", "06:20", "06:40",
        "07:00", "07:20", "07:40",
        "08:00", "08:20", "08:40",
        "09:00", "09:20", "09:40",
        "10:00", "10:20", "10:40",
        "11:00", "11:20", "11:40",
        "12:00", "12:20", "12:40",
        "13:00", "13:20", "13:40",
        "14:00", "14:20", "14:40",
        "15:20", "15:40",
        "16:00", "16:20", "16:40",
        "17:00", "17:20", "17:40",
        "18:00", "18:20", "18:40",
        "19:00", "19:20", "19:40",
        "20:00", "20:20", "20:40",
        "21:00", "21:20", "21:40",
        "22:00", "22:20", "22:40",
        "23:00"
    )

    // 3번 마을버스 - 궁말차고지(기점) 출발 기준
    private val LINE_3_GUNGMAL_TIMETABLE = listOf(
        "06:00", "06:17", "06:34", "06:51",
        "07:06", "07:21", "07:36", "07:51",
        "08:06", "08:21", "08:38", "08:54",
        "09:11", "09:28", "09:44",
        "10:01", "10:18", "10:34", "10:51",
        "11:08", "11:24", "11:41", "11:58",
        "12:14", "12:31", "12:48",
        "13:04", "13:21", "13:38", "13:54",
        "14:11", "14:29", "14:48",
        "15:06", "15:25", "15:43",
        "16:01", "16:19", "16:38", "16:56",
        "17:15", "17:33", "17:48",
        "18:03", "18:18", "18:33", "18:48",
        "19:03", "19:19", "19:35", "19:51",
        "20:07", "20:23", "20:39", "20:55",
        "21:12", "21:29", "21:46",
        "22:03", "22:20"
    )

    // 3번 마을버스 - 과천갈현초(종점) 회차 출발 기준
    private val LINE_3_GALHYEON_TIMETABLE = listOf(
        "06:40", "06:57",
        "07:14", "07:31", "07:46",
        "08:01", "08:16", "08:31", "08:46",
        "09:01", "09:18", "09:34", "09:51",
        "10:08", "10:24", "10:41", "10:58",
        "11:14", "11:31", "11:48",
        "12:04", "12:21", "12:38", "12:54",
        "13:11", "13:28", "13:44",
        "14:01", "14:18", "14:34", "14:51",
        "15:09", "15:28", "15:46",
        "16:05", "16:23", "16:41", "16:59",
        "17:18", "17:36", "17:55",
        "18:13", "18:28", "18:43", "18:58",
        "19:13", "19:28", "19:43", "19:59",
        "20:15", "20:31", "20:47",
        "21:03", "21:19", "21:35", "21:52",
        "22:09", "22:26", "22:43",
        "23:00"
    )

    // 5번 마을버스 - 과천시청 출발 기준
    private val LINE_5_CITYHALL_TIMETABLE = listOf(
        "06:15", "07:00", "07:50", "08:35", "09:20",
        "10:05", "10:50", "11:35", "12:20", "13:05", "13:55",
        "14:40", "15:30", "16:20", "17:15",
        "18:05", "18:50", "19:35", "20:20", "21:05", "21:45", "22:30"
    )

    // 5번 마을버스 - 과천제이드자이(종점) 회차 기준 (시청 출발 후 약 40분 뒤)
    private val LINE_5_JADEXI_TIMETABLE = listOf(
        "06:55", "07:40", "08:30", "09:15", "10:00",
        "10:45", "11:30", "12:15", "13:00", "13:45", "14:35",
        "15:20", "16:10", "17:00", "17:55", "18:45", "19:30",
        "20:15", "21:00", "21:45", "22:25", "23:10"
    )

    /**
     * 타깃에서 노선 번호(1, 2, 3, 5) 추출
     */
    fun extractRouteNumber(target: TransitTarget): String? {
        val candidates = listOf(
            target.subCode,
            target.directionOrRoute.split("|").getOrNull(1).orEmpty(),
            target.name
        )
        for (c in candidates) {
            val trimmed = c.trim()
            val match = Regex("""(?:^|[^0-9])([1235])(?:번|$)""").find(trimmed)
                ?: Regex("""^([1235])$""").find(trimmed)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    /**
     * 과천시 대상 정류장/노선 여부 판별
     */
    fun isGwacheonTarget(target: TransitTarget): Boolean {
        if (target.type != TransitType.BUS) return false
        val parts = target.directionOrRoute.split("|")
        val cityCode = parts.getOrNull(0)?.toIntOrNull() ?: 0
        if (cityCode == 31110) return true

        val text = "${target.name} ${target.subCode} ${target.directionOrRoute}"
        val gwacheonKeywords = listOf(
            "과천", "문원", "궁말", "갈현", "제이드자이", "지식정보타운", "지식산업단지", "지식산업",
            "별양", "원문", "부림", "중앙동", "과천청사", "정부과천청사", "과천시청", "과천역", "스마트케이"
        )
        return gwacheonKeywords.any { text.contains(it) }
    }

    /**
     * 과천시 마을버스(1, 2, 3, 5번) 해당 여부 확인
     */
    fun isGwacheonVillageBus(target: TransitTarget): Boolean {
        if (!isGwacheonTarget(target)) return false
        val routeNo = extractRouteNumber(target)
        return routeNo in listOf("1", "2", "3", "5")
    }

    data class TimetableConfig(
        val timetable: List<String>,
        val originName: String,
        val destName: String,
        val offsetMinutes: Int
    )

    private fun resolveTimetableConfig(routeNo: String, target: TransitTarget): TimetableConfig {
        val stnName = target.name
        val dir = target.directionOrRoute

        return when (routeNo) {
            "1" -> {
                val offset = when {
                    stnName.contains("문원동") || stnName.contains("문원초") -> 0
                    stnName.contains("청사") || stnName.contains("과천시청") -> 4
                    stnName.contains("과천역") || stnName.contains("여고") -> 8
                    else -> 3
                }
                TimetableConfig(
                    timetable = LINE_1_TIMETABLE,
                    originName = "문원동",
                    destName = "문원동종점순환",
                    offsetMinutes = offset
                )
            }
            "2" -> {
                val offset = when {
                    stnName.contains("문원동") -> 0
                    stnName.contains("청사") || stnName.contains("과천시청") -> 4
                    stnName.contains("과천역") || stnName.contains("중앙고") -> 7
                    else -> 3
                }
                TimetableConfig(
                    timetable = LINE_2_TIMETABLE,
                    originName = "문원동",
                    destName = "문원동종점순환",
                    offsetMinutes = offset
                )
            }
            "3" -> {
                // 방향 및 정류장 위치에 따라 궁말차고지 출발 vs 갈현초 회차 구분
                val isToGalhyeon = dir.contains("갈현") || dir.contains("제이드자이") || dir.contains("지정타") ||
                        dir.contains("지식산업") || (stnName.contains("궁말") || stnName.contains("문원") ||
                        (stnName.contains("청사") && !dir.contains("궁말")))

                if (isToGalhyeon) {
                    val offset = when {
                        stnName.contains("궁말") -> 0
                        stnName.contains("청사") || stnName.contains("과천역") || stnName.contains("시청") -> 5
                        stnName.contains("지식산업단지") || stnName.contains("스마트케이") -> 9
                        stnName.contains("갈현초") || stnName.contains("제이드자이") -> 12
                        else -> 4
                    }
                    TimetableConfig(
                        timetable = LINE_3_GUNGMAL_TIMETABLE,
                        originName = "궁말차고지",
                        destName = "갈현초방면",
                        offsetMinutes = offset
                    )
                } else {
                    val offset = when {
                        stnName.contains("갈현초") || stnName.contains("제이드자이") -> 0
                        stnName.contains("지식산업단지") || stnName.contains("스마트케이") -> 3
                        stnName.contains("청사") || stnName.contains("과천역") || stnName.contains("시청") -> 7
                        stnName.contains("궁말") -> 12
                        else -> 4
                    }
                    TimetableConfig(
                        timetable = LINE_3_GALHYEON_TIMETABLE,
                        originName = "과천갈현초",
                        destName = "궁말차고지방면",
                        offsetMinutes = offset
                    )
                }
            }
            "5" -> {
                val isToJadeXi = dir.contains("제이드자이") || dir.contains("갈현") || dir.contains("지정타") ||
                        stnName.contains("시청") || (stnName.contains("청사") && !dir.contains("시청"))

                if (isToJadeXi) {
                    val offset = when {
                        stnName.contains("시청") || stnName.contains("청사") -> 0
                        stnName.contains("과천역") -> 3
                        stnName.contains("제이드자이") -> 10
                        else -> 3
                    }
                    TimetableConfig(
                        timetable = LINE_5_CITYHALL_TIMETABLE,
                        originName = "과천시청",
                        destName = "제이드자이방면",
                        offsetMinutes = offset
                    )
                } else {
                    val offset = when {
                        stnName.contains("제이드자이") -> 0
                        stnName.contains("과천역") -> 6
                        stnName.contains("청사") || stnName.contains("시청") -> 8
                        else -> 4
                    }
                    TimetableConfig(
                        timetable = LINE_5_JADEXI_TIMETABLE,
                        originName = "과천제이드자이",
                        destName = "과천시청방면",
                        offsetMinutes = offset
                    )
                }
            }
            else -> {
                TimetableConfig(
                    timetable = LINE_1_TIMETABLE,
                    originName = "기점",
                    destName = "방면",
                    offsetMinutes = 0
                )
            }
        }
    }

    /**
     * 특정 과천시 마을버스 타깃의 정밀 운행 시간표 기반 도착 예정 정보 계산
     */
    fun getArrivals(target: TransitTarget): List<ArrivalInfo> {
        val routeNo = extractRouteNumber(target) ?: return emptyList()
        val now = LocalTime.now()
        val nowSec = now.toSecondOfDay()

        val config = resolveTimetableConfig(routeNo, target)
        val candidates = mutableListOf<ArrivalInfo>()

        for (timeStr in config.timetable) {
            val parts = timeStr.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val m = parts.getOrNull(1)?.toIntOrNull() ?: continue
            val departSec = h * 3600 + m * 60
            val arriveSec = departSec + (config.offsetMinutes * 60)

            val diffSec = arriveSec - nowSec
            // diffSec가 0보다 크고 약 3시간(10800초) 이내의 다음 도착 차량 선택
            if (diffSec in 0..10800) {
                val arriveHour = (arriveSec / 3600) % 24
                val arriveMin = (arriveSec % 3600) / 60
                val arriveTimeFormatted = String.format("%02d:%02d:00", arriveHour, arriveMin)
                val isSoon = diffSec <= 45

                val arrivalTimeStr = "$arriveTimeFormatted [시간표]"
                val statusDetail = "[시간표] ${config.originName} $timeStr 출발"

                candidates.add(
                    ArrivalInfo(
                        lineOrBusName = "버스",
                        destination = "${routeNo}번 (${config.destName})",
                        arrivalTime = arrivalTimeStr,
                        isSoon = isSoon,
                        remainSeconds = diffSec,
                        statusDetail = statusDetail
                    )
                )

                if (candidates.size >= 2) break
            }
        }

        // 당일 운행이 모두 종료된 경우 익일 첫차 안내
        if (candidates.isEmpty() && config.timetable.isNotEmpty()) {
            val firstTime = config.timetable.first()
            val parts = firstTime.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 6
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val firstArriveSec = (h * 3600 + m * 60) + (config.offsetMinutes * 60)
            val diffSec = (86400 - nowSec) + firstArriveSec

            candidates.add(
                ArrivalInfo(
                    lineOrBusName = "버스",
                    destination = "${routeNo}번 (${config.destName})",
                    arrivalTime = "첫차 $firstTime [시간표]",
                    isSoon = false,
                    remainSeconds = diffSec,
                    statusDetail = "[시간표] 익일 첫차 ${config.originName} $firstTime 출발"
                )
            )
        }

        return candidates.take(2)
    }
}
