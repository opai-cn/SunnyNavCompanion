package ai.sunnypilot.navcompanion.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for bridge/nav_bridge.py running on the comma device. The bridge writes the
 * NavDestination param consumed by sunnypilot's navd and exposes cached navInstruction/navRoute.
 */
class BridgeClient {
  private val client = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .build()

  suspend fun health(base: String): Boolean = withContext(Dispatchers.IO) {
    val root = normalize(base) ?: return@withContext false
    runCatching {
      val request = Request.Builder().url("$root/health").get().build()
      client.newCall(request).execute().use { response -> response.isSuccessful }
    }.getOrDefault(false)
  }

  suspend fun status(base: String): DeviceStatus? = withContext(Dispatchers.IO) {
    val root = normalize(base) ?: return@withContext null
    runCatching {
      val request = Request.Builder().url("$root/status").get().build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          null
        } else {
          val body = response.body?.string()
          if (body == null) null else parseStatus(JSONObject(body))
        }
      }
    }.getOrNull()
  }

  suspend fun sendDestination(base: String, place: Place): Boolean = withContext(Dispatchers.IO) {
    val root = normalize(base) ?: return@withContext false
    runCatching {
      val payload = JSONObject()
        .put("latitude", place.lat)
        .put("longitude", place.lon)
        .put("name", place.name)
        .toString()
        .toRequestBody(JSON_MEDIA)
      val request = Request.Builder().url("$root/destination").post(payload).build()
      client.newCall(request).execute().use { response ->
        response.isSuccessful && JSONObject(response.body?.string() ?: "{}").optBoolean("ok", false)
      }
    }.getOrDefault(false)
  }

  suspend fun clearDestination(base: String): Boolean = withContext(Dispatchers.IO) {
    val root = normalize(base) ?: return@withContext false
    runCatching {
      val request = Request.Builder().url("$root/destination").delete().build()
      client.newCall(request).execute().use { response -> response.isSuccessful }
    }.getOrDefault(false)
  }

  private fun parseStatus(root: JSONObject): DeviceStatus {
    val gps = root.optJSONObject("gps")?.let { obj ->
      DeviceGps(
        point = LngLat(obj.optDouble("longitude"), obj.optDouble("latitude")),
        bearing = obj.optDouble("bearing", 0.0),
        ageSeconds = obj.optDouble("age", 999.0),
      )
    }
    val instruction = root.optJSONObject("instruction")?.let { obj ->
      DeviceInstruction(
        valid = obj.optBoolean("valid", true),
        distanceToManeuver = obj.optDouble("distanceToManeuver", 0.0),
        primaryText = obj.optString("primaryText"),
        secondaryText = obj.optString("secondaryText"),
        modifier = obj.optString("modifier"),
        distanceRemaining = obj.optDouble("distanceRemaining", 0.0),
        timeRemaining = obj.optDouble("timeRemaining", 0.0),
      )
    }
    val destination = root.optJSONObject("destination")?.let {
      LngLat(it.optDouble("longitude"), it.optDouble("latitude"))
    }
    return DeviceStatus(
      navigationEnabled = root.optBoolean("navigationEnabled", false),
      gps = gps,
      instruction = instruction,
      route = parseRoute(root.optJSONArray("route")),
      destination = destination,
    )
  }

  private fun parseRoute(array: JSONArray?): List<LngLat> {
    if (array == null) return emptyList()
    val out = ArrayList<LngLat>(array.length())
    for (i in 0 until array.length()) {
      val pair = array.optJSONArray(i) ?: continue
      if (pair.length() >= 2) out.add(LngLat(pair.getDouble(0), pair.getDouble(1)))
    }
    return out
  }

  private fun normalize(base: String): String? {
    val trimmed = base.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
  }

  companion object {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
  }
}
