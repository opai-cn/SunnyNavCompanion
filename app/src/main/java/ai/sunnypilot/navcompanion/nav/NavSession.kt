package ai.sunnypilot.navcompanion.nav

import ai.sunnypilot.navcompanion.data.LngLat
import ai.sunnypilot.navcompanion.data.NavInstruction
import ai.sunnypilot.navcompanion.data.NavRoute
import kotlin.math.max

/**
 * Tracks progress along a route and produces the same fields sunnypilot's navd publishes
 * in `navInstruction`: distance to the coming maneuver plus remaining distance/time.
 */
class NavSession(
  private val route: NavRoute,
  private val destination: LngLat,
) {
  private var stepIndex = 0
  private var offRouteCount = 0
  private var lastRemaining = route.distance
  private var lastDuration = route.duration

  var arrived: Boolean = false
    private set

  fun update(position: LngLat): NavInstruction {
    if (arrived) return arrivedInstruction()
    val steps = route.steps

    if (steps.isEmpty()) {
      val remaining = Geo.distance(position, destination)
      if (remaining < 30.0) {
        arrived = true
        return arrivedInstruction()
      }
      lastRemaining = remaining
      lastDuration = remaining / 13.0
      return NavInstruction(
        distanceToManeuver = remaining,
        primaryText = "继续直行",
        secondaryText = "",
        type = "continue",
        modifier = "straight",
        distanceRemaining = remaining,
        timeRemaining = remaining / 13.0,
      )
    }

    if (stepIndex > steps.lastIndex) stepIndex = steps.lastIndex
    val step = steps[stepIndex]
    val along = Geo.alongPath(step.geometry, position).along
    val distanceToManeuver = step.distance - along
    val remainingInStep = distanceToManeuver.coerceIn(0.0, step.distance)

    var remaining = remainingInStep
    var time = step.duration * (remainingInStep / max(step.distance, 1.0))
    for (i in stepIndex + 1 until steps.size) {
      remaining += steps[i].distance
      time += steps[i].duration
    }
    lastRemaining = remaining
    lastDuration = time

    val instruction = NavInstruction(
      distanceToManeuver = distanceToManeuver.coerceAtLeast(0.0),
      primaryText = step.maneuver.text,
      secondaryText = "",
      type = step.maneuver.type,
      modifier = step.maneuver.modifier,
      distanceRemaining = remaining,
      timeRemaining = time,
    )

    // Same transition rule as navd: MANEUVER_TRANSITION_THRESHOLD = 10 m past the point.
    if (distanceToManeuver < -10.0) {
      if (stepIndex < steps.lastIndex) {
        stepIndex++
        offRouteCount = 0
      } else {
        arrived = true
      }
    }
    if (!arrived && Geo.distance(position, destination) < 25.0) {
      arrived = true
    }

    return if (arrived) arrivedInstruction() else instruction
  }

  fun shouldReroute(position: LngLat): Boolean {
    if (arrived || route.steps.isEmpty()) return false
    val step = route.steps[stepIndex.coerceIn(0, route.steps.lastIndex)]
    if (step.geometry.size < 2) return false
    val lateral = Geo.alongPath(step.geometry, position).lateral
    offRouteCount = if (lateral > 25.0) offRouteCount + 1 else 0
    return offRouteCount > 3
  }

  private fun arrivedInstruction() = NavInstruction(
    distanceToManeuver = 0.0,
    primaryText = "已到达目的地",
    secondaryText = "",
    type = "arrive",
    modifier = "",
    distanceRemaining = 0.0,
    timeRemaining = 0.0,
    arrived = true,
  )

  val remainingDistance: Double get() = lastRemaining
  val remainingTime: Double get() = lastDuration
}
