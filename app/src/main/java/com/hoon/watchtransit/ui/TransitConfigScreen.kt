package com.hoon.watchtransit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.hoon.watchtransit.R
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hoon.watchtransit.data.TransitConfigManager
import com.hoon.watchtransit.model.TransitCity
import com.hoon.watchtransit.model.TransitTarget
import com.hoon.watchtransit.model.TransitType
import com.hoon.watchtransit.network.TransitSearchRepository
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitConfigScreen(
    savedTargets: List<TransitTarget>,
    configManager: TransitConfigManager? = null,
    repository: TransitSearchRepository? = null,
    initialCityList: List<TransitCity> = emptyList(),
    selectedCityCode: Int = 23,
    onSaveTargets: (List<TransitTarget>) -> Unit,
    onSaveCityCode: ((Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val repo = repository ?: remember(context) { TransitSearchRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    val cachedCitiesFromManager by configManager?.cachedCitiesFlow?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList()) }
    val savedCityCodeFromManager by configManager?.selectedCityCodeFlow?.collectAsState(initial = null)
        ?: remember { mutableStateOf(null) }

    var cityList by remember { mutableStateOf(initialCityList.ifEmpty { cachedCitiesFromManager }) }
    var selectedCity by remember {
        mutableStateOf(
            cityList.find { it.code == (savedCityCodeFromManager ?: selectedCityCode) }
                ?: TransitCity(23, "인천광역시")
        )
    }

    LaunchedEffect(cachedCitiesFromManager, savedCityCodeFromManager) {
        if (cachedCitiesFromManager.isNotEmpty() && cityList.isEmpty()) {
            cityList = cachedCitiesFromManager
        }
        val targetCode = savedCityCodeFromManager ?: selectedCityCode
        val matched = cityList.find { it.code == targetCode }
        if (matched != null) {
            selectedCity = matched
        }
    }

    LaunchedEffect(Unit) {
        val fetched = repo.fetchCityCodes()
        if (fetched.isNotEmpty()) {
            cityList = fetched
            configManager?.saveCities(fetched)
            val targetCode = savedCityCodeFromManager ?: selectedCityCode
            val matched = fetched.find { it.code == targetCode }
            if (matched != null) {
                selectedCity = matched
            }
        }
    }

    var searchKeyword by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<TransitTarget>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }

    var cityDropdownExpanded by remember { mutableStateOf(false) }
    var cityFilterQuery by remember { mutableStateOf("") }

    // 신규 추가 또는 교체 대상 선택
    var pendingTarget by remember { mutableStateOf<TransitTarget?>(null) }
    var customFilterInput by remember { mutableStateOf("") }

    // 편집 모드 토글 (우측 세줄 버튼 표시, 드래그 순서 변경 및 완료 시 일괄 동기화)
    var isEditMode by remember { mutableStateOf(false) }
    var editingTargets by remember { mutableStateOf(savedTargets) }

    LaunchedEffect(savedTargets, isEditMode) {
        if (!isEditMode) {
            editingTargets = savedTargets
        }
    }

    // 드래그 앤 드롭 상태 변수
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // 신설/임시 정류소 직접 수동 추가 다이얼로그 상태
    var showManualAddDialog by remember { mutableStateOf(false) }
    var manualStopName by remember { mutableStateOf("") }
    var manualStopNo by remember { mutableStateOf("") }
    var manualBusFilter by remember { mutableStateOf("") }

    val filteredCityList = remember(cityList, cityFilterQuery) {
        if (cityFilterQuery.isBlank()) cityList
        else cityList.filter { it.name.contains(cityFilterQuery.trim(), ignoreCase = true) }
    }

    fun performSearch() {
        val q = searchKeyword.trim()
        if (q.isEmpty()) return
        coroutineScope.launch {
            isSearching = true
            hasSearched = true
            val list = repo.searchAll(q, selectedCity)
            // 지하철역(SUBWAY)을 항상 최상단에 우선 표출
            searchResults = list.sortedBy { if (it.type == TransitType.SUBWAY) 0 else 1 }
            isSearching = false
        }
    }

    fun startEditMode() {
        editingTargets = savedTargets
        isEditMode = true
    }

    fun applyEditMode() {
        onSaveTargets(editingTargets)
        isEditMode = false
        draggedItemId = null
        dragOffsetY = 0f
    }

    fun cancelEditMode() {
        editingTargets = savedTargets
        isEditMode = false
        draggedItemId = null
        dragOffsetY = 0f
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (isEditMode) {
                        Text(
                            "목록 순서 편집",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_subway_settings),
                            contentDescription = "스마트라이더",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(34.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121318),
                    titleContentColor = Color.White
                ),
                actions = {
                    if (!isEditMode) {
                        Button(
                            onClick = {
                                manualStopName = searchKeyword
                                manualStopNo = if (searchKeyword.all { it.isDigit() }) searchKeyword else ""
                                showManualAddDialog = true
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2A2934),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                "정류소 직접입력",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    } else {
                        TextButton(
                            onClick = { cancelEditMode() },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text("취소", color = Color(0xFF8E9AA8))
                        }
                        Button(
                            onClick = { applyEditMode() },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2A2934),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                "순서편집 완료",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // 편집 모드가 아닐 때만 도시 선택 및 검색창 표출 (편집 모드 시 목록에 집중)
            AnimatedVisibility(visible = !isEditMode) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 1. 도시 선택 (상단 1줄)
                    ExposedDropdownMenuBox(
                        expanded = cityDropdownExpanded,
                        onExpandedChange = {
                            cityDropdownExpanded = it
                            if (it) cityFilterQuery = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedCity.name,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            maxLines = 1,
                            shape = RoundedCornerShape(14.dp),
                            label = { Text("버스 검색 지역", maxLines = 1, color = Color(0xFF8E9AA8)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cityDropdownExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF1E1F21),
                                unfocusedContainerColor = Color(0xFF1E1F21),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedLabelColor = Color(0xFF8E9AA8),
                                unfocusedLabelColor = Color(0xFF8E9AA8)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = cityDropdownExpanded,
                            onDismissRequest = { cityDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF1E2026))
                        ) {
                            OutlinedTextField(
                                value = cityFilterQuery,
                                onValueChange = { cityFilterQuery = it },
                                placeholder = { Text("도시명 검색 (예: 서울, 인천, 과천)", maxLines = 1, color = Color(0xFF64748B)) },
                                singleLine = true,
                                maxLines = 1,
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF121318),
                                    unfocusedContainerColor = Color(0xFF121318),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF2C2F38),
                                    unfocusedBorderColor = Color(0xFF2C2F38)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )

                            HorizontalDivider(color = Color(0xFF23252C))

                            filteredCityList.forEach { city ->
                                DropdownMenuItem(
                                    text = { Text(city.name, color = Color.White) },
                                    onClick = {
                                        selectedCity = city
                                        coroutineScope.launch {
                                            configManager?.saveSelectedCityCode(city.code)
                                        }
                                        onSaveCityCode?.invoke(city.code)
                                        cityDropdownExpanded = false
                                    }
                                )
                            }

                            if (filteredCityList.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("일치하는 도시가 없습니다.", color = Color(0xFF8E9AA8)) },
                                    onClick = {}
                                )
                            }
                        }
                    }

                    // 2. 통합 검색 입력창
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchKeyword,
                            onValueChange = { searchKeyword = it },
                            placeholder = {
                                Text(
                                    "지하철역명 / 정류장명 (번호)",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = Color(0xFF64748B)
                                )
                            },
                            singleLine = true,
                            maxLines = 1,
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF1F2025),
                                unfocusedContainerColor = Color(0xFF1F2025),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        )
                        Button(
                            onClick = { performSearch() },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2B2A31),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "검색",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // 3. 검색 결과 (편집 모드가 아닐 때만)
            if (!isEditMode) {
                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (searchResults.isNotEmpty()) {
                    Text(
                        "검색 결과 (${searchResults.size}건)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(searchResults, key = { "${it.type}_${it.targetCode}_${it.subCode}" }) { result ->
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2026)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pendingTarget = result
                                        customFilterInput = ""
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val isSubway = result.type == TransitType.SUBWAY
                                            Surface(
                                                color = if (isSubway) Color(0xFF0288D1) else Color(0xFF6BD8B0),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.padding(end = 8.dp)
                                            ) {
                                                Text(
                                                    text = if (isSubway) "지하철" else "버스",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSubway) Color.White else Color(0xFF0F172A),
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                )
                                            }
                                            Text(
                                                "${result.name} (${result.subCode})",
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            "고유코드: ${result.targetCode}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF8E9AA8)
                                        )
                                    }
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "추가",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                } else if (hasSearched) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "검색 결과가 없습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "신설/임시 정류소나 마을버스는 공공데이터에 누락될 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                manualStopName = searchKeyword
                                manualStopNo = if (searchKeyword.all { it.isDigit() }) searchKeyword else ""
                                showManualAddDialog = true
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("정류소 직접 입력 추가")
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // 4. 위치 표시 목록 헤더 및 [순서편집] 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isEditMode) "위치 표시 순서 (${editingTargets.size}개)"
                    else "위치 표시 목록 (${savedTargets.size}개)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (!isEditMode) {
                    Button(
                        onClick = { startEditMode() },
                        enabled = savedTargets.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF282830),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "순서편집",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }

            val currentList = if (isEditMode) editingTargets else savedTargets

            if (currentList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "워치에 표시할 역이나 정류소를 검색하여 추가하세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(currentList, key = { _, item -> item.id }) { index, item ->
                        val isDragging = isEditMode && draggedItemId == item.id

                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDragging)
                                    MaterialTheme.colorScheme.surface
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 10f else 1f)
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffsetY else 0f
                                    scaleX = if (isDragging) 1.03f else 1f
                                    scaleY = if (isDragging) 1.03f else 1f
                                    shadowElevation = if (isDragging) 16f else 0f
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. 순번 표시 (1, 2, 3...)
                                Surface(
                                    color = if (isDragging)
                                        Color(0xFFF37A20)
                                    else
                                        MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDragging)
                                                Color.White
                                            else
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // 2. 항목 정보
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = if (item.type == TransitType.SUBWAY)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.secondary,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.padding(end = 6.dp)
                                        ) {
                                            Text(
                                                text = if (item.type == TransitType.SUBWAY) "지하철" else "버스",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (item.type == TransitType.SUBWAY)
                                                    MaterialTheme.colorScheme.onPrimary
                                                else
                                                    MaterialTheme.colorScheme.onSecondary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Text(
                                            "${item.name} (${item.subCode})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        "방면 | ID : ${item.directionOrRoute.ifEmpty { "양방향/전체" }} | ${item.targetCode}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // 3. 우측 컨트롤:
                                // 편집 모드일 때는 [삭제/수정 버튼 대신 세줄 버튼(≡)] 표시 및 드래그 제스처로 순서 변경
                                // 일반 모드일 때는 [수정 버튼] 및 [삭제 버튼] 표시
                                if (isEditMode) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .pointerInput(item.id) {
                                                detectVerticalDragGestures(
                                                    onDragStart = {
                                                        draggedItemId = item.id
                                                        dragOffsetY = 0f
                                                    },
                                                    onDragEnd = {
                                                        draggedItemId = null
                                                        dragOffsetY = 0f
                                                    },
                                                    onDragCancel = {
                                                        draggedItemId = null
                                                        dragOffsetY = 0f
                                                    },
                                                    onVerticalDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragOffsetY += dragAmount

                                                        val currentIdx = editingTargets.indexOfFirst { it.id == draggedItemId }
                                                        if (currentIdx != -1) {
                                                            val itemHeightPx = with(density) { 76.dp.toPx() }
                                                            val threshold = itemHeightPx * 0.55f

                                                            if (dragOffsetY > threshold && currentIdx < editingTargets.size - 1) {
                                                                val nextIdx = currentIdx + 1
                                                                val list = editingTargets.toMutableList()
                                                                val moved = list.removeAt(currentIdx)
                                                                list.add(nextIdx, moved)
                                                                editingTargets = list
                                                                dragOffsetY -= itemHeightPx
                                                            } else if (dragOffsetY < -threshold && currentIdx > 0) {
                                                                val prevIdx = currentIdx - 1
                                                                val list = editingTargets.toMutableList()
                                                                val moved = list.removeAt(currentIdx)
                                                                list.add(prevIdx, moved)
                                                                editingTargets = list
                                                                dragOffsetY += itemHeightPx
                                                            }
                                                        }
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // 사용자 스크린샷과 동일한 세줄 아이콘 (≡)
                                        Icon(
                                            imageVector = Icons.Default.Menu,
                                            contentDescription = "순서 이동 핸들",
                                            tint = if (isDragging) Color(0xFFF37A20) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                } else {
                                    // 삭제 버튼
                                    IconButton(
                                            onClick = {
                                                onSaveTargets(savedTargets.filterNot { it.id == item.id })
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "삭제",
                                                tint = MaterialTheme.colorScheme.error
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

    // 5. 검색 결과 선택 시 세부 필터 설정 다이얼로그 (신규 추가 또는 기존 항목 교체)
    pendingTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingTarget = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color(0xFF1E2026),
            title = {
                Text(
                    "${target.name} 세부 설정",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (target.type == TransitType.SUBWAY)
                            "표시할 열차 방면(예: 서울역행, 인천공항행 등)을 입력하세요. 비워두면 양방향 모두 표시합니다."
                        else
                            "특정 버스 번호만 보려면 노선 번호(예: 3, 1001)를 입력하세요. 비워두면 전체 노선을 표시합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customFilterInput,
                        onValueChange = { customFilterInput = it },
                        label = { Text(if (target.type == TransitType.SUBWAY) "방면 (예: 서울역행, 하행)" else "버스 번호 (예: 3)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF121318),
                            unfocusedContainerColor = Color(0xFF121318)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalFilter = if (target.type == TransitType.BUS) {
                            "${target.directionOrRoute}|${customFilterInput.trim().ifEmpty { "ALL" }}"
                        } else {
                            customFilterInput.trim().ifEmpty { "양방향" }
                        }
                        val confirmed = target.copy(directionOrRoute = finalFilter)

                        // 목록 끝에 추가
                        onSaveTargets(savedTargets + confirmed)

                        pendingTarget = null
                        searchResults = emptyList()
                        searchKeyword = ""
                        hasSearched = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0288D1),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        "워치 목록에 추가",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTarget = null }) {
                    Text("취소", color = MaterialTheme.colorScheme.outline)
                }
            }
        )
    }

    // 7. 신설/임시/마을버스 정류소 직접 수동 추가 다이얼로그
    if (showManualAddDialog) {
        AlertDialog(
            onDismissRequest = { showManualAddDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color(0xFF1E2026),
            title = {
                Text(
                    "정류소 직접 입력 추가",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "공공데이터에 등록되지 않은 임시 정류소이나 마을버스 정류소를 직접 등록합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = manualStopName,
                        onValueChange = { manualStopName = it },
                        label = { Text("정류소명 (예: (임시)지식산업단지)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF121318),
                            unfocusedContainerColor = Color(0xFF121318)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualStopNo,
                        onValueChange = { manualStopNo = it },
                        label = { Text("정류소 번호/ARS-ID (예: 21081)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF121318),
                            unfocusedContainerColor = Color(0xFF121318)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualBusFilter,
                        onValueChange = { manualBusFilter = it },
                        label = { Text("버스 번호 (예: 3, 비워두면 전체)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF121318),
                            unfocusedContainerColor = Color(0xFF121318)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = manualStopName.trim().ifEmpty { "버스정류소" }
                        val code = manualStopNo.trim().ifEmpty { "21081" }
                        val bus = manualBusFilter.trim().ifEmpty { "ALL" }
                        val manualTarget = TransitTarget(
                            id = UUID.randomUUID().toString(),
                            type = TransitType.BUS,
                            name = name,
                            targetCode = code,
                            subCode = code,
                            directionOrRoute = "${selectedCity.code}|$bus"
                        )
                        onSaveTargets(savedTargets + manualTarget)
                        showManualAddDialog = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0288D1),
                        contentColor = Color.White
                    )
                ) {
                    Text("추가", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualAddDialog = false }) {
                    Text("취소", color = MaterialTheme.colorScheme.outline)
                }
            }
        )
    }
}
