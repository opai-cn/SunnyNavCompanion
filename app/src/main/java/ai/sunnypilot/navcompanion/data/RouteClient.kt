package ai.sunnypilot.navcompanion.data

import ai.sunnypilot.navcompanion.nav.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Route planning. OSRM answers with full turn-by-turn steps; BRouter is used as a keyless
 * fallback, with maneuvers inferred from the route geometry.
 */
class RouteClient {
  private val client = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

  suspend fun route(from: LngLat, to: LngLat, provider: String): NavRoute = withContext(Dispatchers.IO) {
    val attempts = when (provider) {
      "osrm" -> listOf("osrm")
      "brouter" -> listOf("brouter")
      else -> listOf("osrm", "brouter")
    }
    var lastError: Exception? = null
    for (attempt in attempts) {
      try {
        return@withContext when (attempt) {
          "osrm" -> osrm(from, to)
          else -> brouter(from, to)
        }
      } catch (e: Exception) {
        lastError = e
      }
    }
    throw lastError ?: IOException("无法规划路线")
  }

  private fun osrm(from: LngLat, to: LngLat): NavRoute {
    val url = "$OSRM_BASE/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}" +
      "?overview=full&geometries=geojson&steps=true&alternatives=false"
    val root = JSONObject(get(url))
    if (root.optString("code") != "Ok") {
      throw IOException("OSRM 返回 ${root.optString("code")}")
    }
    val routes = root.optJSONArray("routes") ?: throw IOException("OSRM 无路线")
    if (routes.length() == 0) throw IOException("OSRM 无路线")
    val route = routes.getJSONObject(0)
    val geometry = coordinateList(route.getJSONObject("geometry").getJSONArray("coordinates"))
    val leg = route.getJSONArray("legs").getJSONObject(0)
    val rawSteps = leg.getJSONArray("steps")
    val osrmSteps = (0 until rawSteps.length()).map { parseOsrmStep(rawSteps.getJSONObject(it)) }
    return NavRoute(
      geometry = geometry,
      distance = route.optDouble("distance", 0.0),
      duration = route.optDouble("duration", 0.0),
      steps = buildOsrmSteps(osrmSteps),
      provider = "OSRM",
    )
  }

  private fun brouter(from: LngLat, to: LngLat): NavRoute {
    val url = "https://brouter.de/brouter".toHttpUrl().newBuilder()
      .addQueryParameter("lonlats", "${from.lon},${from.lat}|${to.lon},${to.lat}")
      .addQueryParameter("profile", "car-fast")
      .addQueryParameter("alternativeidx", "0")
      .addQueryParameter("format", "geojson")
      .build()
    val root = JSONObject(get(url.toString()))
    if (root.has("error")) throw IOException("BRouter: ${root.optString("error")}")
    val features = root.optJSONArray("features") ?: throw IOException("BRouter 无路线")
    if (features.length() == 0) throw IOException("BRouter 无路线")
    val feature = features.getJSONObject(0)
    val points = coordinateList(feature.getJSONObject("geometry").getJSONArray("coordinates"))
    if (points.size < 2) throw IOException("BRouter 路线为空")
    val props = feature.optJSONObject("properties") ?: JSONObject()
    val distance = props.optString("track-length").toDoubleOrNull()
      ?: Geo.cumulativeDistance(points).last()
    val duration = props.optString("total-time").toDoubleOrNull()
      ?: (distance / 13.0)
    return NavRoute(
      geometry = points,
      distance = distance,
      duration = duration,
      steps = deriveSteps(points, distance, duration),
      provider = "BRouter",
    )
  }

  private data class OsrmStep(
    val distance: Double,
    val duration: Double,
    val name: String,
    val type: String,
    val modifier: String,
    val geometry: List<LngLat>,
    val start: LngLat,
  )

  private fun parseOsrmStep(obj: JSONObject): OsrmStep {
    val maneuver = obj.optJSONObject("maneuver") ?: JSONObject()
    val location = maneuver.optJSONArray("location")
    val geometry = obj.optJSONObject("geometry")
      ?.optJSONArray("coordinates")
      ?.let { coordinateList(it) }
      ?: emptyList()
    val start = if (location != null && location.length() >= 2) {
      LngLat(location.getDouble(0), location.getDouble(1))
    } else {
      geometry.firstOrNull() ?: LngLat(0.0, 0.0)
    }
    return OsrmStep(
      distance = obj.optDouble("distance", 0.0),
      duration = obj.optDouble("duration", 0.0),
      name = obj.optString("name").ifBlank { obj.optString("ref") },
      type = maneuver.optString("type"),
      modifier = maneuver.optString("modifier"),
      geometry = geometry,
      start = start,
    )
  }

  /**
   * Converts OSRM's "maneuver at the start of each step" into legs whose maneuver sits at the
   * end, which is what NavSession expects (same convention as navd).
   */
  private fun buildOsrmSteps(osrmSteps: List<OsrmStep>): List<RouteStep> {
    if (osrmSteps.isEmpty()) return emptyList()
    if (osrmSteps.size == 1) {
      val only = osrmSteps[0]
      return listOf(
        RouteStep(
          distance = only.distance.coerceAtLeast(0.0),
          duration = only.duration.coerceAtLeast(0.0),
          name = only.name,
          maneuver = Maneuver("continue", "straight", "继续直行"),
          geometry = only.geometry.ifEmpty { listOf(only.start) },
        )
      )
    }
    val steps = ArrayList<RouteStep>(osrmSteps.size)
    for (i in 0 until osrmSteps.size - 1) {
      val current = osrmSteps[i]
      val next = osrmSteps[i + 1]
      val geometry = if (current.geometry.size >= 2) current.geometry else listOf(current.start, next.start)
      steps.add(
        RouteStep(
          distance = current.distance.coerceAtLeast(0.0),
          duration = current.duration.coerceAtLeast(0.0),
          name = current.name,
          maneuver = maneuverFor(current.type, current.modifier, current.name),
          geometry = geometry,
        )
      )
    }
    val last = osrmSteps.last()
    steps.add(
      RouteStep(
        distance = last.distance.coerceAtLeast(0.0),
        duration = last.duration.coerceAtLeast(0.0),
        name = last.name,
        maneuver = Maneuver("arrive", "", "到达目的地"),
        geometry = last.geometry.ifEmpty { listOf(last.start) },
      )
    )
    return steps
  }

  /** BRouter's GeoJSON carries no turn text, so maneuvers are inferred from heading changes. */
  private fun deriveSteps(points: List<LngLat>, totalDistance: Double, totalDuration: Double): List<RouteStep> {
    if (points.size < 2) return emptyList()
    val cumulative = Geo.cumulativeDistance(points)
    val total = cumulative.last().coerceAtLeast(1.0)
    val speed = if (totalDuration > 1.0) total / totalDuration else 13.0

    val turns = ArrayList<Pair<Int, Maneuver>>()
    var lastTurnDistance = -1000.0
    for (i in 1 until points.size - 1) {
      val before = Geo.bearing(points[i - 1], points[i])
      val after = Geo.bearing(points[i], points[i + 1])
      val delta = Geo.turnDelta(before, after)
      if (abs(delta) < 22.0) continue
      if (cumulative[i] - lastTurnDistance < 25.0) continue
      turns.add(i to maneuverForDelta(delta))
      lastTurnDistance = cumulative[i]
    }

    val steps = ArrayList<RouteStep>()
    var startIndex = 0
    var startDistance = 0.0
    for ((index, maneuver) in turns) {
      val distance = (cumulative[index] - startDistance).coerceAtLeast(0.0)
      steps.add(
        RouteStep(
          distance = distance,
          duration = distance / speed,
          name = "",
          maneuver = maneuver,
          geometry = points.subList(startIndex, index + 1).toList(),
        )
      )
      startIndex = index
      startDistance = cumulative[index]
    }
    val finalDistance = (total - startDistance).coerceAtLeast(0.0)
    steps.add(
      RouteStep(
        distance = finalDistance,
        duration = finalDistance / speed,
        name = "",
        maneuver = Maneuver("arrive", "", "到达目的地"),
        geometry = points.subList(startIndex, points.size).toList(),
      )
    )
    if (turns.isEmpty()) {
      steps[0] = steps[0].copy(maneuver = Maneuver("continue", "straight", "继续直行"))
    }
    return steps
  }

  private fun maneuverFor(type: String, modifier: String, name: String): Maneuver {
    val road = name.trim()
    val text = when (type) {
      "depart" -> "出发"
      "arrive" -> "到达目的地"
      "turn" -> when (modifier) {
        "left" -> "左转"
        "right" -> "右转"
        "slight left" -> "稍向左转"
        "slight right" -> "稍向右转"
        "sharp left" -> "急左转"
        "sharp right" -> "急右转"
        "uturn" -> "掉头"
        else -> "转弯"
      }
      "continue", "new name" -> if (road.isNotEmpty()) "沿 $road 继续行驶" else "继续直行"
      "merge" -> if (road.isNotEmpty()) "汇入 $road" else "汇入车流"
      "fork" -> if (modifier.contains("left")) "靠左行驶" else "靠右行驶"
      "roundabout", "rotary" -> "进入环岛"
      "exit roundabout", "exit rotary" -> "驶出环岛"
      "on ramp" -> "驶入匝道"
      "off ramp" -> "驶出匝道"
      "end of road" -> if (modifier.contains("left")) "路尽头左转" else "路尽头右转"
      else -> "继续行驶"
    }
    val withRoad = when {
      road.isEmpty() -> text
      type == "turn" || type == "end of road" -> "$text，进入 $road"
      else -> text
    }
    return Maneuver(type, modifier, withRoad)
  }

  private fun maneuverForDelta(delta: Double): Maneuver = when {
    abs(delta) >= 150.0 -> Maneuver("turn", "uturn", "掉头")
    delta >= 45.0 -> Maneuver("turn", "sharp right", "急右转")
    delta >= 22.0 -> Maneuver("turn", "right", "右转")
    delta <= -45.0 -> Maneuver("turn", "sharp left", "急左转")
    else -> Maneuver("turn", "left", "左转")
  }

  private fun coordinateList(array: JSONArray): List<LngLat> {
    val out = ArrayList<LngLat>(array.length())
    for (i in 0 until array.length()) {
      val pair = array.optJSONArray(i) ?: continue
      if (pair.length() >= 2) out.add(LngLat(pair.getDouble(0), pair.getDouble(1)))
    }
    return out
  }

  private fun get(url: String): String {
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
      return response.body?.string() ?: throw IOException("响应为空")
    }
  }

  companion object {
    private const val OSRM_BASE = "https://router.project-osrm.org"
    private const val USER_AGENT = "SunnyNavCompanion/1.0 (Android)"
  }
}
