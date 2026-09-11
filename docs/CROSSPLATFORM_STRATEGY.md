# 跨平台策略（Android → iOS / 鸿蒙）

> 本文档记录项目在跨平台问题上的决策依据与路线图，作为未来扩展到 iOS、鸿蒙时的参考。最后更新：2026-09-11。

## 一、核心结论

**这是一个"渲染层跨平台、原生层各写"的典型项目。**

- 现阶段**只做 Android**，不提前抽象、不上跨平台框架。
- Three.js 渲染层是跨平台核心资产，接口保持稳定，未来各端 WebView 直接复用。
- 传感器算法层（纯 Kotlin、无 Android 依赖）未来可通过 Kotlin Multiplatform 共享。
- 未来做 iOS / 鸿蒙时，路径是"**复制 JS assets + 重写原生层**"，不强行统一。

## 二、当前代码跨平台能力评估

| 层 | 跨平台性 | 迁移代价 | 说明 |
|---|---|---|---|
| `assets/viewer/`（Three.js 全套） | ★★★★★ | ~5% | 纯 Web 技术，任何能跑现代 WebView 的系统可直接复用。**最值钱资产** |
| 传感器算法 / 姿态数学（`LowPassFilter`、`EulerUtil`） | ★★★★ | ~30% | 纯 Kotlin 无 Android 依赖，KMP 可直接编译到 iOS；算法概念三端通用 |
| 传感器 / 相机 / 权限的架构思路 | ★★★★ | ~30% | 概念通用（注册→回调→注销、生命周期绑定），但 API 各端不同 |
| Kotlin Compose UI 层 | ★ | ~100% | Compose 为 Android 独占；iOS 用 SwiftUI、鸿蒙用 ArkUI 重写 |
| Kotlin 硬件层（CameraX、SensorManager） | ★ | ~95% | iOS 是 AVFoundation + CMDeviceMotion，鸿蒙是 @ohos.multimedia.camera + sensor |
| Gradle / AndroidManifest / res | ☆ | 100% | 平台基础设施，各端完全不同 |

**真相**：当前实现的跨平台资产集中在 `assets/viewer/` 5 个文件（约 790KB），其余均为 Android 专有。

## 三、为什么现阶段不做提前抽象

1. **跨平台框架会拖累当前验证目标。** 本项目是"相机预览 + 透明 WebView + Three.js + 高频传感器推送"的重度硬件组合，Flutter / React Native / Capacitor 在这条链路上均有显著短板（平台视图合成开销、传感器精度不足、bridge 多层绕行）。
2. **抽象要等两端真相都暴露才能做对。** 现在基于 Android API 形态设计"跨平台 OrientationProvider 接口"，等做 iOS 时会发现 CMDeviceMotion 是完全不同的同步回调模型，抽象会变成错误抽象。
3. **当前阶段"做对"比"做抽象"重要。** 抽象层会掩盖平台细节，不利于深入理解 Android 机制。

## 四、未来三条可选路径

### 路径 A：各端原生 + 共享 Three.js（推荐）

- Android：现状（Kotlin + Compose + CameraX + WebView）
- iOS：Swift + SwiftUI + AVFoundation + CMDeviceMotion + WKWebView
- 鸿蒙：ArkTS + ArkUI + @ohos.multimedia.camera + sensor + Web 组件

**优点**：Three.js 100% 复用；每端用最优工具、最佳性能、可访问全部平台新特性；AR 关键能力（姿态精度、相机叠加渲染）不打折。
**缺点**：三套 UI + 三套硬件 API 需维护，团队需三端技能。
**适用**：重度硬件 + AR 叠加 + 高性能渲染类应用，工业界主流做法（主流 AR/相机 App 均为各端原生）。

### 路径 B：Flutter + 平台视图混合

Flutter 写 UI，硬件层用 platform channel 调原生，Three.js 用平台视图嵌入透明 WebView。

**问题**：平台视图合成开销影响全屏叠加性能；60Hz 传感器推送要绕两层 channel；鸿蒙 Flutter 支持尚早期；最终遇性能瓶颈仍需降级原生。

### 路径 C：Kotlin Multiplatform（KMP）

业务逻辑 / 算法 / 桥协议用 Kotlin 共享编译，UI 各端各写。

**问题**：iOS 上 KMP 仍需大量 expect/actual 桥接 Swift；鸿蒙无官方 KMP 支持；对当前学习 Android 无帮助反增负担。

## 五、现阶段的四项轻量准备（非提前优化）

### 5.1 Three.js 接口冻结为跨平台契约

保持 `assets/viewer/viewer.js` 暴露的以下接口签名稳定，与平台无关：

```js
window.__setOrientation(yawDeg, pitchDeg, rollDeg, rollCompOn)
window.__setFov(deg)
window.__loadGltfUrl(url)
window.__resetView()
```

反向回调（JS → 原生）也保持稳定：

```js
window.NativeBridge.onReady()
window.NativeBridge.onFps(fps)
window.NativeBridge.onModelLoaded(url)
window.NativeBridge.onModelError(error)
```

任何一端原生代码只要实现"拼 JS 字符串 → evaluateJavascript"和"注入 NativeBridge 对象"即可接入。

### 5.2 算法层保持纯 Kotlin

`LowPassFilter.kt`、`EulerUtil.kt` 不引入任何 `android.*` import，确保未来可直接被 KMP 模块收录编译到 iOS。数据类 `OrientationEuler` 同样保持平台无关。

### 5.3 桥协议逻辑可提取为纯字符串生成

"把 (yaw, pitch, roll, rollComp) 拼成 `window.__setOrientation(...)`"这类逻辑与平台无关，未来可提取为纯函数模块共享（KMP），或各端照协议各写一行。

### 5.4 保持物理隔离

各端原生代码互不侵入：不出现跨平台条件编译、不互相 import。未来迁移时路径清晰——复制 JS assets、重写原生层，不存在需要拆除的错误抽象。

## 六、三端 API 对照表（迁移时用）

| 能力 | Android（现状） | iOS（未来） | 鸿蒙（未来） |
|---|---|---|---|
| 相机预览 | CameraX `Preview` + `PreviewView` | AVFoundation `AVCaptureSession` + `AVCaptureVideoPreviewLayer` | `@ohos.multimedia.camera` + XComponent |
| 旋转姿态 | `Sensor.TYPE_GAME_ROTATION_VECTOR` + `SensorManager.getOrientation` | `CMMotionManager.deviceMotion`（attitude: yaw/pitch/roll） | `@ohos.sensor` ROTATION_VECTOR |
| 生命周期 | LifecycleObserver onResume/onPause | viewDidAppear/viewDidDisappear | aboutToAppear/aboutToDisappear |
| WebView | `android.webkit.WebView` + `evaluateJavascript` | `WKWebView` + `evaluateJavaScript` | Web 组件 + runJavaScript |
| JS 桥 | `addJavascriptInterface` + `@JavascriptInterface` | `WKUserContentController` + `WKScriptMessageHandler` | javaScriptProxy |
| UI 框架 | Jetpack Compose | SwiftUI | ArkUI / ArkTS |
| 运行时权限 | ActivityResultContracts.RequestPermission | Info.plist 声明 + requestAccess | module.json5 + abilityAccessCtrl |
| 构建 | Gradle (Kotlin DSL) | Xcode + SwiftPM/CocoaPods | hvigor + ohpm |

## 七、决策建议汇总

| 顾虑 | 决策 |
|---|---|
| 学 Kotlin 是否浪费 | 不浪费。Android 应用层架构认知是跨平台框架替代不了的基础 |
| 未来支持 iOS | 单建 Swift + WKWebView 项目，复用 `assets/viewer/`，行业主流做法 |
| 未来支持鸿蒙 | ArkTS（TS 方言，对 JS 背景友好）+ Web 组件复用同一套 JS |
| 现在是否上 Flutter | 否。硬件深度 + AR 全屏叠加是 Flutter 弱项 |
| 现在是否抽象原生层接口 | 否。等至少一个平台跑通、暴露真实共性后再抽象 |

## 八、启动第二平台时的检查清单

1. 新建独立仓库/独立原生工程，不与 Android 工程混用构建系统。
2. 原样复制 `assets/viewer/` 全部 5 个文件，先让 Three.js 场景在新平台 WebView 中跑起来（透明背景验证）。
3. 实现原生姿态采集（对照第六节 API 表），输出统一为度数的 yaw/pitch/roll。
4. 实现显示方向重映射（横屏不跳变）——这是三端都容易踩的坑。
5. 移植低通 + 死区算法（可参考 `LowPassFilter.kt` 逐行翻译）。
6. 实现 JS 桥：正向 `__setOrientation` 等，反向 `NativeBridge` 回调。
7. 实现相机前后摄切换 + 前摄 yaw 镜像逻辑。
8. 用 `README.md` 的 10 项端到端清单在新平台回归验证。
