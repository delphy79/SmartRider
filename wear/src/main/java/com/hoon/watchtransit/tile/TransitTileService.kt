package com.hoon.watchtransit.tile

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hoon.watchtransit.data.TransitWatchStorage
import com.hoon.watchtransit.model.ArrivalInfo
import com.hoon.watchtransit.model.TransitTarget
import com.hoon.watchtransit.model.TransitType
import com.hoon.watchtransit.network.ArrivalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Wear OS 스마트라이더 실시간 도착 정보 타일 서비스
 * 시계 화면에서 오른쪽으로 스와이프하여 즉시 출퇴근 노선 실시간 도착 시간을 확인
 */
class TransitTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val RESOURCES_VERSION = "1"

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    val tile = buildTile(requestParams)
                    completer.set(tile)
                } catch (e: Exception) {
                    completer.setException(e)
                }
            }
            "TransitTileRequest"
        }
    }

    private suspend fun buildTile(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val storage = TransitWatchStorage(this)
        val targets = storage.savedTargetsFlow.first()

        val rootElement = if (targets.isEmpty()) {
            buildEmptyLayout()
        } else {
            // 워치 메인 앱과 100% 동일한 위치 기반 가장 가까운 역 선택 로직:
            // 1) GPS/네트워크 위치 기반으로 가장 가까운 역 자동 탐색
            // 2) 가장 가까운 역과 동일한 역(예: 계양역)이 여러 개 등록된 경우에만 시간대별(오전 1번 / 오후 2번) 분기
            val userLocation = getUserLocation(this@TransitTileService)
            val isBeforeNoon = LocalTime.now().hour < 12

            val target = if (userLocation != null) {
                val sortedIndices = targets.indices.sortedBy { idx ->
                    getDistanceMeters(userLocation, targets[idx]) ?: Float.MAX_VALUE
                }
                val bestIdx = sortedIndices.firstOrNull() ?: 0
                val bestTarget = targets[bestIdx]
                val bestBaseName = bestTarget.name.split("(")[0].removeSuffix("역").trim()

                val sameStationIndices = targets.indices.filter { idx ->
                    val baseName = targets[idx].name.split("(")[0].removeSuffix("역").trim()
                    baseName == bestBaseName
                }

                if (sameStationIndices.size >= 2) {
                    if (isBeforeNoon) targets[sameStationIndices[0]] else targets[sameStationIndices[1]]
                } else {
                    bestTarget
                }
            } else {
                // 위치 획득 실패 시 스마트폰 알림 목록 기준 (동일 역 2개 시 오전 1번/오후 2번)
                val firstTarget = targets.first()
                val firstBaseName = firstTarget.name.split("(")[0].removeSuffix("역").trim()
                val sameStationTargets = targets.filter { t ->
                    t.name.split("(")[0].removeSuffix("역").trim() == firstBaseName
                }
                if (sameStationTargets.size >= 2) {
                    if (isBeforeNoon) sameStationTargets[0] else sameStationTargets[1]
                } else {
                    targets.first()
                }
            }

            val repository = ArrivalRepository(this)
            val arrivals = runCatching { repository.fetchArrivals(target) }.getOrDefault(emptyList())
                .sortedBy { ArrivalRepository.getEffectiveRemainSeconds(it) }

            val firstArrival = arrivals.firstOrNull()
            val secondArrival = arrivals.getOrNull(1)

            val arrivalTimeStr = firstArrival?.let { formatArrivalForTile(it) } ?: "도착 정보 없음"
            val secondTimeStr = secondArrival?.let { formatArrivalForTile(it) } ?: ""
            val dirStr = formatDirection(target, firstArrival)

            buildMainLayout(
                target = target,
                direction = dirStr,
                arrivalTime = arrivalTimeStr,
                secondArrival = secondTimeStr
            )
        }

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(Layout.Builder().setRoot(rootElement).build())
                    .build()
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(15_000L) // 15초마다 타일 갱신
            .build()
    }

    private fun formatArrivalForTile(arrival: ArrivalInfo): String {
        return when {
            arrival.isSoon -> "곧 도착"
            arrival.remainSeconds in 1..59 -> "${arrival.remainSeconds}초"
            arrival.remainSeconds >= 60 -> {
                val m = arrival.remainSeconds / 60
                val s = arrival.remainSeconds % 60
                if (s == 0) "${m}분" else "${m}분 ${s}초"
            }
            arrival.arrivalTime.isNotEmpty() -> arrival.arrivalTime
            else -> "운행 종료"
        }
    }

    private fun formatDirection(target: TransitTarget, arrival: ArrivalInfo?): String {
        if (arrival != null && arrival.destination.isNotEmpty()) {
            val dest = arrival.destination
            val line = arrival.lineOrBusName.ifEmpty { target.subCode }
            return if (line.isNotEmpty() && !dest.contains(line)) "$line · $dest" else dest
        }
        val dir = target.directionOrRoute.trim()
        return if (dir.isNotEmpty() && dir != "ALL" && !dir.contains("|")) {
            "${target.subCode} · $dir"
        } else {
            target.subCode
        }
    }

    private fun buildMainLayout(
        target: TransitTarget,
        direction: String,
        arrivalTime: String,
        secondArrival: String
    ): LayoutElementBuilders.LayoutElement {
        val clickModifier = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId("open_app_from_tile")
                    .setOnClick(
                        ActionBuilders.LaunchAction.Builder()
                            .setAndroidActivity(
                                ActionBuilders.AndroidActivity.Builder()
                                    .setPackageName(packageName)
                                    .setClassName("com.hoon.watchtransit.presentation.MainActivity")
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val badge = if (target.type == TransitType.SUBWAY) "지하철" else "버스"
        val isImminent = arrivalTime.contains("곧") || (arrivalTime.contains("초") && !arrivalTime.contains("분"))

        // 1. 역명 헤더
        val header = Text.Builder()
            .setText("[$badge] ${target.name}")
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(14f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(argb(0xFFFFD54F.toInt())) // Yellow
                    .build()
            )
            .build()

        // 2. 방면/노선
        val dir = Text.Builder()
            .setText(direction)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(12f))
                    .setColor(argb(0xFFB0B0B0.toInt())) // Light gray
                    .build()
            )
            .build()

        // 3. 실시간 도착 시간
        val arrivalColor = if (isImminent) argb(0xFFFF7043.toInt()) else argb(0xFFFFFFFF.toInt())
        val arrival = Text.Builder()
            .setText(arrivalTime)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(26f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(arrivalColor)
                    .build()
            )
            .build()

        // 4. 다음 차량 안내 또는 탭 안내
        val nextLabel = if (secondArrival.isNotEmpty()) "다음: $secondArrival" else "탭하여 전체보기"
        val next = Text.Builder()
            .setText(nextLabel)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(11f))
                    .setColor(argb(0xFF888888.toInt()))
                    .build()
            )
            .build()

        val column = Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(header)
            .addContent(Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(dir)
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(arrival)
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(next)
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(clickModifier)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .build()
    }

    private fun buildEmptyLayout(): LayoutElementBuilders.LayoutElement {
        val clickModifier = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId("open_app_empty_tile")
                    .setOnClick(
                        ActionBuilders.LaunchAction.Builder()
                            .setAndroidActivity(
                                ActionBuilders.AndroidActivity.Builder()
                                    .setPackageName(packageName)
                                    .setClassName("com.hoon.watchtransit.presentation.MainActivity")
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val title = Text.Builder()
            .setText("대중교통알림")
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(15f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(argb(0xFFFFD54F.toInt()))
                    .build()
            )
            .build()

        val message = Text.Builder()
            .setText("스마트폰 앱에서\n역/정류장을 먼저\n설정해 주세요.")
            .setMaxLines(3)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(12f))
                    .setColor(argb(0xFFB0B0B0.toInt()))
                    .build()
            )
            .build()

        val column = Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(title)
            .addContent(Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(message)
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(clickModifier)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun getUserLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val net = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val passive = runCatching { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
        return listOfNotNull(gps, net, passive).maxByOrNull { it.time }
    }

    private fun getStationLocation(target: TransitTarget): Pair<Double, Double> {
        if (target.lat != 0.0 && target.lon != 0.0) {
            return Pair(target.lat, target.lon)
        }
        val name = target.name.removeSuffix("역").trim()
        return when {
            name.contains("계양") -> Pair(37.5715, 126.7360)
            name.contains("신검단중앙") -> Pair(37.6042, 126.7118)
            name.contains("완정") -> Pair(37.5929, 126.6730)
            name.contains("마전") -> Pair(37.6006, 126.6669)
            name.contains("검단사거리") -> Pair(37.6025, 126.6575)
            name.contains("서울역") || name == "서울" -> Pair(37.5547, 126.9706)
            name.contains("선바위") -> Pair(37.4518, 127.0029)
            name.contains("정부과천청사") -> Pair(37.4265, 126.9899)
            name.contains("과천") -> Pair(37.4337, 126.9967)
            name.contains("지식산업단지") -> Pair(37.4116, 126.9890)
            name.contains("강남") -> Pair(37.4979, 127.0276)
            name.contains("신도림") -> Pair(37.5088, 126.8913)
            name.contains("홍대입구") -> Pair(37.5575, 126.9244)
            name.contains("인천공항") -> Pair(37.4475, 126.4526)
            name.contains("김포공항") -> Pair(37.5623, 126.8015)
            name.contains("부평") -> Pair(37.4895, 126.7248)
            name.contains("송도달빛축제공원") -> Pair(37.3995, 126.6339)
            else -> Pair(37.5, 127.0)
        }
    }

    private fun getDistanceMeters(userLoc: Location?, target: TransitTarget): Float? {
        if (userLoc == null) return null
        val (tLat, tLon) = getStationLocation(target)
        val res = FloatArray(1)
        Location.distanceBetween(userLoc.latitude, userLoc.longitude, tLat, tLon, res)
        return res[0]
    }
}
