package ai.sunnypilot.navcompanion.nav

import ai.sunnypilot.navcompanion.data.LngLat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coordinate conversions for Chinese map services.
 *
 * GPS, OSRM and OpenStreetMap use WGS-84, AMap uses GCJ-02 and Baidu uses BD-09. Feeding one
 * datum into the other shifts the result by hundreds of metres, so every coordinate crossing a
 * provider boundary goes through this object.
 */
object ChinaGeo {
  private const val SEMI_MAJOR_AXIS = 6378245.0
  private const val ECCENTRICITY_SQ = 0.00669342162296594323
  private const val X_PI = Math.PI * 3000.0 / 180.0

  fun wgs84ToGcj02(point: LngLat): LngLat {
    if (outOfChina(point)) return point
    val dLat = transformLat(point.lon - 105.0, point.lat - 35.0)
    val dLon = transformLon(point.lon - 105.0, point.lat - 35.0)
    val radLat = Math.toRadians(point.lat)
    var magic = sin(radLat)
    magic = 1 - ECCENTRICITY_SQ * magic * magic
    val sqrtMagic = sqrt(magic)
    val adjustLat = (dLat * 180.0) / ((SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQ)) / (magic * sqrtMagic) * Math.PI)
    val adjustLon = (dLon * 180.0) / (SEMI_MAJOR_AXIS / sqrtMagic * cos(radLat) * Math.PI)
    return LngLat(point.lon + adjustLon, point.lat + adjustLat)
  }

  /** Iterative inverse of [wgs84ToGcj02]; converges to sub-metre accuracy in a few rounds. */
  fun gcj02ToWgs84(point: LngLat): LngLat {
    if (outOfChina(point)) return point
    var result = point
    repeat(3) {
      val round = wgs84ToGcj02(result)
      result = LngLat(result.lon - (round.lon - point.lon), result.lat - (round.lat - point.lat))
    }
    return result
  }

  fun gcj02ToBd09(point: LngLat): LngLat {
    val z = sqrt(point.lon * point.lon + point.lat * point.lat) + 0.00002 * sin(point.lat * X_PI)
    val theta = atan2(point.lat, point.lon) + 0.000003 * cos(point.lon * X_PI)
    return LngLat(z * cos(theta) + 0.0065, z * sin(theta) + 0.006)
  }

  fun bd09ToGcj02(point: LngLat): LngLat {
    val x = point.lon - 0.0065
    val y = point.lat - 0.006
    val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
    val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
    return LngLat(z * cos(theta), z * sin(theta))
  }

  fun wgs84ToBd09(point: LngLat): LngLat = gcj02ToBd09(wgs84ToGcj02(point))

  fun bd09ToWgs84(point: LngLat): LngLat = gcj02ToWgs84(bd09ToGcj02(point))

  /** Converts a WGS-84 point into the datum used by the given map provider. */
  fun toProvider(point: LngLat, provider: String): LngLat = when (provider) {
    "amap" -> wgs84ToGcj02(point)
    "baidu" -> wgs84ToBd09(point)
    else -> point
  }

  /** Converts a provider coordinate back into WGS-84. */
  fun fromProvider(point: LngLat, provider: String): LngLat = when (provider) {
    "amap" -> gcj02ToWgs84(point)
    "baidu" -> bd09ToWgs84(point)
    else -> point
  }

  private fun outOfChina(point: LngLat): Boolean =
    point.lon < 72.004 || point.lon > 137.8347 || point.lat < 0.8293 || point.lat > 55.8271

  private fun transformLat(x: Double, y: Double): Double {
    var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(kotlin.math.abs(x))
    ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
    return ret
  }

  private fun transformLon(x: Double, y: Double): Double {
    var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(kotlin.math.abs(x))
    ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
    return ret
  }
}
