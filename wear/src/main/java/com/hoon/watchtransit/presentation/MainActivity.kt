@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.hoon.watchtransit.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import com.hoon.watchtransit.data.SubwayCoordinates
import com.hoon.watchtransit.data.TransitLocationCache
import com.hoon.watchtransit.data.TransitWatchStorage
import com.hoon.watchtransit.model.ArrivalInfo
import com.hoon.watchtransit.model.TransitTarget
import com.hoon.watchtransit.model.TransitType
import com.hoon.watchtransit.network.ArrivalRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.time.LocalTime


/**
 * 디자인 목업에 맞춰 노선명(예: 4호선, 3번)과 방면/행선지(예: 불암산방면, 궁말차고지행)를 깔끔하게 분리 추출하는 헬퍼
 */
private fun resolveLineAndDest(target: TransitTarget, arrival: ArrivalInfo): Pair<String, String> {
    if (target.type == TransitType.SUBWAY) {
        val line = arrival.lineOrBusName.ifEmpty { target.subCode.ifEmpty { "지하철" } }
        val dest = arrival.destination.ifEmpty { target.directionOrRoute.ifEmpty { "방면" } }
        return Pair(line, dest)
    } else {
        // 버스의 경우 (예: "3번 (궁말차고지행)", "3번", "궁말차고지행")
        val busMatch = Regex("""^([0-9a-zA-Z\-가-힣]+번)(.*)""").find(arrival.destination)
        if (busMatch != null) {
            val routeNo = busMatch.groupValues[1]
            var rest = busMatch.groupValues[2].trim()
            if (rest.startsWith("(") && rest.endsWith(")")) {
                rest = rest.substring(1, rest.length - 1).trim()
            }
            val dest = if (rest.isNotEmpty()) rest else target.directionOrRoute.takeIf { it != "ALL" && !it.contains("|") } ?: "방면"
            return Pair(routeNo, dest)
        } else {
            val routeNo = target.subCode.takeIf { it.isNotEmpty() && it != "번호없음" && it.contains("번") }
                ?: if (target.subCode.isNotEmpty() && target.subCode.all { it.isDigit() }) "${target.subCode}번"
                else arrival.lineOrBusName.takeIf { it != "버스" }
                ?: "버스"
            val dest = arrival.destination.ifEmpty { "방면" }
            return Pair(routeNo, dest)
        }
    }
}

/**
 * 텍스트 내용의 문자 종류(한글, 영문, 숫자, 기호)별 너비 가중치를 계산하여
 * 화면 가용 폭 내에서 한 줄을 넘지 않는 최대 폰트 크기를 동적으로 산출
 */
private fun getFittedFontSize(
    text: String,
    targetMaxSp: Float,
    targetMinSp: Float = 11f,
    maxAvailableWidthDp: Float = 180f
): TextUnit {
    if (text.isEmpty()) return targetMaxSp.sp
    var weightedLength = 0f
    for (ch in text) {
        weightedLength += when {
            ch in '가'..'힣' || ch in 'ㄱ'..'ㅎ' -> 1.0f   // 한글 1글자
            ch.isDigit() -> 0.58f                          // 숫자
            ch == ':' || ch == '.' || ch == ',' || ch == ' ' -> 0.32f // 기호 및 공백
            ch in 'a'..'z' || ch in 'A'..'Z' -> 0.55f      // 영문
            ch == '(' || ch == ')' || ch == '[' || ch == ']' -> 0.4f  // 괄호
            else -> 0.8f
        }
    }
    val calculatedSp = maxAvailableWidthDp / weightedLength
    val finalSp = calculatedSp.coerceIn(targetMinSp, targetMaxSp)
    return finalSp.sp
}

class MainActivity : ComponentActivity() {
    private val storage by lazy { TransitWatchStorage(applicationContext) }
    private val repository by lazy { ArrivalRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WatchAppScreen(storage, repository)
            }
        }
    }
}

@Composable
fun WatchAppScreen(storage: TransitWatchStorage, repository: ArrivalRepository) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val targets by storage.savedTargetsFlow.collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    var selectedTarget by remember { mutableStateOf<TransitTarget?>(null) }
    var arrivalList by remember { mutableStateOf<List<ArrivalInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // 1. 현재 위치 상태 관리 (가장 가까운 곳 자동 스크롤)
    var isLocating by remember { mutableStateOf(true) }
    var userLocation by remember { mutableStateOf<Location?>(null) }
    val listState = rememberScalingLazyListState()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { arrivalList.size.coerceAtLeast(1) })

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            requestUserLocation(context) { loc -> userLocation = loc }
        }
    }

    LaunchedEffect(Unit) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            withTimeoutOrNull(2500L) {
                suspendCancellableCoroutine { cont ->
                    requestUserLocation(context) { loc ->
                        userLocation = loc
                        TransitLocationCache.saveLocation(context, loc)
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                }
            }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (userLocation == null) {
            userLocation = TransitLocationCache.getCachedLocation(context)
        }
        isLocating = false
    }

    // 위치 획득 시 목록 중 가장 가까운 역으로 자동 스크롤
    // 단, 가장 가까운 역과 동일한 역(예: 계양역)이 여러 개 등록된 경우에만 시간대별 분기 적용:
    // 오전(오후 12시 이전) -> 1번째 등록 역, 오후(오후 12시 이후) -> 2번째 등록 역
    val closestIndex = remember(userLocation, targets) {
        if (userLocation == null || targets.isEmpty()) {
            null
        } else {
            // 1. 현재 위치에서 가장 가까운 순으로 인덱스 정렬
            val sortedIndices = targets.indices.sortedBy { idx ->
                getDistanceMeters(userLocation, targets[idx]) ?: Float.MAX_VALUE
            }
            val bestIdx = sortedIndices.firstOrNull() ?: return@remember null
            val bestTarget = targets[bestIdx]
            val bestBaseName = bestTarget.name.split("(")[0].removeSuffix("역").trim()

            // 2. 가장 가까운 역과 동일한 역(역명이 같은 항목)만 원래 등록 순서대로 필터링
            val sameStationIndices = targets.indices.filter { idx ->
                val baseName = targets[idx].name.split("(")[0].removeSuffix("역").trim()
                baseName == bestBaseName
            }

            val isBeforeNoon = LocalTime.now().hour < 12

            if (sameStationIndices.size >= 2) {
                // 같은 역이 2개 이상 등록된 경우: 오전은 1번째, 오후는 2번째 역 선택
                if (isBeforeNoon) {
                    sameStationIndices[0]
                } else {
                    sameStationIndices[1]
                }
            } else {
                // 서로 다른 역인 경우: 물리적으로 가장 가까운 역(bestIdx) 그대로 표시
                bestIdx
            }
        }
    }

    fun refreshArrival(target: TransitTarget) {
        coroutineScope.launch {
            isLoading = true
            arrivalList = repository.fetchArrivals(target).sortedBy { ArrivalRepository.getEffectiveRemainSeconds(it) }
            isLoading = false
            pagerState.scrollToPage(0)
        }
    }

    // 앱 실행 후 위치 획득 완료 시 목록이 먼저 표출되고, 가장 가까운 역으로 자동 스크롤
    LaunchedEffect(isLocating, closestIndex) {
        if (!isLocating && closestIndex != null && closestIndex >= 0 && selectedTarget == null) {
            listState.animateScrollToItem(closestIndex)
        }
    }

    // 2. 실시간 정보 보는 중에는 워치가 꺼지지 않도록 화면 켜짐 유지 (FLAG_KEEP_SCREEN_ON)
    DisposableEffect(selectedTarget != null) {
        if (selectedTarget != null) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 3. 1초마다 자동 카운트다운 갱신 및 15초 주기 백그라운드 데이터 동기화
    var secondsOffset by remember { mutableStateOf(0) }
    LaunchedEffect(selectedTarget) {
        secondsOffset = 0
        if (selectedTarget != null) {
            pagerState.scrollToPage(0)
            while (isActive) {
                delay(1000L)
                secondsOffset++
                // 15초마다 최신 서버 데이터 동기화
                if (secondsOffset % 15 == 0 && selectedTarget != null) {
                    val updated = repository.fetchArrivals(selectedTarget!!)
                    if (updated.isNotEmpty()) {
                        arrivalList = updated
                        secondsOffset = 0
                    }
                }
            }
        }
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (isLocating) {
                // 앱 실행 시 현재 위치 검색 중 화면
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "현재 위치를 검색하고 있습니다.",
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else if (selectedTarget == null) {
                // 1. 역/정류장 목록 화면
                if (targets.isEmpty()) {
                    Text(
                        text = "스마트폰 앱에서\n역/정류장을 먼저\n설정해 주세요.",
                        textAlign = TextAlign.Center,
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                } else {
                    ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        autoCentering = AutoCenteringParams(itemIndex = 0)
                    ) {
                        items(targets.size) { idx ->
                            val item = targets[idx]
                            val isClosest = idx == closestIndex
                            val dist = getDistanceMeters(userLocation, item)
                            val distText = if (dist != null) {
                                if (dist < 1000) " · ${dist.toInt()}m" else " · ${(dist / 1000f).let { String.format("%.1fkm", it) }}"
                            } else ""

                            Chip(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                onClick = {
                                    selectedTarget = item
                                    refreshArrival(item)
                                },
                                label = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = item.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isClosest) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "📍",
                                                fontSize = 9.sp,
                                                color = Color(0xFFFFD54F),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                },
                                secondaryLabel = {
                                    val subText = if (item.type == TransitType.BUS) {
                                        val arsId = item.targetCode.ifEmpty { item.subCode.split("|")[0].trim() }
                                        "ARS ID: $arsId"
                                    } else {
                                        val dirText = formatDirection(item)
                                        if (dirText.isNotEmpty() && dirText != "ALL" && !dirText.contains("|")) {
                                            "${item.subCode} · $dirText"
                                        } else {
                                            item.subCode
                                        }
                                    }
                                    Text(
                                        text = subText,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = if (isClosest) {
                                    ChipDefaults.gradientBackgroundChipColors(
                                        startBackgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.5f),
                                        endBackgroundColor = MaterialTheme.colors.surface
                                    )
                                } else {
                                    ChipDefaults.primaryChipColors()
                                }
                            )
                        }
                    }
                }
            } else {
                // 2. 도착 예정 정보 화면 (원형 화면 상하 여백 최적화 및 다단 비율 레이아웃)
                val current = selectedTarget!!

                if (isLoading) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "도착 정보 조회 중...", fontSize = 11.sp, color = Color.Gray)
                    }
                } else if (arrivalList.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        val emptyText = "도착 정보 없음"
                        val emptyFontSize = getFittedFontSize(emptyText, targetMaxSp = 18f, targetMinSp = 13f, maxAvailableWidthDp = 160f)
                        Text(
                            text = emptyText,
                            textAlign = TextAlign.Center,
                            color = Color.LightGray,
                            fontSize = emptyFontSize,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF2C303A))
                                .border(1.dp, Color(0xFF4C5466), RoundedCornerShape(16.dp))
                                .clickable { selectedTarget = null }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "목록",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                } else {
                    val sortedArrivals = remember(arrivalList, secondsOffset) {
                        arrivalList.sortedBy { arrival ->
                            val remaining = maxOf(0, arrival.remainSeconds - secondsOffset)
                            if (arrival.isSoon) {
                                if (remaining in 1..40) remaining else 15
                            } else if (arrival.remainSeconds > 0) {
                                remaining
                            } else {
                                ArrivalRepository.getEffectiveRemainSeconds(arrival)
                            }
                        }
                    }

                    // 한 번 스크롤(스와이프)마다 다음 도착 카드로 즉시 스냅 전환되는 VerticalPager
                    VerticalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        if (page < sortedArrivals.size) {
                            val arrival = sortedArrivals[page]
                            val liveTimeText = formatLiveArrivalText(arrival, secondsOffset)
                            val numCircle = when (page) { 0 -> "①"; 1 -> "②"; 2 -> "③"; else -> "(${page + 1})" }
                            val (lineLabel, destLabel) = resolveLineAndDest(current, arrival)
                            val stationDisplayName = if (current.type == TransitType.SUBWAY && !current.name.endsWith("역")) {
                                "${current.name}역"
                            } else {
                                current.name
                            }
                            val hasDetail = arrival.statusDetail.isNotEmpty()
                            val stationFontSize = getFittedFontSize(stationDisplayName, targetMaxSp = 16f, targetMinSp = 12f, maxAvailableWidthDp = 175f)
                            val noPaddingStyle = LocalTextStyle.current.copy(
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )

                            // statusDetail 라벨이 추가된 경우 전체 상하 여백과 각 라벨 영역(Box)의 비율을 재조정하여 모든 라벨이 잘리지 않고 선명히 보이도록 배치
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        start = 8.dp,
                                        end = 8.dp,
                                        top = if (hasDetail) 15.dp else 20.dp,
                                        bottom = if (hasDetail) 10.dp else 18.dp
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    // 1. 순번 + 노선명
                                    Box(
                                        modifier = Modifier
                                            .weight(if (hasDetail) 0.95f else 1f)
                                            .fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val lineText = "$numCircle $lineLabel"
                                        val lineFontSize = getFittedFontSize(lineText, targetMaxSp = 15f, targetMinSp = 11f, maxAvailableWidthDp = 155f)
                                        Text(
                                            text = lineText,
                                            fontSize = lineFontSize,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colors.primary,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            softWrap = false,
                                            style = noPaddingStyle
                                        )
                                    }

                                    // 2. 역 / 정류소 이름 (2행으로 이동)
                                    Box(
                                        modifier = Modifier
                                            .weight(if (hasDetail) 1.0f else 1.1f)
                                            .fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val stationFontSize = getFittedFontSize(stationDisplayName, targetMaxSp = 18f, targetMinSp = 13f, maxAvailableWidthDp = 175f)
                                        Text(
                                            text = stationDisplayName,
                                            fontSize = stationFontSize,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            softWrap = false,
                                            style = noPaddingStyle
                                        )
                                    }

                                    // 3. 도착 시간 (메인 카운트다운/시각 - 시간표 아이콘 반으로 축소 및 세로 중앙 정렬)
                                    Box(
                                        modifier = Modifier
                                            .weight(if (hasDetail) 1.6f else 2.0f)
                                            .fillMaxWidth()
                                            .clickable { refreshArrival(current) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val isTimetable = liveTimeText.endsWith("🕒")
                                        val pureTimeText = if (isTimetable) liveTimeText.removeSuffix("🕒").trim() else liveTimeText

                                        val timeFontSize = getFittedFontSize(
                                            liveTimeText,
                                            targetMaxSp = if (hasDetail) 36f else 44f,
                                            targetMinSp = 20f,
                                            maxAvailableWidthDp = 185f
                                        )
                                        val timeColor = if (arrival.isSoon || isSoonByRemaining(arrival, secondsOffset) || pureTimeText.contains("곧"))
                                            Color(0xFFFF5252)
                                        else
                                            Color(0xFF4ADE80)

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = pureTimeText,
                                                fontSize = timeFontSize,
                                                fontWeight = FontWeight.Black,
                                                color = timeColor,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                softWrap = false,
                                                style = noPaddingStyle
                                            )
                                            if (isTimetable) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "🕒",
                                                    fontSize = (timeFontSize.value * 0.5f).sp,
                                                    textAlign = TextAlign.Center,
                                                    style = noPaddingStyle
                                                )
                                            }
                                        }
                                    }

                                    // 4. 실시간 위치 / 부가 정보 (추가된 독립 라벨 영역 - 역명 라벨과 동일한 높이·크기 확보)
                                    if (hasDetail) {
                                        Box(
                                            modifier = Modifier
                                                .weight(0.95f)
                                                .fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val detailFontSize = getFittedFontSize(
                                                arrival.statusDetail,
                                                targetMaxSp = stationFontSize.value,
                                                targetMinSp = 12f,
                                                maxAvailableWidthDp = 180f
                                            )
                                            Text(
                                                text = arrival.statusDetail,
                                                fontSize = detailFontSize,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFFB0BEC5),
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                softWrap = false,
                                                style = noPaddingStyle
                                            )
                                        }
                                    }

                                    // 5. 행선지 / 방면 (5행으로 이동)
                                    Box(
                                        modifier = Modifier
                                            .weight(if (hasDetail) 0.95f else 1f)
                                            .fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val destMaxSp = if (current.type == TransitType.BUS) 16f else 18f
                                        val destFontSize = getFittedFontSize(destLabel, targetMaxSp = destMaxSp, targetMinSp = 12f, maxAvailableWidthDp = 175f)
                                        Text(
                                            text = destLabel,
                                            fontSize = destFontSize,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFDDDDDD),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            softWrap = false,
                                            style = noPaddingStyle
                                        )
                                    }

                                    // 6. [ 목록 ] 버튼
                                    Box(
                                        modifier = Modifier
                                            .weight(if (hasDetail) 1.0f else 1f)
                                            .fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(Color(0xFF2C303A))
                                                .border(1.dp, Color(0xFF4C5466), RoundedCornerShape(16.dp))
                                                .clickable { selectedTarget = null }
                                                .padding(horizontal = 16.dp, vertical = if (hasDetail) 4.dp else 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Menu,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text(
                                                    text = "목록",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    style = noPaddingStyle
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 시간 텍스트 포맷팅:
 * 인천1호선, 인천2호선 및 버스/지하철 시간표 기준인 경우 몇분 몇초 대신 시각("HH:mm" 또는 "HH:mm:ss") 표출
 */
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

/**
 * 시간 텍스트 포맷팅:
 * - 시간표 기반이거나 시각 형식("HH:mm", "HH:mm:ss")인 경우에도 남은 시간(분/초)으로 변환 후 뒤에 "[시간표]"만 표시
 * - 일반 실시간 도착인 경우 남은 시간 카운트다운("X분 X초", "곧 도착") 표출
 */
private fun extractTime(text: String): String? {
    val clean = text.trim()
    if (clean.matches(Regex("""^\d{1,2}:\d{2}(?::\d{2})?$"""))) {
        return clean
    }
    val parenMatch = Regex("""\((\d{1,2}:\d{2}(?::\d{2})?)\)""").find(clean)
    if (parenMatch != null) {
        return parenMatch.groupValues[1]
    }
    if (clean.contains("시간표")) {
        val timeRegex = Regex("""(\d{1,2}:\d{2}(?::\d{2})?)""").find(clean)
        if (timeRegex != null) {
            return timeRegex.groupValues[1]
        }
    }
    return null
}

private fun formatLiveArrivalText(arrival: ArrivalInfo, secondsOffset: Int): String {
    val rawMsg = arrival.arrivalTime.trim()
    val isTimetable = rawMsg.contains("시간표") || arrival.statusDetail.contains("시간표") || extractTime(rawMsg) != null
    val suffix = if (isTimetable) " 🕒" else ""

    val baseRemain = if (arrival.remainSeconds > 0) {
        arrival.remainSeconds
    } else {
        getTimetableRemainSeconds(rawMsg) ?: 0
    }

    if (baseRemain > 0) {
        val remaining = maxOf(0, baseRemain - secondsOffset)
        val timeStr = when {
            remaining <= 30 || (arrival.isSoon && remaining <= 45) -> "곧 도착"
            remaining in 31..59 -> "${remaining}초"
            else -> {
                val m = remaining / 60
                val s = remaining % 60
                "${m}분 ${s}초"
            }
        }
        return "$timeStr$suffix"
    }

    if (rawMsg.contains("곧 도착") || rawMsg.contains("당역") || arrival.isSoon) {
        return "곧 도착$suffix"
    }

    if (rawMsg.contains("종료")) return "운행 종료"

    val minMatch = Regex("""(\d+)\s*분""").find(rawMsg)
    val secMatch = Regex("""(\d+)\s*초""").find(rawMsg)
    if (minMatch != null) {
        val m = minMatch.groupValues[1].toIntOrNull() ?: 0
        val s = secMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val timeStr = "${m}분 ${s}초"
        return "$timeStr$suffix"
    }

    val clean = rawMsg.removeSuffix(" [시간표]").removeSuffix("[시간표]").removeSuffix(" 🕒").removeSuffix(" 후").trim()
    return if (clean.isNotEmpty()) "$clean$suffix" else "도착 정보 없음"
}

private fun isSoonByRemaining(arrival: ArrivalInfo, secondsOffset: Int): Boolean {
    val remain = if (arrival.remainSeconds > 0) arrival.remainSeconds else (getTimetableRemainSeconds(arrival.arrivalTime) ?: 0)
    val remaining = maxOf(0, remain - secondsOffset)
    return remaining in 1..120 || arrival.statusDetail.contains("전역") || arrival.statusDetail.contains("진입") || arrival.statusDetail.contains("1번째")
}

/**
 * GPS/Network 위치 1회 요청 헬퍼
 */
@SuppressLint("MissingPermission")
private fun requestUserLocation(context: Context, onLocation: (Location) -> Unit) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
    val net = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
    val passive = runCatching { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()

    val best = listOfNotNull(gps, net, passive).maxByOrNull { it.time }
    if (best != null) {
        onLocation(best)
    }

    runCatching {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(location)
                lm.removeUpdates(this)
            }
        }
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        lm.requestSingleUpdate(provider, listener, null)
    }
}

/**
 * 정류장/지하철역 좌표 조회 (타깃 자체 lat/lon 우선, 부재 시 주요 역 사전 매핑)
 */
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

/**
 * 방향/행선지 표시 포맷팅:
 * 노선명(subCode)을 기반으로 "하행/상행"을 해당 노선의 실제 대표 행선지("~행")로 정확하게 변환
 */
private fun formatDirection(item: TransitTarget): String {
    val dir = item.directionOrRoute.trim()
    if (dir.isEmpty() || dir == "양방향") return "양방향"
    if (item.type != TransitType.SUBWAY) return dir

    if (dir != "하행" && dir != "상행" && (dir.contains("행") || dir.contains("방면") || dir.contains("순환"))) {
        return dir
    }

    val sub = item.subCode.replace(" ", "")

    return when {
        // 1. 공항철도
        sub.contains("공항철도") || sub.contains("공항") -> when (dir) {
            "하행" -> "서울역행"
            "상행" -> "인천공항행"
            else -> dir
        }

        // 2. 인천 2호선
        sub.contains("인천2") || (sub.contains("2호선") && isLine2Station(item.name)) -> when (dir) {
            "하행" -> "운연행"
            "상행" -> "검단오류행"
            else -> dir
        }

        // 3. 인천 1호선
        sub.contains("인천1") || sub.contains("인천") -> when (dir) {
            "하행" -> "송도달빛축제공원행"
            "상행" -> "검단호수공원행"
            else -> dir
        }

        // 4. 수도권 주요 노선
        sub.contains("1호선") -> when (dir) {
            "하행" -> "인천/신창행"
            "상행" -> "소요산/청량리행"
            else -> dir
        }
        sub.contains("2호선") -> when (dir) {
            "내선", "상행" -> "내선순환"
            "외선", "하행" -> "외선순환"
            else -> dir
        }
        sub.contains("3호선") -> when (dir) {
            "하행" -> "오금행"
            "상행" -> "대화행"
            else -> dir
        }
        sub.contains("4호선") -> when (dir) {
            "하행" -> "오이도행"
            "상행" -> "진접/당고개행"
            else -> dir
        }
        sub.contains("5호선") -> when (dir) {
            "하행" -> "하남검단산/마천행"
            "상행" -> "방화행"
            else -> dir
        }
        sub.contains("6호선") -> when (dir) {
            "하행" -> "신내행"
            "상행" -> "응암순환행"
            else -> dir
        }
        sub.contains("7호선") -> when (dir) {
            "하행" -> "석남/온수행"
            "상행" -> "장암행"
            else -> dir
        }
        sub.contains("8호선") -> when (dir) {
            "하행" -> "모란행"
            "상행" -> "별내행"
            else -> dir
        }
        sub.contains("9호선") -> when (dir) {
            "하행" -> "중앙보훈병원행"
            "상행" -> "개화행"
            else -> dir
        }
        sub.contains("수인분당") -> when (dir) {
            "하행" -> "인천/고색행"
            "상행" -> "청량리/왕십리행"
            else -> dir
        }
        sub.contains("신분당") -> when (dir) {
            "하행" -> "광교행"
            "상행" -> "신사행"
            else -> dir
        }
        sub.contains("경의중앙") -> when (dir) {
            "하행" -> "지평/용문행"
            "상행" -> "문산행"
            else -> dir
        }
        sub.contains("경춘") -> when (dir) {
            "하행" -> "춘천행"
            "상행" -> "청량리/상봉행"
            else -> dir
        }
        sub.contains("서해") -> when (dir) {
            "하행" -> "원시행"
            "상행" -> "일산행"
            else -> dir
        }
        sub.contains("김포") -> when (dir) {
            "하행" -> "양촌행"
            "상행" -> "김포공항행"
            else -> dir
        }
        else -> dir
    }
}

private fun isLine2Station(stationName: String): Boolean {
    val clean = stationName.removeSuffix("역").trim()
    val line2Stations = setOf(
        "검단오류", "왕길", "검단사거리", "마전", "완정", "독정", "검암", "검바위", "아시아드경기장", "아시아드",
        "서구청", "가정", "가정중앙시장", "가정중앙", "석남", "서부여성회관", "서부여성", "인천가좌", "가재울", "주안국가산단", "주안국가",
        "주안", "시민공원", "석바위시장", "석바위", "인천시청", "석천사거리", "모래내시장", "만수", "남동구청", "인천대공원", "운연"
    )
    return line2Stations.contains(clean)
}
