package com.hoon.watchtransit.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// 1. TAGO 버스 도착 정보 응답 DTO
@Serializable
data class TagoBusArrivalResponse(
    val response: TagoBusArrivalBodyContainer? = null
)

@Serializable
data class TagoBusArrivalBodyContainer(
    val body: TagoBusArrivalBody? = null
)

@Serializable
data class TagoBusArrivalBody(
    val items: JsonElement? = null
)

@Serializable
data class TagoBusArrivalItem(
    @SerialName("routeno") val routeNo: JsonElement? = null, // 노선번호 (숫자나 문자열 혼용 대응)
    @SerialName("arrtime") val arrTime: Int = 0,             // 남은 초(sec)
    @SerialName("arrprevstationcnt") val arrPrevCnt: Int = 0, // 남은 정류장 수
    @SerialName("vehicletp") val vehicleTp: String = ""      // 차량유형 (예: 저상버스, 일반차량)
)

// 2. 서울시 실시간 지하철 도착 정보 DTO
@Serializable
data class SeoulSubwayRealtimeResponse(
    val realtimeArrivalList: List<SubwayRealtimeItem> = emptyList()
)

@Serializable
data class SubwayRealtimeItem(
    @SerialName("subwayId") val subwayId: String = "",         // 지하철호선ID (예: 1065: 공항철도, 1001: 1호선)
    @SerialName("trainLineNm") val trainLineNm: String = "",   // 예: "검단오류행 - 마전방면"
    @SerialName("arvlMsg2") val arvlMsg2: String = "",         // 예: "3분 후 (완정)", "전역 도착", "[3]번째 전역 (범계)"
    @SerialName("arvlMsg3") val arvlMsg3: String = "",         // 예: "완정", "범계"
    @SerialName("updnLine") val updnLine: String = "",         // 상행/하행/내선/외선
    @SerialName("barvlDt") val barvlDt: String = "0",          // 남은 시간(초)
    @SerialName("bstatnNm") val bstatnNm: String = "",         // 종착지하철역명 (예: "방화", "성수", "송도달빛축제공원")
    @SerialName("arvlCd") val arvlCd: String = "",             // 도착코드 (0:진입, 1:도착, 2:출발, 3:전역출발, 4:전역진입, 5:전역도착, 99:운행중)
    @SerialName("ordkey") val ordkey: String = ""              // 순번 키 (예: 01001성수0)
)

// 3. 서울 열린데이터광장 지하철 시간표 조회 DTO
@Serializable
data class SeoulSubwayTimetableResponse(
    @SerialName("SearchSTNTimeTableByIDService")
    val service: SubwayTimetableService? = null
)

@Serializable
data class SubwayTimetableService(
    @SerialName("list_total_count") val totalCount: Int = 0,
    val row: List<SubwayTimetableItem> = emptyList()
)

@Serializable
data class SubwayTimetableItem(
    @SerialName("STATION_CD") val stationCd: String = "",
    @SerialName("STATION_NM") val stationNm: String = "",
    @SerialName("LINE_NUM") val lineNum: String = "",
    @SerialName("LEFTTIME") val leftTime: String = "",       // 출발시간 (HH:mm:ss)
    @SerialName("ARRIVETIME") val arriveTime: String = "",   // 도착시간 (HH:mm:ss)
    @SerialName("SUBWAYSNAME") val subwaySname: String = "", // 출발역 (예: "당고개", "오이도")
    @SerialName("SUBWAYENAME") val subwayEname: String = "", // 종착역 (예: "성수", "광운대")
    @SerialName("INOUT_TAG") val inoutTag: String = "",       // 1: 상행/내선, 2: 하행/외선
    @SerialName("EXPRESS_YN") val expressYn: String = ""     // G: 일반, D: 급행
)

// 4. 경기도 버스도착정보 (GBIS v2) DTO
@Serializable
data class GgbisBusArrivalResponse(
    val response: GgbisResponseContainer? = null
)

@Serializable
data class GgbisResponseContainer(
    val msgHeader: GgbisMsgHeader? = null,
    val msgBody: GgbisMsgBody? = null
)

@Serializable
data class GgbisMsgHeader(
    val resultCode: Int = -1,
    val resultMessage: String = ""
)

@Serializable
data class GgbisMsgBody(
    val busArrivalList: JsonElement? = null
)

@Serializable
data class GgbisBusArrivalItem(
    val routeId: Long = 0,
    val routeName: JsonElement? = null,       // int or string (e.g. 3, 6, "8-1")
    val routeDestName: String = "",           // 행선지 (예: "궁말차고지")
    val flag: String = "",                    // RUN, PASS, STOP, WAIT
    val locationNo1: JsonElement? = null,     // int or string
    val predictTime1: JsonElement? = null,    // 분 (int or string)
    val predictTimeSec1: JsonElement? = null, // 초 (int or string)
    val locationNo2: JsonElement? = null,
    val predictTime2: JsonElement? = null,
    val predictTimeSec2: JsonElement? = null,
    val remainSeatCnt1: JsonElement? = null,
    val remainSeatCnt2: JsonElement? = null,
    val stationNm1: String = "",              // 1번째 버스 현재 위치 정류소명
    val stationNm2: String = ""               // 2번째 버스 현재 위치 정류소명
)
