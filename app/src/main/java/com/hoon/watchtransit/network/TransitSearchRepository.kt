package com.hoon.watchtransit.network

import android.content.Context
import android.util.Log
import com.hoon.watchtransit.data.SubwayCoordinates
import com.hoon.watchtransit.model.TransitCity
import com.hoon.watchtransit.model.TransitTarget
import com.hoon.watchtransit.model.TransitType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.UUID

interface PublicTransitApiService {

    // 1. 서울 열린데이터광장 지하철역 검색 API (JSON 포맷 호출)
    // 오타 수정 완료: SearchSTNBySubwayNmService -> SearchInfoBySubwayNameService (서울시 500 에러 해결)
    @GET("http://openapi.seoul.go.kr:8088/{apiKey}/json/SearchInfoBySubwayNameService/1/50/{stationName}")
    suspend fun searchSubwayStations(
        @Path("apiKey") apiKey: String,
        @Path("stationName") stationName: String
    ): SeoulSubwaySearchResponse

    // 2. TAGO 버스정류소명 또는 번호 검색 API
    @GET("https://apis.data.go.kr/1613000/BusSttnInfoInqireService/getSttnNoList")
    suspend fun searchBusStops(
        @Query(value = "serviceKey", encoded = true) serviceKey: String,
        @Query("cityCode") cityCode: Int,
        @Query("nodeNm") keyword: String? = null,
        @Query("nodeNo") nodeNo: String? = null,
        @Query("_type") type: String = "json",
        @Query("numOfRows") numOfRows: Int = 30
    ): TagoBusStopResponse

    // 3. TAGO 전국 도시코드 목록 조회 API (지자체별 버스 조회를 위한 도시코드 수집)
    @GET("https://apis.data.go.kr/1613000/BusSttnInfoInqireService/getCtyCodeList")
    suspend fun getCityCodes(
        @Query(value = "serviceKey", encoded = true) serviceKey: String,
        @Query("_type") type: String = "json"
    ): TagoCityCodeResponse

    // 4. 서울 열린데이터광장 서울시 버스정류소 검색 API
    @GET("http://openapi.seoul.go.kr:8088/{apiKey}/json/busStopLocationXyInfo/1/30/{searchName}")
    suspend fun searchSeoulBusStops(
        @Path("apiKey") apiKey: String,
        @Path("searchName") searchName: String
    ): SeoulBusStopResponse
}

class TransitSearchRepository(context: Context) {

    companion object {
        // 서울 열린데이터광장 인증키
        private const val SEOUL_SUBWAY_KEY = "62567966526a686b373648645a5255"

        // 국토교통부 TAGO 인증키
        private const val TAGO_SERVICE_KEY_ENCODED =
            "C2AoTlWc9HO0E1s9QrrQVJui3pGSQBr%2BbeSgwqYSfynWWrbcA3H8NOBwrY3ONCZ%2FosuYrdvE1RjKvkDWCe58OQ%3D%3D"
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val api: PublicTransitApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://apis.data.go.kr/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PublicTransitApiService::class.java)
    }

    /**
     * 전국 버스 운행 도시코드 목록 조회 (TAGO 기반)
     */
    suspend fun fetchCityCodes(): List<TransitCity> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.getCityCodes(serviceKey = TAGO_SERVICE_KEY_ENCODED)
            val itemsEl = response.response?.body?.items ?: return@runCatching defaultCityList()
            val itemNode = (itemsEl as? JsonObject)?.get("item") ?: return@runCatching defaultCityList()

            val rawItems: List<TagoCityCodeItem> = when (itemNode) {
                is JsonArray -> json.decodeFromJsonElement(itemNode)
                is JsonObject -> listOf(json.decodeFromJsonElement(itemNode))
                else -> emptyList()
            }

            val list = mutableListOf<TransitCity>()
            // 서울특별시 우선 추가
            list.add(TransitCity(11, "서울특별시"))

            rawItems.mapNotNull {
                val code = it.cityCode.takeIf { c -> c > 0 } ?: return@mapNotNull null
                val name = it.cityName.orEmpty()
                TransitCity(code, name)
            }.forEach { city ->
                if (city.code != 11 && list.none { it.code == city.code }) {
                    list.add(city)
                }
            }

            list
        }.getOrElse { e ->
            Log.w("TransitSearch", "도시코드 API 조회 실패, 기본 목록 사용: ${e.message}")
            defaultCityList()
        }
    }

    private fun defaultCityList(): List<TransitCity> = listOf(
        TransitCity(11, "서울특별시"),
        TransitCity(23, "인천광역시"),
        TransitCity(31010, "수원시"),
        TransitCity(31020, "성남시"),
        TransitCity(31040, "안양시"),
        TransitCity(31050, "부천시"),
        TransitCity(31100, "고양시"),
        TransitCity(31110, "과천시"),
        TransitCity(31130, "남양주시"),
        TransitCity(31170, "의왕시"),
        TransitCity(31190, "용인시"),
        TransitCity(31200, "파주시"),
        TransitCity(21, "부산광역시"),
        TransitCity(22, "대구광역시"),
        TransitCity(24, "광주광역시"),
        TransitCity(25, "대전광역시"),
        TransitCity(26, "울산광역시"),
        TransitCity(12, "세종특별시"),
        TransitCity(39, "제주도")
    )

    /**
     * 지하철역과 선택된 도시의 버스정류소을 비동기 병렬로 동시에 검색 (통합 검색)
     * 검색 결과 중 지하철역이 있으면 항상 최상단에 우선 표출
     */
    suspend fun searchAll(keyword: String, city: TransitCity): List<TransitTarget> = withContext(Dispatchers.IO) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return@withContext emptyList()

        val subwayDeferred = async { searchSubway(clean) }
        val busDeferred = async {
            if (city.code == 11) {
                searchSeoulBusStop(clean)
            } else {
                searchBusStop(city.code, clean)
            }
        }

        val subwayList = subwayDeferred.await()
        val busList = busDeferred.await()

        // 1. 지하철역(SUBWAY)을 항상 최상단에 배치
        // 2. 지하철역 중에서도 검색어와 정확히 일치하는 역을 최우선 정렬
        val sortedSubway = subwayList.sortedWith(
            compareBy<TransitTarget> {
                val cleanQuery = clean.removeSuffix("역").trim()
                val stationName = it.name.removeSuffix("역").trim()
                if (stationName == cleanQuery) 0 else 1
            }
        )

        sortedSubway + busList
    }

    suspend fun searchSubway(keyword: String): List<TransitTarget> = withContext(Dispatchers.IO) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val queries = if (trimmed == "서울" || trimmed == "서울역") {
            listOf("서울역", "서울")
        } else {
            val clean = trimmed.removeSuffix("역")
            if (clean.isNotEmpty() && clean != trimmed) {
                listOf(clean, trimmed)
            } else {
                listOf(trimmed)
            }
        }

        val results = mutableListOf<TransitTarget>()
        for (q in queries) {
            val res = runCatching {
                val response = api.searchSubwayStations(
                    apiKey = SEOUL_SUBWAY_KEY,
                    stationName = q
                )
                val rows = response.service?.row ?: emptyList()
                rows.map { row ->
                    val (lat, lon) = lookupSubwayCoords(row.stationNm)
                    TransitTarget(
                        id = UUID.randomUUID().toString(),
                        type = TransitType.SUBWAY,
                        name = row.stationNm,
                        targetCode = row.stationCd,
                        subCode = formatLineName(row.lineNum),
                        directionOrRoute = "양방향",
                        lat = lat,
                        lon = lon
                    )
                }
            }.getOrDefault(emptyList())

            results.addAll(res)
            if (results.isNotEmpty()) break
        }

        results.distinctBy { "${it.name}_${it.subCode}" }
    }

    private fun lookupSubwayCoords(stationName: String): Pair<Double, Double> {
        return SubwayCoordinates.get(stationName) ?: Pair(0.0, 0.0)
    }

    private fun formatLineName(rawLine: String): String {
        return when (rawLine) {
            "인천선" -> "인천1호선"
            else -> rawLine.removePrefix("0") // "04호선" -> "4호선"
        }
    }

    private suspend fun searchSeoulBusStop(keyword: String): List<TransitTarget> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.searchSeoulBusStops(
                apiKey = SEOUL_SUBWAY_KEY,
                searchName = keyword.trim()
            )

            response.service?.row?.map { item ->
                TransitTarget(
                    id = UUID.randomUUID().toString(),
                    type = TransitType.BUS,
                    name = item.stopsNm,
                    targetCode = item.stopsNo.ifEmpty { item.nodeId },
                    subCode = if (item.nodeId.isNotEmpty()) item.nodeId else item.stopsType,
                    directionOrRoute = "11",
                    lat = item.ycrd.toDoubleOrNull() ?: 0.0,
                    lon = item.xcrd.toDoubleOrNull() ?: 0.0
                )
            } ?: emptyList()
        }.onFailure { e ->
            Log.e("TransitSearch", "서울 버스정류소 API 검색 실패: ${e.message}", e)
        }.getOrDefault(emptyList())
    }

    /**
     * 국토교통부(TAGO) 및 경기도(GBIS) 버스정류소 검색 (정류소명 및 정류소번호 동시 지원)
     */
    suspend fun searchBusStop(cityCode: Int, keyword: String): List<TransitTarget> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKeyword = keyword.trim()
            if (cleanKeyword.isEmpty()) return@runCatching emptyList()

            val isNumeric = cleanKeyword.all { it.isDigit() }
            val results = mutableListOf<TransitTarget>()

            // 1. 정류소명(nodeNm)으로 TAGO 검색
            val resByName = runCatching {
                api.searchBusStops(
                    serviceKey = TAGO_SERVICE_KEY_ENCODED,
                    cityCode = cityCode,
                    keyword = cleanKeyword
                )
            }.getOrNull()
            results.addAll(parseTagoBusStops(resByName, cityCode))

            // 2. 숫자인 경우 정류소번호(nodeNo)로도 추가 TAGO 검색
            if (isNumeric) {
                val resByNo = runCatching {
                    api.searchBusStops(
                        serviceKey = TAGO_SERVICE_KEY_ENCODED,
                        cityCode = cityCode,
                        nodeNo = cleanKeyword
                    )
                }.getOrNull()
                results.addAll(parseTagoBusStops(resByNo, cityCode))
            }

            // 3. 경기도 도시이거나 결과가 없을 경우 경기도 GBIS 검색 병합 ((임시)지식산업단지, 마을버스 등 완벽 지원)
            if (cityCode in 31010..31380 || results.isEmpty()) {
                val ggbisList = searchGgbisBusStop(cleanKeyword, cityCode)
                results.addAll(ggbisList)
            }

            results.distinctBy { it.targetCode }
        }.onFailure { e ->
            Log.e("TransitSearch", "버스정류소 API 검색 실패: ${e.message}", e)
        }.getOrDefault(emptyList())
    }

    /**
     * 경기도 버스정보시스템(GBIS) 정류소 통합 검색
     */
    private fun searchGgbisBusStop(keyword: String, cityCode: Int): List<TransitTarget> {
        return runCatching {
            val formBody = FormBody.Builder()
                .add("cmd", "searchAllJson")
                .add("searchKeyword", keyword)
                .add("pageOfRoute", "1")
                .add("pageOfBus", "1")
                .add("pageOfSubway", "1")
                .add("pageOfPOI", "1")
                .build()

            val request = Request.Builder()
                .url("https://www.gbis.go.kr/gbis2014/schBusAPI.action")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string().orEmpty()
            if (bodyStr.isEmpty()) return emptyList()

            val root = json.parseToJsonElement(bodyStr).jsonObject
            val list = root["result"]?.jsonObject
                ?.get("busStation")?.jsonObject
                ?.get("list")?.jsonArray ?: return emptyList()

            list.mapNotNull { itemEl ->
                val obj = itemEl.jsonObject
                val stationId = obj["stationId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val stationNm = obj["stationNm"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val stationNo = obj["stationNo"]?.jsonPrimitive?.contentOrNull ?: "번호없음"

                if (stationId.isEmpty() || stationNm.isEmpty()) return@mapNotNull null

                TransitTarget(
                    id = UUID.randomUUID().toString(),
                    type = TransitType.BUS,
                    name = stationNm,
                    targetCode = stationId,
                    subCode = stationNo,
                    directionOrRoute = cityCode.toString()
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseTagoBusStops(response: TagoBusStopResponse?, cityCode: Int): List<TransitTarget> {
        val itemsElement = response?.response?.body?.items ?: return emptyList()
        val itemNode = (itemsElement as? JsonObject)?.get("item") ?: return emptyList()

        val stopElements: List<JsonObject> = when (itemNode) {
            is JsonArray -> itemNode.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(itemNode)
            else -> emptyList()
        }

        return stopElements.map { obj ->
            val nodeId = obj["nodeid"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val nodeNm = obj["nodenm"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val nodeNo = obj["nodeno"]?.jsonPrimitive?.contentOrNull ?: "번호없음"
            val gpsLati = obj["gpslati"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val gpsLong = obj["gpslong"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0

            TransitTarget(
                id = UUID.randomUUID().toString(),
                type = TransitType.BUS,
                name = nodeNm,
                targetCode = nodeId,
                subCode = nodeNo,
                directionOrRoute = cityCode.toString(),
                lat = gpsLati,
                lon = gpsLong
            )
        }
    }
}
