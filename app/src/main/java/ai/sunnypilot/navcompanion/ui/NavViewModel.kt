package ai.sunnypilot.navcompanion.ui

import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.sunnypilot.navcompanion.data.BridgeClient
import ai.sunnypilot.navcompanion.data.DeviceInstruction
import ai.sunnypilot.navcompanion.data.LngLat
import ai.sunnypilot.navcompanion.data.NavInstruction
import ai.sunnypilot.navcompanion.data.NavRoute
import ai.sunnypilot.navcompanion.data.Place
import ai.sunnypilot.navcompanion.data.PlaceSearchClient
import ai.sunnypilot.navcompanion.data.RouteClient
import ai.sunnypilot.navcompanion.nav.NavSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UiState(
  val query: String = "",
  val places: List<Place> = emptyList(),
  val searching: Boolean = false,
  val planning: Boolean = false,
  val route: NavRoute? = null,
  val destination: Place? = null,
  val navigating: Boolean = false,
  val arrived: Boolean = false,
  val instruction: NavInstruction? = null,
  val position: LngLat? = null,
  val bearing: Double = 0.0,
  val follow: Boolean = true,
  val permissionGranted: Boolean = false,
  val bridgeUrl: String = "",
  val sendToDevice: Boolean = true,
  val deviceOnline: Boolean = false,
  val deviceInstruction: DeviceInstruction? = null,
  val provider: String = "auto",
  val mapProvider: String = "maplibre",
  val amapKey: String = "",
  val amapJsKey: String = "",
  val amapSecurity: String = "",
  val baiduAk: String = "",
  val baiduMapAk: String = "",
  val message: String? = null,
)

/** Editable configuration returned by the settings dialog. */
data class NavSettings(
  val bridgeUrl: String = "",
  val routeProvider: String = "auto",
  val mapProvider: String = "maplibre",
  val amapKey: String = "",
  val amapJsKey: String = "",
  val amapSecurity: String = "",
  val baiduAk: String = "",
  val baiduMapAk: String = "",
)

class NavViewModel(
  application: Application,
  /** When true, defer the bridge polling loop until [startPolling] is called explicitly. */
  private val skipPolling: Boolean = false,
) : AndroidViewModel(application) {
  private val prefs = application.getSharedPreferences("sunnynav", Context.MODE_PRIVATE)
  private val searchClient = PlaceSearchClient()
  private val routeClient = RouteClient()
  private val bridgeClient = BridgeClient()

  private val _state = MutableStateFlow(
    UiState(
      bridgeUrl = prefs.getString(KEY_BRIDGE_URL, "") ?: "",
      sendToDevice = prefs.getBoolean(KEY_SEND_TO_DEVICE, true),
      provider = prefs.getString(KEY_PROVIDER, "auto") ?: "auto",
      mapProvider = prefs.getString(KEY_MAP_PROVIDER, "maplibre") ?: "maplibre",
      amapKey = prefs.getString(KEY_AMAP_KEY, "") ?: "",
      amapJsKey = prefs.getString(KEY_AMAP_JS_KEY, "") ?: "",
      amapSecurity = prefs.getString(KEY_AMAP_SECURITY, "") ?: "",
      baiduAk = prefs.getString(KEY_BAIDU_AK, "") ?: "",
      baiduMapAk = prefs.getString(KEY_BAIDU_MAP_AK, "") ?: "",
    )
  )
  val state: StateFlow<UiState> = _state.asStateFlow()

  private var session: NavSession? = null
  private var searchJob: Job? = null
  private var pollJob: Job? = null
  private var locationManager: LocationManager? = null
  private var locationStarted = false
  private var lastDeviceFixAt = 0L
  private var lastRerouteAt = 0L

  private val locationListener = LocationListener { location -> handlePhoneFix(location) }

  init {
    if (!skipPolling) startPolling()
  }

  fun setLocationPermission(granted: Boolean) {
    _state.update { it.copy(permissionGranted = granted) }
    if (granted) startLocation()
  }

  private fun startLocation() {
    if (locationStarted) return
    val manager = getApplication<Application>().getSystemService(LocationManager::class.java) ?: return
    locationManager = manager
    try {
      listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
        if (manager.isProviderEnabled(provider)) {
          manager.requestLocationUpdates(provider, 1000L, 0f, locationListener, Looper.getMainLooper())
        }
      }
      manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { handlePhoneFix(it) }
      manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { handlePhoneFix(it) }
      locationStarted = true
    } catch (_: SecurityException) {
      postMessage("定位权限不可用")
    }
  }

  fun setQuery(query: String) {
    _state.update { it.copy(query = query) }
    searchJob?.cancel()
    val trimmed = query.trim()
    if (trimmed.length < 2) {
      _state.update { it.copy(places = emptyList(), searching = false) }
      return
    }
    searchJob = viewModelScope.launch {
      delay(400)
      _state.update { it.copy(searching = true) }
      val current = _state.value
      val places = withContext(Dispatchers.IO) {
        runCatching {
          searchClient.search(
            query = trimmed,
            near = current.position,
            provider = current.provider,
            amapKey = current.amapKey,
            baiduAk = current.baiduAk,
          )
        }.getOrDefault(emptyList())
      }
      _state.update { it.copy(searching = false, places = places) }
    }
  }

  fun selectPlace(place: Place) {
    _state.update {
      it.copy(query = place.name, places = emptyList(), destination = place, route = null, instruction = null)
    }
    val from = _state.value.position
    if (from == null) {
      postMessage("正在等待 GPS 定位，请稍后再试")
      return
    }
    planRoute(from, place)
  }

  private fun planRoute(from: LngLat, place: Place) {
    viewModelScope.launch {
      _state.update { it.copy(planning = true) }
      val current = _state.value
      val attempt = withContext(Dispatchers.IO) {
        runCatching {
          routeClient.route(from, place.point, current.provider, current.amapKey, current.baiduAk)
        }
      }
      attempt.onSuccess { route ->
        session = NavSession(route, place.point)
        _state.update { it.copy(route = route, planning = false, instruction = null, arrived = false) }
      }.onFailure { error ->
        _state.update { it.copy(planning = false) }
        postMessage("路线规划失败：${error.message ?: "网络异常"}")
      }
    }
  }

  fun startNavigation() {
    val current = _state.value
    val route = current.route ?: return
    val destination = current.destination ?: return
    session = NavSession(route, destination.point)
    _state.update { it.copy(navigating = true, follow = true, arrived = false, instruction = null) }
    // Phone-only navigation is the default when the bridge is unset or device sync is off.
    if (current.sendToDevice && current.bridgeUrl.isNotBlank()) {
      sendDestination(navigating = true)
    }
  }

  fun stopNavigation() {
    session = null
    _state.update { it.copy(navigating = false, follow = false, instruction = null) }
  }

  fun clearRoute() {
    session = null
    _state.update {
      it.copy(
        route = null,
        destination = null,
        navigating = false,
        follow = true,
        instruction = null,
        arrived = false,
        query = "",
        places = emptyList(),
      )
    }
  }

  fun sendDestination(navigating: Boolean = false) {
    val current = _state.value
    val destination = current.destination ?: return
    if (current.bridgeUrl.isBlank()) {
      postMessage("未配置车机桥接，已切换为纯手机导航")
      return
    }
    viewModelScope.launch {
      val success = withContext(Dispatchers.IO) {
        runCatching { bridgeClient.sendDestination(current.bridgeUrl, destination) }.getOrDefault(false)
      }
      _state.update { it.copy(deviceOnline = it.deviceOnline || success) }
      postMessage(
        when {
          success -> "目的地已下发到车机"
          navigating -> "车机离线，已切换为纯手机导航"
          else -> "下发失败，请检查车机桥接服务与 Wi-Fi"
        }
      )
    }
  }

  fun setSendToDevice(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_SEND_TO_DEVICE, enabled).apply()
    _state.update { it.copy(sendToDevice = enabled) }
    if (enabled) startPolling()
  }

  fun testBridge(url: String) {
    if (url.isBlank()) {
      postMessage("请先填写桥接地址")
      return
    }
    viewModelScope.launch {
      val online = withContext(Dispatchers.IO) {
        runCatching { bridgeClient.health(url) }.getOrDefault(false)
      }
      postMessage(if (online) "桥接服务可访问" else "无法连接桥接服务")
    }
  }

  fun applySettings(settings: NavSettings) {
    val trimmedUrl = settings.bridgeUrl.trim()
    val previousUrl = _state.value.bridgeUrl
    prefs.edit()
      .putString(KEY_BRIDGE_URL, trimmedUrl)
      .putString(KEY_PROVIDER, settings.routeProvider)
      .putString(KEY_MAP_PROVIDER, settings.mapProvider)
      .putString(KEY_AMAP_KEY, settings.amapKey.trim())
      .putString(KEY_AMAP_JS_KEY, settings.amapJsKey.trim())
      .putString(KEY_AMAP_SECURITY, settings.amapSecurity.trim())
      .putString(KEY_BAIDU_AK, settings.baiduAk.trim())
      .putString(KEY_BAIDU_MAP_AK, settings.baiduMapAk.trim())
      .apply()
    _state.update {
      it.copy(
        bridgeUrl = trimmedUrl,
        provider = settings.routeProvider,
        mapProvider = settings.mapProvider,
        amapKey = settings.amapKey.trim(),
        amapJsKey = settings.amapJsKey.trim(),
        amapSecurity = settings.amapSecurity.trim(),
        baiduAk = settings.baiduAk.trim(),
        baiduMapAk = settings.baiduMapAk.trim(),
      )
    }
    if (trimmedUrl != previousUrl) startPolling()
    when {
      settings.mapProvider == "amap" && settings.amapJsKey.isBlank() ->
        postMessage("未填写高德 JS API Key，底图仍使用 MapLibre")
      settings.mapProvider == "baidu" && settings.baiduMapAk.isBlank() ->
        postMessage("未填写百度浏览器端 AK，底图仍使用 MapLibre")
    }
  }

  fun notify(text: String) {
    postMessage(text)
  }

  fun reportMapError(message: String) {
    postMessage("底图加载失败：$message")
  }

  fun setFollow(follow: Boolean) {
    _state.update { it.copy(follow = follow) }
  }

  fun onUserGesture() {
    if (_state.value.navigating) _state.update { it.copy(follow = false) }
  }

  fun consumeMessage() {
    _state.update { it.copy(message = null) }
  }

  private fun startPolling() {
    pollJob?.cancel()
    val url = _state.value.bridgeUrl
    if (url.isBlank()) {
      _state.update { it.copy(deviceOnline = false, deviceInstruction = null) }
      return
    }
    pollJob = viewModelScope.launch {
      while (isActive) {
        val status = withContext(Dispatchers.IO) {
          runCatching { bridgeClient.status(url) }.getOrNull()
        }
        if (status != null) {
          _state.update { it.copy(deviceOnline = true, deviceInstruction = status.instruction) }
          val gps = status.gps
          if (gps != null && gps.ageSeconds < 3.0 && _state.value.navigating) {
            lastDeviceFixAt = SystemClock.elapsedRealtime()
            handleFix(gps.point, gps.bearing)
          }
        } else {
          _state.update { it.copy(deviceOnline = false, deviceInstruction = null) }
        }
        delay(if (_state.value.navigating) 1000L else 3000L)
      }
    }
  }

  private fun handlePhoneFix(location: Location) {
    if (SystemClock.elapsedRealtime() - lastDeviceFixAt < 3000L) return
    val bearing = if (location.hasBearing()) location.bearing.toDouble() else _state.value.bearing
    handleFix(LngLat(location.longitude, location.latitude), bearing)
  }

  private fun handleFix(point: LngLat, bearing: Double) {
    _state.update { it.copy(position = point, bearing = bearing) }
    val current = _state.value
    if (!current.navigating) return
    val active = session ?: return
    val instruction = active.update(point)
    _state.update { it.copy(instruction = instruction, arrived = instruction.arrived) }
    if (instruction.arrived) {
      session = null
      _state.update { it.copy(navigating = false, follow = false, instruction = instruction) }
      postMessage("已到达目的地")
      return
    }
    if (active.shouldReroute(point)) reroute(point)
  }

  private fun reroute(from: LngLat) {
    val current = _state.value
    val destination = current.destination ?: return
    val now = System.currentTimeMillis()
    if (now - lastRerouteAt < 10_000L) return
    lastRerouteAt = now
    viewModelScope.launch {
      val attempt = withContext(Dispatchers.IO) {
        runCatching {
          routeClient.route(from, destination.point, current.provider, current.amapKey, current.baiduAk)
        }
      }
      attempt.onSuccess { route ->
        session = NavSession(route, destination.point)
        _state.update { it.copy(route = route, instruction = null) }
      }
    }
  }

  private fun postMessage(text: String) {
    _state.update { it.copy(message = text) }
  }

  override fun onCleared() {
    pollJob?.cancel()
    searchJob?.cancel()
    try {
      locationManager?.removeUpdates(locationListener)
    } catch (_: SecurityException) {
    }
    super.onCleared()
  }

  companion object {
    private const val KEY_BRIDGE_URL = "bridge_url"
    private const val KEY_SEND_TO_DEVICE = "send_to_device"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_MAP_PROVIDER = "map_provider"
    private const val KEY_AMAP_KEY = "amap_key"
    private const val KEY_AMAP_JS_KEY = "amap_js_key"
    private const val KEY_AMAP_SECURITY = "amap_security"
    private const val KEY_BAIDU_AK = "baidu_ak"
    private const val KEY_BAIDU_MAP_AK = "baidu_map_ak"
  }
}
