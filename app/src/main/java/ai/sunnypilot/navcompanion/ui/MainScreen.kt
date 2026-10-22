package ai.sunnypilot.navcompanion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.sunnypilot.navcompanion.data.NavInstruction
import ai.sunnypilot.navcompanion.data.Place
import ai.sunnypilot.navcompanion.nav.ChinaGeo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun MainScreen(viewModel: NavViewModel) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbarHost = remember { SnackbarHostState() }
  val view = LocalView.current
  var showSettings by remember { mutableStateOf(false) }

  SideEffect { view.keepScreenOn = state.navigating }

  LaunchedEffect(state.message) {
    val message = state.message
    if (message != null) {
      snackbarHost.showSnackbar(message)
      viewModel.consumeMessage()
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    viewModel.setLocationPermission(granted)
  }

  LaunchedEffect(Unit) {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    if (granted) {
      viewModel.setLocationPermission(true)
    } else {
      permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
  }

  Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // DIAGNOSTIC: temporarily disabled NavMapView/WebMapView to isolate crash root cause.
    // Replace with the original block once we have the logcat stacktrace.
    Box(
      Modifier
        .fillMaxSize()
        .background(Color(0xFF0B141A))
    )

    Column(
      modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(horizontal = 12.dp),
    ) {
      Spacer(Modifier.height(8.dp))
      if (state.navigating) {
        NavBanner(state.instruction)
      } else {
        SearchBar(
          query = state.query,
          onQueryChange = viewModel::setQuery,
          onSettings = { showSettings = true },
          deviceOnline = state.deviceOnline,
          bridgeConfigured = state.bridgeUrl.isNotBlank(),
        )
        if (state.query.isNotBlank() && (state.places.isNotEmpty() || state.searching)) {
          SearchResults(state.places, state.searching, onSelect = viewModel::selectPlace)
        }
      }

      Spacer(Modifier.weight(1f))

      when {
        state.navigating -> NavigationBottomBar(state, onStop = viewModel::stopNavigation)
        state.destination != null && state.route != null -> RouteBottomBar(
          state = state,
          onStart = viewModel::startNavigation,
          onSend = { viewModel.sendDestination() },
          onToggleSync = { viewModel.setSendToDevice(!state.sendToDevice) },
          onClear = viewModel::clearRoute,
          onExternal = { target ->
            val place = state.destination
            if (place != null && !openExternalNavigation(context, target, place)) {
              viewModel.notify("未安装${if (target == "amap") "高德地图" else "百度地图"}，也没有可用的浏览器")
            }
          },
        )
        state.destination != null || state.planning -> PlanningCard()
        else -> HintCard(state)
      }
      Spacer(Modifier.height(12.dp))
    }

    if (state.navigating && !state.follow) {
      FloatingActionButton(
        onClick = { viewModel.setFollow(true) },
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .navigationBarsPadding()
          .padding(end = 16.dp, bottom = 148.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
      ) {
        Icon(Icons.Filled.MyLocation, contentDescription = "回到当前位置")
      }
    }

    SnackbarHost(
      hostState = snackbarHost,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(bottom = 120.dp),
    )

    if (showSettings) {
      SettingsDialog(
        initial = NavSettings(
          bridgeUrl = state.bridgeUrl,
          routeProvider = state.provider,
          mapProvider = state.mapProvider,
          amapKey = state.amapKey,
          amapJsKey = state.amapJsKey,
          amapSecurity = state.amapSecurity,
          baiduAk = state.baiduAk,
          baiduMapAk = state.baiduMapAk,
        ),
        onTest = viewModel::testBridge,
        onSave = { settings ->
          viewModel.applySettings(settings)
          showSettings = false
        },
        onDismiss = { showSettings = false },
      )
    }
  }
}

@Composable
private fun SearchBar(
  query: String,
  onQueryChange: (String) -> Unit,
  onSettings: () -> Unit,
  deviceOnline: Boolean,
  bridgeConfigured: Boolean,
) {
  val focusManager = LocalFocusManager.current
  Surface(
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 6.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        decorationBox = { inner ->
          Box {
            if (query.isEmpty()) {
              Text(
                "搜索目的地",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            inner()
          }
        },
      )
      if (query.isNotEmpty()) {
        IconButton(onClick = { onQueryChange("") }) {
          Icon(Icons.Filled.Close, contentDescription = "清除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      if (bridgeConfigured) {
        Box(
          Modifier
            .padding(end = 4.dp)
            .size(9.dp)
            .clip(CircleShape)
            .background(if (deviceOnline) MaterialTheme.colorScheme.primary else Color(0xFF546E7A))
        )
      }
      IconButton(onClick = onSettings) {
        Icon(Icons.Filled.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun SearchResults(places: List<Place>, searching: Boolean, onSelect: (Place) -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 360.dp),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    if (searching && places.isEmpty()) {
      Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text("正在搜索…", style = MaterialTheme.typography.bodyMedium)
      }
    } else {
      LazyColumn {
        items(places) { place ->
          PlaceRow(place, onSelect)
          HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
      }
    }
  }
}

@Composable
private fun PlaceRow(place: Place, onSelect: (Place) -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clickable { onSelect(place) }
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (place.address.isNotBlank()) {
      Text(
        place.address,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun NavBanner(instruction: NavInstruction?) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xF00B2A20)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
  ) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
      if (instruction == null) {
        Column {
          Text("正在定位…", style = MaterialTheme.typography.titleLarge)
          Text("等待 GPS 或车机定位数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        ManeuverIcon(instruction.type, instruction.modifier, Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
          Text(
            formatDistance(instruction.distanceToManeuver),
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
          )
          Text(
            instruction.primaryText,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
          Text(formatDistance(instruction.distanceRemaining), style = MaterialTheme.typography.labelLarge)
          Text(
            formatDuration(instruction.timeRemaining),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun NavigationBottomBar(state: UiState, onStop: () -> Unit) {
  val instruction = state.instruction
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          state.destination?.name ?: "导航中",
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          "剩余 ${formatDistance(instruction?.distanceRemaining ?: 0.0)} · ${formatDuration(instruction?.timeRemaining ?: 0.0)}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (instruction != null && instruction.timeRemaining > 0) {
          Text(
            "预计 ${arrivalClock(instruction.timeRemaining)} 到达",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        val device = state.deviceInstruction
        when {
          !state.sendToDevice || state.bridgeUrl.isBlank() -> Text(
            "纯手机导航 · 未同步车机",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          !state.deviceOnline -> Text(
            "车机离线 · 本机导航继续",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
          )
          device != null && device.valid -> Text(
            "车机同步：剩余 ${formatDistance(device.distanceRemaining)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
          )
        }
      }
      TextButton(onClick = onStop) { Text("结束") }
    }
  }
}

@Composable
private fun RouteBottomBar(
  state: UiState,
  onStart: () -> Unit,
  onSend: () -> Unit,
  onToggleSync: () -> Unit,
  onClear: () -> Unit,
  onExternal: (String) -> Unit,
) {
  val route = state.route ?: return
  val destination = state.destination ?: return
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
          Text(
            destination.name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (destination.address.isNotBlank()) {
            Text(
              destination.address,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        IconButton(onClick = onClear) {
          Icon(Icons.Filled.Close, contentDescription = "清除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "${formatDistance(route.distance)} · ${formatDuration(route.duration)}",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        Text(route.provider, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Spacer(Modifier.height(12.dp))
      if (state.planning) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(12.dp))
          Text("正在规划路线…", style = MaterialTheme.typography.bodyMedium)
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Button(
            onClick = onStart,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
          ) {
            Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("开始导航")
          }
          OutlinedButton(
            onClick = onSend,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
          ) {
            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("发送到车机")
          }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedButton(
            onClick = { onExternal("amap") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
          ) {
            Text("用高德导航")
          }
          OutlinedButton(
            onClick = { onExternal("baidu") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
          ) {
            Text("用百度导航")
          }
        }
        if (state.bridgeUrl.isBlank()) {
          Spacer(Modifier.height(8.dp))
          Text(
            "未配置车机桥接，导航只在本机运行",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Spacer(Modifier.height(6.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("同步到车机", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Switch(checked = state.sendToDevice, onCheckedChange = { onToggleSync() })
          }
        }
      }
    }
  }
}

@Composable
private fun PlanningCard() {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
      Spacer(Modifier.width(12.dp))
      Text("正在规划路线…", style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun HintCard(state: UiState) {
  val mapLabel = when {
    state.mapProvider == "amap" && state.amapJsKey.isNotBlank() -> "地图 © 高德"
    state.mapProvider == "baidu" && state.baiduMapAk.isNotBlank() -> "地图 © 百度"
    else -> "地图 © OpenStreetMap"
  }
  val bridgeLabel = when {
    state.bridgeUrl.isBlank() -> "车机桥接未配置"
    state.deviceOnline -> "车机已连接"
    else -> "车机离线"
  }
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
  ) {
    Column(Modifier.padding(14.dp)) {
      Text("搜索目的地开始导航", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(4.dp))
      Text(
        "$bridgeLabel · $mapLabel",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SettingsDialog(
  initial: NavSettings,
  onTest: (String) -> Unit,
  onSave: (NavSettings) -> Unit,
  onDismiss: () -> Unit,
) {
  var settings by remember(initial) { mutableStateOf(initial) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("设置") },
    text = {
      Column(
        Modifier
          .heightIn(max = 460.dp)
          .verticalScroll(rememberScrollState()),
      ) {
        Text("车机桥接地址", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = settings.bridgeUrl,
          onValueChange = { settings = settings.copy(bridgeUrl = it) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          placeholder = { Text("192.168.1.50:8765") },
        )
        Spacer(Modifier.height(16.dp))
        Text("底图", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ProviderChip("MapLibre", settings.mapProvider == "maplibre") {
            settings = settings.copy(mapProvider = "maplibre")
          }
          ProviderChip("高德", settings.mapProvider == "amap") {
            settings = settings.copy(mapProvider = "amap")
          }
          ProviderChip("百度", settings.mapProvider == "baidu") {
            settings = settings.copy(mapProvider = "baidu")
          }
        }
        Spacer(Modifier.height(16.dp))
        Text("路线与搜索服务", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ProviderChip("自动", settings.routeProvider == "auto") {
            settings = settings.copy(routeProvider = "auto")
          }
          ProviderChip("OSRM", settings.routeProvider == "osrm") {
            settings = settings.copy(routeProvider = "osrm")
          }
          ProviderChip("BRouter", settings.routeProvider == "brouter") {
            settings = settings.copy(routeProvider = "brouter")
          }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ProviderChip("高德", settings.routeProvider == "amap") {
            settings = settings.copy(routeProvider = "amap")
          }
          ProviderChip("百度", settings.routeProvider == "baidu") {
            settings = settings.copy(routeProvider = "baidu")
          }
        }
        Spacer(Modifier.height(16.dp))
        Text("高德 Key", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = settings.amapKey,
          onValueChange = { settings = settings.copy(amapKey = it) },
          singleLine = true,
          label = { Text("Web 服务 Key（搜索/路线）") },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = settings.amapJsKey,
          onValueChange = { settings = settings.copy(amapJsKey = it) },
          singleLine = true,
          label = { Text("JS API Key（底图）") },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = settings.amapSecurity,
          onValueChange = { settings = settings.copy(amapSecurity = it) },
          singleLine = true,
          label = { Text("安全密钥 securityJsCode") },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text("百度 Key", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = settings.baiduAk,
          onValueChange = { settings = settings.copy(baiduAk = it) },
          singleLine = true,
          label = { Text("服务端 AK（搜索/路线）") },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = settings.baiduMapAk,
          onValueChange = { settings = settings.copy(baiduMapAk = it) },
          singleLine = true,
          label = { Text("浏览器端 AK（底图）") },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(
          "高德与百度需要各自申请 Key，并保持全网可用（域名白名单留空）。" +
            "未填写 Key 时底图回退 MapLibre，路线回退 OSRM/BRouter。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          "车机桥接：先在车机上运行 bridge/nav_bridge.py，手机与车机需在同一 Wi-Fi。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(settings) }) { Text("保存") }
    },
    dismissButton = {
      Row {
        TextButton(onClick = { onTest(settings.bridgeUrl) }) { Text("测试连接") }
        TextButton(onClick = onDismiss) { Text("取消") }
      }
    },
  )
}

@Composable
private fun ProviderChip(label: String, selected: Boolean, onClick: () -> Unit) {
  FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ManeuverIcon(type: String, modifier: String, iconModifier: Modifier) {
  val color = MaterialTheme.colorScheme.primary
  Canvas(iconModifier) {
    val w = size.width
    val h = size.height
    val strokeWidth = 5.dp.toPx()
    val turn = modifier.lowercase()

    if (type == "arrive") {
      drawCircle(color, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(strokeWidth))
      drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.5f, h * 0.5f))
      return@Canvas
    }

    val path = Path()
    when {
      turn.contains("uturn") -> {
        path.moveTo(w * 0.7f, h * 0.9f)
        path.lineTo(w * 0.7f, h * 0.4f)
        path.quadraticBezierTo(w * 0.7f, h * 0.15f, w * 0.45f, h * 0.15f)
        path.quadraticBezierTo(w * 0.2f, h * 0.15f, w * 0.2f, h * 0.4f)
        path.lineTo(w * 0.2f, h * 0.55f)
      }
      turn.contains("left") -> {
        path.moveTo(w * 0.55f, h * 0.92f)
        path.lineTo(w * 0.55f, h * 0.5f)
        path.lineTo(w * 0.18f, h * 0.5f)
      }
      turn.contains("right") -> {
        path.moveTo(w * 0.45f, h * 0.92f)
        path.lineTo(w * 0.45f, h * 0.5f)
        path.lineTo(w * 0.82f, h * 0.5f)
      }
      else -> {
        path.moveTo(w * 0.5f, h * 0.92f)
        path.lineTo(w * 0.5f, h * 0.18f)
      }
    }
    drawPath(
      path,
      color = color,
      style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    when {
      turn.contains("left") -> {
        drawLine(color, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.34f, h * 0.34f), strokeWidth, StrokeCap.Round)
        drawLine(color, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.34f, h * 0.66f), strokeWidth, StrokeCap.Round)
      }
      turn.contains("right") -> {
        drawLine(color, Offset(w * 0.82f, h * 0.5f), Offset(w * 0.66f, h * 0.34f), strokeWidth, StrokeCap.Round)
        drawLine(color, Offset(w * 0.82f, h * 0.5f), Offset(w * 0.66f, h * 0.66f), strokeWidth, StrokeCap.Round)
      }
      turn.contains("uturn") -> {
        drawLine(color, Offset(w * 0.2f, h * 0.55f), Offset(w * 0.08f, h * 0.42f), strokeWidth, StrokeCap.Round)
        drawLine(color, Offset(w * 0.2f, h * 0.55f), Offset(w * 0.32f, h * 0.42f), strokeWidth, StrokeCap.Round)
      }
      else -> {
        drawLine(color, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.33f, h * 0.36f), strokeWidth, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.67f, h * 0.36f), strokeWidth, StrokeCap.Round)
      }
    }
  }
}

/**
 * Hands the destination to the installed AMap or Baidu app. Both schemes take GCJ-02, so the
 * WGS-84 point is converted first; if neither the app nor a browser is available, returns false.
 */
private fun openExternalNavigation(context: Context, target: String, place: Place): Boolean {
  val point = ChinaGeo.wgs84ToGcj02(place.point)
  val name = Uri.encode(place.name)
  val lat = point.lat
  val lon = point.lon
  val appUri = when (target) {
    "amap" -> "amapuri://route/plan/?dlat=$lat&dlon=$lon&dname=$name&dev=0&t=0"
    else -> "baidumap://map/direction?destination=latlng:$lat,$lon|name:$name&coord_type=gcj02&mode=driving"
  }
  val webUrl = when (target) {
    "amap" -> "https://uri.amap.com/navigation?to=$lon,$lat,$name&mode=car&coordinate=gaode&callnative=0"
    else -> "https://api.map.baidu.com/direction?destination=latlng:$lat,$lon|name:$name" +
      "&mode=driving&coord_type=gcj02&output=html&src=webapp.sunnynav"
  }
  return runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(appUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }.recoverCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }.isSuccess
}

private fun formatDistance(meters: Double): String {
  val value = meters.coerceAtLeast(0.0)
  return if (value < 1000.0) {
    val rounded = (value / 10.0).roundToInt() * 10
    if (rounded < 10) "现在" else "$rounded 米"
  } else {
    String.format(Locale.getDefault(), "%.1f 公里", value / 1000.0)
  }
}

private fun formatDuration(seconds: Double): String {
  val value = seconds.coerceAtLeast(0.0)
  return if (value < 3600.0) {
    "${max(1, (value / 60.0).roundToInt())} 分钟"
  } else {
    val hours = (value / 3600.0).toInt()
    val minutes = ((value % 3600.0) / 60.0).roundToInt()
    "$hours 小时 $minutes 分"
  }
}

private fun arrivalClock(seconds: Double): String {
  val time = System.currentTimeMillis() + (seconds * 1000.0).toLong()
  return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
}
