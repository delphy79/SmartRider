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
                .setFreshnessIntervalMillis(15_000L)
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
                .setFreshnessIntervalMillis(3_000L) // 위치 획득 대기 (3초 후 재시도)
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

        val arrivalTimeStr = if (firstArrival == null) {
            "도착 정보 없음"
        } else {
            val remain = if (firstArrival.remainSeconds > 0) {
                firstArrival.remainSeconds
            } else {
                getTimetableRemainSeconds(firstArrival.arrivalTime) ?: ArrivalRepository.getEffectiveRemainSeconds(firstArrival)
            }
            formatRemainSecondsForTile(remain, firstArrival.arrivalTime)
        }

        val secondTimeStr = if (secondArrival == null) {
            ""
        } else {
            val secRemain = if (secondArrival.remainSeconds > 0) {
                secondArrival.remainSeconds
            } else {
                getTimetableRemainSeconds(secondArrival.arrivalTime) ?: ArrivalRepository.getEffectiveRemainSeconds(secondArrival)
            }
            if (secRemain > 0) formatRemainSecondsForTile(secRemain, secondArrival.arrivalTime) else ""
        }

        // 3. 타일 단일 레이아웃 생성 (폰트 확대 및 상하 여백 최적화)
        val layout = buildMainLayout(
            target = target,
            direction = dirStr,
            arrivalTime = arrivalTimeStr,
            secondArrival = secondTimeStr
        )

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(Layout.Builder().setRoot(layout).build())
                    .build()
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(0L) // 실행/진입 시점에 무조건 즉시 최신 정보로 새로고침
            .build()
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

        // 1. 역명 헤더 (글자 길이에 맞춰 18sp ~ 21sp로 확대)
        val headerSp = if (target.name.length > 7) 18f else 21f
        val header = Text.Builder()
            .setText(target.name)
            .setMaxLines(1)
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

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(openAppClickModifier) // 중앙 어디든 탭하면 워치 앱 상세 화면으로 진입
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .build()
    }

    private fun buildSearchingLocationLayout(): LayoutElementBuilders.LayoutElement {
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
        // 앱/타일 실행 시 현재 위치부터 찾기 (최신 GPS/Network 위치 1.2초 대기)
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
        return freshLocation ?: TransitLocationCache.getCachedLocation(this@TransitTileService) ?: getUserLocation(this@TransitTileService)
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
        SubwayCoordinates.get(target.name)?.let {
            return it
        }
        return Pair(37.5, 127.0)
    }

    private fun getDistanceMeters(userLoc: Location?, target: TransitTarget): Float? {
        if (userLoc == null) return null
        val (tLat, tLon) = getStationLocation(target)
        val res = FloatArray(1)
        Location.distanceBetween(userLoc.latitude, userLoc.longitude, tLat, tLon, res)
        return res[0]
    }
}
