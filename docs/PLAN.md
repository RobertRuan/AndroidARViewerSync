# Android AR 同步漫游框架 — 实现方案

## 背景

用户从零搭建一个**仅 Android 端**的 App 框架，把手机的三类硬件能力——摄像头、三轴陀螺仪（旋转传感器）和触摸输入——联动起来，驱动一个三维模型 viewer，使手机的实际拍摄朝向与三维 viewer 内的虚拟相机姿态保持实时一致。两个核心目标：

1. **姿态实时同步**：手机的实时摄像头朝向（yaw/pitch）和 roll 必须与三维 viewer 的虚拟相机一一对应，这样当用户转动/俯仰手机时，相机画面和三维场景"看向"同一方向。用户可以借助真实相机画面与三维场景的对比，直观验证三维 camera 是否运动正确。
2. **封闭模型内漫游**：默认加载一个封闭的三维环境（程序化生成的房间：地板、天花板、墙体、可见天空盒的窗户、家具），便于用户清晰感知相机在受限空间内的运动。同时支持加载自定义 GLTF/GLB 模型。

本次为**框架/骨架搭建**：可在真机上跑通（模拟器对传感器支持有限），架构留出后续扩展位（SLAM、自定义模型、ARCore 等）。项目位置：D:/src`/AndroidARViewerSync`。

## 已确认的架构决策（用户确认）

| 维度    | 选择                                                                |
| ----- | ----------------------------------------------------------------- |
| 三维渲染层 | **混合方案**：原生 Kotlin + WebView 承载 Three.js，通过优化后的 JS 桥推送数据（目标 60Hz） |
| 布局    | **AR 叠加式**：全屏实时相机预览作为背景，全屏 WebView 透明 WebGL 画布覆盖在上层显示三维场景         |
| 默认模型  | **二者都要**：默认加载程序化生成的封闭房间，同时支持从 URL 加载自定义 GLTF/GLB                  |
| 平台    | 仅 Android（Kotlin + Jetpack Compose）；minSdk 24，targetSdk 34        |
| 构建    | Gradle Kotlin DSL                                                 |

## 技术栈

* **Kotlin 1.9.x** + **Jetpack Compose**（BOM 2024.06+）

* **CameraX**（1.3.x）做相机预览，前后摄像头，lifecycle-aware

* **Android SensorManager** 使用 `TYPE_GAME_ROTATION_VECTOR`（不依赖地磁北极——无需罗盘校准，更稳定）

* **WebView**（Chromium 内核）承载 **Three.js r160**（UMD 构建内置于 assets，所有 WebView 版本兼容）

* **GLTFLoader.js** 用于加载自定义模型

* 自研传感器平滑（指数低通 + 死区）抑制抖动

* AndroidX lifecycle + Compose `viewModel` 持有状态

## 项目结构

```
AndroidARViewerSync/
├── settings.gradle.kts
├── build.gradle.kts                       # 项目级
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew.bat
├── .gitignore
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/arviewersync/
│       │   ├── ArViewerApplication.kt
│       │   ├── MainActivity.kt                      # 单 Activity，承载 Compose
│       │   ├── ui/
│       │   │   ├── ARViewerScreen.kt                 # Compose 根：Box 叠加布局
│       │   │   ├── HudOverlay.kt                     # 顶部 yaw/pitch/roll/FPS 标签
│       │   │   ├── ControlBar.kt                     # 底部控制按钮
│       │   │   └── theme/Theme.kt
│       │   ├── camera/
│       │   │   ├── CameraController.kt               # CameraX 生命周期封装
│       │   │   └── CameraPreviewView.kt              # Compose AndroidView 适配
│       │   ├── sensor/
│       │   │   ├── OrientationProvider.kt           # SensorManager 监听器，lifecycle-aware
│       │   │   ├── LowPassFilter.kt                  # 指数平滑 + 死区
│       │   │   └── EulerUtil.kt                      # 旋转矩阵 → yaw/pitch/roll，按显示方向重映射
│       │   ├── viewer/
│       │   │   ├── ThreeViewerWebView.kt             # Compose AndroidView 适配
│       │   │   ├── ThreeBridge.kt                   # 60Hz 节流的 evaluateJavascript 推送器
│       │   │   └── WebViewPinchGesture.kt           # 双指缩放 → FOV 调整
│       │   └── viewmodel/
│       │       └── ARViewerViewModel.kt             # 持有摄像头方向、roll 补偿、FOV、场景状态
│       ├── assets/viewer/
│       │   ├── index.html                            # 透明画布宿主页
│       │   ├── viewer.js                             # 场景构建、渲染循环、window.__setOrientation 接口
│       │   ├── three.min.js                          # 内置 Three.js UMD r160
│       │   ├── GLTFLoader.js                         # 内置 Three.js GLTFLoader（UMD）
│       │   └── RoomScene.js                          # 程序化房间构建器
│       └── res/
│           ├── values/{strings.xml,colors.xml,themes.xml}
│           ├── xml/file_paths.xml                    # FileProvider，用于 GLB 文件选择
│           └── mipmap-*/ic_launcher.webp             # 占位启动图标
```

## 实现步骤

### 第 1 步 — 项目骨架与 Gradle 配置

* `settings.gradle.kts`、根 `build.gradle.kts`、`gradle.properties`、`gradle/wrapper/gradle-wrapper.properties`、`gradlew.bat`（Gradle 8.7）

* `app/build.gradle.kts`：Kotlin 1.9.22、Compose BOM、CameraX、AndroidX lifecycle，minSdk 24 / targetSdk 34

* `AndroidManifest.xml`：权限（CAMERA、INTERNET 用于 CDN 加载模型、READ\_EXTERNAL\_STORAGE 用于 GLB 文件选择），`configChanges="orientation|screenSize|keyboardHidden"` 防止旋转时 Activity 重建，`screenOrientation="unspecified"`（横竖屏均允许），注册 FileProvider

* `.gitignore`

* 启动图标：简单生成的 `ic_launcher.webp`

### 第 2 步 — 摄像头层

* `CameraController.kt`：封装 `ProcessCameraProvider`，构建 `Preview` 用例，暴露 `start(cameraLens)`、`switchCamera()`，绑定到 lifecycle owner

* `CameraPreviewView.kt`：Compose `AndroidView` 工厂包装 `PreviewView`，为 CameraX 暴露 `getSurfaceProvider()`

* 前置摄像头（LENS\_FACING\_FRONT）：PreviewView 默认 `setMirrorHint(true)` 实现自拍直觉；为同步验证，三维 yaw 相对后摄会**镜像翻转**，使前置预览方向仍与虚拟相机方向对齐

### 第 3 步 — 传感器层

* `OrientationProvider.kt`：

  * 注册 `Sensor.TYPE_GAME_ROTATION_VECTOR`，`SENSOR_DELAY_GAME`（\~20ms，原生 \~50Hz）

  * `SensorEvent` 回调：取 `event.values`（旋转向量），构造 `rotationMatrix`

  * 调 `SensorManager.remapCoordinateSystem(...)`，按当前 display rotation 重映射（自动处理横竖屏）

  * `SensorManager.getOrientation(remappedMatrix, orientationAngles)` → `[azimuth, pitch, roll]`（弧度）

  * 过 `LowPassFilter`

  * 节流：仅当（Δ 角 > 0.1° 或 距上次 >16ms）才推送 → 上限 60Hz

  * 实现 `DefaultLifecycleObserver`，在 resume/pause 注册/注销（节电 + 正确性）

* `LowPassFilter.kt`：每轴指数平滑 `out = out + α*(in - out)`，`α = 0.25`，加 0.15° 死区消除微抖

* `EulerUtil.kt`：角度归一化到 \[-180,180]、弧度→度、yaw 最短路径平滑

* 留出 One-Euro 升级接口（filter 可插拔）

### 第 4 步 — 三维 viewer（WebView + Three.js）

* `assets/viewer/index.html`：透明 `<canvas>`（CSS `background: transparent`），`WebView` 背景同样设为透明；以经典 `<script>` 标签加载 `three.min.js`、`GLTFLoader.js`、`RoomScene.js`、`viewer.js`

* `assets/viewer/RoomScene.js`：

  * 构建封闭房间：6m × 6m × 3m 盒子（BoxGeometry 法线翻转，从内部可见）

  * 地板（canvas 渐变模拟木纹）、天花板（白色）、四面墙，其中一面带窗户开口（Shape + hole + ExtrudeGeometry），窗外可见 CubeTextureLoader 加载的天空盒

  * 家具：一张桌子、两把椅子、一盏台灯（灯泡 = `PointLight`）、一块地毯——均由基础几何体 + MeshStandardMaterial 拼出

  * 光照：`HemisphereLight` + 一个 `DirectionalLight`（柔和阴影）+ 灯内 `PointLight`

  * `scene.background = null`（透明，AR 叠加）

* `assets/viewer/viewer.js`：

  * `init()`：renderer（`alpha: true, antialias: true`），透视相机（FOV 75，near 0.1，far 100），相机位置 `[0, 1.6, 0]`（人眼高度）

  * `window.__setOrientation(yawDeg, pitchDeg, rollDeg, rollCompOn)` — 桥主入口。设 camera.rotation（Euler 顺序 YXZ）：rotation.y = -yaw，rotation.x = -pitch，rotation.z = rollCompOn ? 0 : -roll。负号是因为 Three.js 相机看向 -Z，正 yaw 应看"右"

  * `window.__setFov(deg)` — 双指缩放入口

  * `window.__loadGltfUrl(url)` — 从 URL 加载自定义模型，替换房间（保留天空盒/地面）

  * `window.__resetView()` — 复位相机

  * `requestAnimationFrame` 主循环——仅在 dirty 标记置位时重绘（空闲省电）

* 双指手势由 Compose `detectTransformGestures` 在 WebView 上层的透明 overlay 上捕获 → 调 `ThreeBridge.setFov(...)`（避免 WebView 自身手势冲突）。备选：直接在 WebView `setOnTouchListener` 上处理

### 第 5 步 — 桥与状态

* `ThreeBridge.kt`：

  * 持 `WebView` 引用 + `Handler(Looper.getMainLooper())`

  * `pushOrientation(yaw, pitch, roll, rollComp)`：拼最小 JS `window.__setOrientation(${yaw},${pitch},${roll},${if (rollComp) 1 else 0});`，在 UI 线程 post `evaluateJavascript`；16ms 内的连续推送合并

  * `loadGltf(url)`、`setFov(deg)`、`resetView()`

* `ARViewerViewModel.kt`：状态持有——`cameraFacing: StateFlow<Int>`、`rollCompensationEnabled: StateFlow<Boolean>`、`fov: StateFlow<Float>`、`currentSceneUrl: StateFlow<String?>`，加上桥消费的传感器姿态流

* `MainActivity.kt`：承载 Compose，内容设为 `ARViewerScreen`，把 `OrientationProvider` 与 `CameraController` 绑定到 lifecycle

### 第 6 步 — Compose UI（叠加布局）

* `ARViewerScreen.kt`：`Box` 布局

  * 第 0 层（全屏，最底层）：`CameraPreviewView`

  * 第 1 层（全屏，透明）：`ThreeViewerWebView`（透明渲染器）

  * 第 2 层（顶部叠加）：`HudOverlay` — 实时 `yaw / pitch / roll`（度）、FPS、当前摄像头（前/后）、roll 补偿状态

  * 第 3 层（底部叠加）：`ControlBar` — 按钮：翻转摄像头 / Roll 补偿 ON-OFF / 复位视角 / 加载 GLTF… / 复位 FOV

  * 双指手势检测在 Box 层 → 更新 ViewModel 中的 `fov` → 推送到桥

* 方向处理：`LocalConfiguration.current.orientation` 触发 recomposition 但 Activity 不重建（manifest `configChanges`）；`OrientationProvider` 每次 event 都读取 `display.rotation` 重映射传感器矩阵

* `WindowCompat.setDecorFitsSystemWindows(window, false)` 实现 edge-to-edge 全屏 AR 体验

### 第 7 步 — Roll 补偿逻辑

* `rollCompensationEnabled = true` 时：桥给 JS 传 `rollDeg = 0`（无论手机怎么 roll，三维相机始终水平），yaw/pitch 仍跟随手机

* `false` 时：roll 全透传（三维视图随手机一起倾斜）

* 默认：`true`（补偿）

### 第 8 步 — 前/后摄镜像与三维同步

* **后摄**（默认）：传感器 yaw 与三维相机 yaw 1:1 对应（手机转、画面和场景同向转）

* **前摄**：PreviewView mirror hint = true（自拍直觉），但三维相机 yaw **取反**，使旋转的场景"跟随"镜像后的预览。这是标准 AR 前摄行为。逻辑集中在 `OrientationProvider`（前摄激活时把发布的 yaw 取反），下游代码无需感知

### 第 9 步 — 模型加载

* 默认首载：`viewer.js` 调 `RoomScene.build(scene)` 构建程序化房间

* "加载 GLTF" 按钮：唤起 `ActivityResultContracts.GetContent()`，MIME `*/*`，把所选文件拷到 app cache，通过 FileProvider 暴露 `file:///...` URL，调 `bridge.loadGltf(url)`。同时支持 URL 输入对话框（粘贴 GLB URL）

* 加载自定义模型时移除程序化房间，天空盒保留

### 第 10 步 — 构建与验证

* 用户当前环境未装 Android Studio / SDK，本次交付为**可独立构建的 Gradle 项目**，用户在 Android Studio 打开（或装好 Android SDK + cmdline-tools 后跑 `gradlew assembleDebug`）即可

* 项目根 `README.md` 说明：（a）如何在 Android Studio 打开、（b）硬件要求（需真机——模拟器对传感器/摄像头支持弱）、（c）开启 USB 调试、（d）切横屏、（e）加载自定义 GLB

* Three.js 库文件（`three.min.js`、`GLTFLoader.js`）本地内置——写入时通过 WebFetch 拉取 minified UMD 文件落到 `assets/viewer/`

## 关键对外接口（单一数据源）

```kotlin
// OrientationProvider.kt
class OrientationProvider(...) : DefaultLifecycleObserver {
    val orientation: StateFlow<OrientationEuler?>   // yaw, pitch, roll（度），已平滑
}

// ThreeBridge.kt
class ThreeBridge(...) {
    fun pushOrientation(yaw: Float, pitch: Float, roll: Float, rollComp: Boolean)
    fun setFov(deg: Float)
    fun loadGltf(url: String)
    fun resetView()
}
```

## 验证计划

端到端人工验证（需真机）：

1. **项目可构建**：Android Studio 打开 → Build APK 成功；或 CLI 跑 `gradlew assembleDebug`
2. **安装到设备**：手机开启 USB 调试后 `gradlew installDebug`
3. **摄像头可用**：启动即全屏实时预览；点"翻转摄像头"→ 前/后切换
4. **传感器同步**：手机左转 → 实时预览显示左侧画面 + 三维相机也转到看到三维房间左墙；俯仰手机 → 三维相机俯仰；保持不动 → 三维视图稳定（无抖动）
5. **Roll 补偿**：开启时手机顺时针倾斜 → 三维地平线保持水平；关闭 → 三维视图随手机倾斜
6. **横屏**：手机横置 → 布局自适应；传感器值重映射正确（无跳变/旋转）
7. **双指 FOV**：双指捏合/张开 → 三维 FOV 缩小/放大；HUD 显示新 FOV 值
8. **自定义模型**：点"加载 GLTF"→ 选 .glb → 房间被替换为该模型；相机旋转仍正确
9. **HUD**：实时 yaw/pitch/roll 数字平滑更新（无抖动），FPS ≥30
10. **后台/电量**：按 Home → 传感器注销（logcat 验证），无后台耗电

## 明确不在范围内（仅做框架，故以下不做）

* ARCore / 真正的 SLAM 锚定（不基于相机帧重投影做设备端姿态跟踪）

* 跨平台（iOS）——用户明确暂缓

* 录制相机画面

* 多端同步

* 云端模型托管（仅支持 URL）

