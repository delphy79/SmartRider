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
import com.hoon.watchtransit.data.SubwayCoordinates
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import kotlin.coroutines.resume

/**
 * Wear OS 스마트라이더 실시간 도착 정보 타일 서비스
 * - 타일 실행/진입 시 무조건 현재 위치에서 가장 가까운 역부터 표시 (이전 선택 기억 초기화)
 * - 5초 단위 타임라인 자동 전진으로 시간이 멈추지 않고 흘러가도록 구현
 * - 시간표 기반인 경우에도 남은 시간으로 환산 후 뒤에 [시간표]만 표기
 * - user_img4.png 디자인 반영: 역명(군더더기 없이 깔끔하게), 노선/방면, 도착시간, 다음안내, ◀ N/M ▶
 */
class TransitTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val RESOURCES_VERSION = "9"

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {

        // 백그라운드 GPS 위치 갱신 트리거
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
                .setFreshnessIntervalMillis(60_000L)
                .build()
        }

        // 1. 현재 사용자 위치 획득 (앱/타일 실행 시 현재 위치부터 찾기)
        val userLocation = getOrFetchUserLocation()

        if (userLocation == null && TransitLocationCache.getCachedLocation(this) == null) {
            val searchingLayout = buildSearchingLocationLayout()
            val timeline = TimelineBuilders.Timeline.Builder()
                .addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder()
                        .setLayout(Layout.Builder().setRoot(searchingLayout).build())
                        .build()
                )
                .build()

            return TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(timeline)
                .setFreshnessIntervalMillis(5_000L) // 위치 획득 대기 (5초 후 재시도)
                .build()
        }

        // 2. 현재 위치랑 가까운 곳부터 순서대로 목록 정렬
        val sortedTargets = sortTargetsByProximity(rawTargets, userLocation)
        // 이동 버튼 기능 삭제: 무조건 가장 가까운 곳 1개만 표시 (12시 기준 2개인 역 2번째 분기 유지)
        val (target, _) = sortedTargets.first()
        val repository = ArrivalRepository(this)
        val arrivals = runCatching { repository.fetchArrivals(target) }.getOrDefault(emptyList())
            .sortedBy { ArrivalRepository.getEffectiveRemainSeconds(it) }

        val firstArrival = arrivals.firstOrNull()
        val secondArrival = arrivals.getOrNull(1)

        val dirStr = formatDirection(target, firstArrival)

        // 3. 5초 단위 동적 타임라인 생성 (2분 분량 실시간 자동 전진)
        val timeline = buildDynamicTimeline(
            target = target,
            direction = dirStr,
            firstArrival = firstArrival,
            secondArrival = secondArrival
        )

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(120_000L) // 120초(2분) 주기 자동 새로고침 (동적 타임라인이 2분간 자동 전진, 터치 시 즉시 새로고침)
            .build()
    }

    /**
     * 5초 단위 타임라인 생성 (약 2분 분량, 5초마다 남은 시간 감소 및 실시간 전진)
     */
    private fun buildDynamicTimeline(
        target: TransitTarget,
        direction: String,
        firstArrival: ArrivalInfo?,
        secondArrival: ArrivalInfo?,
    ): TimelineBuilders.Timeline {
        val timelineBuilder = TimelineBuilders.Timeline.Builder()
        val nowMs = System.currentTimeMillis()

        if (firstArrival == null) {
            val layout = buildMainLayout(
                target = target,
                direction = direction,
                arrivalTime = "도착 정보 없음",
                secondArrival = "",
            )
            timelineBuilder.addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(Layout.Builder().setRoot(layout).build())
                    .build()
            )
            return timelineBuilder.build()
        }

        val initialRemain = if (firstArrival.remainSeconds > 0) {
            firstArrival.remainSeconds
        } else {
            getTimetableRemainSeconds(firstArrival.arrivalTime) ?: ArrivalRepository.getEffectiveRemainSeconds(firstArrival)
        }

        val secondInitialRemain = secondArrival?.let {
            if (it.remainSeconds > 0) it.remainSeconds else (getTimetableRemainSeconds(it.arrivalTime) ?: ArrivalRepository.getEffectiveRemainSeconds(it))
        }

        val stepSeconds = 5
        val maxSteps = 24 // 24 * 5초 = 120초 (2분 분량)

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
                arrivalTime = arrivalTimeStr,
                secondArrival = secondTimeStr,
            )

            val entryBuilder = TimelineBuilders.TimelineEntry.Builder()
                .setValidity(
                    TimelineBuilders.TimeInterval.Builder()
                        .setStartMillis(entryStartMs)
                        .apply {
                            if (step < steps - 1) {
                                setEndMillis(entryEndMs)
                            }
                        }
                        .build()
                )
                .setLayout(Layout.Builder().setRoot(layout).build())

            timelineBuilder.addTimelineEntry(entryBuilder.build())
        }

        return timelineBuilder.build()
    }

    /**
     * 현재 위치를 기준으로 역 목록 정렬
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

    private fun getTimetableRemainSeconds(rawTimeStr: String): Int? {
        val clean = rawTimeStr.trim()
        val match = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""").find(clean) ?: return null
        val h = match.groupValues[1].toIntOrNull() ?: return null
        val m = match.groupValues[2].toIntOrNull() ?: return null
        val s = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

        val now = LocalTime.now()
        var diffSec = (h * 3600 + m * 60 + s) - (now.hour * 3600 + now.minute * 60 + now.second)
        if (diffSec < -60) {
            diffSec += 86400
        }
        return if (diffSec >= 0) diffSec else 0
    }

    private fun formatRemainSecondsForTile(remainSeconds: Int, originalArrivalTime: String): String {
        val isTimetable = originalArrivalTime.contains("시간표") || Regex("""\d{1,2}:\d{2}""").containsMatchIn(originalArrivalTime)
        val suffix = if (isTimetable) " 🕒" else ""

        return when {
            remainSeconds <= 30 -> "곧 도착$suffix"
            remainSeconds in 31..59 -> "${remainSeconds}초$suffix"
            remainSeconds >= 60 -> {
                val m = remainSeconds / 60
                val s = remainSeconds % 60
                val tStr = "${m}분 ${s}초"
                "$tStr$suffix"
            }
            originalArrivalTime.isNotEmpty() -> {
                val clean = originalArrivalTime.removeSuffix(" [시간표]").removeSuffix("[시간표]").removeSuffix(" 🕒").removeSuffix(" 후").trim()
                if (clean.isNotEmpty()) "$clean$suffix" else "운행 종료"
            }
            else -> "운행 종료"
        }
    }

    private fun formatDirection(target: TransitTarget, arrival: ArrivalInfo?): String {
        if (target.type == TransitType.SUBWAY) {
            val line = arrival?.lineOrBusName?.ifEmpty { target.subCode } ?: target.subCode
            val dest = arrival?.destination?.ifEmpty { target.directionOrRoute } ?: target.directionOrRoute
            return if (line.isNotEmpty() && !dest.contains(line) && dest != "ALL" && !dest.contains("|")) {
                "$line · $dest"
            } else if (dest != "ALL" && !dest.contains("|") && dest.isNotEmpty()) {
                dest
            } else {
                line
            }
        } else {
            val line = arrival?.lineOrBusName?.ifEmpty {
                target.subCode.split("|").getOrNull(1)?.ifEmpty { target.subCode } ?: target.subCode
            } ?: target.subCode
            val dest = arrival?.destination?.ifEmpty { target.directionOrRoute } ?: target.directionOrRoute
            return if (line.isNotEmpty() && !dest.contains(line) && dest != "ALL" && !dest.contains("|")) {
                "$line · $dest"
            } else if (dest != "ALL" && !dest.contains("|") && dest.isNotEmpty()) {
                dest
            } else {
                line
            }
        }
    }

    /**
     * 메인 타일 레이아웃 (user_img4.png 완벽 일치):
     * 1) 역명 (예: 정부과천청사 - 군더더기 없이 중앙 배치)
     * 2) 노선/방면 (예: 4호선 · 불암산행 - 과천방면)
     * 3) 첫 번째 도착 예정 시간 (★ 38sp 대형 볼드 - 예: 6분 22초)
     * 4) 다음 차량 도착 안내 (예: 다음: 16분 22초)
     * 5) 네비게이션 컨트롤 (◀  2/8  ▶)
     */
    private fun buildMainLayout(
        target: TransitTarget,
        direction: String,
        arrivalTime: String,
        secondArrival: String,
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

        val isImminent = arrivalTime.contains("곧") || (arrivalTime.contains("초") && !arrivalTime.contains("분"))

        // 1. 역명 헤더 (글자 길이에 맞춰 18sp ~ 21sp로 확대, 역명 탭 시 앱 진입)
        val headerSp = if (target.name.length > 7) 18f else 21f
        val header = Text.Builder()
            .setText(target.name)
            .setMaxLines(1)
            .setModifiers(openAppClickModifier) // 역명 터치 시 워치 앱 상세 화면으로 진입
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(headerSp))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(argb(0xFFFFD54F.toInt())) // Vibrant Warm Yellow
                    .build()
            )
            .build()

        // 2. 방면/노선 서브타이틀 (13sp -> 15sp로 확대)
        val dir = Text.Builder()
            .setText(direction)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(15f))
                    .setColor(argb(0xFFCBD5E1.toInt())) // Slate Light Gray
                    .build()
            )
            .build()

        // 3. 첫 번째 도착 예정 시간 (38sp -> 42~46sp 대형 볼드로 시원하게 확대)
        val arrivalColor = if (isImminent) argb(0xFFFF5722.toInt()) else argb(0xFFFFFFFF.toInt())
        val isTimetable = arrivalTime.endsWith("🕒")
        val pureArrivalTime = if (isTimetable) arrivalTime.removeSuffix("🕒").trim() else arrivalTime
        val arrivalSp = if (pureArrivalTime.length > 6) 40f else 46f

        val arrivalText = Text.Builder()
            .setText(pureArrivalTime)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(arrivalSp))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(arrivalColor)
                    .build()
            )
            .build()

        val arrivalElement: LayoutElementBuilders.LayoutElement = if (isTimetable) {
            Row.Builder()
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(arrivalText)
                .addContent(Spacer.Builder().setWidth(dp(4f)).build())
                .addContent(
                    Text.Builder()
                        .setText("🕒")
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(sp(arrivalSp * 0.5f))
                                .build()
                        )
                        .build()
                )
                .build()
        } else {
            arrivalText
        }

        // 4. 다음 차량 도착 예정 안내 (12sp -> 14sp로 확대)
        val nextText = if (secondArrival.isNotEmpty()) "다음: $secondArrival" else " "
        val next = Text.Builder()
            .setText(nextText)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(14f))
                    .setColor(argb(0xFF94A3B8.toInt()))
                    .build()
            )
            .build()

        // 메인 컨텐츠 칼럼 (상하 여백 및 요소 간격 정돈으로 화면을 꽉 차게)
        val column = Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(header)
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(dir)
            .addContent(Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(arrivalElement)
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(next)
            .build()

        val refreshClickModifier = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId("refresh_tile_action")
                    .setOnClick(ActionBuilders.LoadAction.Builder().build())
                    .build()
            )
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(refreshClickModifier) // 타일 어디든 탭하면 즉시 실시간 정보 새로고침
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .build()
    }

    private fun buildSearchingLocationLayout(): LayoutElementBuilders.LayoutElement {
        val refreshClickModifier = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId("refresh_searching_tile")
                    .setOnClick(ActionBuilders.LoadAction.Builder().build())
                    .build()
            )
            .build()

        val text = Text.Builder()
            .setText("현재 위치를 검색하고 있습니다.")
            .setMaxLines(2)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(14f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(argb(0xFFCBD5E1.toInt()))
                    .build()
            )
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(refreshClickModifier) // 터치 시 위치 및 타일 새로고침
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
    private suspend fun getOrFetchUserLocation(): Location? {
        // 1. 최근 10분 이내 유효한 캐시 위치가 있으면 즉시 반환 (하드웨어 칩 구동 방지 -> 배터리 대폭 절약 & 즉시 로딩)
        val freshCached = TransitLocationCache.getCachedLocation(this@TransitTileService, TransitLocationCache.DEFAULT_MAX_AGE_MS)
        if (freshCached != null) {
            return freshCached
        }

        // 2. 단말의 최근 알려진 위치 중 10분 이내 데이터가 있는지 확인
        val lastKnown = getUserLocation(this@TransitTileService)
        if (lastKnown != null && (System.currentTimeMillis() - lastKnown.time) <= TransitLocationCache.DEFAULT_MAX_AGE_MS) {
            TransitLocationCache.saveLocation(this@TransitTileService, lastKnown)
            return lastKnown
        }

        // 3. 신선한 위치가 없을 때만 단일 위치 조회 (배터리 소모가 적은 Network Provider 우선)
        val freshLocation = withTimeoutOrNull(1200L) {
            suspendCancellableCoroutine { cont ->
                val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        TransitLocationCache.saveLocation(this@TransitTileService, loc)
                        if (cont.isActive) cont.resume(loc)
                        runCatching { lm.removeUpdates(this) }
                    }
                    override fun onProviderDisabled(provider: String) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    LocationManager.NETWORK_PROVIDER
                } else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    LocationManager.GPS_PROVIDER
                } else {
                    LocationManager.PASSIVE_PROVIDER
                }
                runCatching {
                    lm.requestSingleUpdate(provider, listener, mainLooper)
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    runCatching { lm.removeUpdates(listener) }
                }
            }
        }
        return freshLocation ?: TransitLocationCache.getCachedLocation(this@TransitTileService) ?: lastKnown
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
        // 이미 10분 이내 유효한 캐시 위치가 있으면 배터리를 소모하는 GPS/센서 갱신을 생략
        if (TransitLocationCache.isCacheFresh(this@TransitTileService)) return

        runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationManager.NETWORK_PROVIDER
            } else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                return
            }
            lm.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    TransitLocationCache.saveLocation(this@TransitTileService, location)
                    lm.removeUpdates(this)
                }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
            }, null)
        }
    }

    private fun getStationLocation(target: TransitTarget): Pair<Double, Double>? {
        if (target.lat != 0.0 && target.lon != 0.0) {
            return Pair(target.lat, target.lon)
        }
        return SubwayCoordinates.get(target.name)
    }

    private fun getDistanceMeters(userLoc: Location?, target: TransitTarget): Float? {
        if (userLoc == null) return null
        val (tLat, tLon) = getStationLocation(target) ?: return null
        val res = FloatArray(1)
        Location.distanceBetween(userLoc.latitude, userLoc.longitude, tLat, tLon, res)
        return res[0]
    }
}
