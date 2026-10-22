package ai.sunnypilot.navcompanion.data

/** Longitude/latitude pair, matching sunnypilot's navd/navRoute coordinate order. */
data class LngLat(val lon: Double, val lat: Double)

data class Place(
  val name: String,
  val address: String,
  val lon: Double,
  val lat: Double,
) {
  val point: LngLat get() = LngLat(lon, lat)
}

data class Maneuver(
  val type: String,
  val modifier: String,
  val text: String,
)

/**
 * One navigation leg. The geometry leads to the maneuver at its end, mirroring navd's use of
 * `step.distance - distance_along_geometry` as the distance to the coming maneuver.
 */
data class RouteStep(
  val distance: Double,
  val duration: Double,
  val name: String,
  val maneuver: Maneuver,
  val geometry: List<LngLat>,
)

data class NavRoute(
  val geometry: List<LngLat>,
  val distance: Double,
  val duration: Double,
  val steps: List<RouteStep>,
  val provider: String,
)

data class NavInstruction(
  val distanceToManeuver: Double,
  val primaryText: String,
  val secondaryText: String,
  val type: String,
  val modifier: String,
  val distanceRemaining: Double,
  val timeRemaining: Double,
  val arrived: Boolean = false,
)

data class DeviceGps(
  val point: LngLat,
  val bearing: Double,
  val ageSeconds: Double,
)

data class DeviceInstruction(
  val valid: Boolean,
  val distanceToManeuver: Double,
  val primaryText: String,
  val secondaryText: String,
  val modifier: String,
  val distanceRemaining: Double,
  val timeRemaining: Double,
)

data class DeviceStatus(
  val navigationEnabled: Boolean = false,
  val gps: DeviceGps? = null,
  val instruction: DeviceInstruction? = null,
  val route: List<LngLat> = emptyList(),
  val destination: LngLat? = null,
)
