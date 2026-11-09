package com.hoon.watchtransit.data

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * 서울교통공사, 인천교통공사(1·2호선·7호선 연장), 공항철도 공식 역간거리 및 소요시간 데이터를 기반으로
 * 역 간 실시간 소요시간(초) 및 남은 역 수를 정밀 계산하는 매니저
 */
object SeoulSubwayTravelTimeManager {
    private const val TAG = "SeoulTravelTime"

    // 호선별 인접 그래프: lineNum -> (출발역 -> (도착역 -> 소요시간(초)))
    private val lineGraphs = ConcurrentHashMap<String, Map<String, Map<String, Int>>>()
    // 호선별 역명 별칭 맵: lineNum -> (입력역명 -> 표준역명)
    private val stationAliases = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile
    private var isInitialized = false

    private data class StationRow(
        val seq: Int,
        val line: String,
        val station: String,
        val travelSec: Int
    )

    private data class Edge(val to: String, val weight: Int) : Comparable<Edge> {
        override fun compareTo(other: Edge): Int = weight.compareTo(other.weight)
    }

    /**
     * CSV 데이터를 로드하여 호선별 그래프 및 역명 별칭 매핑 생성
     */
    fun ensureInitialized(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                loadData(context)
                isInitialized = true
                Log.d(TAG, "Subway travel time data initialized successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize subway travel time data", e)
            }
        }
    }

    private fun loadData(context: Context) {
        val rowsByLine = mutableMapOf<String, MutableList<StationRow>>()
        val aliasesByLine = mutableMapOf<String, MutableMap<String, String>>()

        val inputStream = context.assets.open("timetable/seoul_subway_travel_time.csv")

        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            val header = reader.readLine() // 연번,호선,역명,소요시간,역간거리,누계거리
            var lineStr: String?
            while (reader.readLine().also { lineStr = it } != null) {
                val line = lineStr?.trim() ?: continue
                if (line.isEmpty()) continue

                val tokens = line.split(",")
                if (tokens.size >= 4) {
                    val seq = tokens[0].trim().toIntOrNull() ?: continue
                    val lineNum = tokens[1].trim()
                    val stn = tokens[2].trim()
                    val travelSec = parseMmSsToSeconds(tokens[3].trim())

                    val row = StationRow(seq, lineNum, stn, travelSec)
                    rowsByLine.getOrPut(lineNum) { mutableListOf() }.add(row)

                    // 역명 별칭 매핑 등록
                    val lineAliasMap = aliasesByLine.getOrPut(lineNum) { mutableMapOf() }
                    registerStationAlias(lineAliasMap, stn)
                }
            }
        }

        // 호선별 특수 역명 별칭 추가 등록
        registerSpecialAliases(aliasesByLine)

        // 호선별 그래프 구축
        for ((lineNum, rows) in rowsByLine) {
            val adj = mutableMapOf<String, MutableMap<String, Int>>()

            fun addEdge(u: String, v: String, w: Int) {
                adj.getOrPut(u) { mutableMapOf() }[v] = minOf(adj.getOrPut(u) { mutableMapOf() }[v] ?: Int.MAX_VALUE, w)
                adj.getOrPut(v) { mutableMapOf() }[u] = minOf(adj.getOrPut(v) { mutableMapOf() }[u] ?: Int.MAX_VALUE, w)
            }

            when (lineNum) {
                "1" -> {
                    // seq 1~10 (서울역 ~ 청량리)
                    for (i in 0 until rows.size - 1) {
                        addEdge(rows[i].station, rows[i + 1].station, rows[i + 1].travelSec)
                    }
                }
                "2" -> {
                    // 본선 순환선: seq 11~53, 그리고 53(충정로) -> 54(시청)
                    val mainRows = rows.filter { it.seq in 11..54 }
                    for (i in 0 until mainRows.size - 1) {
                        addEdge(mainRows[i].station, mainRows[i + 1].station, mainRows[i + 1].travelSec)
                    }

                    // 성수지선: 성수 <-> 용답(seq 55, 180초), seq 55~58 (신설동)
                    addEdge("성수", "용답", 180)
                    val seongsuRows = rows.filter { it.seq in 55..58 }
                    for (i in 0 until seongsuRows.size - 1) {
                        addEdge(seongsuRows[i].station, seongsuRows[i + 1].station, seongsuRows[i + 1].travelSec)
                    }

                    // 신정지선: 신도림 <-> 도림천(seq 59, 90초), seq 59~62 (까치산)
                    addEdge("신도림", "도림천", 90)
                    val sinjeongRows = rows.filter { it.seq in 59..62 }
                    for (i in 0 until sinjeongRows.size - 1) {
                        addEdge(sinjeongRows[i].station, sinjeongRows[i + 1].station, sinjeongRows[i + 1].travelSec)
                    }
                }
                "5" -> {
                    // 본선: seq 123(방화) ~ 171(하남검단산)
                    val mainRows = rows.filter { it.seq in 123..171 }
                    for (i in 0 until mainRows.size - 1) {
                        addEdge(mainRows[i].station, mainRows[i + 1].station, mainRows[i + 1].travelSec)
                    }

                    // 마천지선: 강동 <-> 둔촌동(seq 172, 110초), seq 172~178 (마천)
                    addEdge("강동", "둔촌동", 110)
                    val macheonRows = rows.filter { it.seq in 172..178 }
                    for (i in 0 until macheonRows.size - 1) {
                        addEdge(macheonRows[i].station, macheonRows[i + 1].station, macheonRows[i + 1].travelSec)
                    }
                }
                "6" -> {
                    // 응암루프: seq 179~185 (응암 -> 역촌 -> 불광 -> 독바위 -> 연신내 -> 구산 -> 응암)
                    val loopRows = rows.filter { it.seq in 179..185 }
                    for (i in 0 until loopRows.size - 1) {
                        addEdge(loopRows[i].station, loopRows[i + 1].station, loopRows[i + 1].travelSec)
                    }

                    // 본선: seq 185(응암) ~ 218(신내역)
                    val mainRows = rows.filter { it.seq in 185..218 }
                    for (i in 0 until mainRows.size - 1) {
                        addEdge(mainRows[i].station, mainRows[i + 1].station, mainRows[i + 1].travelSec)
                    }
                }
                else -> {
                    // 3, 4, 7, 8호선 및 인천1호선(I1), 인천2호선(I2), 공항철도(AREX) 순차 연결
                    for (i in 0 until rows.size - 1) {
                        addEdge(rows[i].station, rows[i + 1].station, rows[i + 1].travelSec)
                    }
                }
            }

            lineGraphs[lineNum] = adj
        }

        for ((lineNum, map) in aliasesByLine) {
            stationAliases[lineNum] = map
        }
    }

    private fun registerStationAlias(map: MutableMap<String, String>, originalStn: String) {
        val clean = originalStn.replace(Regex("""\(.*?\)"""), "").trim()
        val noStationSuffix = clean.removeSuffix("역").trim()

        map[originalStn] = originalStn
        map[clean] = originalStn
        map[noStationSuffix] = originalStn
        map["${noStationSuffix}역"] = originalStn
    }

    private fun registerSpecialAliases(aliasesByLine: MutableMap<String, MutableMap<String, String>>) {
        // 4호선 총신대입구 / 이수
        aliasesByLine["4"]?.let { map ->
            map["이수"] = "총신대입구"
            map["이수역"] = "총신대입구"
            map["총신대"] = "총신대입구"
            map["총신대입구(이수)"] = "총신대입구"
            map["총신대입구역"] = "총신대입구"
            map["동작(현충원)"] = "동작"
            map["미아사거리"] = "미아사거리"
        }
        // 2호선 / 4호선 / 5호선 동대문역사문화공원
        listOf("2", "4", "5").forEach { line ->
            aliasesByLine[line]?.let { map ->
                map["동대문역사문화공원"] = "동대문역사문화공원"
                map["동대문역사문화공원역"] = "동대문역사문화공원"
                map["동대문역사문화"] = "동대문역사문화공원"
                map["동역사"] = "동대문역사문화공원"
                map["DDP"] = "동대문역사문화공원"
            }
        }
        // 6호선 신내 / 신내역
        aliasesByLine["6"]?.let { map ->
            map["신내"] = "신내역"
            map["신내역"] = "신내역"
        }
        // 인천2호선 특수 역명 별칭 (인천교통공사 표기 차이 대응)
        aliasesByLine["I2"]?.let { map ->
            map["서해구청"] = "서구청"
            map["서해구청역"] = "서구청"
            map["서구청"] = "서구청"
            map["아시아드"] = "아시아드경기장"
            map["가정중앙"] = "가정중앙시장"
            map["서부여성"] = "서부여성회관"
            map["주안국가"] = "주안국가산단"
            map["석바위"] = "석바위시장"
        }
        // 공항철도 특수 역명 별칭
        aliasesByLine["AREX"]?.let { map ->
            map["서울역"] = "서울"
            map["서울"] = "서울"
            map["DMC"] = "디지털미디어시티"
            map["디지털미디어시티"] = "디지털미디어시티"
            map["청라"] = "청라국제도시"
            map["인천공항"] = "인천공항1터미널"
            map["인천국제공항"] = "인천공항1터미널"
        }
    }

    private fun parseMmSsToSeconds(timeStr: String): Int {
        val parts = timeStr.split(":")
        if (parts.size != 2) return 0
        val m = parts[0].toIntOrNull() ?: 0
        val s = parts[1].toIntOrNull() ?: 0
        return m * 60 + s
    }

    /**
     * 호선 식별자(subwayId "1004", "1065" 등 또는 노선명 "4호선", "인천1호선", "공항철도")에서 호선 코드 추출
     */
    fun extractLineNumber(subwayIdOrName: String): String? {
        val clean = subwayIdOrName.replace(" ", "").trim()
        return when {
            clean == "1065" || clean.contains("공항철도") || clean.contains("공항") || clean.equals("AREX", ignoreCase = true) -> "AREX"
            clean == "1069" || clean.contains("인천1") || clean == "I1" -> "I1"
            clean == "1070" || clean.contains("인천2") || clean == "I2" -> "I2"
            clean == "1001" || (clean.contains("1호선") && !clean.contains("인천")) -> "1"
            clean == "1002" || (clean.contains("2호선") && !clean.contains("인천")) -> "2"
            clean == "1003" || clean.contains("3호선") -> "3"
            clean == "1004" || clean.contains("4호선") -> "4"
            clean == "1005" || clean.contains("5호선") -> "5"
            clean == "1006" || clean.contains("6호선") -> "6"
            clean == "1007" || clean.contains("7호선") -> "7"
            clean == "1008" || clean.contains("8호선") -> "8"
            clean.all { it.isDigit() } && clean.toIntOrNull() in 1..8 -> clean
            else -> null
        }
    }

    /**
     * 입력된 역명을 해당 호선의 표준 역명으로 정규화
     */
    fun resolveCanonicalStationName(lineNum: String, rawStation: String): String? {
        val lineMap = stationAliases[lineNum] ?: return null
        val clean = rawStation.replace(Regex("""\(.*?\)"""), "").trim()
        val noSuffix = clean.removeSuffix("역").trim()

        return lineMap[rawStation]
            ?: lineMap[clean]
            ?: lineMap[noSuffix]
            ?: lineMap["${noSuffix}역"]
    }

    /**
     * 출발역(열차 현재 위치)에서 도착역(사용자 조회 역)까지의 공식 소요시간(초)을 계산
     * 
     * @return 구간 내 경로가 존재하면 누적 소요시간(초), 파일에 없는 역이거나 경로가 없으면 null 반환
     */
    fun getTravelSeconds(
        context: Context,
        lineNum: String,
        fromStationRaw: String,
        toStationRaw: String
    ): Int? {
        ensureInitialized(context)

        val adj = lineGraphs[lineNum] ?: return null
        val fromCanonical = resolveCanonicalStationName(lineNum, fromStationRaw) ?: return null
        val toCanonical = resolveCanonicalStationName(lineNum, toStationRaw) ?: return null

        if (fromCanonical == toCanonical) return 0

        if (!adj.containsKey(fromCanonical) || !adj.containsKey(toCanonical)) {
            return null
        }

        // Dijkstra 최단 경로 계산
        val dist = mutableMapOf<String, Int>()
        val pq = PriorityQueue<Pair<Int, String>>(compareBy { it.first })

        dist[fromCanonical] = 0
        pq.add(Pair(0, fromCanonical))

        while (pq.isNotEmpty()) {
            val (currentDist, u) = pq.poll() ?: break
            if (currentDist > (dist[u] ?: Int.MAX_VALUE)) continue
            if (u == toCanonical) return currentDist

            val neighbors = adj[u] ?: continue
            for ((v, weight) in neighbors) {
                val newDist = currentDist + weight
                if (newDist < (dist[v] ?: Int.MAX_VALUE)) {
                    dist[v] = newDist
                    pq.add(Pair(newDist, v))
                }
            }
        }

        return dist[toCanonical]
    }

    /**
     * 출발역(열차 현재 위치)에서 도착역(사용자 조회 역)까지 몇 정거장(역) 차이인지 BFS로 계산
     */
    fun getStationHops(
        context: Context,
        lineNum: String,
        fromStationRaw: String,
        toStationRaw: String
    ): Int? {
        ensureInitialized(context)

        val adj = lineGraphs[lineNum] ?: return null
        val fromCanonical = resolveCanonicalStationName(lineNum, fromStationRaw) ?: return null
        val toCanonical = resolveCanonicalStationName(lineNum, toStationRaw) ?: return null

        if (fromCanonical == toCanonical) return 0
        if (!adj.containsKey(fromCanonical) || !adj.containsKey(toCanonical)) return null

        val queue = ArrayDeque<Pair<String, Int>>()
        val visited = mutableSetOf<String>()
        queue.add(Pair(fromCanonical, 0))
        visited.add(fromCanonical)

        while (queue.isNotEmpty()) {
            val (u, hops) = queue.removeFirst()
            if (u == toCanonical) return hops
            val neighbors = adj[u]?.keys ?: continue
            for (v in neighbors) {
                if (visited.add(v)) {
                    queue.add(Pair(v, hops + 1))
                }
            }
        }
        return null
    }

    /**
     * 시간표 기반 도착 예정 열차에 대해 현재 시각과 역간 소요시간 그래프를 이용하여
     * 현재 몇 번째 전역(어느 역)에 위치하는지 역산
     * 단, 조회 역이 출발역이거나 아직 출발역 출발 시각 전인 경우 "출발 대기 (출발역명)" 반환
     */
    fun estimatePositionByRemainSeconds(
        context: Context?,
        lineNum: String?,
        targetStationRaw: String,
        startStationRaw: String = "",
        endStationRaw: String = "",
        remainSeconds: Int
    ): String {
        val cleanTarget = targetStationRaw.removeSuffix("역").trim()
        val cleanStart = startStationRaw.replace(Regex("""\(.*?\)"""), "").removeSuffix("행").removeSuffix("역").trim()

        // 출발역과 조회역이 동일한 경우 시발역이므로 전역이 없음
        if (cleanStart.isNotEmpty() && cleanStart == cleanTarget) {
            return if (remainSeconds <= 60) "당역 도착 ($cleanTarget)" else "출발 대기 ($cleanTarget)"
        }

        if (context != null && lineNum != null) {
            ensureInitialized(context)
            val adj = lineGraphs[lineNum]
            val targetCanonical = resolveCanonicalStationName(lineNum, cleanTarget)

            if (adj != null && targetCanonical != null && adj.containsKey(targetCanonical)) {
                val startCanonical = startStationRaw.takeIf { it.isNotBlank() }
                    ?.replace(Regex("""\(.*?\)"""), "")
                    ?.let { resolveCanonicalStationName(lineNum, it.removeSuffix("행").trim()) }
                val endCanonical = endStationRaw.takeIf { it.isNotBlank() }
                    ?.replace(Regex("""\(.*?\)"""), "")
                    ?.let { resolveCanonicalStationName(lineNum, it.removeSuffix("행").trim()) }

                if (startCanonical != null && startCanonical == targetCanonical) {
                    return if (remainSeconds <= 60) "당역 도착 ($targetCanonical)" else "출발 대기 ($targetCanonical)"
                }

                // 시발/종착 단말역(이웃이 1개뿐인 역)에서 다른 역(endCanonical)으로 출발하는 열차인 경우 전역이 없음
                if ((adj[targetCanonical]?.size ?: 0) <= 1 && endCanonical != null && endCanonical != targetCanonical) {
                    return if (remainSeconds <= 60) "당역 도착 ($targetCanonical)" else "출발 대기 ($targetCanonical)"
                }

                val upstreamPath = buildUpstreamPath(adj, targetCanonical, startCanonical, endCanonical)
                if (upstreamPath.size >= 2) {
                    if (remainSeconds <= 20) {
                        return "당역 진입 ($targetCanonical)"
                    }
                    var cumSec = 0
                    var chosenHops = 1
                    var chosenStn = upstreamPath[1]
                    var reachedOriginBeforeDeparture = true

                    for (i in 1 until upstreamPath.size) {
                        val u = upstreamPath[i - 1]
                        val v = upstreamPath[i]
                        val edgeSec = adj[u]?.get(v) ?: 120
                        cumSec += edgeSec
                        chosenHops = i
                        chosenStn = v
                        if (cumSec >= remainSeconds) {
                            reachedOriginBeforeDeparture = false
                            break
                        }
                    }
                    // 남은 시간이 시발역까지의 총 소요시간보다 크면 아직 시발역에서 출발하기 전 상태
                    return if (reachedOriginBeforeDeparture && remainSeconds > cumSec) {
                        "출발 대기 ($chosenStn)"
                    } else {
                        "[$chosenHops]번째 전역 ($chosenStn)"
                    }
                } else if (endCanonical != null && endCanonical != targetCanonical) {
                    // 종착역 반대 방향으로 이전 역이 존재하지 않는 시발역인 경우
                    return if (remainSeconds <= 60) "당역 도착 ($targetCanonical)" else "출발 대기 ($targetCanonical)"
                }
            }
        }

        if (remainSeconds <= 20) {
            return if (cleanTarget.isNotEmpty()) "당역 진입 ($cleanTarget)" else "당역 진입"
        }

        val fallbackHops = maxOf(1, (remainSeconds + 60) / 120)
        return "[$fallbackHops]번째 전역"
    }

    /**
     * 조회 역(targetCanonical)에서 열차가 달려오는 이전 역 방향(upstream)으로의 역 리스트 반환
     * 반환 리스트: [targetCanonical, 1번째전역, 2번째전역, ...]
     * 만약 targetCanonical이 시발역이어서 이전 역이 없으면 [targetCanonical] (size 1) 반환
     */
    private fun buildUpstreamPath(
        adj: Map<String, Map<String, Int>>,
        targetCanonical: String,
        startCanonical: String?,
        endCanonical: String?
    ): List<String> {
        // 1) 출발역(startCanonical)을 아는 경우: targetCanonical -> startCanonical 최단 경로
        if (startCanonical != null && startCanonical != targetCanonical && adj.containsKey(startCanonical)) {
            val bfsPath = findShortestStationPath(adj, targetCanonical, startCanonical)
            if (bfsPath.size >= 2) return bfsPath
        }

        // 2) 종착역(endCanonical)을 아는 경우: 종착역으로부터 거리가 멀어지는 방향(열차가 달려온 이전 역 방향)으로만 역추적
        if (endCanonical != null && adj.containsKey(endCanonical)) {
            val distToEnd = computeHopDistancesFrom(adj, endCanonical)
            val result = mutableListOf(targetCanonical)
            val visited = mutableSetOf(targetCanonical)
            var curr = targetCanonical

            while (result.size <= 40) {
                val currDist = distToEnd[curr] ?: break
                val neighbors = adj[curr]?.keys ?: break
                // 반드시 종착역까지의 거리가 현재 역보다 더 먼 이웃 역(= 이전 역)만 선택! (앞쪽 역으로 역주행 금지)
                val prevStation = neighbors
                    .filter { it !in visited && (distToEnd[it] ?: -1) > currDist }
                    .maxByOrNull { distToEnd[it] ?: -1 }
                    ?: break

                visited.add(prevStation)
                result.add(prevStation)
                curr = prevStation
            }
            return result
        }

        return emptyList()
    }

    private fun findShortestStationPath(
        adj: Map<String, Map<String, Int>>,
        from: String,
        to: String
    ): List<String> {
        val queue = ArrayDeque<List<String>>()
        val visited = mutableSetOf(from)
        queue.add(listOf(from))

        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val last = path.last()
            if (last == to) return path
            val neighbors = adj[last]?.keys ?: continue
            for (nxt in neighbors) {
                if (visited.add(nxt)) {
                    queue.add(path + nxt)
                }
            }
        }
        return emptyList()
    }

    private fun computeHopDistancesFrom(
        adj: Map<String, Map<String, Int>>,
        source: String
    ): Map<String, Int> {
        val dist = mutableMapOf(source to 0)
        val queue = ArrayDeque<String>()
        queue.add(source)

        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            val d = dist[u] ?: 0
            val neighbors = adj[u]?.keys ?: continue
            for (v in neighbors) {
                if (!dist.containsKey(v)) {
                    dist[v] = d + 1
                    queue.add(v)
                }
            }
        }
        return dist
    }
}
