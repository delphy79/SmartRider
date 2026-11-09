package com.hoon.watchtransit.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransitType {
    SUBWAY, BUS
}

@Serializable
data class TransitTarget(
    val id: String,
    val type: TransitType,
    val name: String,
    val targetCode: String,
    val subCode: String = "",
    val directionOrRoute: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

// 도착 정보 표시용 UI 모델
data class ArrivalInfo(
    val lineOrBusName: String, // 78번, 2호선 등
    val destination: String,   // 마전역 방면, 당산역행 등
    val arrivalTime: String,   // 3분 후 [2번째 전], 곧 도착 등
    val isSoon: Boolean = false,
    val remainSeconds: Int = 0, // 1초 단위 실시간 카운트다운용 남은 초
    val statusDetail: String = "" // 도착 시간 아래 작게 표시할 위치/부가정보 (예: "[3]번째 전역 (범계)", "[3]번째 전 (빈좌석 12석)")
)
