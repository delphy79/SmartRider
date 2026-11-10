package com.hoon.watchtransit.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// 1. 서울 열린데이터광장 지하철역 검색 API 응답 DTO
@Serializable
data class SeoulSubwaySearchResponse(
    @SerialName("SearchInfoBySubwayNameService")
    val service: SubwaySearchService? = null
)

@Serializable
data class SubwaySearchService(
    @SerialName("list_total_count") val totalCount: Int = 0,
    val row: List<SubwayStationItem> = emptyList()
)

@Serializable
data class SubwayStationItem(
    @SerialName("STATION_CD") val stationCd: String = "", // 전철역코드 (예: 3108, 3205)
    @SerialName("STATION_NM") val stationNm: String = "", // 전철역명 (예: 신검단중앙, 완정)
    @SerialName("LINE_NUM") val lineNum: String = "",     // 호선명 (예: 인천선, 인천2호선, 04호선)
    @SerialName("FR_CODE") val frCode: String = ""        // 외부코드 (예: I108, I205)
)

// 2. 국토교통부(TAGO) 버스정류소 검색 API 응답 DTO
@Serializable
data class TagoBusStopResponse(
    val response: TagoResponseBody? = null
)

@Serializable
data class TagoResponseBody(
    val header: TagoHeader? = null,
    val body: TagoBody? = null
)

@Serializable
data class TagoHeader(
    val resultCode: String = "",
    val resultMsg: String = ""
)

@Serializable
data class TagoBody(
    val items: JsonElement? = null,
    val totalCount: Int = 0
)

@Serializable
data class TagoBusStopItem(
    @SerialName("nodeid") val nodeId: String = "",
    @SerialName("nodenm") val nodeNm: String = "",
    @SerialName("nodeno") val nodeNo: String? = null,
    @SerialName("citycode") val cityCode: Int? = null
)

// 3. 서울 열린데이터광장 버스정류소 검색 API 응답 DTO
@Serializable
data class SeoulBusStopResponse(
    @SerialName("busStopLocationXyInfo")
    val service: SeoulBusStopService? = null
)

@Serializable
data class SeoulBusStopService(
    @SerialName("list_total_count") val totalCount: Int = 0,
    val row: List<SeoulBusStopItem> = emptyList()
)

@Serializable
data class SeoulBusStopItem(
    @SerialName("STOPS_NO") val stopsNo: String = "",
    @SerialName("STOPS_NM") val stopsNm: String = "",
    @SerialName("NODE_ID") val nodeId: String = "",
    @SerialName("STOPS_TYPE") val stopsType: String = "",
    @SerialName("XCRD") val xcrd: String = "",
    @SerialName("YCRD") val ycrd: String = ""
)

// 4. 국토교통부(TAGO) 도시코드 목록 조회 API 응답 DTO
@Serializable
data class TagoCityCodeResponse(
    val response: TagoCityCodeResponseBody? = null
)

@Serializable
data class TagoCityCodeResponseBody(
    val header: TagoHeader? = null,
    val body: TagoCityCodeBody? = null
)

@Serializable
data class TagoCityCodeBody(
    val items: JsonElement? = null
)

@Serializable
data class TagoCityCodeItem(
    @SerialName("citycode") val cityCode: Int = 0,
    @SerialName("cityname") val cityName: String = ""
)
