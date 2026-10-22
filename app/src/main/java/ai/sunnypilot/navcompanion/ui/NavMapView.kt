package ai.sunnypilot.navcompanion.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.sunnypilot.navcompanion.data.LngLat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val ROUTE_SOURCE = "route-source"
private const val EGO_SOURCE = "ego-source"
private const val DEST_SOURCE = "dest-source"
private const val EGO_IMAGE = "ego-arrow"

private class MapFlags {
  var lastRouteKey = ""
  var lastEgoKey = ""
  var lastDestKey = ""
  var lastCameraKey = ""
  var fittedRouteKey = ""
}

@Composable
fun NavMapView(
  route: List<LngLat>,
  destination: LngLat?,
  position: LngLat?,
  bearing: Double,
  navigating: Boolean,
  follow: Boolean,
  onUserGesture: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val gesture = rememberUpdatedState(onUserGesture)
  val navState = rememberUpdatedState(navigating)
  val followState = rememberUpdatedState(follow)
  val flags = remember { MapFlags() }

  var mapView by remember { mutableStateOf<MapView?>(null) }
  var map by remember { mutableStateOf<MapLibreMap?>(null) }
  var styleReady by remember { mutableStateOf(false) }

  AndroidView(
    modifier = modifier,
    factory = { context ->
      MapView(context).also { view ->
        view.onCreate(null)
        view.getMapAsync { mapInstance ->
          map = mapInstance
          mapInstance.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
            style.addImage(EGO_IMAGE, egoArrowBitmap())
            style.addSource(GeoJsonSource(ROUTE_SOURCE, emptyCollection()))
            style.addSource(GeoJsonSource(EGO_SOURCE, emptyCollection()))
            style.addSource(GeoJsonSource(DEST_SOURCE, emptyCollection()))
            style.addLayer(
              LineLayer("route-casing", ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor("#0B1F2A"),
                PropertyFactory.lineWidth(9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
              )
            )
            style.addLayer(
              LineLayer("route-line", ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor("#38E08B"),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
              )
            )
            style.addLayer(
              CircleLayer("dest-circle", DEST_SOURCE).withProperties(
                PropertyFactory.circleColor("#FF5C5C"),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
              )
            )
            style.addLayer(
              SymbolLayer("ego-layer", EGO_SOURCE).withProperties(
                PropertyFactory.iconImage(EGO_IMAGE),
                PropertyFactory.iconSize(0.5f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
              )
            )
            styleReady = true
          }
          mapInstance.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
              gesture.value()
            }
          }
        }
        mapView = view
      }
    },
    update = { view ->
      val mapInstance = map
      if (mapInstance == null || !styleReady) return@AndroidView

      val routeKey = route.joinToString(";") { "${it.lon},${it.lat}" }
      if (routeKey != flags.lastRouteKey) {
        flags.lastRouteKey = routeKey
        val source = mapInstance.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
        if (route.size >= 2) {
          source?.setGeoJson(lineCollection(route))
          if (!navState.value && flags.fittedRouteKey != routeKey) {
            flags.fittedRouteKey = routeKey
            val bounds = LatLngBounds.Builder().apply {
              route.forEach { include(LatLng(it.lat, it.lon)) }
            }.build()
            mapInstance.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 140))
          }
        } else {
          source?.setGeoJson(emptyCollection())
        }
      }

      if (position != null) {
        val egoKey = "${position.lat},${position.lon}"
        if (egoKey != flags.lastEgoKey) {
          flags.lastEgoKey = egoKey
          mapInstance.style?.getSourceAs<GeoJsonSource>(EGO_SOURCE)?.setGeoJson(pointCollection(position))
        }
        if (navState.value && followState.value) {
          val cameraKey = "$egoKey,${bearing.toInt()}"
          if (cameraKey != flags.lastCameraKey) {
            flags.lastCameraKey = cameraKey
            mapInstance.animateCamera(
              CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                  .target(LatLng(position.lat, position.lon))
                  .zoom(16.5)
                  .bearing(bearing)
                  .tilt(45.0)
                  .build()
              ),
              400,
            )
          }
        }
      }

      val destKey = destination?.let { "${it.lat},${it.lon}" } ?: ""
      if (destKey != flags.lastDestKey) {
        flags.lastDestKey = destKey
        val source = mapInstance.style?.getSourceAs<GeoJsonSource>(DEST_SOURCE)
        if (destination != null) {
          source?.setGeoJson(pointCollection(destination))
        } else {
          source?.setGeoJson(emptyCollection())
        }
      }
    },
  )

  DisposableEffect(lifecycleOwner, mapView) {
    val view = mapView
    if (view == null) {
      onDispose { }
    } else {
      val observer = LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_START -> view.onStart()
          Lifecycle.Event.ON_RESUME -> view.onResume()
          Lifecycle.Event.ON_PAUSE -> view.onPause()
          Lifecycle.Event.ON_STOP -> view.onStop()
          else -> Unit
        }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      when {
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> {
          view.onStart()
          view.onResume()
        }
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> view.onStart()
      }
      onDispose {
        lifecycleOwner.lifecycle.removeObserver(observer)
        view.onDestroy()
      }
    }
  }
}

private fun emptyCollection(): FeatureCollection =
  FeatureCollection.fromJson("""{"type":"FeatureCollection","features":[]}""")

private fun lineCollection(route: List<LngLat>): FeatureCollection {
  val points = route.map { Point.fromLngLat(it.lon, it.lat) }
  return FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points)))
}

private fun pointCollection(point: LngLat): FeatureCollection =
  FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)))

private fun egoArrowBitmap(size: Int = 72): Bitmap {
  val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#38E08B")
    style = Paint.Style.FILL
  }
  val outline = Paint(fill).apply {
    color = Color.WHITE
    style = Paint.Style.STROKE
    strokeWidth = size * 0.06f
  }
  val path = Path().apply {
    moveTo(size * 0.5f, size * 0.08f)
    lineTo(size * 0.9f, size * 0.92f)
    lineTo(size * 0.5f, size * 0.72f)
    lineTo(size * 0.1f, size * 0.92f)
    close()
  }
  canvas.drawPath(path, fill)
  canvas.drawPath(path, outline)
  return bitmap
}
