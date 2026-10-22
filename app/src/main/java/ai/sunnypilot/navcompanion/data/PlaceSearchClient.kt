package ai.sunnypilot.navcompanion.data

import ai.sunnypilot.navcompanion.nav.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Place search. AMap or Baidu is preferred when the route provider selects one of them and its
 * key is present; Photon (OSM) and Nominatim remain the keyless fallback.
 */
class PlaceSearchClient {
  private val client = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()
  private val amap = AmapClient()
  private val baidu = BaiduClient()

  suspend fun search(
    query: String,
    near: LngLat?,
    provider: String = "auto",
    amapKey: String = "",
    baiduAk: String = "",
  ): List<Place> = withContext(Dispatchers.IO) {
    val china = when {
      provider == "amap" && amapKey.isNotBlank() ->
        runCatching { amap.search(amapKey, query) }.getOrDefault(emptyList())
      provider == "baidu" && baiduAk.isNotBlank() ->
        runCatching { baidu.search(baiduAk, query, near) }.getOrDefault(emptyList())
      else -> emptyList()
    }
    if (china.isNotEmpty()) return@withContext china
    val photon = runCatching { photon(query, near) }.getOrDefault(emptyList())
    if (photon.isNotEmpty()) photon else runCatching { nominatim(query) }.getOrDefault(emptyList())
  }

  private fun photon(query: String, near: LngLat?): List<Place> {
    val builder = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
      .addQueryParameter("q", query)
      .addQueryParameter("limit", "10")
    if (near != null) {
      builder.addQueryParameter("lat", near.lat.toString())
      builder.addQueryParameter("lon", near.lon.toString())
    }
    val request = Request.Builder()
      .url(builder.build())
      .header("User-Agent", USER_AGENT)
      .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return emptyList()
      val root = JSONObject(response.body?.string() ?: return emptyList())
      val features = root.optJSONArray("features") ?: return emptyList()
      val out = ArrayList<Place>()
      for (i in 0 until features.length()) {
        val feature = features.optJSONObject(i) ?: continue
        val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
        if (coords.length() < 2) continue
        val props = feature.optJSONObject("properties") ?: JSONObject()
        val street = listOf(props.optString("street"), props.optString("housenumber"))
          .filter { it.isNotBlank() }
          .joinToString(" ")
        val city = props.optString("city").ifBlank { props.optString("district") }
        val region = props.optString("state").ifBlank { props.optString("county") }
        val country = props.optString("country")
        val name = props.optString("name").ifBlank { street }.ifBlank { city }
        val address = listOf(street, city, region, country)
          .filter { it.isNotBlank() }
          .distinct()
          .joinToString(" · ")
        out.add(
          Place(
            name = name.ifBlank { "未命名地点" },
            address = address,
            lon = coords.getDouble(0),
            lat = coords.getDouble(1),
          )
        )
      }
      return out
    }
  }

  private fun nominatim(query: String): List<Place> {
    val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
      .addQueryParameter("format", "jsonv2")
      .addQueryParameter("limit", "10")
      .addQueryParameter("accept-language", "zh-CN")
      .addQueryParameter("q", query)
      .build()
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return emptyList()
      val array = JSONArray(response.body?.string() ?: return emptyList())
      val out = ArrayList<Place>()
      for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val name = item.optString("name").ifBlank {
          item.optString("display_name").substringBefore(",")
        }
        out.add(
          Place(
            name = name.ifBlank { "未命名地点" },
            address = item.optString("display_name"),
            lon = item.optDouble("lon"),
            lat = item.optDouble("lat"),
          )
        )
      }
      return out
    }
  }

  companion object {
    private const val USER_AGENT = "SunnyNavCompanion/1.0 (Android)"
  }
}
