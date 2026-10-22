# SunnyNavCompanion

[![Android CI](https://github.com/opai-cn/SunnyNavCompanion/actions/workflows/android-ci.yml/badge.svg)](https://github.com/opai-cn/SunnyNavCompanion/actions/workflows/android-ci.yml)

sunnypilot 导航功能的 Android 伴侣应用：在手机上搜索目的地、规划路线、显示实时转向指引，并把目的地下发到车机，让车机端的 `navd`/`mapsd` 真正跑起来。

sunnypilot 的 master（`openpilot/selfdrive/navigation/`，2026-10-12 合入）只有导航**后端**，车机端 `settings/navigation.py` 是空的，没有输入目的地的入口。本项目补上「目的地输入 + 路线 + 转向显示」这一层。

```
┌────────────── 手机 (Android) ──────────────┐        ┌──────── 车机 (comma) ────────┐
│ 搜索地点 → 规划路线 → 本机转向引擎          │  LAN   │ nav_bridge.py (HTTP 8765)    │
│ 底图：MapLibre / 高德 / 百度                │ ─────► │   → Params.NavDestination    │
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

- 地点搜索：高德/百度（配置 Key 且选中对应服务时优先），Photon/Nominatim 兜底
- 路线规划：高德/百度（可选）、OSRM 优先，不可达时自动降级到 BRouter（`--profile car-fast`）
- 本机转向引擎：从 sunnypilot `route.py` 移植，含沿路径进度、机动切换（-10 m）、偏航检测（25 m × 3 次）、10 s 节流重算
- 地图：MapLibre 11 + OpenFreeMap 深色样式（免 key）；可切换高德 JS API 2.0 或百度 BMapGL 底图
- 外部导航：一键把目的地交给手机上的高德地图或百度地图 App（未安装时回退网页版）
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

## 持续集成与发布

| 工作流 | 触发条件 | 内容 |
|---|---|---|
| [`android-ci.yml`](.github/workflows/android-ci.yml) | push / PR 到 `main`，或手动触发 | 校验桥接脚本语法、构建 debug APK、上传 artifact（保留 14 天） |
| [`android-release.yml`](.github/workflows/android-release.yml) | 推送 `v*` 标签，或手动触发 | 构建 APK、生成 `SHA256SUMS.txt`、创建 GitHub Release |

CI 使用 JDK 21 + `platforms;android-36` + `build-tools;36.0.0`，Gradle 依赖由 `gradle/actions/setup-gradle` 缓存。发布新版本：

```bash
git tag v1.0.0
git push origin v1.0.0
```

Release 附件中的 APK 是 debug 签名（可直接安装，不适合 Play 商店）；如需正式签名，请自行配置 keystore。

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

设置里的「路线与搜索服务」：

- `自动`：先 OSRM，失败后 BRouter
- `OSRM`：`router.project-osrm.org`，转向文本规范，但公共服务器可能不可达
- `BRouter`：`brouter.de`，在受限网络下更稳定，但无转向文本，转向由航向变化推断
- `高德`：`restapi.amap.com` 驾车路线 + POI 搜索，转向文本直接使用高德返回的 `instruction`
- `百度`：`api.map.baidu.com` 驾车路线 + POI 搜索，同样使用百度的 `instruction`

高德/百度失败或未填 Key 时会自动回退到 OSRM → BRouter，不会中断导航。

## 高德 / 百度地图

App 同时支持「底图」和「服务」两种接入，二者独立配置：

| 设置项 | 用途 | 申请入口 |
|---|---|---|
| 高德 Web 服务 Key | 地点搜索、驾车路线 | [高德开放平台](https://lbs.amap.com/) → 应用管理 → Web 服务 |
| 高德 JS API Key | 高德底图 | 同上，Key 类型选「Web 端 (JS API)」 |
| 高德安全密钥 | JS API 2.0 必需 | 与 JS API Key 一同生成（`securityJsCode`） |
| 百度服务端 AK | 地点搜索、驾车路线 | [百度地图开放平台](https://lbsyun.baidu.com/) → 应用管理 → 服务端 |
| 百度浏览器端 AK | 百度底图 | 同上，应用类型选「浏览器端」 |

填写位置：右上角「设置」→「底图」选择 `高德` / `百度`，再填入对应 Key 并保存。域名白名单建议留空（WebView 的 Referer 不是固定域名）；若必须填写，请同时允许 `*`。

### 坐标基准

GPS、OSRM、MapLibre 使用 WGS-84，高德使用 GCJ-02，百度使用 BD-09。所有跨服务坐标都会经过 [ChinaGeo.kt](app/src/main/java/ai/sunnypilot/navcompanion/nav/ChinaGeo.kt) 转换：

- 搜索结果：GCJ-02 / BD-09 → WGS-84，再参与路线计算
- 路线请求：起点/终点 WGS-84 → GCJ-02
- 底图渲染：WGS-84 → 目标底图基准
- 外部导航跳转：WGS-84 → GCJ-02（两个 App 的 URL scheme 都用 GCJ-02）

### 外部导航跳转

路线卡上的「用高德导航」「用百度导航」按钮：

- 高德：`amapuri://route/plan/?dlat=..&dlon=..&dname=..`
- 百度：`baidumap://map/direction?destination=latlng:lat,lon|name:..&coord_type=gcj02`

未安装对应 App 时自动改用浏览器打开高德/百度网页版导航。

## 目录结构

```
app/src/main/java/ai/sunnypilot/navcompanion/
  data/     Models、PlaceSearchClient、RouteClient、AmapClient、BaiduClient、BridgeClient
  nav/      Geo（几何工具，移植自 route.py）、ChinaGeo（WGS-84/GCJ-02/BD-09）、NavSession
  ui/       MainScreen、NavMapView（MapLibre）、WebMapView（高德/百度）、NavViewModel、Theme
bridge/
  nav_bridge.py            车机端 HTTP 桥接
  sunnynav-bridge.service  systemd 单元
  install.sh               安装 / hook / 卸载
```

## 已知限制

- 未做真机运行验证，仅验证到构建通过
- 高德/百度底图通过 WebView 加载官方 JS API，需要有效 Key 且手机能访问对应 CDN；加载失败时会提示并回退 MapLibre
- 百度 WebGL 底图不支持自定义深色样式，与 App 深色界面观感不一致
- 车机端 `mapsd` 仍使用 comma 的 PMTiles，手机端选择高德/百度不影响车机地图
- BRouter 模式的转向为航向推断，复杂路口精度低于 OSRM/高德/百度
- 桥接服务无鉴权、无 TLS
- 到达判定为距终点 25 m 内或走完最后一步

## 致谢

- [sunnypilot](https://github.com/sunnypilot/sunnypilot)：导航消息协议与 `route.py` 几何算法
- [MapLibre](https://maplibre.org/)、[OpenFreeMap](https://openfreemap.org/)：地图渲染与瓦片
- [高德开放平台](https://lbs.amap.com/)、[百度地图开放平台](https://lbsyun.baidu.com/)：底图、地点搜索与驾车路线
- [Photon](https://photon.komoot.io/)、[Nominatim](https://nominatim.org/)：地点搜索兜底
- [OSRM](http://project-osrm.org/)、[BRouter](https://brouter.de/)：路线规划兜底
- 地图数据 © OpenStreetMap 贡献者
