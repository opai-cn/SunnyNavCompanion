package ai.sunnypilot.navcompanion.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import ai.sunnypilot.navcompanion.NavApplication
import ai.sunnypilot.navcompanion.R
import ai.sunnypilot.navcompanion.data.LngLat
import ai.sunnypilot.navcompanion.data.NavInstruction
import ai.sunnypilot.navcompanion.ui.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import kotlin.math.cos

/**
 * Car-side navigation screen for Android Auto / Android Automotive OS.
 *
 * We listen to the singleton NavViewModel and rebuild templates and canvas frames on every snapshot
 * change. The SurfaceCallback hooks are purely for host lifecycle; our own render loop drives
 * canvas frames into the host-provided [Surface].
 */
class NavCarScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val app: NavApplication = carContext.applicationContext as NavApplication
  private val mainHandler = Handler(Looper.getMainLooper())

  private val _snapshot = MutableStateFlow<UiState?>(null)
  private val snapshot: StateFlow<UiState?> = _snapshot.asStateFlow()

  private var surfaceContainer: SurfaceContainer? = null

  init {
    // Register SurfaceCallback through AppManager — this is how the host tells us about the
    // available Surface, not through NavigationTemplate.Builder.
    carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

    scope.launch {
      app.viewModel.state.collect { state ->
        _snapshot.value = state
        invalidate()
      }
    }
  }

  override fun onGetTemplate(): Template {
    val s = snapshot.value ?: return MessageTemplate.Builder("正在连接 SunnyNav…").build()

    if (!s.navigating) {
      return MessageTemplate.Builder("请在手机上选择目的地")
        .addAction(Action.Builder().setTitle("刷新").setOnClickListener { invalidate() }.build())
        .build()
    }

    val instr = s.instruction
      ?: return MessageTemplate.Builder("路线计算中…").build()

    return buildNavigationTemplate(s, instr)
  }

  private fun buildNavigationTemplate(state: UiState, instr: NavInstruction): NavigationTemplate {
    val maneuverType = mapManeuver(instr)

    val currentStep = Step.Builder()
      .setManeuver(
        Maneuver.Builder(maneuverType)
          .setIcon(carManeuverIcon(maneuverType))
          .build()
      )
      .setCue(instr.primaryText)
      .build()

    val currentDistance = Distance.create(instr.distanceToManeuver, Distance.UNIT_METERS)
    val routingInfo = RoutingInfo.Builder()
      .setCurrentStep(currentStep, currentDistance)
      .build()

    val remainingDistance = Distance.create(instr.distanceRemaining, Distance.UNIT_METERS)
    val arrivalTime = ZonedDateTime.now().plusSeconds(instr.timeRemaining.toLong())
    val travelEstimate = TravelEstimate.Builder(remainingDistance, arrivalTime)
      .setRemainingTimeSeconds(instr.timeRemaining.toLong())
      .build()

    val stopAction = Action.Builder()
      .setTitle("停止")
      .setOnClickListener {
        app.viewModel.stopNavigation()
        invalidate()
      }
      .build()

    return NavigationTemplate.Builder()
      .setActionStrip(ActionStrip.Builder().addAction(stopAction).build())
      .setNavigationInfo(routingInfo)
      .setDestinationTravelEstimate(travelEstimate)
      .setBackgroundColor(CarColor.createCustom(0xFF0B141A.toInt(), 0xFF0B141A.toInt()))
      .build()
  }

  private fun carManeuverIcon(type: Int): CarIcon {
    val drawableRes = ManeuverIcon.forType(type)
    val iconCompat = IconCompat.createWithResource(carContext, drawableRes)
    return CarIcon.Builder(iconCompat).build()
  }

  private fun mapManeuver(instr: NavInstruction): Int = when (instr.type) {
    "arrive" -> Maneuver.TYPE_DESTINATION
    "turn" -> when (instr.modifier) {
      "uturn" -> Maneuver.TYPE_U_TURN_LEFT
      "left" -> Maneuver.TYPE_TURN_NORMAL_LEFT
      "slight left" -> Maneuver.TYPE_TURN_SLIGHT_LEFT
      "right" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
      "slight right" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
      else -> Maneuver.TYPE_TURN_NORMAL_LEFT
    }
    "continue" -> when (instr.modifier) {
      "straight" -> Maneuver.TYPE_STRAIGHT
      "roundabout" -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
      else -> Maneuver.TYPE_STRAIGHT
    }
    else -> Maneuver.TYPE_STRAIGHT
  }

  // ────────────────────────────────── SurfaceCallback ──────────────────────────────────

  override fun onSurfaceAvailable(surface: SurfaceContainer) {
    surfaceContainer = surface
    startRenderLoop()
  }

  override fun onSurfaceDestroyed(surface: SurfaceContainer) {
    surfaceContainer = null
    mainHandler.removeCallbacks(renderRunnable)
  }

  private fun startRenderLoop() {
    mainHandler.removeCallbacks(renderRunnable)
    mainHandler.post(renderRunnable)
  }

  private val renderRunnable = object : Runnable {
    override fun run() {
      drawSurface()
      mainHandler.postDelayed(this, 80L) // ~12fps is plenty for car navigation
    }
  }

  private fun drawSurface() {
    val container = surfaceContainer ?: return
    val surface = container.surface ?: return
    if (!surface.isValid) return

    val canvas = surface.lockCanvas(null) ?: return
    try {
      drawCanvas(canvas, container.width, container.height)
    } finally {
      surface.unlockCanvasAndPost(canvas)
    }
  }

  private fun drawCanvas(canvas: Canvas, w: Int, h: Int) {
    val state = app.viewModel.state.value
    val route = state.route?.geometry ?: return
    val position = state.position ?: route.firstOrNull() ?: return
    val destination = state.destination?.point

    val zoom = if (state.navigating) 16.8 else 14.5
    val metersPerPixel = w / (200_000.0 / zoom)
    val bearingDeg = if (state.navigating) state.bearing else 0.0

    val projector = LatLonProjector(position, w, h, metersPerPixel, bearingDeg)

    canvas.drawColor(0xFF0B141A.toInt())

    if (destination != null) {
      val (dx, dy) = projector.project(destination)
      canvas.drawCircle(dx, dy, 16f, destPaint)
      canvas.drawCircle(dx, dy, 16f, egoStrokePaint)
    }

    drawPolyline(canvas, route.toList(), projector, casingPaint, 20f)
    drawPolyline(canvas, route.toList(), projector, routePaint, 12f)

    val (ex, ey) = projector.project(position)
    canvas.save()
    canvas.translate(ex, ey)
    canvas.rotate(bearingDeg.toFloat())
    path.reset()
    path.moveTo(0f, -20f)
    path.lineTo(16f, 16f)
    path.lineTo(0f, 9f)
    path.lineTo(-16f, 16f)
    path.close()
    canvas.drawPath(path, egoPaint)
    canvas.drawPath(path, egoStrokePaint)
    canvas.restore()
  }

  private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    style = Paint.Style.STROKE; color = 0xFF38E08B.toInt()
  }
  private val casingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    style = Paint.Style.STROKE; color = 0xFF0B1F2A.toInt()
  }
  private val destPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xFFFF5C5C.toInt(); style = Paint.Style.FILL
  }
  private val egoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xFF38E08B.toInt(); style = Paint.Style.FILL
  }
  private val egoStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE
  }
  private val path = Path()

  private fun drawPolyline(
    canvas: Canvas,
    route: List<LngLat>,
    projector: LatLonProjector,
    paint: Paint,
    width: Float,
  ) {
    if (route.isEmpty()) return
    paint.strokeWidth = width
    path.reset()
    route.forEachIndexed { i, point ->
      val (x, y) = projector.project(point)
      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    canvas.drawPath(path, paint)
  }
}

/** Projects WGS-84 (lng, lat) pairs onto an Android Canvas with bearing rotation. */
class LatLonProjector(
  private val center: LngLat,
  private val canvasWidth: Int,
  private val canvasHeight: Int,
  private val metersPerPixel: Double,
  private val bearingDeg: Double,
) {
  private val cosLat = cos(Math.toRadians(center.lat))
  private val cosBear = Math.cos(Math.toRadians(bearingDeg))
  private val sinBear = Math.sin(Math.toRadians(bearingDeg))

  fun project(point: LngLat): Pair<Float, Float> {
    val dLat = (point.lat - center.lat) * 111_320.0
    val dLon = (point.lon - center.lon) * 111_320.0 * cosLat
    val nx = dLon * cosBear - dLat * sinBear
    val ny = -dLon * sinBear - dLat * cosBear
    val x = (canvasWidth / 2.0 + nx / metersPerPixel).toFloat()
    val y = (canvasHeight / 2.0 - ny / metersPerPixel).toFloat()
    return x to y
  }
}

/** Maps maneuver type constants to drawable resource IDs we ship in the carauto flavor. */
object ManeuverIcon {
  fun forType(type: Int): Int = when (type) {
    Maneuver.TYPE_DESTINATION,
    Maneuver.TYPE_DESTINATION_STRAIGHT,
    Maneuver.TYPE_DESTINATION_LEFT,
    Maneuver.TYPE_DESTINATION_RIGHT -> R.drawable.ic_maneuver_arrive

    Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_RIGHT -> R.drawable.ic_maneuver_uturn

    Maneuver.TYPE_TURN_NORMAL_LEFT,
    Maneuver.TYPE_TURN_SHARP_LEFT -> R.drawable.ic_maneuver_turn_left

    Maneuver.TYPE_TURN_NORMAL_RIGHT,
    Maneuver.TYPE_TURN_SHARP_RIGHT -> R.drawable.ic_maneuver_turn_right

    Maneuver.TYPE_TURN_SLIGHT_LEFT -> R.drawable.ic_maneuver_turn_slight_left
    Maneuver.TYPE_TURN_SLIGHT_RIGHT -> R.drawable.ic_maneuver_turn_slight_right

    else -> R.drawable.ic_maneuver_straight
  }
}
