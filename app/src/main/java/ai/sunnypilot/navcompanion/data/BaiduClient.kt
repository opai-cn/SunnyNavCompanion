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
 * Baidu (百度) web service client: POI search and driving directions.
 *
 * Search results arrive as BD-09 and are converted to WGS-84; route requests send GCJ-02 and ask
 * Baidu to return GCJ-02 geometry, which is then converted back.
 */
class BaiduClient {
  private val client = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

  suspend fun search(ak: String, query: String, near: LngLat?): List<Place> = withContext(Dispatchers.IO) {
    val builder = "https://api.map.baidu.com/place/v2/search".toHttpUrl().newBuilder()
      .addQueryParameter("query", query)
      .addQueryParameter("region", "全国")
      .addQueryParameter("output", "json")
      .addQueryParameter("scope", "2")
      .addQueryParameter("page_size", "10")
      .addQueryParameter("page_num", "0")
      .addQueryParameter("coord_type", "2")
      .addQueryParameter("ak", ak)
    if (near != null) {
      val gcj = ChinaGeo.wgs84ToGcj02(near)
      builder.addQueryParameter("location", "${gcj.lat},${gcj.lon}")
      builder.addQueryParameter("radius", "50000")
    }
    val root = JSONObject(get(builder.build().toString()))
    if (root.optInt("status", -1) != 0) {
      throw IOException("百度搜索：${root.optString("message").ifBlank { root.optInt("status").toString() }}")
    }
    val results = root.optJSONArray("results") ?: return@withContext emptyList()
    val out = ArrayList<Place>()
    for (i in 0 until results.length()) {
      val item = results.optJSONObject(i) ?: continue
      val location = item.optJSONObject("location") ?: continue
      val lon = location.optDouble("lng", Double.NaN)
      val lat = location.optDouble("lat", Double.NaN)
      if (lon.isNaN() || lat.isNaN()) continue
      val wgs = ChinaGeo.bd09ToWgs84(LngLat(lon, lat))
      out.add(
        Place(
          name = item.optString("name").ifBlank { "未命名地点" },
          address = listOf(item.optString("address"), item.optString("area"), item.optString("city"))
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

  suspend fun route(ak: String, from: LngLat, to: LngLat): NavRoute = withContext(Dispatchers.IO) {
    val origin = ChinaGeo.wgs84ToGcj02(from).let { "${it.lat},${it.lon}" }
    val destination = ChinaGeo.wgs84ToGcj02(to).let { "${it.lat},${it.lon}" }
    val url = "https://api.map.baidu.com/direction/v2/driving".toHttpUrl().newBuilder()
      .addQueryParameter("origin", origin)
      .addQueryParameter("destination", destination)
      .addQueryParameter("ak", ak)
      .addQueryParameter("coord_type", "gcj02")
      .addQueryParameter("ret_coordtype", "gcj02")
      .addQueryParameter("alternatives", "0")
      .build()
    val root = JSONObject(get(url.toString()))
    if (root.optInt("status", -1) != 0) {
      throw IOException("百度路线：${root.optString("message").ifBlank { root.optInt("status").toString() }}")
    }
    val routes = root.optJSONObject("result")?.optJSONArray("routes") ?: throw IOException("百度无路线")
    if (routes.length() == 0) throw IOException("百度无路线")
    val route = routes.getJSONObject(0)
    val rawSteps = route.optJSONArray("steps") ?: throw IOException("百度无路线分段")

    val steps = ArrayList<RouteStep>()
    val geometry = ArrayList<LngLat>()
    var cursor: LngLat? = null
    for (i in 0 until rawSteps.length()) {
      val step = rawSteps.optJSONObject(i) ?: continue
      var points = parsePolyline(step.optString("path")).map { ChinaGeo.gcj02ToWgs84(it) }
      if (points.isEmpty() && cursor != null) points = listOf(cursor)
      if (points.size == 1 && cursor != null && points[0] != cursor) points = listOf(cursor, points[0])
      if (points.isNotEmpty()) cursor = points.last()
      for (point in points) {
        if (geometry.lastOrNull() != point) geometry.add(point)
      }
      steps.add(
        RouteStep(
          distance = step.optDouble("distance", 0.0),
          duration = step.optDouble("duration", 0.0),
          name = step.optString("road_name"),
          maneuver = maneuverOf(step.optString("instruction")),
          geometry = points.ifEmpty { listOfNotNull(cursor) },
        )
      )
    }
    if (steps.isEmpty()) throw IOException("百度无路线分段")
    if (geometry.size < 2) throw IOException("百度路线为空")

    NavRoute(
      geometry = geometry,
      distance = route.optDouble("distance", steps.sumOf { it.distance }),
      duration = route.optDouble("duration", steps.sumOf { it.duration }),
      steps = steps,
      provider = "百度",
    )
  }

  private fun maneuverOf(instruction: String): Maneuver {
    val text = instruction.trim()
    return when {
      text.contains("到达") || text.contains("终点") -> Maneuver("arrive", "", "到达目的地")
      text.contains("掉头") -> Maneuver("turn", "uturn", text)
      text.contains("靠左") || text.contains("左侧") -> Maneuver("turn", "slight left", text)
      text.contains("靠右") || text.contains("右侧") -> Maneuver("turn", "slight right", text)
      text.contains("左转") || text.contains("向左") || text.contains("左前方") ->
        Maneuver("turn", "left", text)
      text.contains("右转") || text.contains("向右") || text.contains("右前方") ->
        Maneuver("turn", "right", text)
      text.isBlank() -> Maneuver("continue", "straight", "继续行驶")
      else -> Maneuver("continue", "straight", text)
    }
  }

  private fun parsePolyline(value: String): List<LngLat> {
    if (value.isBlank()) return emptyList()
    return value.split(";").mapNotNull { pair ->
      val parts = pair.split(",")
      if (parts.size < 2) return@mapNotNull null
      val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
      val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
      LngLat(lon, lat)
    }
  }

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
