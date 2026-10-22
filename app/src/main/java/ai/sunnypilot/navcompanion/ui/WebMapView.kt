package ai.sunnypilot.navcompanion.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import ai.sunnypilot.navcompanion.data.LngLat
import ai.sunnypilot.navcompanion.nav.ChinaGeo

private class WebMapHolder {
  var webView: WebView? = null
  var loadedKey = ""
  var ready = false
  var payload = "{}"
  var routeKey = ""
  var routeJson = "[]"
  var fittedRouteKey = ""
}

/**
 * Web-based AMap/Baidu basemap.
 *
 * The native SDKs for both providers need keys bound to the app signature and, for Baidu, an AAR
 * that cannot be fetched offline, so the basemaps are rendered by the providers' JavaScript APIs
 * in a WebView. Coordinates are converted from WGS-84 to the provider datum before every push.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun WebMapView(
  provider: String,
  amapJsKey: String,
  amapSecurity: String,
  baiduMapAk: String,
  route: List<LngLat>,
  destination: LngLat?,
  position: LngLat?,
  bearing: Double,
  navigating: Boolean,
  follow: Boolean,
  onUserGesture: () -> Unit,
  onError: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val holder = remember { WebMapHolder() }
  val gesture = rememberUpdatedState(onUserGesture)
  val errorSink = rememberUpdatedState(onError)

  AndroidView(
    modifier = modifier,
    factory = { context ->
      WebView(context).apply {
        holder.webView = this
        setBackgroundColor(Color.rgb(11, 20, 26))
        settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true
          mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
          loadWithOverviewMode = true
          useWideViewPort = true
          setSupportZoom(false)
          builtInZoomControls = false
          cacheMode = WebSettings.LOAD_DEFAULT
        }
        addJavascriptInterface(
          MapJsBridge(holder, onGesture = { gesture.value() }, onError = { errorSink.value(it) }),
          "NavBridge",
        )
        webViewClient = WebViewClient()
      }
    },
    update = { view ->
      val key = "$provider|$amapJsKey|$amapSecurity|$baiduMapAk"
      if (key != holder.loadedKey) {
        holder.loadedKey = key
        holder.ready = false
        holder.routeKey = ""
        holder.fittedRouteKey = ""
        val html = if (provider == "amap") amapHtml(amapJsKey, amapSecurity) else baiduHtml(baiduMapAk)
        val base = if (provider == "amap") "https://webapi.amap.com/" else "https://api.map.baidu.com/"
        view.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
      }

      val routeKey = "$provider|" + route.joinToString(";") { "${it.lon},${it.lat}" }
      if (routeKey != holder.routeKey) {
        holder.routeKey = routeKey
        holder.routeJson = routeJson(route, provider)
      }
      val shouldFit = !navigating && route.size >= 2 && holder.fittedRouteKey != routeKey
      if (shouldFit) holder.fittedRouteKey = routeKey
      holder.payload = buildString {
        append("{\"route\":").append(holder.routeJson)
        append(",\"dest\":").append(destination?.let { coordJson(it, provider) } ?: "null")
        append(",\"pos\":").append(position?.let { coordJson(it, provider) } ?: "null")
        append(",\"bearing\":").append(if (bearing.isFinite()) bearing else 0.0)
        append(",\"nav\":").append(navigating)
        append(",\"follow\":").append(follow)
        append(",\"fit\":").append(shouldFit)
        append('}')
      }
      if (holder.ready) {
        view.evaluateJavascript("window.mapUpdate && window.mapUpdate(${holder.payload})", null)
      }
    },
  )

  DisposableEffect(Unit) {
    onDispose {
      holder.webView?.apply {
        removeJavascriptInterface("NavBridge")
        loadUrl("about:blank")
        destroy()
      }
      holder.webView = null
    }
  }
}

private class MapJsBridge(
  private val holder: WebMapHolder,
  private val onGesture: () -> Unit,
  private val onError: (String) -> Unit,
) {
  private val main = Handler(Looper.getMainLooper())

  @JavascriptInterface
  fun onMapReady() {
    main.post {
      holder.ready = true
      holder.webView?.evaluateJavascript("window.mapUpdate && window.mapUpdate(${holder.payload})", null)
    }
  }

  @JavascriptInterface
  fun onMapError(message: String) {
    main.post { onError(message.ifBlank { "地图初始化失败" }) }
  }

  @JavascriptInterface
  fun onUserGesture() {
    main.post { onGesture() }
  }
}

private fun coordJson(point: LngLat, provider: String): String {
  val converted = ChinaGeo.toProvider(point, provider)
  return "[${converted.lon},${converted.lat}]"
}

private fun routeJson(route: List<LngLat>, provider: String): String =
  route.joinToString(prefix = "[", postfix = "]") { coordJson(it, provider) }

private fun escapeJs(value: String): String =
  value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")

private fun amapHtml(key: String, security: String): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<style>
  html, body, #map { height: 100%; width: 100%; margin: 0; padding: 0; background: #0b141a; }
  .ego { width: 26px; height: 26px; position: relative; }
  .ego:before {
    content: ""; position: absolute; left: 5px; top: 0;
    border-left: 8px solid transparent; border-right: 8px solid transparent;
    border-bottom: 20px solid #38e08b;
  }
  .dest { width: 22px; height: 22px; border-radius: 50%; background: #ff5c5c; border: 3px solid #fff; box-sizing: border-box; }
</style>
<script>
  window._AMapSecurityConfig = { securityJsCode: '${escapeJs(security)}' };
</script>
<script src="https://webapi.amap.com/maps?v=2.0&key=${escapeJs(key)}"></script>
</head>
<body>
<div id="map"></div>
<script>
var map = null, routeLine = null, egoMarker = null, destMarker = null, centered = false;
function bridge() { return window.NavBridge || null; }
function fail(msg) { var b = bridge(); if (b) b.onMapError(String(msg)); }
function initMap() {
  if (!window.AMap) { fail('高德 JS API 未加载，请检查 JS API Key 与安全密钥'); return; }
  try {
    map = new AMap.Map('map', {
      zoom: 16,
      center: [116.397428, 39.90923],
      viewMode: '3D',
      pitch: 45,
      mapStyle: 'amap://styles/dark',
      resizeEnable: true
    });
    map.on('dragstart', function () { var b = bridge(); if (b) b.onUserGesture(); });
    routeLine = new AMap.Polyline({
      path: [], strokeColor: '#38E08B', strokeWeight: 7,
      lineJoin: 'round', lineCap: 'round', showDir: true, zIndex: 50
    });
    map.add(routeLine);
    egoMarker = new AMap.Marker({
      position: [116.397428, 39.90923], content: '<div class="ego"></div>',
      anchor: 'center', zIndex: 100, visible: false
    });
    map.add(egoMarker);
    destMarker = new AMap.Marker({
      position: [116.397428, 39.90923], content: '<div class="dest"></div>',
      anchor: 'center', zIndex: 90, visible: false
    });
    map.add(destMarker);
    var b = bridge(); if (b) b.onMapReady();
  } catch (e) {
    fail(e && e.message ? e.message : e);
  }
}
window.mapUpdate = function (data) {
  if (!map || !routeLine) return;
  try {
    var pts = (data.route || []).map(function (p) { return [p[0], p[1]]; });
    routeLine.setPath(pts);
    if (data.dest) {
      destMarker.setPosition([data.dest[0], data.dest[1]]);
      destMarker.show();
    } else {
      destMarker.hide();
    }
    if (data.pos) {
      egoMarker.setPosition([data.pos[0], data.pos[1]]);
      egoMarker.show();
      if (egoMarker.setAngle) egoMarker.setAngle(data.bearing || 0);
    } else {
      egoMarker.hide();
    }
    if (data.nav && data.follow && data.pos) {
      if (map.setPitch) map.setPitch(45);
      if (map.setRotation) map.setRotation(data.bearing || 0);
      map.setZoomAndCenter(17, [data.pos[0], data.pos[1]], false, 350);
      centered = true;
    } else if (data.fit && pts.length > 1) {
      map.setFitView(routeLine, false, [90, 60, 260, 60]);
      centered = true;
    } else if (!centered && data.pos) {
      map.setZoomAndCenter(16, [data.pos[0], data.pos[1]], false, 300);
      centered = true;
    }
  } catch (e) { }
};
initMap();
</script>
</body>
</html>
"""

private fun baiduHtml(ak: String): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<style>
  html, body, #map { height: 100%; width: 100%; margin: 0; padding: 0; background: #0b141a; }
</style>
<script src="https://api.map.baidu.com/api?v=3.0&type=webgl&ak=${escapeJs(ak)}"></script>
</head>
<body>
<div id="map"></div>
<script>
var map = null, routeLine = null, egoMarker = null, destMarker = null, centered = false;
function bridge() { return window.NavBridge || null; }
function fail(msg) { var b = bridge(); if (b) b.onMapError(String(msg)); }
function initMap() {
  if (!window.BMapGL) { fail('百度地图 JS API 未加载，请检查浏览器端 AK'); return; }
  try {
    map = new BMapGL.Map('map', { enableMapClick: false });
    map.centerAndZoom(new BMapGL.Point(116.397428, 39.90923), 16);
    map.enableScrollWheelZoom(true);
    map.addEventListener('dragstart', function () { var b = bridge(); if (b) b.onUserGesture(); });
    routeLine = new BMapGL.Polyline([], {
      strokeColor: '#38E08B', strokeWeight: 7, strokeOpacity: 0.95, enableMassClear: false
    });
    map.addOverlay(routeLine);
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="28" height="28" viewBox="0 0 28 28">'
      + '<path d="M14 1 L26 27 L14 21 L2 27 Z" fill="#38E08B" stroke="#ffffff" stroke-width="2"/></svg>';
    var icon = new BMapGL.Icon(
      'data:image/svg+xml;charset=UTF-8,' + encodeURIComponent(svg),
      new BMapGL.Size(28, 28),
      { anchor: new BMapGL.Size(14, 14) }
    );
    egoMarker = new BMapGL.Marker(new BMapGL.Point(116.397428, 39.90923), {
      icon: icon, rotation: 0, enableMassClear: false
    });
    egoMarker.hide();
    map.addOverlay(egoMarker);
    destMarker = new BMapGL.Marker(new BMapGL.Point(116.397428, 39.90923), { enableMassClear: false });
    destMarker.hide();
    map.addOverlay(destMarker);
    var b = bridge(); if (b) b.onMapReady();
  } catch (e) {
    fail(e && e.message ? e.message : e);
  }
}
window.mapUpdate = function (data) {
  if (!map || !routeLine) return;
  try {
    var pts = (data.route || []).map(function (p) { return new BMapGL.Point(p[0], p[1]); });
    routeLine.setPath(pts);
    if (data.dest) {
      destMarker.setPosition(new BMapGL.Point(data.dest[0], data.dest[1]));
      destMarker.show();
    } else {
      destMarker.hide();
    }
    if (data.pos) {
      egoMarker.setPosition(new BMapGL.Point(data.pos[0], data.pos[1]));
      egoMarker.show();
      if (egoMarker.setRotation) egoMarker.setRotation(data.bearing || 0);
    } else {
      egoMarker.hide();
    }
    if (data.nav && data.follow && data.pos) {
      if (map.setHeading) map.setHeading(data.bearing || 0);
      if (map.setTilt) map.setTilt(45);
      map.setZoom(17);
      map.setCenter(new BMapGL.Point(data.pos[0], data.pos[1]));
      centered = true;
    } else if (data.fit && pts.length > 1) {
      map.setViewport(pts, { margins: [90, 60, 260, 60] });
      centered = true;
    } else if (!centered && data.pos) {
      map.setCenter(new BMapGL.Point(data.pos[0], data.pos[1]));
      map.setZoom(16);
      centered = true;
    }
  } catch (e) { }
};
initMap();
</script>
</body>
</html>
"""
