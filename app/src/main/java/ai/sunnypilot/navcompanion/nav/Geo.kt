package ai.sunnypilot.navcompanion.nav

import ai.sunnypilot.navcompanion.data.LngLat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Planar route geometry and progress helpers, ported from sunnypilot's
 * openpilot/selfdrive/navigation/route.py so the phone computes the same distances.
 */
object Geo {
  const val METERS_PER_DEGREE = 2 * Math.PI * 6371008.8 / 360.0
  private const val EARTH_RADIUS = 6371007.2

  fun localMeters(points: List<LngLat>, origin: LngLat): List<DoubleArray> {
    val scaleX = METERS_PER_DEGREE * cos(Math.toRadians(origin.lat))
    val scaleY = METERS_PER_DEGREE
    return points.map { p ->
      doubleArrayOf((p.lon - origin.lon) * scaleX, (p.lat - origin.lat) * scaleY)
    }
  }

  fun cumulativeDistance(route: List<LngLat>): DoubleArray {
    if (route.size < 2) return doubleArrayOf(0.0)
    val out = DoubleArray(route.size)
    for (i in 1 until route.size) {
      val a = route[i - 1]
      val b = route[i]
      val midLat = Math.toRadians((a.lat + b.lat) / 2.0)
      val d = hypot((b.lon - a.lon) * cos(midLat), b.lat - a.lat) * METERS_PER_DEGREE
      out[i] = out[i - 1] + d
    }
    return out
  }

  /** Returns index of the closest segment, fraction along it, and perpendicular distance. */
  fun nearestSegment(starts: List<DoubleArray>, ends: List<DoubleArray>, point: DoubleArray): Triple<Int, Double, Double> {
    var bestIndex = 0
    var bestT = 0.0
    var bestDistance = Double.MAX_VALUE
    for (i in starts.indices) {
      val abx = ends[i][0] - starts[i][0]
      val aby = ends[i][1] - starts[i][1]
      val lengthSq = max(abx * abx + aby * aby, 1e-12)
      val t = (((point[0] - starts[i][0]) * abx) + ((point[1] - starts[i][1]) * aby)) / lengthSq
      val clamped = t.coerceIn(0.0, 1.0)
      val px = starts[i][0] + clamped * abx
      val py = starts[i][1] + clamped * aby
      val d = hypot(px - point[0], py - point[1])
      if (d < bestDistance) {
        bestDistance = d
        bestIndex = i
        bestT = clamped
      }
    }
    return Triple(bestIndex, bestT, bestDistance)
  }

  /** Meters along a (lon, lat) path to the given point and the point's distance from the path. */
  fun alongPath(path: List<LngLat>, point: LngLat): Along {
    if (path.isEmpty()) return Along(0.0, 0.0)
    if (path.size == 1) return Along(0.0, distance(path[0], point))
    val local = localMeters(path, path[0])
    val p = localMeters(listOf(point), path[0])[0]
    val (segment, _, lateral) = nearestSegment(local.dropLast(1), local.drop(1), p)
    val cum = cumulativeDistance(path)
    val anchor = path[min(segment, path.size - 1)]
    return Along(cum[segment] + distance(anchor, point), lateral)
  }

  fun distance(a: LngLat, b: LngLat): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
      cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * Math.asin(sqrt(h.coerceIn(0.0, 1.0))) * EARTH_RADIUS
  }

  /** Bearing in degrees clockwise from north. */
  fun bearing(a: LngLat, b: LngLat): Double {
    val dLon = b.lon - a.lon
    val dLat = b.lat - a.lat
    if (abs(dLon) < 1e-12 && abs(dLat) < 1e-12) return 0.0
    val degrees = Math.toDegrees(atan2(dLon * cos(Math.toRadians(a.lat)), dLat))
    return (degrees + 360.0) % 360.0
  }

  /** Signed turn angle in (-180, 180]; positive is a clockwise (right) turn. */
  fun turnDelta(before: Double, after: Double): Double {
    var d = after - before
    while (d > 180.0) d -= 360.0
    while (d <= -180.0) d += 360.0
    return d
  }

  data class Along(val along: Double, val lateral: Double)
}
