package com.hoon.watchtransit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    var pendingTarget by remember { mutableStateOf<TransitTarget?>(null) }
    var customFilterInput by remember { mutableStateOf("") }

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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "대중교통 알림 설정",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    FilledTonalButton(
                        onClick = {
                            manualStopName = searchKeyword
                            manualStopNo = if (searchKeyword.all { it.isDigit() }) searchKeyword else ""
                            showManualAddDialog = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            "정류소 직접입력",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
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
                    label = { Text("버스 검색 지역", maxLines = 1) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cityDropdownExpanded) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )

                ExposedDropdownMenu(
                    expanded = cityDropdownExpanded,
                    onDismissRequest = { cityDropdownExpanded = false }
                ) {
                    OutlinedTextField(
                        value = cityFilterQuery,
                        onValueChange = { cityFilterQuery = it },
                        placeholder = { Text("도시명 검색 (예: 서울, 인천, 과천)", maxLines = 1) },
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )

                    HorizontalDivider()

                    filteredCityList.forEach { city ->
                        DropdownMenuItem(
                            text = { Text(city.name) },
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
                            text = { Text("일치하는 도시가 없습니다.", color = MaterialTheme.colorScheme.outline) },
                            onClick = {}
                        )
                    }
                }
            }

            // 2. 통합 검색 입력창 (정사각형 56dp 검색 버튼 + 56dp 입력창 완벽한 수직/수평 정렬)
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
                            "지하철역명 / 정류장명(번호)",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    maxLines = 1,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                )
                Button(
                    onClick = { performSearch() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "검색",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // 3. 검색 결과
            if (isSearching) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
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
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(searchResults, key = { "${it.type}_${it.targetCode}_${it.subCode}" }) { result ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = if (result.type == TransitType.SUBWAY)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.secondary,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.padding(end = 8.dp)
                                        ) {
                                            Text(
                                                text = if (result.type == TransitType.SUBWAY) "지하철" else "버스",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (result.type == TransitType.SUBWAY)
                                                    MaterialTheme.colorScheme.onPrimary
                                                else
                                                    MaterialTheme.colorScheme.onSecondary,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            )
                                        }
                                        Text(
                                            "${result.name} (${result.subCode})",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "고유코드: ${result.targetCode}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "추가",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            } else if (hasSearched) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "검색 결과가 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "신설/임시 정류소나 마을버스는 공공데이터에 누락될 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // 4. 워치 표시 목록
            Text(
                "워치 표시 목록 (${savedTargets.size}개)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(savedTargets, key = { it.id }) { item ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = if (item.type == TransitType.SUBWAY)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.secondary,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Text(
                                            text = if (item.type == TransitType.SUBWAY) "지하철" else "버스",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (item.type == TransitType.SUBWAY)
                                                MaterialTheme.colorScheme.onPrimary
                                            else
                                                MaterialTheme.colorScheme.onSecondary,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        )
                                    }
                                    Text(
                                        "${item.name} (${item.subCode})",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "방면 | ID : ${item.directionOrRoute} | ${item.targetCode}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                onSaveTargets(savedTargets.filterNot { it.id == item.id })
                            }) {
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

    // 5. 검색 결과 선택 시 세부 필터 설정 다이얼로그
    pendingTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingTarget = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface,
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
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
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
                        onSaveTargets(savedTargets + confirmed)
                        pendingTarget = null
                        searchResults = emptyList()
                        searchKeyword = ""
                        hasSearched = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("워치 목록에 추가", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTarget = null }) {
                    Text("취소", color = MaterialTheme.colorScheme.outline)
                }
            }
        )
    }

    // 6. 신설/임시/마을버스 정류소 직접 수동 추가 다이얼로그
    if (showManualAddDialog) {
        AlertDialog(
            onDismissRequest = { showManualAddDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface,
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
                        "공공데이터에 등록되지 않은 임시 정류장이나 마을버스 정류소를 직접 등록합니다.",
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
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                    OutlinedTextField(
                        value = manualStopNo,
                        onValueChange = { manualStopNo = it },
                        label = { Text("정류소 번호/ARS-ID (예: 21081)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                    OutlinedTextField(
                        value = manualBusFilter,
                        onValueChange = { manualBusFilter = it },
                        label = { Text("버스 번호 (예: 3, 비워두면 전체)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
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
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
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
