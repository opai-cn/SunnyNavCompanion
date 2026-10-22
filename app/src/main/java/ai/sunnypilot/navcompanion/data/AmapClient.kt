package ai.sunnypilot.navcompanion.data

import ai.sunnypilot.navcompanion.nav.ChinaGeo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AMap (高德) web service client: POI text search and driving directions.
 *
 * Requests use GCJ-02 coordinates, so everything entering or leaving this class is converted to
 * the WGS-84 datum the rest of the app works in.
 */
class AmapClient {
  private val client = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

  suspend fun search(key: String, query: String): List<Place> = withContext(Dispatchers.IO) {
    val url = "https://restapi.amap.com/v3/place/text".toHttpUrl().newBuilder()
      .addQueryParameter("key", key)
      .addQueryParameter("keywords", query)
      .addQueryParameter("offset", "10")
      .addQueryParameter("page", "1")
      .addQueryParameter("extensions", "base")
      .build()
    val root = JSONObject(get(url.toString()))
    if (root.optString("status") != "1") {
      throw IOException("高德搜索：${root.optString("info").ifBlank { root.optString("infocode") }}")
    }
    val pois = root.optJSONArray("pois") ?: return@withContext emptyList()
    val out = ArrayList<Place>()
    for (i in 0 until pois.length()) {
      val poi = pois.optJSONObject(i) ?: continue
      val point = parseLocation(string(poi, "location")) ?: continue
      val wgs = ChinaGeo.gcj02ToWgs84(point)
      val name = string(poi, "name")
      out.add(
        Place(
          name = name.ifBlank { "未命名地点" },
          address = listOf(string(poi, "address"), string(poi, "adname"), string(poi, "cityname"))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · "),
          lon = wgs.lon,
          lat = wgs.lat,
        )
      )
    }
    out
  }

  suspend fun route(key: String, from: LngLat, to: LngLat): NavRoute = withContext(Dispatchers.IO) {
    val origin = ChinaGeo.wgs84ToGcj02(from).let { "${it.lon},${it.lat}" }
    val destination = ChinaGeo.wgs84ToGcj02(to).let { "${it.lon},${it.lat}" }
    val url = "https://restapi.amap.com/v3/direction/driving".toHttpUrl().newBuilder()
      .addQueryParameter("key", key)
      .addQueryParameter("origin", origin)
      .addQueryParameter("destination", destination)
      .addQueryParameter("extensions", "all")
      .addQueryParameter("strategy", "0")
      .addQueryParameter("ferry", "0")
      .build()
    val root = JSONObject(get(url.toString()))
    if (root.optString("status") != "1") {
      throw IOException("高德路线：${root.optString("info").ifBlank { root.optString("infocode") }}")
    }
    val paths = root.optJSONObject("route")?.optJSONArray("paths") ?: throw IOException("高德无路线")
    if (paths.length() == 0) throw IOException("高德无路线")
    val path = paths.getJSONObject(0)
    val rawSteps = path.optJSONArray("steps") ?: throw IOException("高德无路线分段")

    val steps = ArrayList<RouteStep>()
    val geometry = ArrayList<LngLat>()
    var cursor: LngLat? = null
    for (i in 0 until rawSteps.length()) {
      val step = rawSteps.optJSONObject(i) ?: continue
      var points = parsePolyline(string(step, "polyline")).map { ChinaGeo.gcj02ToWgs84(it) }
      if (points.isEmpty() && cursor != null) points = listOf(cursor)
      if (points.size == 1 && cursor != null && points[0] != cursor) points = listOf(cursor, points[0])
      if (points.isNotEmpty()) cursor = points.last()
      for (point in points) {
        if (geometry.lastOrNull() != point) geometry.add(point)
      }
      val distance = string(step, "distance").toDoubleOrNull() ?: 0.0
      val duration = string(step, "duration").toDoubleOrNull() ?: 0.0
      steps.add(
        RouteStep(
          distance = distance,
          duration = duration,
          name = string(step, "road"),
          maneuver = maneuverOf(
            string(step, "instruction"),
            string(step, "action"),
            string(step, "assistant_action"),
          ),
          geometry = points.ifEmpty { listOfNotNull(cursor) },
        )
      )
    }
    if (steps.isEmpty()) throw IOException("高德无路线分段")

    val totalDistance = string(path, "distance").toDoubleOrNull()
      ?: steps.sumOf { it.distance }
    val totalDuration = string(path, "duration").toDoubleOrNull()
      ?: steps.sumOf { it.duration }
    if (geometry.size < 2) throw IOException("高德路线为空")

    NavRoute(
      geometry = geometry,
      distance = totalDistance,
      duration = totalDuration,
      steps = steps,
      provider = "高德",
    )
  }

  private fun maneuverOf(instruction: String, action: String, assistant: String): Maneuver {
    val probe = action + assistant + instruction
    val text = instruction.ifBlank { action }.ifBlank { assistant }
    return when {
      probe.contains("到达") -> Maneuver("arrive", "", "到达目的地")
      probe.contains("掉头") -> Maneuver("turn", "uturn", text.ifBlank { "掉头" })
      probe.contains("靠左") || probe.contains("左侧") -> Maneuver("turn", "slight left", text)
      probe.contains("靠右") || probe.contains("右侧") -> Maneuver("turn", "slight right", text)
      probe.contains("左转") || probe.contains("向左") || probe.contains("左前方") ->
        Maneuver("turn", "left", text)
      probe.contains("右转") || probe.contains("向右") || probe.contains("右前方") ->
        Maneuver("turn", "right", text)
      else -> Maneuver("continue", "straight", text.ifBlank { "继续行驶" })
    }
  }

  private fun parseLocation(value: String): LngLat? {
    val parts = value.split(",")
    if (parts.size < 2) return null
    val lon = parts[0].toDoubleOrNull() ?: return null
    val lat = parts[1].toDoubleOrNull() ?: return null
    return LngLat(lon, lat)
  }

  private fun parsePolyline(value: String): List<LngLat> {
    if (value.isBlank()) return emptyList()
    return value.split(";").mapNotNull { parseLocation(it) }
  }

  /** AMap encodes absent string fields as an empty array, so only accept real strings. */
  private fun string(obj: JSONObject, key: String): String =
    (obj.opt(key) as? String)?.trim().orEmpty()

  private fun get(url: String): String {
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
      return response.body?.string() ?: throw IOException("响应为空")
    }
  }

  companion object {
    private const val USER_AGENT = "SunnyNavCompanion/1.0 (Android)"
  }
}
