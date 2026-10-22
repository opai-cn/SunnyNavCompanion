# SunnyNavCompanion

sunnypilot 导航功能的 Android 伴侣应用：在手机上搜索目的地、规划路线、显示实时转向指引，并把目的地下发到车机，让车机端的 `navd`/`mapsd` 真正跑起来。

sunnypilot 的 master（`openpilot/selfdrive/navigation/`，2026-10-12 合入）只有导航**后端**，车机端 `settings/navigation.py` 是空的，没有输入目的地的入口。本项目补上「目的地输入 + 路线 + 转向显示」这一层。

```
┌────────────── 手机 (Android) ──────────────┐        ┌──────── 车机 (comma) ────────┐
│ 搜索地点 → 规划路线 → 本机转向引擎          │  LAN   │ nav_bridge.py (HTTP 8765)    │
│ MapLibre + OpenFreeMap 地图                 │ ─────► │   → Params.NavDestination    │
│ BridgeClient 轮询 /status 显示车机状态      │ ◄───── │   → Params.NavigationEnabled │
└────────────────────────────────────────────┘        │ navd → navInstruction/navRoute│
                                                       └──────────────────────────────┘
```

## 适用条件

| 项目 | 要求 |
|---|---|
| 车机 | 运行 sunnypilot master（含 `openpilot/selfdrive/navigation/navd.py`）的 comma 3 / 3X / four |
| 车机状态 | navd/mapsd 只在 `started && Params.NavigationEnabled` 时启动，因此导航需在车辆启动后使用 |
| 网络 | 手机与车机在同一 Wi-Fi；手机可访问外网（地点搜索与路线服务） |
| 手机 | Android 8.0（API 26）及以上，需授予定位权限 |
| 构建 | JDK 21、Android SDK 36（AGP 8.13.2 / Gradle 8.13 / Kotlin 2.2.21） |

本 App **不依赖** sunnylink 云端、不需要 dongle 私钥，车机侧只通过局域网 HTTP 通信。

## 功能

- 地点搜索：Photon 优先，Nominatim 兜底（中文结果）
- 路线规划：OSRM 优先，不可达时自动降级到 BRouter（`--profile car-fast`）
- 本机转向引擎：从 sunnypilot `route.py` 移植，含沿路径进度、机动切换（-10 m）、偏航检测（25 m × 3 次）、10 s 节流重算
- 地图：MapLibre 11 + OpenFreeMap 深色样式（免 key），路线/目的地/自车箭头
- 车机同步：下发目的地、轮询车机 GPS 与 `navInstruction`，车机定位更新时优先使用
- 纯手机模式：桥接未配置或车机离线时照常导航，只是不同步

## 构建

```bash
# 需要 local.properties 指向本机 SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或直接用 Android Studio 打开工程同步后运行。

## 车机端部署

把 `bridge/` 目录拷到车机（`scp -r bridge comma@<device-ip>:~/bridge`），然后 SSH 登录执行：

```bash
cd ~/bridge
./install.sh            # systemd 服务，开机自启（推荐）
# 备选：./install.sh hook   在 launch_chffrplus.sh 中插入启动行（openpilot 更新后需重跑）
# 卸载：./install.sh uninstall
```

脚本会：

1. 把 `nav_bridge.py` 安装到 `/data/sunnynav/`（openpilot 更新不会覆盖）
2. 注册 `/etc/systemd/system/sunnynav-bridge.service`，`Restart=always`
3. 启动并调用 `http://127.0.0.1:8765/health` 自检

如果 `/etc` 只读导致注册失败，改用 `./install.sh hook`。

桥接服务自行补齐 `PYTHONPATH`（`/data/openpilot`、`msgq_repo`、`/data/pythonpath`），因此可以在 `launch_chffrplus.sh` 建软链之前启动；openpilot 尚未运行时会每 2 s 重试订阅。

### 桥接接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | `{"ok": true}` |
| GET | `/status` | 车机 GPS、`navInstruction`、`navRoute`、`NavDestination` 快照 |
| POST | `/destination` | `{"latitude": .., "longitude": ..}`，写入 `NavDestination` 并置 `NavigationEnabled=true` |
| POST | `/navigation` | `{"enabled": true|false}` |
| DELETE | `/destination` | 删除 `NavDestination` |

`/status` 中指令超过 5 s、路线超过 30 s 视为过期。**接口无鉴权**，只应在可信局域网运行。

## 使用流程

1. 车机启动 openpilot，桥接服务运行（`systemctl status sunnynav-bridge`）
2. 手机安装 APK，连接同一 Wi-Fi
3. 打开 App，右上角「设置」填写车机地址，如 `192.168.1.50:8765`，点「测试连接」
4. 搜索目的地 → 选中结果自动规划路线
5. 路线卡可选：「同步到车机」开关（默认开）
6. 点「开始导航」：本机立即进入转向指引；若同步开启，目的地同时下发到车机
7. 导航中：顶部横幅显示下一步动作与距离，底部显示剩余里程、预计到达时间、车机同步状态

### 路线服务选择

设置里的「路线服务」：

- `自动`：先 OSRM，失败后 BRouter
- `OSRM`：`router.project-osrm.org`，转向文本规范，但公共服务器可能不可达
- `BRouter`：`brouter.de`，在受限网络下更稳定，但无转向文本，转向由航向变化推断

## 目录结构

```
app/src/main/java/ai/sunnypilot/navcompanion/
  data/     Models、PlaceSearchClient、RouteClient、BridgeClient
  nav/      Geo（几何工具，移植自 route.py）、NavSession（转向引擎）
  ui/       MainScreen、NavMapView、NavViewModel、Theme
bridge/
  nav_bridge.py            车机端 HTTP 桥接
  sunnynav-bridge.service  systemd 单元
  install.sh               安装 / hook / 卸载
```

## 已知限制

- 未做真机运行验证，仅验证到构建通过
- 手机地图与车机 `mapsd` 各渲染各的：车机地图仍需 comma 的 PMTiles 权限，手机端不共用
- BRouter 模式的转向为航向推断，复杂路口精度低于 OSRM
- 桥接服务无鉴权、无 TLS
- 到达判定为距终点 25 m 内或走完最后一步

## 致谢

- [sunnypilot](https://github.com/sunnypilot/sunnypilot)：导航消息协议与 `route.py` 几何算法
- [MapLibre](https://maplibre.org/)、[OpenFreeMap](https://openfreemap.org/)：地图渲染与瓦片
- [Photon](https://photon.komoot.io/)、[Nominatim](https://nominatim.org/)：地点搜索
- [OSRM](http://project-osrm.org/)、[BRouter](https://brouter.de/)：路线规划
- 地图数据 © OpenStreetMap 贡献者
