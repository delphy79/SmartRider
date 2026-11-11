package com.hoon.watchtransit.network

import android.content.Context
import android.util.Log
import com.hoon.watchtransit.data.ArexTimetableManager
import com.hoon.watchtransit.data.IncheonTimetableManager
import com.hoon.watchtransit.data.SeoulSubwayTravelTimeManager
import com.hoon.watchtransit.data.SubwayLocalDatabase
import com.hoon.watchtransit.model.ArrivalInfo
import com.hoon.watchtransit.model.TransitTarget
import com.hoon.watchtransit.model.TransitType
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

interface ArrivalApiService {
    // 1. TAGO 버스정류소별 도착예정정보 목록조회
    @GET("https://apis.data.go.kr/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList")
    suspend fun getBusArrival(
        @Query(value = "serviceKey", encoded = true) serviceKey: String,
        @Query("cityCode") cityCode: Int,
        @Query("nodeId") nodeId: String,
        @Query("_type") type: String = "json",
        @Query("numOfRows") numOfRows: Int = 30
    ): TagoBusArrivalResponse

    // 2. 서울시 실시간 지하철 도착정보 조회
    @GET("http://swopenAPI.seoul.go.kr/api/subway/{apiKey}/json/realtimeStationArrival/0/20/{stationName}")
    suspend fun getSubwayArrival(
        @Path("apiKey") apiKey: String,
        @Path("stationName") stationName: String
    ): SeoulSubwayRealtimeResponse

    // 3. 서울 열린데이터광장 지하철 시간표 조회 (실시간 정보 부재 시 사용)
    @GET("http://openapi.seoul.go.kr:8088/{apiKey}/json/SearchSTNTimeTableByIDService/{startIndex}/{endIndex}/{stationCd}/{weekTag}/{inoutTag}/")
    suspend fun getSubwayTimetable(
        @Path("apiKey") apiKey: String,
        @Path("startIndex") startIndex: Int = 1,
        @Path("endIndex") endIndex: Int = 250,
        @Path("stationCd") stationCd: String,
        @Path("weekTag") weekTag: String,
        @Path("inoutTag") inoutTag: String
    ): SeoulSubwayTimetableResponse

    // 4. 경기도 버스도착정보 목록조회 (v2)
    @GET("https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2")
    suspend fun getGgbisBusArrival(
        @Query(value = "serviceKey", encoded = true) serviceKey: String,
        @Query("stationId") stationId: String,
        @Query("format") format: String = "json"
    ): GgbisBusArrivalResponse
}

class ArrivalRepository(private val context: Context? = null) {
    companion object {
        // 국토교통부 TAGO 및 경기도 버스(GBIS v2) 인증키
        private const val BUS_SERVICE_KEY =
            "C2AoTlWc9HO0E1s9QrrQVJui3pGSQBr%2BbeSgwqYSfynWWrbcA3H8NOBwrY3ONCZ%2FosuYrdvE1RjKvkDWCe58OQ%3D%3D"

        // 서울 열린데이터광장 지하철 실시간 도착정보 및 시간표 승인 키
        private const val SEOUL_SUBWAY_KEY = "414a5058486a686b3639627851786e"

        /**
         * 가장 빠른 도착 열차/버스가 항상 최상단(1번째)에 오도록
         * 남은 시간을 초 단위로 정밀하게 환산하는 헬퍼
         */
        fun getEffectiveRemainSeconds(info: ArrivalInfo): Int {
            if (info.lineOrBusName.contains("실패") || info.arrivalTime.contains("오류") ||
                info.arrivalTime.contains("미등록") || info.arrivalTime.contains("연결 안 됨") ||
                info.arrivalTime.contains("없습니다") || info.arrivalTime.contains("종료")) {
                return Int.MAX_VALUE - 100
            }

            // '곧 도착', '당역 진입/도착'은 10~30초로 최우선 정렬
            if (info.isSoon) {
                return if (info.remainSeconds in 1..40) info.remainSeconds else 15
            }

            // 실시간 남은 초가 양수이면 그대로 사용
            if (info.remainSeconds > 0) {
                return info.remainSeconds
            }

            // arrivalTime 문자열에서 남은 분/초 추출
            val minMatch = Regex("""(\d+)\s*분""").find(info.arrivalTime)
            val secMatch = Regex("""(\d+)\s*초""").find(info.arrivalTime)
            if (minMatch != null) {
                val m = minMatch.groupValues[1].toIntOrNull() ?: 99
                val s = secMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                return m * 60 + s
            }
            if (secMatch != null) {
                return secMatch.groupValues[1].toIntOrNull() ?: 30
            }

            val prevMatch = Regex("""\[(\d+)\]번째""").find(info.arrivalTime)
            if (prevMatch != null) {
                val n = prevMatch.groupValues[1].toIntOrNull() ?: 2
                return n * 120
            }

            if (info.arrivalTime.contains("전역")) return 90
            if (info.arrivalTime.contains("도착") || info.arrivalTime.contains("진입")) return 20

            return 99999
        }
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client = OkHttpClient.Builder().build()

    private val api: ArrivalApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://apis.data.go.kr/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ArrivalApiService::class.java)
    }

    suspend fun fetchArrivals(target: TransitTarget): List<ArrivalInfo> = runCatching {
        val list = if (target.type == TransitType.SUBWAY) {
            fetchSubwayArrivals(target)
        } else {
            fetchBusArrivals(target)
        }
        // 가장 빠른 도착 정보가 항상 맨 앞에 오도록 남은 시간 기준 오름차순 정렬 후 최대 2개 반환
        list.sortedBy { getEffectiveRemainSeconds(it) }.take(2)
    }.onFailure { e ->
        Log.e("ArrivalRepo", "도착 정보 조회 실패 (${target.name}): ${e.message}", e)
    }.getOrElse { e ->
        val errorMsg = when {
            e.message?.contains("SERVICE_KEY_IS_NOT_REGISTERED") == true -> "버스도착 API 키 미등록"
            e.message?.contains("ERROR-338") == true -> "실시간 지하철 API 키 미승인"
            e.message?.contains("Unable to resolve host") == true -> "워치 인터넷 연결 안 됨"
            else -> "오류: ${e.message?.take(30) ?: "알 수 없음"}"
        }
        listOf(
            ArrivalInfo(
                lineOrBusName = "조회 실패",
                destination = target.name,
                arrivalTime = errorMsg,
                isSoon = true
            )
        )
    }

    private suspend fun fetchSubwayArrivals(target: TransitTarget): List<ArrivalInfo> {
        val cleanName = target.name.removeSuffix("역").trim()
        val filter = target.directionOrRoute.trim()
        val defaultLineName = target.subCode.ifEmpty { "지하철" }

        // 1. 인천도시철도(인천1호선, 인천2호선)는 내부 SQLite DB 시간표 기반으로 정확한 도착 시각 표출
        if (isIncheonSubway(target)) {
            if (context != null) {
                val dbArrivals = IncheonTimetableManager.getArrivals(context, target)
                if (dbArrivals.isNotEmpty()) {
                    return dbArrivals.take(2)
                }
            }
            val incheonArrivals = calculateIncheonSubwayArrivals(target)
            if (incheonArrivals.isNotEmpty()) {
                return incheonArrivals.take(2)
            }
        }

        val targetSubwayId = getSubwayIdFromLineName(target.subCode)
        val isArexTerminalOrigin = (targetSubwayId == "1065" || ArexTimetableManager.isArex(target)) &&
                (cleanName == "서울" || cleanName == "인천공항2터미널")

        // 공항철도 시발역(서울역, 인천공항2터미널역)은 출발 시간표가 가장 정확하며 전역이 없으므로 내부 DB 공식 시간표 우선 적용
        if (isArexTerminalOrigin && context != null) {
            val arexArrivals = ArexTimetableManager.getArrivals(context, target)
            if (arexArrivals.isNotEmpty()) {
                return arexArrivals.take(2)
            }
        }

        // 2. 서울교통공사 실시간 도착정보 조회 (공항철도, 서울 1~9호선, 인천1·2호선, 수인분당선, 경의중앙선 등)
        val realtimeList = runCatching {
            val response = api.getSubwayArrival(SEOUL_SUBWAY_KEY, cleanName)
            response.realtimeArrivalList
                // 1) 해당 노선 필터링 (환승역에서 다른 호선 열차 혼입 방지)
                .filter { item ->
                    if (targetSubwayId.isNotEmpty()) item.subwayId == targetSubwayId
                    else true
                }
                // 2) 이미 출발하여 떠나간 열차, 당역 종착 열차, 및 공항철도 직통/급행 열차 제외
                .filterNot { item ->
                    val bstatnClean = item.bstatnNm.removeSuffix("역").trim()
                    val isTerminatingHere = bstatnClean == cleanName || item.trainLineNm.trim().startsWith("${cleanName}행")
                    val hasDeparted = item.arvlCd == "2" || (item.arvlMsg2.contains("출발") && !item.arvlMsg2.contains("전역"))
                    val isArex = item.subwayId == "1065" || targetSubwayId == "1065" || ArexTimetableManager.isArex(target)
                    val isArexExpress = isArex && (
                        item.btrainSttus.contains("급행") ||
                        item.btrainSttus.contains("직통") ||
                        item.trainLineNm.contains("급행") ||
                        item.trainLineNm.contains("직통") ||
                        item.btrainNo.startsWith("A1")
                    )
                    isTerminatingHere || hasDeparted || isArexExpress
                }
                // 3) 사용자 방향/행선지 필터 적용
                .filter { item ->
                    if (filter.isEmpty() || filter == "양방향" || isArexTerminalOrigin) true
                    else item.trainLineNm.contains(filter) || item.updnLine.contains(filter) ||
                            (item.bstatnNm.isNotEmpty() && item.bstatnNm.contains(filter))
                }
                // 4) 도착 순서 정렬 (남은 시간/거리 기준 오름차순, 내부 DB 역간 소요시간 데이터 우선 적용)
                .sortedBy { item ->
                    calculateEstimatedArrivalSeconds(item, cleanName)
                }
                // 5) ArrivalInfo UI 모델로 변환
                .map { item ->
                    val estSec = calculateEstimatedArrivalSeconds(item, cleanName)
                    val destTitle = when {
                        item.trainLineNm.contains("행") -> item.trainLineNm
                        item.bstatnNm.isNotEmpty() -> "${item.bstatnNm}행"
                        item.updnLine.isNotEmpty() -> "${item.updnLine}행"
                        else -> item.trainLineNm
                    }
                    val detailText = buildSubwayStatusDetail(item, cleanName)
                    ArrivalInfo(
                        lineOrBusName = defaultLineName,
                        destination = destTitle,
                        arrivalTime = item.arvlMsg2,
                        isSoon = item.arvlMsg2.contains("도착") || item.arvlMsg2.contains("진입") || item.arvlCd in listOf("0", "1", "4", "5"),
                        remainSeconds = estSec,
                        statusDetail = detailText
                    )
                }
                .take(2)
        }.getOrDefault(emptyList())

        // 실시간 정보가 존재하면 최대 2개 즉시 반환
        if (realtimeList.isNotEmpty()) {
            return realtimeList.take(2)
        }

        // 3. 공항철도 역이고 실시간 정보가 없는 경우 내부 DB 공식 시간표 기반으로 도착 시각 표출
        if (ArexTimetableManager.isArex(target) || ArexTimetableManager.isArexStation(target.name)) {
            if (context != null) {
                val arexArrivals = ArexTimetableManager.getArrivals(context, target)
                if (arexArrivals.isNotEmpty()) {
                    return arexArrivals.take(2)
                }
            }
        }

        // 4. 차후 내부 DB(timetable_trains / timetable_stops)에 추가된 타 노선 시간표가 있는 경우 DB에서 조회
        val lineCode = SeoulSubwayTravelTimeManager.extractLineNumber(target.subCode) ?: target.subCode.trim()
        if (context != null && lineCode.isNotEmpty() && SubwayLocalDatabase.hasLineTimetable(context, lineCode)) {
            val dbLineArrivals = IncheonTimetableManager.getArrivalsByLineCode(context, lineCode, defaultLineName, target)
            if (dbLineArrivals.isNotEmpty()) {
                return dbLineArrivals.take(2)
            }
        }

        // 5. 서울시 정적 시간표 API 및 내부 SQLite DB(station_static_timetables) 조회
        val timetableArrivals = fetchSubwayTimetableArrivals(target)
        if (timetableArrivals.isNotEmpty()) {
            return timetableArrivals.take(2)
        }

        return listOf(
            ArrivalInfo(
                lineOrBusName = defaultLineName,
                destination = target.name,
                arrivalTime = "도착 예정 정보 없음",
                isSoon = false
            )
        )
    }

    /**
     * 실시간 지하철 도착 시간 아래에 작게 표출할 위치 상세 정보 생성
     * 예: "[3]번째 전역 (범계)", "전역 출발 (인덕원)", "당역 진입 (선바위)"
     */
    private fun buildSubwayStatusDetail(item: SubwayRealtimeItem, targetStationName: String): String {
        val msg = item.arvlMsg2.trim()
        val stn = item.arvlMsg3.trim().ifBlank {
            Regex("""\(([^)]+)\)""").find(msg)?.groupValues?.get(1)?.trim() ?: ""
        }
        val cleanStn = stn.removeSuffix("역").trim()
        val cleanTarget = targetStationName.removeSuffix("역").trim()

        // 공항철도 서울역·인천공항2터미널역 등 시발역이거나 열차 현재 역이 조회 역과 동일한 경우 전역이 없음
        if ((item.subwayId == "1065" && (cleanTarget == "서울" || cleanTarget == "인천공항2터미널")) ||
            (cleanStn.isNotEmpty() && cleanStn == cleanTarget)) {
            return when (item.arvlCd) {
                "0" -> "당역 진입 ($cleanTarget)"
                "1" -> "당역 도착 ($cleanTarget)"
                else -> "출발 대기 ($cleanTarget)"
            }
        }

        // 1) 이미 "[3]번째 전역 (범계)" 형식이면 그대로 반환
        val bracketMatch = Regex("""(\[\d+\]번째\s*전역)""").find(msg)
        if (bracketMatch != null) {
            val base = bracketMatch.groupValues[1]
            return if (stn.isNotEmpty()) "$base ($stn)" else msg
        }

        // 2) 도착코드에 따른 명시적 상태
        when (item.arvlCd) {
            "0" -> return if (stn.isNotEmpty()) "당역 진입 ($stn)" else "당역 진입"
            "1" -> return if (stn.isNotEmpty()) "당역 도착 ($stn)" else "당역 도착"
            "3" -> return if (stn.isNotEmpty()) "[1]번째 전역 ($stn)" else "전역 출발"
            "4" -> return if (stn.isNotEmpty()) "전역 진입 ($stn)" else "전역 진입"
            "5" -> return if (stn.isNotEmpty()) "전역 도착 ($stn)" else "전역 도착"
        }

        // 3) ordkey 또는 역간 그래프에서 남은 역 수 추출
        val lineNum = SeoulSubwayTravelTimeManager.extractLineNumber(item.subwayId)
        val graphHops = if (context != null && lineNum != null && stn.isNotEmpty()) {
            SeoulSubwayTravelTimeManager.getStationHops(context, lineNum, stn, targetStationName)
        } else null
        if (graphHops == 0) {
            return if (stn.isNotEmpty()) "당역 도착 ($stn)" else "당역 도착"
        }
        val ordHops = item.ordkey.drop(2).take(3).toIntOrNull()?.takeIf { it in 1..40 }
        val hops = graphHops?.takeIf { it > 0 } ?: ordHops

        if (hops != null && hops > 0) {
            return if (stn.isNotEmpty()) "[$hops]번째 전역 ($stn)" else "[$hops]번째 전역"
        }

        if (stn.isNotEmpty()) {
            return "현재위치 ($stn)"
        }
        return msg
    }

    /**
     * 실시간 열차의 도착 예정 시간(초)을 정밀 계산하여 도착 순서대로 정렬하기 위한 헬퍼
     * 내부 SQLite DB 역간 소요시간 테이블에 존재하는 구간은 DB 데이터를 우선 적용하고,
     * DB에 없는 역 구간(코레일 평촌, 인덕원 등)은 기존 알고리즘(160초 배수 등)을 그대로 유지하며,
     * 실시간 도착정보 생성시각(recptnDt)과 현재 시각의 경과 시간(초)을 차감하여 보정
     */
    private fun calculateEstimatedArrivalSeconds(item: SubwayRealtimeItem, targetStationName: String): Int {
        // 1. 당역 진입/도착인 경우 즉시 초 반환
        if (item.arvlCd == "0" || item.arvlMsg2.contains("당역 진입")) return 10  // 당역 진입
        if (item.arvlCd == "1" || item.arvlMsg2.contains("당역 도착") || item.arvlMsg2.contains("곧 도착")) return 15  // 당역 도착

        val elapsedSec = getElapsedSecondsFromRecptnDt(item.recptnDt)

        // 2. 내부 DB 역간 소요시간 데이터 적용 시도 (서울1~8호선, 인천1·2호선, 공항철도 및 추가 노선)
        val currentStation = item.arvlMsg3.ifBlank {
            Regex("""\(([^)]+)\)""").find(item.arvlMsg2)?.groupValues?.get(1) ?: ""
        }
        val lineNum = SeoulSubwayTravelTimeManager.extractLineNumber(item.subwayId)
        if (context != null && lineNum != null && currentStation.isNotBlank()) {
            val travelSec = SeoulSubwayTravelTimeManager.getTravelSeconds(
                context = context,
                lineNum = lineNum,
                fromStationRaw = currentStation,
                toStationRaw = targetStationName
            )
            if (travelSec != null) {
                if (travelSec == 0) return 15
                return maxOf(1, travelSec - elapsedSec)
            }
        }

        // 3. DB에 없는 역 구간(예: 코레일 구간 평촌, 인덕원 등)이거나 매칭 불가 시 -> 기존 로직 + recptnDt 경과 시간 보정
        val barvl = item.barvlDt.toIntOrNull() ?: 0
        if (barvl > 0) return maxOf(1, barvl - elapsedSec)

        val msg = item.arvlMsg2
        val minMatch = Regex("""(\d+)\s*분""").find(msg)
        val secMatch = Regex("""(\d+)\s*초""").find(msg)
        if (minMatch != null) {
            val min = minMatch.groupValues[1].toIntOrNull() ?: 0
            val sec = secMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return maxOf(1, min * 60 + sec - elapsedSec)
        }

        val baseSec = when (item.arvlCd) {
            "4" -> 90  // 전역 진입
            "5" -> 110 // 전역 도착
            "3" -> 160 // 전역 출발
            else -> {
                val prevStationMatch = Regex("""\[(\d+)\]번째""").find(msg)
                if (prevStationMatch != null) {
                    val n = prevStationMatch.groupValues[1].toIntOrNull() ?: 2
                    n * 160
                } else {
                    val ordPrev = item.ordkey.drop(2).take(3).toIntOrNull()
                    if (ordPrev != null && ordPrev > 0) {
                        ordPrev * 150
                    } else {
                        val ordRank = item.ordkey.getOrNull(1)?.digitToIntOrNull() ?: 1
                        ordRank * 200
                    }
                }
            }
        }
        return maxOf(1, baseSec - elapsedSec)
    }

    /**
     * 실시간 도착정보 생성시각(recptnDt, 예: "2026-11-10 09:05:12")과 현재 시각의 경과 시간(초)을 계산
     */
    private fun getElapsedSecondsFromRecptnDt(recptnDt: String): Int {
        if (recptnDt.isBlank()) return 0
        return runCatching {
            val cleaned = recptnDt.trim().substringBefore(".")
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val recptnDateTime = LocalDateTime.parse(cleaned, fmt)
            val nowDateTime = LocalDateTime.now()
            val diffSec = Duration.between(recptnDateTime, nowDateTime).seconds
            if (diffSec in 0..600) {
                diffSec.toInt()
            } else {
                val timeDiff = nowDateTime.toLocalTime().toSecondOfDay() - recptnDateTime.toLocalTime().toSecondOfDay()
                if (timeDiff in 0..600) timeDiff else 0
            }
        }.getOrDefault(0)
    }

    private fun getSubwayIdFromLineName(subCode: String): String {
        val clean = subCode.replace(" ", "")
        return when {
            clean.contains("인천1") || (clean.contains("1호선") && clean.contains("인천")) -> "1069"
            clean.contains("인천2") || (clean.contains("2호선") && clean.contains("인천")) -> "1070"
            clean.contains("1호선") && !clean.contains("인천") -> "1001"
            clean.contains("2호선") && !clean.contains("인천") -> "1002"
            clean.contains("3호선") -> "1003"
            clean.contains("4호선") -> "1004"
            clean.contains("5호선") -> "1005"
            clean.contains("6호선") -> "1006"
            clean.contains("7호선") -> "1007"
            clean.contains("8호선") -> "1008"
            clean.contains("9호선") -> "1009"
            clean.contains("경의중앙") -> "1063"
            clean.contains("공항철도") || clean.contains("공항") -> "1065"
            clean.contains("경춘") -> "1067"
            clean.contains("수인분당") -> "1075"
            clean.contains("신분당") -> "1077"
            clean.contains("신림") -> "1081"
            clean.contains("우이신설") -> "1092"
            clean.contains("서해") -> "1093"
            else -> ""
        }
    }

    /**
     * 서울시 정적 시간표 API 및 내부 SQLite DB(station_static_timetables) 기반으로
     * 현재 시각 이후의 가장 가까운 열차 도착 예정 목록 계산
     */
    private suspend fun fetchSubwayTimetableArrivals(target: TransitTarget): List<ArrivalInfo> {
        val stationCd = target.targetCode.trim()
        val cleanTargetName = target.name.removeSuffix("역").trim()
        val weekTag = getWeekTag()
        val filter = target.directionOrRoute.trim()
        val lineName = target.subCode.ifEmpty { "지하철" }
        val lineNum = SeoulSubwayTravelTimeManager.extractLineNumber(lineName) ?: lineName

        if (stationCd.isEmpty() && context == null) return emptyList()

        val inoutTags = when {
            filter.contains("상행") || filter.contains("내선") -> listOf("1")
            filter.contains("하행") || filter.contains("외선") -> listOf("2")
            else -> listOf("1", "2") // 양방향 모두 조회
        }

        val now = LocalTime.now()
        val nowSeconds = now.toSecondOfDay()
        val candidatePairs = mutableListOf<Pair<Int, ArrivalInfo>>()

        for (inout in inoutTags) {
            val rows = runCatching {
                val ctx = context
                // 1) 먼저 내부 SQLite DB(station_static_timetables)에 저장된 역 시간표가 있는지 확인
                if (ctx != null) {
                    val cachedDbRows = SubwayLocalDatabase.getStationStaticTimetable(
                        context = ctx,
                        stationCd = stationCd,
                        stationName = cleanTargetName,
                        lineCode = lineNum,
                        dayType = weekTag,
                        inoutTag = inout
                    )
                    if (cachedDbRows.isNotEmpty()) {
                        return@runCatching cachedDbRows
                    }
                }

                // 2) 내부 DB에 없으면 서울 열린데이터광장 API 조회 후 내부 SQLite DB에 저장
                if (stationCd.isEmpty()) return@runCatching emptyList()
                val response = api.getSubwayTimetable(
                    apiKey = SEOUL_SUBWAY_KEY,
                    startIndex = 1,
                    endIndex = 250,
                    stationCd = stationCd,
                    weekTag = weekTag,
                    inoutTag = inout
                )
                val list = response.service?.row ?: emptyList()
                if (list.isNotEmpty() && ctx != null) {
                    SubwayLocalDatabase.saveStationStaticTimetable(
                        context = ctx,
                        stationCd = stationCd,
                        stationName = cleanTargetName,
                        lineCode = lineNum,
                        dayType = weekTag,
                        inoutTag = inout,
                        items = list
                    )
                }
                list
            }.getOrElse { e ->
                Log.w("ArrivalRepo", "지하철 시간표 조회 실패, 내부 DB 확인 ($stationCd/$cleanTargetName): ${e.message}")
                val ctx = context
                if (ctx != null) {
                    SubwayLocalDatabase.getStationStaticTimetable(
                        context = ctx,
                        stationCd = stationCd,
                        stationName = cleanTargetName,
                        lineCode = lineNum,
                        dayType = weekTag,
                        inoutTag = inout
                    )
                } else emptyList()
            }

            val directionName = if (inout == "1") "상행" else "하행"

            rows.mapNotNullTo(candidatePairs) { item ->
                // 당역 종착 열차는 탑승 불가하므로 제외
                val cleanEnd = item.subwayEname.removeSuffix("역").trim()
                if (cleanEnd.isNotEmpty() && cleanEnd == cleanTargetName) {
                    return@mapNotNullTo null
                }

                val timeStr = item.leftTime.ifEmpty { item.arriveTime }
                val parts = timeStr.split(":").mapNotNull { it.toIntOrNull() }
                if (parts.size < 2) return@mapNotNullTo null

                val itemSeconds = parts[0] * 3600 + parts[1] * 60 + (parts.getOrNull(2) ?: 0)
                if (itemSeconds < nowSeconds) return@mapNotNullTo null

                val diffSeconds = itemSeconds - nowSeconds
                val diffMinutes = (diffSeconds + 30) / 60
                val expressTag = if (item.expressYn == "D" || item.expressYn == "EXPRESS") " (급행)" else ""

                val destTitle = if (item.subwayEname.isNotEmpty()) {
                    "${item.subwayEname}행$expressTag"
                } else {
                    "${directionName}방면$expressTag"
                }
                if (filter.isNotEmpty() && filter != "양방향" && !filter.contains(directionName) && !destTitle.contains(filter)) {
                    return@mapNotNullTo null
                }

                // 시간표 기준일 때는 몇분 몇초가 아닌 시간(HH:mm:ss) 자체를 표출
                val timeDisplay = if (diffMinutes <= 0) "곧 출발" else String.format("%02d:%02d:%02d", parts[0], parts[1], parts.getOrNull(2) ?: 0)
                val posDetail = SeoulSubwayTravelTimeManager.estimatePositionByRemainSeconds(
                    context = context,
                    lineNum = SeoulSubwayTravelTimeManager.extractLineNumber(lineName) ?: SeoulSubwayTravelTimeManager.extractLineNumber(item.lineNum),
                    targetStationRaw = target.name,
                    startStationRaw = item.subwaySname,
                    endStationRaw = item.subwayEname,
                    remainSeconds = diffSeconds
                )

                Pair(
                    diffSeconds,
                    ArrivalInfo(
                        lineOrBusName = lineName,
                        destination = destTitle,
                        arrivalTime = timeDisplay,
                        isSoon = diffMinutes <= 2,
                        remainSeconds = diffSeconds,
                        statusDetail = posDetail
                    )
                )
            }
        }

        return candidatePairs.sortedBy { it.first }.map { it.second }.take(2)
    }

    /**
     * 인천도시철도(1호선·2호선) 판별
     * 계양역, 검암역 등 타 노선(공항철도 등) 환승역인 경우 subCode가 인천선이어야 true
     */
    private fun isIncheonSubway(target: TransitTarget): Boolean {
        val line = target.subCode.replace(" ", "")
        // 노선명에 공항철도, 1호선, 7호선 등 타 노선이 명시되어 있으면 인천도시철도가 아님
        if (line.isNotEmpty() && !line.contains("인천")) {
            return false
        }
        if (line.contains("인천")) return true

        val code = target.targetCode
        if (code.startsWith("31") || code.startsWith("32")) return true // 인천1호선: 31xx, 인천2호선: 32xx

        val incheonOnlyStations = setOf(
            "신검단중앙", "아라", "원당", "검단호수공원", "귤현", "동양", "박촌", "임학", "계산", "경인교대입구", "작전",
            "갈산", "부평시장", "부평구청", "부평", "동수", "부평삼거리", "간석오거리", "인천시청", "예술회관",
            "인천터미널", "문학경기장", "선학", "신연수", "원인재", "동춘", "동막", "캠퍼스타운", "테크노파크",
            "지식정보단지", "인천대입구", "센트럴파크", "국제업무지구", "송도달빛축제공원",
            "검단오류", "왕길", "검단사거리", "마전", "완정", "독정", "검암", "검바위", "아시아드경기장", "아시아드",
            "서구청", "가정", "가정중앙시장", "가정중앙", "석남", "서부여성회관", "서부여성", "인천가좌", "가재울", "주안국가산단", "주안국가",
            "주안", "시민공원", "석바위시장", "석바위", "석천사거리", "모래내시장", "만수", "남동구청", "인천대공원", "운연"
        )
        val name = target.name.removeSuffix("역").trim()
        return incheonOnlyStations.contains(name)
    }

    /**
     * (Fallback용) 인천교통공사 공식 배차 간격 및 운행 시간 기반으로 다음 도착 예정 열차 계산
     */
    private fun calculateIncheonSubwayArrivals(target: TransitTarget): List<ArrivalInfo> {
        val name = target.name.removeSuffix("역").trim()
        val isLine2 = target.subCode.contains("2호선") || isLine2Station(name)
        val lineName = if (isLine2) "인천2호선" else "인천1호선"
        val lineCode = if (isLine2) "I2" else "I1"
        val filter = target.directionOrRoute.trim()

        val now = LocalTime.now()
        val hour = now.hour
        val minute = now.minute

        // 운행 종료 시간대 (00:30 ~ 05:30)
        if (hour in 1..4 || (hour == 0 && minute > 30) || (hour == 5 && minute < 30)) {
            return listOf(
                ArrivalInfo(
                    lineOrBusName = lineName,
                    destination = "운행 종료",
                    arrivalTime = "첫차 운행 05:30:00 [시간표]",
                    isSoon = false
                )
            )
        }

        // 출퇴근(RH), 평시(NH), 심야 배차 간격(분)
        val isLine1 = !isLine2
        val intervalMinutes = when {
            // 출퇴근 RH (07:00~09:00, 18:00~20:00)
            (hour in 7..8) || (hour in 18..19) -> if (isLine1) 5 else 4
            // 심야 (23:00~24:30)
            hour >= 23 || hour == 0 -> if (isLine1) 12 else 10
            // 평시 NH
            else -> if (isLine1) 8 else 6
        }

        val remainder = minute % intervalMinutes
        val nextMinutes1 = if (intervalMinutes - remainder <= 0) intervalMinutes else (intervalMinutes - remainder)
        val nextMinutes2 = nextMinutes1 + intervalMinutes

        val nextTime1 = now.plusMinutes(nextMinutes1.toLong())
        val nextTime2 = now.plusMinutes(nextMinutes2.toLong())
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm:00")

        val sec1 = maxOf(0, nextMinutes1 * 60 - now.second)
        val sec2 = maxOf(0, nextMinutes2 * 60 - now.second)

        // 방향 및 행선지 정의
        val destinations = if (isLine1) {
            listOf(
                Pair("상행", "검단호수공원행"),
                Pair("하행", "송도달빛축제공원행")
            )
        } else {
            listOf(
                Pair("상행", "검단오류행"),
                Pair("하행", "운연행")
            )
        }

        val results = mutableListOf<Pair<Int, ArrivalInfo>>()
        for ((dir, destTitle) in destinations) {
            if (destTitle.removeSuffix("행") == name) continue
            if (filter.isNotEmpty() && filter != "양방향" && !filter.contains(dir) && !destTitle.contains(filter)) {
                continue
            }

            val detail1 = SeoulSubwayTravelTimeManager.estimatePositionByRemainSeconds(
                context = context,
                lineNum = lineCode,
                targetStationRaw = name,
                endStationRaw = destTitle,
                remainSeconds = sec1
            )
            val detail2 = SeoulSubwayTravelTimeManager.estimatePositionByRemainSeconds(
                context = context,
                lineNum = lineCode,
                targetStationRaw = name,
                endStationRaw = destTitle,
                remainSeconds = sec2
            )

            // 첫 번째 도착 예정 열차
            results.add(
                Pair(
                    nextMinutes1,
                    ArrivalInfo(
                        lineOrBusName = lineName,
                        destination = destTitle,
                        arrivalTime = nextTime1.format(timeFmt),
                        isSoon = nextMinutes1 <= 2,
                        remainSeconds = sec1,
                        statusDetail = detail1
                    )
                )
            )

            // 두 번째 도착 예정 열차
            results.add(
                Pair(
                    nextMinutes2,
                    ArrivalInfo(
                        lineOrBusName = lineName,
                        destination = destTitle,
                        arrivalTime = nextTime2.format(timeFmt),
                        isSoon = false,
                        remainSeconds = sec2,
                        statusDetail = detail2
                    )
                )
            )
        }

        return results.sortedBy { it.first }.map { it.second }.take(2)
    }

    private fun isLine2Station(stationName: String): Boolean {
        val line2Stations = setOf(
            "검단오류", "왕길", "검단사거리", "마전", "완정", "독정", "검암", "검바위", "아시아드경기장", "아시아드",
            "서구청", "가정", "가정중앙시장", "가정중앙", "석남", "서부여성회관", "서부여성", "인천가좌", "가재울", "주안국가산단", "주안국가",
            "주안", "시민공원", "석바위시장", "석바위", "인천시청", "석천사거리", "모래내시장", "만수", "남동구청", "인천대공원", "운연"
        )
        return line2Stations.contains(stationName)
    }

    private fun getWeekTag(): String {
        return when (LocalDate.now().dayOfWeek) {
            DayOfWeek.SATURDAY -> "2"
            DayOfWeek.SUNDAY -> "3"
            else -> "1"
        }
    }

    private fun isGyeonggiBus(target: TransitTarget, cityCode: Int): Boolean {
        if (target.type != TransitType.BUS) return false
        val code = target.targetCode
        if (code.startsWith("GGB")) return true
        if (cityCode in 31010..31380) return true
        if (code.length == 9 && code.startsWith("2")) return true
        if (code == "21081" || target.subCode == "21081" || target.name.contains("지식산업단지")) return true
        return false
    }

    /**
     * 경기도 버스도착정보 (GBIS v2) API 조회
     * https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2
     */
    private suspend fun fetchGgbisBusArrivals(target: TransitTarget, filterRoute: String): List<ArrivalInfo> {
        var stationId = target.targetCode.removePrefix("GGB").trim()
        if (stationId == "21081") {
            stationId = "220000091" // (임시)지식산업단지 GBIS stationId 매핑
        }
        if (stationId.isEmpty()) return emptyList()

        return runCatching {
            val response = api.getGgbisBusArrival(
                serviceKey = BUS_SERVICE_KEY,
                stationId = stationId
            )
            val listEl = response.response?.msgBody?.busArrivalList ?: return@runCatching emptyList()
            val rawItems: List<JsonObject> = when (listEl) {
                is JsonArray -> listEl.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(listEl)
                else -> emptyList()
            }
            val parsed = rawItems.mapNotNull { obj ->
                runCatching { json.decodeFromJsonElement<GgbisBusArrivalItem>(obj) }.getOrNull()
            }

            val results = mutableListOf<Pair<Int, ArrivalInfo>>()
            for (item in parsed) {
                val routeName = item.routeName?.jsonPrimitive?.contentOrNull.orEmpty()
                if (routeName.isEmpty()) continue
                if (filterRoute.isNotEmpty() && !routeName.contains(filterRoute)) {
                    continue
                }

                val pTime1 = item.predictTime1?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val pSec1 = item.predictTimeSec1?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: ((pTime1 ?: 9999) * 60)
                val loc1 = item.locationNo1?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val seat1 = item.remainSeatCnt1?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 }

                val pTime2 = item.predictTime2?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val pSec2 = item.predictTimeSec2?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: ((pTime2 ?: 9999) * 60)
                val loc2 = item.locationNo2?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val seat2 = item.remainSeatCnt2?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 }

                val dest = if (item.routeDestName.isNotEmpty()) " (${item.routeDestName}행)" else ""

                if (pTime1 != null && pTime1 >= 0) {
                    val timeStr = if (pTime1 <= 0) "곧 도착" else "${pTime1}분 후 (${loc1 ?: 1}전)"
                    val extra1 = when {
                        item.stationNm1.isNotBlank() -> " (${item.stationNm1})"
                        seat1 != null -> " (빈좌석 ${seat1}석)"
                        else -> ""
                    }
                    val detail1 = "[${loc1 ?: 1}]번째 전$extra1"

                    results.add(
                        Pair(
                            pSec1,
                            ArrivalInfo(
                                lineOrBusName = "버스",
                                destination = "${routeName}번$dest",
                                arrivalTime = timeStr,
                                isSoon = pTime1 <= 2,
                                remainSeconds = pSec1,
                                statusDetail = detail1
                            )
                        )
                    )

                    // 같은 노선 필터인 경우 다음 2번째 버스도 함께 추가
                    if (filterRoute.isNotEmpty() && pTime2 != null && pTime2 > 0) {
                        val extra2 = when {
                            item.stationNm2.isNotBlank() -> " (${item.stationNm2})"
                            seat2 != null -> " (빈좌석 ${seat2}석)"
                            else -> ""
                        }
                        val detail2 = "[${loc2 ?: 2}]번째 전$extra2"

                        results.add(
                            Pair(
                                pSec2,
                                ArrivalInfo(
                                    lineOrBusName = "버스",
                                    destination = "${routeName}번$dest",
                                    arrivalTime = "${pTime2}분 후 (${loc2 ?: 2}전)",
                                    isSoon = false,
                                    remainSeconds = pSec2,
                                    statusDetail = detail2
                                )
                            )
                        )
                    }
                } else if (filterRoute.isNotEmpty()) {
                    val statusMsg = when (item.flag) {
                        "STOP" -> "운행 종료"
                        "WAIT" -> "차고지 대기"
                        else -> "도착 예정 정보 없음"
                    }
                    results.add(
                        Pair(
                            99999,
                            ArrivalInfo(
                                lineOrBusName = "버스",
                                destination = "${routeName}번$dest",
                                arrivalTime = statusMsg,
                                isSoon = false
                            )
                        )
                    )
                }
            }

            results.sortedBy { it.first }.map { it.second }.ifEmpty {
                // 실시간 버스가 없으면 배차 기반 시간표 안내 생성
                generateBusTimetableFallback(target)
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchBusArrivals(target: TransitTarget): List<ArrivalInfo> {
        val parts = target.directionOrRoute.split("|")
        val cityCode = parts.getOrNull(0)?.toIntOrNull() ?: 23
        val filterRoute = parts.getOrNull(1)?.takeIf { it != "ALL" } ?: ""

        // 1. 경기도 버스는 전용 GBIS v2 API 우선 호출
        if (isGyeonggiBus(target, cityCode)) {
            val ggArrivals = fetchGgbisBusArrivals(target, filterRoute)
            if (ggArrivals.isNotEmpty()) {
                return ggArrivals
            }
        }

        // 2. 전국 타 지역 버스는 국토부 TAGO API 호출
        val response = runCatching {
            api.getBusArrival(
                serviceKey = BUS_SERVICE_KEY,
                cityCode = cityCode,
                nodeId = target.targetCode
            )
        }.getOrNull()

        val itemsEl = response?.response?.body?.items
        val itemNode = (itemsEl as? JsonObject)?.get("item")
        val list: List<TagoBusArrivalItem> = when (itemNode) {
            is JsonArray -> json.decodeFromJsonElement(itemNode)
            is JsonObject -> listOf(json.decodeFromJsonElement(itemNode))
            else -> emptyList()
        }

        val results = list.mapNotNull { item ->
            val routeNoStr = item.routeNo?.jsonPrimitive?.content ?: ""
            if (filterRoute.isNotEmpty() && !routeNoStr.contains(filterRoute)) {
                return@mapNotNull null
            }
            val min = item.arrTime / 60
            val timeText = if (min <= 0) "곧 도착" else "${min}분 후 (${item.arrPrevCnt}전)"
            val vType = item.vehicleTp.takeIf { it.isNotBlank() && it != "일반차량" }?.let { " ($it)" } ?: ""
            val detail = "[${item.arrPrevCnt}]번째 전$vType"

            Pair(
                item.arrTime,
                ArrivalInfo(
                    lineOrBusName = "버스",
                    destination = "${routeNoStr}번",
                    arrivalTime = timeText,
                    isSoon = min <= 2,
                    remainSeconds = item.arrTime,
                    statusDetail = detail
                )
            )
        }.sortedBy { it.first }.map { it.second }

        return results.ifEmpty {
            generateBusTimetableFallback(target)
        }
    }

    /**
     * 버스 실시간 정보 부재 시 운행 시간표/배차 간격 기반 도착 정보 생성
     * (사용자 요청에 따라 "몇분 몇초" 대신 도착 시각 "HH:mm:ss" 표출)
     */
    private fun generateBusTimetableFallback(target: TransitTarget): List<ArrivalInfo> {
        val now = LocalTime.now()
        val hour = now.hour
        val minute = now.minute

        val routeLabel = target.subCode.takeIf { it.isNotEmpty() && it != "버스" }
            ?: target.directionOrRoute.split("|").getOrNull(1)?.takeIf { it.isNotEmpty() && it != "ALL" }
            ?: "버스"

        // 심야 운행 종료 시간대 (23:30 ~ 05:30)
        if (hour in 0..4 || (hour == 23 && minute > 30) || (hour == 5 && minute < 30)) {
            return listOf(
                ArrivalInfo(
                    lineOrBusName = routeLabel,
                    destination = target.name,
                    arrivalTime = "첫차 05:30:00",
                    isSoon = false,
                    remainSeconds = 0
                )
            )
        }

        // 출퇴근 8분, 평시 12분 배차 기반 다음 도착 시각 산출
        val interval = if (hour in 7..8 || hour in 18..19) 8 else 12
        val nextMin = interval - (minute % interval)
        val nextTime1 = now.plusMinutes(nextMin.toLong()).format(DateTimeFormatter.ofPattern("HH:mm:00"))
        val nextTime2 = now.plusMinutes((nextMin + interval).toLong()).format(DateTimeFormatter.ofPattern("HH:mm:00"))

        val remainSec1 = maxOf(0, nextMin * 60 - now.second)
        val remainSec2 = maxOf(0, (nextMin + interval) * 60 - now.second)
        val hops1 = maxOf(1, (remainSec1 + 45) / 90)
        val hops2 = maxOf(1, (remainSec2 + 45) / 90)

        return listOf(
            ArrivalInfo(
                lineOrBusName = routeLabel,
                destination = target.name,
                arrivalTime = nextTime1,
                isSoon = nextMin <= 2,
                remainSeconds = remainSec1,
                statusDetail = "[${hops1}]번째 전"
            ),
            ArrivalInfo(
                lineOrBusName = routeLabel,
                destination = target.name,
                arrivalTime = nextTime2,
                isSoon = false,
                remainSeconds = remainSec2,
                statusDetail = "[${hops2}]번째 전"
            )
        )
    }
}
