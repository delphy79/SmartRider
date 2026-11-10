package com.hoon.watchtransit.tile

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
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
import com.hoon.watchtransit.data.TransitLocationCache
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
 * - 현재 위치에서 가장 가까운 역부터 순서대로 자동 정렬 및 표시
 * - 5초 단위 타임라인 자동 전진으로 시간이 멈추지 않고 흘러가도록 구현
 * - 38sp 대형 타이포그래피로 화면 가득 시원한 도착 시간 표시
 * - ◀ / ▶ 버튼으로 1번째 가까운 역 -> 2번째 가까운 역 등으로 순회 가능
 */
class TransitTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val RESOURCES_VERSION = "3"

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

        // 백그라운드 GPS 위치 1회 갱신 트리거 (다음 타일 갱신 시 최신 위치 반영)
        triggerLocationUpdate()

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
        val rawTargets = storage.savedTargetsFlow.first()

        if (rawTargets.isEmpty()) {
            val emptyLayout = buildEmptyLayout()
            val timeline = TimelineBuilders.Timeline.Builder()
                .addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder()
                        .setLayout(Layout.Builder().setRoot(emptyLayout).build())
                        .build()
                )
                .build()

            return TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(timeline)
                .setFreshnessIntervalMillis(15_000L)
                .build()
        }

        // 1. 현재 사용자 위치 획득 (GPS / Network / SharedPreferences 캐시 종합)
        val userLocation = getUserLocation(this@TransitTileService)

        // 2. 현재 위치랑 가까운 곳부터 순서대로 목록 정렬!
        val sortedTargets = sortTargetsByProximity(rawTargets, userLocation)
        val totalCount = sortedTargets.size

        // 3. 수동으로 ◀ / ▶ 조작한 경우 3분간 유지, 그 외에는 0번(가장 가까운 곳)부터 표시!
        val isManualActive = lastManualIndex != null &&
                (System.currentTimeMillis() - lastManualTimeMs < 180_000L)

        val currentIndex = if (isManualActive && lastManualIndex != null) {
            ((lastManualIndex!! % totalCount) + totalCount) % totalCount
        } else {
            0 // 현재 위치에서 가장 가까운 역이 0번 인덱스
        }

        val (target, distanceMeters) = sortedTargets[currentIndex]
        val repository = ArrivalRepository(this)
        val arrivals = runCatching { repository.fetchArrivals(target) }.getOrDefault(emptyList())
            .sortedBy { ArrivalRepository.getEffectiveRemainSeconds(it) }

        val firstArrival = arrivals.firstOrNull()
        val secondArrival = arrivals.getOrNull(1)

        val dirStr = formatDirection(target, firstArrival)
        val distText = if (distanceMeters != null) {
            if (distanceMeters < 1000) " · ${distanceMeters.toInt()}m" else " · ${(distanceMeters / 1000f).let { String.format("%.1fkm", it) }}"
        } else ""

        // 4. 시간이 흘러가도록 5초 단위 타임라인 생성 (TileTimeline)
        val timeline = buildDynamicTimeline(
            target = target,
            direction = dirStr,
            distText = distText,
            firstArrival = firstArrival,
            secondArrival = secondArrival,
            currentIndex = currentIndex,
            totalCount = totalCount
        )

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(15_000L) // 15초마다 새 실시간 API 정보로 갱신
            .build()
    }

    /**
     * 현재 위치를 기준으로 역 목록 정렬:
     * 1) 현재 위치에서 가장 가까운 순서대로 정렬
     * 2) 가장 가까운 역과 동일한 역(예: 계양역)이 복수 등록된 경우:
     *    - 오전(12시 이전): 1번째 등록 노선 우선
     *    - 오후(12시 이후): 2번째 등록 노선 우선
     * 3) 그 외의 역들도 현재 위치에서 가까운 순서대로 정렬되어 배치됨 (1번째 가까운 곳 -> 2번째 가까운 곳 -> ...)
     */
    private fun sortTargetsByProximity(
        targets: List<TransitTarget>,
        userLocation: Location?
    ): List<Pair<TransitTarget, Float?>> {
        if (targets.isEmpty()) return emptyList()
        if (userLocation == null) {
            return targets.map { it to null }
        }

        val withDistance = targets.map { target ->
            target to getDistanceMeters(userLocation, target)
        }

        val sortedByDist = withDistance.sortedBy { it.second ?: Float.MAX_VALUE }
        val closest = sortedByDist.firstOrNull() ?: return withDistance

        val closestBaseName = closest.first.name.split("(")[0].removeSuffix("역").trim()
        val sameStationList = sortedByDist.filter {
            it.first.name.split("(")[0].removeSuffix("역").trim() == closestBaseName
        }
        val otherStationsList = sortedByDist.filter {
            it.first.name.split("(")[0].removeSuffix("역").trim() != closestBaseName
        }

        val isBeforeNoon = LocalTime.now().hour < 12
        val primaryStation = if (sameStationList.size >= 2) {
            if (isBeforeNoon) sameStationList else listOf(sameStationList[1], sameStationList[0]) + sameStationList.drop(2)
        } else {
            sameStationList
        }

        return primaryStation + otherStationsList
    }

    /**
     * 5초 단위 타임라인 생성:
     * - 시스템이 5초마다 다음 타임라인 엔트리로 자동 전진하여 초/분이 실제로 흘러가도록 구현
     * - 화면을 켜놓고 볼 때도 5초마다 카운트다운이 갱신되며, 화면을 껐다 켜도 정확한 현재 남은 시간이 표시됨
     */
    private fun buildDynamicTimeline(
        target: TransitTarget,
        direction: String,
        distText: String,
        firstArrival: ArrivalInfo?,
        secondArrival: ArrivalInfo?,
        currentIndex: Int,
        totalCount: Int
    ): TimelineBuilders.Timeline {
        val timelineBuilder = TimelineBuilders.Timeline.Builder()
        val nowMs = System.currentTimeMillis()

        if (firstArrival == null) {
            val layout = buildMainLayout(
                target = target,
                direction = direction,
                distText = distText,
                arrivalTime = "도착 정보 없음",
                secondArrival = "",
                currentIndex = currentIndex,
                totalCount = totalCount
            )
            timelineBuilder.addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(Layout.Builder().setRoot(layout).build())
                    .build()
            )
            return timelineBuilder.build()
        }

        val initialRemain = ArrivalRepository.getEffectiveRemainSeconds(firstArrival)
        val secondInitialRemain = secondArrival?.let { ArrivalRepository.getEffectiveRemainSeconds(it) }

        // 5초 단위로 최대 5분(60개 엔트리) 생성
        val stepSeconds = 5
        val maxSteps = 60

        // 남은 시간 + 15초(도착 완료 표시)까지 타임라인 생성
        val steps = if (initialRemain in 1..300) {
            ((initialRemain + 15) / stepSeconds).coerceIn(1, maxSteps)
        } else {
            maxSteps
        }

        for (step in 0 until steps) {
            val elapsedSec = step * stepSeconds
            val entryStartMs = nowMs + elapsedSec * 1000L
            val entryEndMs = entryStartMs + stepSeconds * 1000L

            val curRemain = (initialRemain - elapsedSec).coerceAtLeast(0)
            val arrivalTimeStr = formatRemainSecondsForTile(curRemain, firstArrival.arrivalTime)

            val curSecondRemain = secondInitialRemain?.let { (it - elapsedSec).coerceAtLeast(0) }
            val secondTimeStr = if (curSecondRemain != null && curSecondRemain > 0) {
                formatRemainSecondsForTile(curSecondRemain, secondArrival.arrivalTime)
            } else ""

            val layout = buildMainLayout(
                target = target,
                direction = direction,
                distText = distText,
                arrivalTime = arrivalTimeStr,
                secondArrival = secondTimeStr,
                currentIndex = currentIndex,
                totalCount = totalCount
            )

            timelineBuilder.addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setValidity(
                        TimelineBuilders.TimeInterval.Builder()
                            .setStartMillis(entryStartMs)
                            .setEndMillis(entryEndMs)
                            .build()
                    )
                    .setLayout(Layout.Builder().setRoot(layout).build())
                    .build()
            )
        }

        return timelineBuilder.build()
    }

    private fun formatRemainSecondsForTile(remainSeconds: Int, originalArrivalTime: String): String {
        return when {
            remainSeconds <= 0 -> {
                if (originalArrivalTime.isNotEmpty()) originalArrivalTime else "곧 도착"
            }
            remainSeconds < 30 -> "곧 도착"
            remainSeconds in 30..59 -> "${remainSeconds}초"
            remainSeconds >= 60 -> {
                val m = remainSeconds / 60
                val s = remainSeconds % 60
                if (s == 0) "${m}분" else "${m}분 ${s}초"
            }
            originalArrivalTime.isNotEmpty() -> originalArrivalTime
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
     * - 현재 위치로부터의 거리 표시 (예: · 120m)
     * - 하단 이전/다음 역(◀ / ▶) 터치 네비게이션 버튼 배치
     */
    private fun buildMainLayout(
        target: TransitTarget,
        direction: String,
        distText: String,
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

        // 1. 역명 헤더 + 거리 표시 (17sp 볼드, 옐로우 골드)
        val headerTitle = "[$badge] ${target.name}$distText"
        val header = Text.Builder()
            .setText(headerTitle)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(16f))
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

        // 3. 첫 번째 도착 예정 시간 (★ 38sp 대형 볼드 - 시간이 5초마다 자동으로 흘러감)
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

        // 메인 컨텐츠 칼럼
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
     * - 거리순으로 정렬된 목록을 ◀ / ▶로 자유롭게 순회
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

        // 현재 순서 번호 (예: 1 / 3)
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
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val gps = runCatching { lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val net = runCatching { lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val passive = runCatching { lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
        val cached = TransitLocationCache.getCachedLocation(context)

        return listOfNotNull(gps, net, passive, cached).maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private fun triggerLocationUpdate() {
        runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }
            lm.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    TransitLocationCache.saveLocation(this@TransitTileService, location)
                    lm.removeUpdates(this)
                }
            }, null)
        }
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
