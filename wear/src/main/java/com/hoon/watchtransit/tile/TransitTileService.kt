package com.hoon.watchtransit.tile

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
            // 시간대별 분기: 오후 12시 이전 -> 1번째 대상 (출근), 오후 12시 이후 -> 2번째 대상 (퇴근, 2개 이상일 때)
            val isBeforeNoon = LocalTime.now().hour < 12
            val target = if (isBeforeNoon) {
                targets.first()
            } else {
                if (targets.size >= 2) targets[1] else targets.first()
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
}
