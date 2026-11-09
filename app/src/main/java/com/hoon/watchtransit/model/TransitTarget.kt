package com.hoon.watchtransit.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransitType {
    SUBWAY, BUS
}

@Serializable
data class TransitTarget(
    val id: String,              // 고유 식별자 (UUID 등)
    val type: TransitType,       // SUBWAY 또는 BUS
    val name: String,            // 역 이름 (예: "강남") 또는 정류장 이름 (예: "강남역중앙차로")
    val targetCode: String,      // 지하철 역코드 또는 버스 정류장 ID (stId / nodeId)
    val subCode: String = "",    // 지하철 호선명(예: "2호선") 또는 버스 ARS-ID(예: "22-001")
    val directionOrRoute: String = "", // 지하철 방면(예: "역삼방면") 또는 감시할 버스 번호(예: "광역 9404")
    val lat: Double = 0.0,       // 위도
    val lon: Double = 0.0        // 경도
)
