package com.hoon.watchtransit.data

import com.hoon.watchtransit.model.ArrivalInfo
import com.hoon.watchtransit.model.TransitTarget
import com.hoon.watchtransit.model.TransitType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 과천시 전체 버스 (마을버스 1, 2, 3, 5번 / 시내버스 6, 7번 / 맞춤형버스 8, 8-1번) 공식 운행 시간표 매니저
 * - 과천시 공식 포털 버스노선 안내 최신 인가 시간표 완전 수록
 * - 평일, 토요일, 일요일/공휴일 인가 시간표 완전 수록
 * - 선바위역 5번출구 등 각 정류소 위치 및 방향별 정확한 기점/회차점 매핑
 * - 실시간 BIS 정보 부재 또는 차고지 대기 시 공식 시간표 기반 표출
 */
object GwacheonBusTimetableManager {

    enum class DayType {
        WEEKDAY,
        SATURDAY,
        SUNDAY_OR_HOLIDAY
    }

    private fun getCurrentDayType(): DayType {
        return when (LocalDate.now().dayOfWeek) {
            DayOfWeek.SATURDAY -> DayType.SATURDAY
            DayOfWeek.SUNDAY -> DayType.SUNDAY_OR_HOLIDAY
            else -> DayType.WEEKDAY
        }
    }

    // ==========================================
    // 1번 마을버스 (문원동 종점 순환)
    // ==========================================
    private val LINE_1_WEEKDAY = listOf(
        "05:20", "05:28", "05:36", "05:44", "05:52",
        "06:00", "06:08", "06:16", "06:24", "06:32", "06:40", "06:48", "06:56",
        "07:04", "07:11", "07:19", "07:25", "07:32", "07:38", "07:45", "07:51", "07:58",
        "08:04", "08:11", "08:17", "08:24", "08:30", "08:37", "08:43", "08:50",
        "09:05", "09:13", "09:21", "09:29", "09:37", "09:45", "09:53",
        "10:01", "10:09", "10:17", "10:25", "10:33", "10:41", "10:49", "10:57",
        "11:05", "11:13", "11:21", "11:29", "11:37", "11:45", "11:53",
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

    private val LINE_1_SATURDAY = listOf(
        "05:20", "05:30", "05:40", "05:50", "06:00", "06:10", "06:20", "06:30", "06:40", "06:50",
        "07:00", "07:10", "07:20", "07:30", "07:40", "07:50", "08:00", "08:10", "08:20", "08:30",
        "08:40", "08:50", "09:00", "09:10", "09:20", "09:30", "09:40", "09:50", "10:00", "10:10",
        "10:20", "10:30", "10:40", "10:50", "11:00", "11:10", "11:20", "11:30", "11:40", "11:50",
        "12:00", "12:10", "12:20", "12:30", "12:40", "12:50", "13:00", "13:10", "13:25", "13:40",
        "13:55", "14:10", "14:25", "14:40", "14:55", "15:05", "15:20", "15:35", "15:50", "16:05",
        "16:20", "16:35", "16:50", "17:05", "17:20", "17:35", "17:50", "18:05", "18:20", "18:35",
        "18:50", "19:05", "19:20", "19:35", "19:50", "20:05", "20:20", "20:35", "20:50", "21:05",
        "21:20", "21:35", "21:50", "22:05", "22:25", "22:40", "23:00", "23:20", "23:30"
    )

    private val LINE_1_SUNDAY = listOf(
        "05:20", "05:40", "06:00", "06:20", "06:40", "07:00", "07:15", "07:30", "07:45", "08:00",
        "08:15", "08:30", "08:45", "09:00", "09:15", "09:30", "09:45", "10:10", "10:25", "10:40",
        "10:55", "11:10", "11:25", "11:40", "11:55", "12:10", "12:25", "12:40", "12:55", "13:10",
        "13:25", "13:40", "13:55", "14:10", "14:25", "14:40", "14:55", "15:20", "15:35", "15:50",
        "16:05", "16:20", "16:35", "16:50", "17:05", "17:20", "17:35", "17:50", "18:05", "18:20",
        "18:35", "18:50", "19:05", "19:20", "19:35", "19:50", "20:05", "20:20", "20:35", "20:50",
        "21:05", "21:20", "21:35", "21:50", "22:05", "22:20", "22:30", "22:50", "23:10", "23:30"
    )

    // ==========================================
    // 2번 마을버스 (문원동 종점 순환)
    // ==========================================
    private val LINE_2_WEEKDAY = listOf(
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

    private val LINE_2_SATURDAY = listOf(
        "05:40", "06:20", "07:00", "07:40", "08:20", "09:00", "09:40", "10:20", "11:00", "11:40",
        "12:20", "13:00", "13:40", "14:20", "15:00", "16:20", "17:00", "17:40", "18:20", "19:00",
        "19:40", "20:20", "21:00", "21:40", "22:20", "23:00"
    )

    private val LINE_2_SUNDAY = listOf(
        "05:40", "06:20", "07:00", "07:40", "08:20", "09:00", "09:40", "11:00", "11:40", "12:20",
        "13:00", "13:40", "14:20", "15:00", "16:20", "17:00", "17:40", "18:20", "19:00", "19:40",
        "20:20", "21:00", "21:40", "22:20", "23:00"
    )

    // ==========================================
    // 3번 마을버스 (궁말차고지 ↔ 과천갈현초)
    // ==========================================
    private val LINE_3_GUNGMAL_WEEKDAY = listOf(
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

    private val LINE_3_GALHYEON_WEEKDAY = listOf(
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

    private val LINE_3_GUNGMAL_WEEKEND = listOf(
        "06:00", "06:35", "07:10", "07:40", "08:15", "08:50", "09:20", "09:55",
        "10:30", "11:00", "11:35", "12:10", "12:40", "13:15", "13:50", "14:20",
        "14:55", "15:30", "16:10", "16:45", "17:20", "17:50", "18:25", "19:00",
        "19:30", "20:05", "20:40", "21:10", "21:45", "22:20"
    )

    private val LINE_3_GALHYEON_WEEKEND = listOf(
        "06:40", "07:15", "07:50", "08:20", "08:55", "09:30", "10:00", "10:35",
        "11:10", "11:40", "12:15", "12:50", "13:20", "13:55", "14:30", "15:00",
        "15:35", "16:10", "16:50", "17:25", "18:00", "18:30", "19:05", "19:40",
        "20:10", "20:45", "21:20", "21:50", "22:25", "23:00"
    )

    // ==========================================
    // 5번 마을버스 (과천시청 ↔ 과천린파밀리에)
    // ==========================================
    private val LINE_5_CITYHALL_WEEKDAY = listOf(
        "06:15", "06:45", "07:10", "07:40", "08:10", "08:35", "09:05", "09:35", "10:05", "10:35",
        "11:05", "11:35", "12:05", "12:35", "13:05", "13:35", "14:05", "14:35", "15:05", "15:40",
        "16:15", "16:50", "17:25", "18:00", "18:30", "19:00", "19:30", "20:00", "20:30", "21:00",
        "21:30", "22:00", "22:30"
    )

    private val LINE_5_RINPAMILLIE_WEEKDAY = listOf(
        "06:55", "07:25", "07:50", "08:20", "08:50", "09:15", "09:45", "10:15", "10:45", "11:15",
        "11:45", "12:15", "12:45", "13:15", "13:45", "14:15", "14:45", "15:15", "15:45", "16:20",
        "16:55", "17:30", "18:05", "18:40", "19:10", "19:40", "20:10", "20:40", "21:10", "21:40",
        "22:10", "22:40", "23:10"
    )

    private val LINE_5_CITYHALL_SATURDAY = listOf(
        "06:15", "07:00", "07:50", "08:35", "09:20", "10:05", "10:50", "11:35", "12:20", "13:05",
        "13:55", "14:40", "15:30", "16:20", "17:15", "18:05", "18:50", "19:35", "20:20", "21:05",
        "21:45", "22:30"
    )

    private val LINE_5_RINPAMILLIE_SATURDAY = listOf(
        "06:55", "07:40", "08:30", "09:15", "10:00", "10:45", "11:30", "12:15", "13:00", "13:45",
        "14:35", "15:20", "16:10", "17:00", "17:55", "18:45", "19:30", "20:15", "21:00", "21:45",
        "22:25", "23:10"
    )

    private val LINE_5_CITYHALL_SUNDAY = listOf(
        "06:15", "07:45", "09:20", "11:05", "12:40", "14:15", "16:45", "17:40", "19:15", "20:50", "22:30"
    )

    private val LINE_5_RINPAMILLIE_SUNDAY = listOf(
        "06:55", "08:25", "10:00", "11:45", "13:20", "15:00", "17:20", "18:20", "19:55", "21:30", "23:10"
    )

    // ==========================================
    // 6번 시내버스 (과천린파밀리에 ↔ 양재역)
    // ==========================================
    private val LINE_6_RINPAMILLIE_WEEKDAY = listOf(
        "05:40", "05:55", "06:10", "06:25", "06:40", "06:55", "07:10", "07:25", "07:40", "07:55",
        "08:10", "08:25", "08:40", "08:55", "09:11", "09:27", "09:42", "09:57", "10:13", "10:28",
        "10:44", "10:59", "11:15", "11:30", "11:46", "12:01", "12:17", "12:32", "12:48", "13:04",
        "13:20", "13:36", "13:52", "14:08", "14:24", "14:40", "14:56", "15:12", "15:27", "15:43",
        "15:59", "16:15", "16:31", "16:48", "17:03", "17:19", "17:35", "17:50", "18:06", "18:21",
        "18:37", "18:53", "19:09", "19:26", "19:44", "20:00", "20:16", "20:32", "20:49", "21:05",
        "21:22", "21:39", "21:56", "22:13", "22:30"
    )

    private val LINE_6_YANGJAE_WEEKDAY = listOf(
        "06:35", "06:45", "07:00", "07:20", "07:35", "07:55", "08:10", "08:25", "08:40", "08:55",
        "09:10", "09:25", "09:40", "09:55", "10:11", "10:27", "10:42", "10:52", "11:08", "11:23",
        "11:39", "11:54", "12:05", "12:20", "12:36", "12:51", "13:07", "13:22", "13:38", "13:54",
        "14:10", "14:26", "14:42", "14:58", "15:14", "15:30", "15:46", "16:02", "16:17", "16:38",
        "16:54", "17:10", "17:26", "17:43", "18:03", "18:14", "18:30", "18:45", "19:01", "19:16",
        "19:27", "19:43", "19:59", "20:16", "20:34", "20:50", "21:06", "21:22", "21:39", "21:55",
        "22:12", "22:29", "22:46", "23:03", "23:30"
    )

    private val LINE_6_RINPAMILLIE_WEEKEND = listOf(
        "05:40", "06:10", "06:40", "07:10", "07:40", "08:10", "08:38", "09:08", "09:36", "10:06",
        "10:34", "11:04", "11:34", "12:04", "12:34", "13:04", "13:34", "14:04", "14:34", "15:04",
        "15:34", "16:04", "16:34", "17:04", "17:34", "18:04", "18:34", "19:04", "19:34", "20:04",
        "20:34", "21:04", "21:34", "22:02", "22:30"
    )

    private val LINE_6_YANGJAE_WEEKEND = listOf(
        "06:35", "07:00", "07:30", "08:00", "08:30", "09:00", "09:33", "10:03", "10:31", "11:01",
        "11:29", "11:59", "12:29", "12:59", "13:29", "13:59", "14:29", "14:59", "15:29", "15:59",
        "16:29", "16:59", "17:29", "17:59", "18:29", "18:59", "19:29", "19:59", "20:24", "20:54",
        "21:24", "21:54", "22:24", "22:52", "23:30"
    )

    // ==========================================
    // 7번 시내버스 (궁말차고지 ↔ 지정타 라비엔오/데시앙)
    // ==========================================
    private val LINE_7_GUNGMAL_WEEKDAY = listOf(
        "05:40", "05:50", "06:00", "06:10", "06:25", "06:40", "07:00", "07:15", "07:30", "07:40",
        "07:50", "08:00", "08:15", "08:35", "08:50", "09:05", "09:20", "09:35", "09:50", "10:05",
        "10:20", "10:35", "10:50", "11:05", "11:20", "11:35", "11:50", "12:05", "12:20", "12:35",
        "12:50", "13:05", "13:20", "13:35", "13:50", "14:05", "14:20", "14:35", "14:50", "15:05",
        "15:20", "15:40", "16:00", "16:15", "16:30", "16:45", "17:00", "17:15", "17:35", "17:45",
        "18:00", "18:15", "18:30", "18:45", "19:00", "19:20", "19:35", "19:50", "20:05", "20:20",
        "20:35", "20:50", "21:10", "21:30", "21:50", "22:10", "22:28", "22:45", "23:00"
    )

    private val LINE_7_JIJEONGTA_WEEKDAY = listOf(
        "06:23", "06:33", "06:43", "06:55", "07:10", "07:25", "07:45", "08:00", "08:15", "08:25",
        "08:35", "08:45", "09:00", "09:20", "09:35", "09:50", "10:05", "10:20", "10:35", "10:50",
        "11:05", "11:20", "11:35", "11:50", "12:05", "12:20", "12:35", "12:50", "13:05", "13:20",
        "13:35", "13:50", "14:05", "14:20", "14:35", "14:50", "15:05", "15:20", "15:35", "15:50",
        "16:05", "16:25", "16:45", "17:00", "17:15", "17:30", "17:45", "18:00", "18:20", "18:30",
        "18:45", "19:00", "19:15", "19:30", "19:45", "20:05", "20:20", "20:35", "20:50", "21:05",
        "21:20", "21:35", "21:55", "22:15", "22:35", "22:55", "23:11", "23:28", "23:43"
    )

    private val LINE_7_GUNGMAL_WEEKEND = listOf(
        "05:40", "06:05", "06:30", "06:55", "07:20", "07:45", "08:10", "08:35", "09:00", "09:25",
        "09:50", "10:15", "10:45", "11:10", "11:35", "12:00", "12:30", "12:55", "13:20", "13:45",
        "14:10", "14:40", "15:05", "15:35", "16:05", "16:35", "17:00", "17:30", "17:55", "18:20",
        "18:45", "19:15", "19:45", "20:15", "20:45", "21:15", "21:50", "22:25", "23:00"
    )

    private val LINE_7_JIJEONGTA_WEEKEND = listOf(
        "06:23", "06:48", "07:13", "07:40", "08:05", "08:30", "08:55", "09:20", "09:45", "10:10",
        "10:35", "11:00", "11:30", "11:55", "12:20", "12:45", "13:15", "13:40", "14:05", "14:30",
        "14:55", "15:25", "15:50", "16:20", "16:50", "17:20", "17:45", "18:15", "18:40", "19:05",
        "19:30", "20:00", "20:30", "21:00", "21:30", "22:00", "22:35", "23:08", "23:43"
    )

    // ==========================================
    // 8번 맞춤형버스 (평일 운행: 궁말차고지 ↔ 삼부골 ↔ 뒷골 ↔ 과천시청)
    // ==========================================
    private val LINE_8_GUNGMAL_WEEKDAY = listOf(
        "05:50", "06:30", "07:05", "07:45", "08:15", "08:55", "09:30", "10:10", "10:45", "11:25",
        "12:00", "12:40", "13:15", "13:55", "14:30", "15:10", "15:55", "16:35", "17:10", "17:50",
        "18:25", "19:05", "19:45", "20:25", "21:05", "21:50"
    )

    private val LINE_8_CITYHALL_WEEKDAY = listOf(
        "06:18", "06:58", "07:34", "08:14", "08:44", "09:24", "09:59", "10:39", "11:14", "11:54",
        "12:29", "13:09", "13:44", "14:24", "14:59", "15:39", "16:24", "17:04", "17:39", "18:19",
        "18:54", "19:34", "20:14", "20:54", "21:33", "22:18"
    )

    // ==========================================
    // 8-1번 맞춤형버스 (주말/공휴일 운행: 궁말차고지 ↔ 삼부골 ↔ 뒷골 ↔ 과천시청)
    // ==========================================
    private val LINE_8_1_GUNGMAL_WEEKEND = listOf(
        "05:50", "06:30", "07:00", "07:40", "08:15", "08:55", "09:30", "10:15", "10:50", "11:30",
        "12:05", "12:45", "13:20", "14:00", "14:35", "15:15", "15:55", "16:35", "17:10", "17:55",
        "18:30", "19:10", "19:45", "20:30", "21:05", "21:50"
    )

    private val LINE_8_1_CITYHALL_WEEKEND = listOf(
        "06:18", "06:58", "07:29", "08:09", "08:44", "09:24", "09:59", "10:44", "11:19", "11:59",
        "12:34", "13:14", "13:49", "14:29", "15:04", "15:44", "16:24", "17:04", "17:39", "18:24",
        "18:59", "19:39", "20:14", "20:59", "21:33", "22:18"
    )

    /**
     * 타깃에서 과천 버스 노선 번호 (1, 2, 3, 5, 6, 7, 8, 8-1) 추출
     * - '선바위역5번출구'와 같은 지하철 출구 번호를 노선 번호로 오인하는 버그 원천 방지
     * - 정류소 위치 기반(선바위역/경마공원역/궁말 -> 3번) 자동 추론 지원
     */
    fun extractRouteNumber(target: TransitTarget): String? {
        val dirParts = target.directionOrRoute.split("|")
        val routePart = dirParts.getOrNull(1)?.takeIf { it != "ALL" }
            ?: if (dirParts.size == 1 && !target.directionOrRoute.all { it.isDigit() }) target.directionOrRoute else ""
        if (routePart.isNotEmpty()) {
            val match = Regex("""(8-1|[1-8])(?:번|$)""").find(routePart.trim())
            if (match != null) return match.groupValues[1]
        }

        val cleanSubCode = target.subCode.trim()
        if (cleanSubCode.isNotEmpty() && cleanSubCode.length <= 4) {
            val match = Regex("""^(8-1|[1-8])(?:번)?$""").find(cleanSubCode)
            if (match != null) return match.groupValues[1]
        }

        // 정류소명(target.name)에서 출구 번호("5번출구", "1번출구" 등)를 완전히 제거한 텍스트에서 버스 번호 탐색
        val nameWithoutExit = target.name.replace(Regex("""\d+\s*번\s*출구"""), "").trim()
        val nameMatch = Regex("""(?:^|[^0-9])(8-1|[1-8])\s*번(?!출구)""").find(nameWithoutExit)
        if (nameMatch != null) {
            return nameMatch.groupValues[1]
        }

        // 정류소 위치 기반 자동 추론
        val stn = target.name
        return when {
            stn.contains("선바위") || stn.contains("경마공원") || stn.contains("궁말") || stn.contains("용마골") || stn.contains("양지마을") -> "3"
            stn.contains("문원") -> "1"
            stn.contains("갈현초") || stn.contains("제이드자이") || stn.contains("린파밀리에") || stn.contains("라비엔오") -> "3"
            stn.contains("시청") || stn.contains("과천역") || stn.contains("청사") -> "3"
            else -> null
        }
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
            "과천", "문원", "궁말", "갈현", "제이드자이", "린파밀리에", "라비엔오", "데시앙", "지식정보타운", "지식산업단지", "지식산업",
            "별양", "원문", "부림", "중앙동", "과천청사", "정부과천청사", "과천시청", "과천역", "스마트케이",
            "선바위", "경마공원", "양지마을", "용마골", "삼부골", "광창"
        )
        return gwacheonKeywords.any { text.contains(it) }
    }

    /**
     * 과천시 버스(1, 2, 3, 5, 6, 7, 8, 8-1번) 해당 여부 확인
     */
    fun isGwacheonBus(target: TransitTarget): Boolean {
        if (!isGwacheonTarget(target)) return false
        val routeNo = extractRouteNumber(target)
        return routeNo in listOf("1", "2", "3", "5", "6", "7", "8", "8-1")
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
        val dayType = getCurrentDayType()

        return when (routeNo) {
            "1" -> {
                val table = when (dayType) {
                    DayType.WEEKDAY -> LINE_1_WEEKDAY
                    DayType.SATURDAY -> LINE_1_SATURDAY
                    DayType.SUNDAY_OR_HOLIDAY -> LINE_1_SUNDAY
                }
                val offset = when {
                    stnName.contains("문원동") || stnName.contains("문원초") -> 0
                    stnName.contains("청사") || stnName.contains("과천시청") -> 4
                    stnName.contains("과천역") || stnName.contains("여고") -> 8
                    else -> 3
                }
                TimetableConfig(
                    timetable = table,
                    originName = "문원동",
                    destName = "과천역·문원동종점순환",
                    offsetMinutes = offset
                )
            }
            "2" -> {
                val table = when (dayType) {
                    DayType.WEEKDAY -> LINE_2_WEEKDAY
                    DayType.SATURDAY -> LINE_2_SATURDAY
                    DayType.SUNDAY_OR_HOLIDAY -> LINE_2_SUNDAY
                }
                val offset = when {
                    stnName.contains("문원동") -> 0
                    stnName.contains("청사") || stnName.contains("과천시청") -> 4
                    stnName.contains("과천역") || stnName.contains("중앙고") -> 7
                    else -> 3
                }
                TimetableConfig(
                    timetable = table,
                    originName = "문원동",
                    destName = "우체국·문원동종점순환",
                    offsetMinutes = offset
                )
            }
            "3" -> {
                // 선바위역 5번 출구 / 3번 출구: 궁말차고지 기점 출발 -> 갈현초(지정타) 방면
                // 선바위역 2번 출구 / 경마공원역 1번 출구: 갈현초 회차 출발 -> 궁말차고지 방면
                val isToGalhyeon = when {
                    stnName.contains("선바위역 5번") || stnName.contains("선바위역5번") ||
                    stnName.contains("선바위역 3번") || stnName.contains("선바위역3번") ||
                    stnName.contains("삼부골") || stnName.contains("광창") || stnName.contains("궁말") -> true
                    stnName.contains("선바위역 2번") || stnName.contains("선바위역2번") ||
                    stnName.contains("경마공원역 1번") || stnName.contains("양지마을") -> false
                    dir.contains("갈현") || dir.contains("제이드자이") || dir.contains("지정타") || dir.contains("시청") -> true
                    dir.contains("궁말") || dir.contains("선바위") -> false
                    else -> true
                }

                if (isToGalhyeon) {
                    val table = when (dayType) {
                        DayType.WEEKDAY -> LINE_3_GUNGMAL_WEEKDAY
                        else -> LINE_3_GUNGMAL_WEEKEND
                    }
                    val isSeonbawi5 = stnName.contains("선바위역 5번") || stnName.contains("선바위역5번")
                    val offset = if (isSeonbawi5 || stnName.contains("궁말")) 0 else 4
                    TimetableConfig(
                        timetable = table,
                        originName = "궁말차고지",
                        destName = "갈현초행",
                        offsetMinutes = offset
                    )
                } else {
                    val table = when (dayType) {
                        DayType.WEEKDAY -> LINE_3_GALHYEON_WEEKDAY
                        else -> LINE_3_GALHYEON_WEEKEND
                    }
                    val offset = if (stnName.contains("갈현초") || stnName.contains("벨라르테")) 0 else 4
                    TimetableConfig(
                        timetable = table,
                        originName = "과천갈현초",
                        destName = "궁말차고지행",
                        offsetMinutes = offset
                    )
                }
            }
            "5" -> {
                val isToJadeXi = dir.contains("린파밀리에") || dir.contains("제이드자이") || dir.contains("갈현") ||
                        stnName.contains("시청") || (stnName.contains("청사") && !dir.contains("시청"))

                if (isToJadeXi) {
                    val table = when (dayType) {
                        DayType.WEEKDAY -> LINE_5_CITYHALL_WEEKDAY
                        DayType.SATURDAY -> LINE_5_CITYHALL_SATURDAY
                        DayType.SUNDAY_OR_HOLIDAY -> LINE_5_CITYHALL_SUNDAY
                    }
                    val offset = if (stnName.contains("시청") || stnName.contains("청사")) 0 else 4
                    TimetableConfig(
                        timetable = table,
                        originName = "과천시청",
                        destName = "린파밀리에행",
                        offsetMinutes = offset
                    )
                } else {
                    val table = when (dayType) {
                        DayType.WEEKDAY -> LINE_5_RINPAMILLIE_WEEKDAY
                        DayType.SATURDAY -> LINE_5_RINPAMILLIE_SATURDAY
                        DayType.SUNDAY_OR_HOLIDAY -> LINE_5_RINPAMILLIE_SUNDAY
                    }
                    val offset = if (stnName.contains("린파밀리에") || stnName.contains("제이드자이")) 0 else 4
                    TimetableConfig(
                        timetable = table,
                        originName = "과천린파밀리에",
                        destName = "과천시청행",
                        offsetMinutes = offset
                    )
                }
            }
            "6" -> {
                val isToYangjae = dir.contains("양재") || (!dir.contains("과천") && !dir.contains("린파밀리에") && !stnName.contains("양재"))
                if (isToYangjae) {
                    val table = when (dayType) {
                        DayType.WEEKDAY -> LINE_6_RINPAMILLIE_WEEKDAY
                        else -> LINE_6_RINPAMILLIE_WEEKEND
                    }
                    val offset = if (stnName.contains("린파밀리에")) 0 else 4
                    TimetableConfig(
                        timetable = table,
                        originName = "과천린파밀리에",
                        destName = "양재역행",
                        offsetMinutes = offset
                    )
                } else {
                    val table = when (dayType) {
                        DayType.WEEKDAY -> LINE_6_YANGJAE_WEEKDAY
                        else -> LINE_6_YANGJAE_WEEKEND
                    }
                    val offset = if (stnName.contains("양재")) 0 else 4
                    TimetableConfig(
                        timetable = table,
                        originName = "양재역",
                        destName = "과천린파밀리에행",
                        offsetMinutes = offset
                    )
                }
            }
            "7" -> {
                val isToJijeongta = when {
                    stnName.contains("선바위역 5번") || stnName.contains("선바위역5번") ||
                    stnName.contains("선바위역 3번") || stnName.contains("선바위역3번") ||
                    stnName.contains("궁말") -> true
                    stnName.contains("선바위역 2번") || stnName.contains("선바위역2번") -> false
                    dir.contains("지정타") || dir.contains("라비엔오") || dir.contains("데시앙") -> true
                    dir.contains("궁말") || dir.contains("선바위") -> false
                    else -> true
                }

                if (isToJijeongta) {
                    val table = when (dayType) {
                        DayType.WEEKDAY -> LINE_7_GUNGMAL_WEEKDAY
                        else -> LINE_7_GUNGMAL_WEEKEND
                    }
                    val isSeonbawi5 = stnName.contains("선바위역 5번") || stnName.contains("선바위역5번")
                    val offset = if (isSeonbawi5 || stnName.contains("궁말")) 0 else 4
                    TimetableConfig(
                        timetable = table,
                        originName = "궁말차고지",
                        destName = "지식정보타운행",
                        offsetMinutes = offset
                    )
                } else {
                    val table = when (dayType) {
                        DayType.WEEKDAY -> LINE_7_JIJEONGTA_WEEKDAY
                        else -> LINE_7_JIJEONGTA_WEEKEND
                    }
                    val offset = if (stnName.contains("라비엔오") || stnName.contains("데시앙")) 0 else 4
                    TimetableConfig(
                        timetable = table,
                        originName = "지식정보타운",
                        destName = "궁말차고지행",
                        offsetMinutes = offset
                    )
                }
            }
            "8" -> {
                val isToCityHall = when {
                    stnName.contains("선바위역 5번") || stnName.contains("선바위역5번") ||
                    stnName.contains("선바위역 3번") || stnName.contains("선바위역3번") ||
                    stnName.contains("삼부골") || stnName.contains("궁말") -> true
                    stnName.contains("선바위역 2번") || stnName.contains("선바위역2번") -> false
                    dir.contains("시청") -> true
                    dir.contains("궁말") || dir.contains("선바위") -> false
                    else -> true
                }
                if (isToCityHall) {
                    val isSeonbawi5 = stnName.contains("선바위역 5번") || stnName.contains("선바위역5번")
                    val offset = if (isSeonbawi5 || stnName.contains("궁말")) 0 else 4
                    TimetableConfig(
                        timetable = LINE_8_GUNGMAL_WEEKDAY,
                        originName = "궁말차고지",
                        destName = "과천시청행",
                        offsetMinutes = offset
                    )
                } else {
                    val offset = if (stnName.contains("시청")) 0 else 4
                    TimetableConfig(
                        timetable = LINE_8_CITYHALL_WEEKDAY,
                        originName = "과천시청",
                        destName = "궁말차고지행",
                        offsetMinutes = offset
                    )
                }
            }
            "8-1" -> {
                val isToCityHall = when {
                    stnName.contains("선바위역 5번") || stnName.contains("선바위역5번") ||
                    stnName.contains("선바위역 3번") || stnName.contains("선바위역3번") ||
                    stnName.contains("삼부골") || stnName.contains("궁말") -> true
                    stnName.contains("선바위역 2번") || stnName.contains("선바위역2번") -> false
                    dir.contains("시청") -> true
                    dir.contains("궁말") || dir.contains("선바위") -> false
                    else -> true
                }
                if (isToCityHall) {
                    val isSeonbawi5 = stnName.contains("선바위역 5번") || stnName.contains("선바위역5번")
                    val offset = if (isSeonbawi5 || stnName.contains("궁말")) 0 else 4
                    TimetableConfig(
                        timetable = LINE_8_1_GUNGMAL_WEEKEND,
                        originName = "궁말차고지",
                        destName = "과천시청행",
                        offsetMinutes = offset
                    )
                } else {
                    val offset = if (stnName.contains("시청")) 0 else 4
                    TimetableConfig(
                        timetable = LINE_8_1_CITYHALL_WEEKEND,
                        originName = "과천시청",
                        destName = "궁말차고지행",
                        offsetMinutes = offset
                    )
                }
            }
            else -> {
                TimetableConfig(
                    timetable = LINE_1_WEEKDAY,
                    originName = "기점",
                    destName = "행",
                    offsetMinutes = 0
                )
            }
        }
    }

    /**
     * 특정 과천시 버스 타깃의 공식 인가 운행 시간표 기반 도착 예정 정보 계산
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
            // 3시간(10800초) 이내의 다음 도착 차량 선택
            if (diffSec in 0..10800) {
                val arriveHour = (arriveSec / 3600) % 24
                val arriveMin = (arriveSec % 3600) / 60
                val arriveTimeFormatted = String.format("%02d:%02d:00", arriveHour, arriveMin)
                val isSoon = diffSec <= 45

                val arrivalTimeStr = "$arriveTimeFormatted 🕒"
                val statusDetail = if (nowSec < departSec) "${config.originName} $timeStr 출발" else "운행 중 (${config.originName})"

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
                    arrivalTime = "첫차 $firstTime 🕒",
                    isSoon = false,
                    remainSeconds = diffSec,
                    statusDetail = "${config.originName} $firstTime 출발"
                )
            )
        }

        return candidates.take(2)
    }
}
