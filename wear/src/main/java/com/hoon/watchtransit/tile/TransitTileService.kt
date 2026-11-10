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
import androidx.wear.protolayout.LayoutElementBuilders.Row
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
 * - 화면을 가득 채우는 대형 타이포그래피 (첫 번째 도착 시간 38sp 강조)
 * - 등록된 역/정류장 좌우 네비게이션 (◀ / ▶ 및 현재 페이지 인덱스 표시)
 * - 워치 메인 앱과 100% 동일한 GPS 기반 자동 역 선택 & 시간대별 분기
 */
class TransitTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val RESOURCES_VERSION = "2"

    companion object {
        @Volatile
        private var lastManualIndex: Int? = null
        @Volatile
        private var lastManualTimeMs: Long = 0L
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val lastClickableId = requestParams.currentState?.lastClickableId

        // 사용자가 타일 상의 ◀ 또는 ▶ 버튼을 탭하여 특정 역으로 이동을 요청한 경우 처리
        if (lastClickableId != null && lastClickableId.startsWith("nav_goto_")) {
            val targetIdx = lastClickableId.removePrefix("nav_goto_").toIntOrNull()
            if (targetIdx != null) {
                lastManualIndex = targetIdx
                lastManualTimeMs = System.currentTimeMillis()
            }
        }

        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    val tile = buildTile()
                    completer.set(tile)
                } catch (e: Exception) {
                    completer.setException(e)
                }
            }
            "TransitTileRequest"
        }
    }

    private suspend fun buildTile(): TileBuilders.Tile {
        val storage = TransitWatchStorage(this)
        val targets = storage.savedTargetsFlow.first()

        val rootElement = if (targets.isEmpty()) {
            buildEmptyLayout()
        } else {
            val totalCount = targets.size
            val defaultIndex = calculateDefaultTargetIndex(targets)

            // 수동으로 이전/다음 역을 선택한 경우 3분간 해당 역 유지, 이후 또는 초기에는 GPS 최적 역 표시
            val isManualActive = lastManualIndex != null &&
                    (System.currentTimeMillis() - lastManualTimeMs < 180_000L)

            val currentIndex = if (isManualActive && lastManualIndex != null) {
                ((lastManualIndex!! % totalCount) + totalCount) % totalCount
            } else {
                defaultIndex
            }

            val target = targets[currentIndex]
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
                secondArrival = secondTimeStr,
                currentIndex = currentIndex,
                totalCount = totalCount
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
            .setFreshnessIntervalMillis(15_000L) // 15초 주기 갱신
            .build()
    }

    /**
     * 워치 메인 앱과 동일한 위치 기반 가장 가까운 역 선택 로직:
     * 1) GPS/네트워크 위치 기반으로 가장 가까운 역 자동 탐색
     * 2) 가장 가까운 역과 동일한 역(예: 계양역)이 여러 개 등록된 경우에만 시간대별(오전 1번 / 오후 2번) 분기
     */
    private fun calculateDefaultTargetIndex(targets: List<TransitTarget>): Int {
        val userLocation = getUserLocation(this@TransitTileService)
        val isBeforeNoon = LocalTime.now().hour < 12

        return if (userLocation != null) {
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
                if (isBeforeNoon) sameStationIndices[0] else sameStationIndices[1]
            } else {
                bestIdx
            }
        } else {
            val firstTarget = targets.first()
            val firstBaseName = firstTarget.name.split("(")[0].removeSuffix("역").trim()
            val sameStationIndices = targets.indices.filter { idx ->
                targets[idx].name.split("(")[0].removeSuffix("역").trim() == firstBaseName
            }
            if (sameStationIndices.size >= 2) {
                if (isBeforeNoon) sameStationIndices[0] else sameStationIndices[1]
            } else {
                0
            }
        }
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

    /**
     * 메인 타일 레이아웃:
     * - 화면을 꽉 채우는 대형 폰트 구성
     * - 첫 번째 도착 시간 38sp 대형 볼드 적용
     * - 하단 이전/다음 역(◀ / ▶) 터치 네비게이션 버튼 배치
     */
    private fun buildMainLayout(
        target: TransitTarget,
        direction: String,
        arrivalTime: String,
        secondArrival: String,
        currentIndex: Int,
        totalCount: Int
    ): LayoutElementBuilders.LayoutElement {
        val openAppClickModifier = ModifiersBuilders.Modifiers.Builder()
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

        // 1. 역명 헤더 (17sp 볼드, 옐로우 골드)
        val header = Text.Builder()
            .setText("[$badge] ${target.name}")
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(17f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(argb(0xFFFFD54F.toInt())) // Vibrant Warm Yellow
                    .build()
            )
            .build()

        // 2. 방면/노선 서브타이틀 (13sp)
        val dir = Text.Builder()
            .setText(direction)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(13f))
                    .setColor(argb(0xFFCBD5E1.toInt())) // Slate Light Gray
                    .build()
            )
            .build()

        // 3. 첫 번째 도착 예정 시간 (★ 38sp 대형 볼드 - 화면을 가득 채우는 하이라이트)
        val arrivalColor = if (isImminent) argb(0xFFFF5722.toInt()) else argb(0xFFFFFFFF.toInt())
        val arrival = Text.Builder()
            .setText(arrivalTime)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(38f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(arrivalColor)
                    .build()
            )
            .build()

        // 4. 다음 차량 도착 예정 안내 (12sp)
        val nextText = if (secondArrival.isNotEmpty()) "다음: $secondArrival" else " "
        val next = Text.Builder()
            .setText(nextText)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(12f))
                    .setColor(argb(0xFF94A3B8.toInt()))
                    .build()
            )
            .build()

        // 5. 이전/다음 역(◀ / ▶) 이동 컨트롤
        val navigationRow = buildNavigationRow(currentIndex, totalCount)

        // 메인 컨텐츠 칼럼 (상단 정보 + 대형 도착 시간 + 다음 안내 + 네비게이션)
        val column = Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(header)
            .addContent(Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(dir)
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(arrival)
            .addContent(Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(next)
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(navigationRow)
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(openAppClickModifier) // 중앙 어디든 탭하면 워치 앱 상세 화면으로 진입
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .build()
    }

    /**
     * 좌우 목록 이동 컨트롤 바:
     * - 등록된 역이 2개 이상일 때 ◀ / ▶ 터치 버튼과 (1/N) 인덱스 표시
     * - ◀ 클릭 시 이전 역, ▶ 클릭 시 다음 역 즉시 로드 (ActionBuilders.LoadAction)
     */
    private fun buildNavigationRow(currentIndex: Int, totalCount: Int): LayoutElementBuilders.LayoutElement {
        if (totalCount <= 1) {
            return Text.Builder()
                .setText("탭하여 전체보기")
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(11f))
                        .setColor(argb(0xFF64748B.toInt()))
                        .build()
                )
                .build()
        }

        val prevIdx = ((currentIndex - 1) % totalCount + totalCount) % totalCount
        val nextIdx = (currentIndex + 1) % totalCount

        // ◀ 이전 역 버튼
        val prevButton = buildNavButton("◀", "nav_goto_$prevIdx")

        // 현재 페이지 번호 (예: 2 / 3)
        val pageIndicator = Text.Builder()
            .setText("${currentIndex + 1} / $totalCount")
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(12f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(argb(0xFFCBD5E1.toInt()))
                    .build()
            )
            .build()

        // ▶ 다음 역 버튼
        val nextButton = buildNavButton("▶", "nav_goto_$nextIdx")

        return Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(prevButton)
            .addContent(Spacer.Builder().setWidth(dp(10f)).build())
            .addContent(pageIndicator)
            .addContent(Spacer.Builder().setWidth(dp(10f)).build())
            .addContent(nextButton)
            .build()
    }

    private fun buildNavButton(label: String, clickableId: String): LayoutElementBuilders.LayoutElement {
        val clickModifier = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId(clickableId)
                    .setOnClick(ActionBuilders.LoadAction.Builder().build())
                    .build()
            )
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(argb(0xFF262C3A.toInt()))
                    .setCorner(
                        ModifiersBuilders.Corner.Builder()
                            .setRadius(dp(14f))
                            .build()
                    )
                    .build()
            )
            .build()

        val text = Text.Builder()
            .setText(label)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(13f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(argb(0xFF60A5FA.toInt())) // Sky Blue Accent
                    .build()
            )
            .build()

        return Box.Builder()
            .setWidth(dp(34f))
            .setHeight(dp(28f))
            .setModifiers(clickModifier)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(text)
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
                    .setSize(sp(16f))
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
                    .setSize(sp(13f))
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
