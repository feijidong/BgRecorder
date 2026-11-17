# BgRecorder · 红米 Note 12 Turbo 超广角后台录像

![platform](https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?logo=android&logoColor=white)
![kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)
![gradle](https://img.shields.io/badge/Gradle-8.7-02303A?logo=gradle&logoColor=white)
![license](https://img.shields.io/badge/license-MIT-blue)

一个只干一件事的 Android 小工具：**在后台用超广角一直录 1080p30 H.265 视频**，屏幕关了也照录。
界面只有一个红色按钮，所有参数写死，没有任何可以调错的地方。

只适配 **红米 Note 12 Turbo**（codename `marble`）——后置 ID `2` 是这颗机器的超广角（2.2 mm ≈ 16 mm 等效）。
其他机型不做兼容，也不保证能跑。

## 特性

- **后台 / 熄屏继续录**：Camera2 直接把 `MediaRecorder` 的 Surface 当输出目标，**没有预览层**，界面退到后台、锁屏都不影响。
- **超广角写死 ID 2**：不走枚举，不猜等效焦距，启动就开那颗。
- **H.265 + 8 Mbps**：1080p30 约 2.9 GB/小时，比系统默认档位省一半多。
- **抗闪烁 50 Hz**：压掉 LED / 日光灯下的明暗横条滚动。
- **无 HDR、无电子防抖、无光学防抖**：EIS 会额外裁画面，超广角不该被裁。
- **AWB 自动且不锁定**：修正偏黄。
- **文件不脏**：先以 `IS_PENDING=1` 写 MediaStore，正常停止才转正；异常中断会删掉半截文件。

## 固定参数

| 项目 | 值 |
|---|---|
| 摄像头 | `ID 2`（超广角，2.2 mm ≈ 16 mm） |
| 分辨率 / 帧率 | 1920×1080 @ 30 fps（`AE_TARGET_FPS_RANGE` 优先 `[30,30]`） |
| 编码 | H.265 / HEVC，MP4（无 HEVC 编码器时回落 H.264） |
| 码率 | 8 Mbps |
| 音频 | AAC 48 kHz / 双声道 / 128 kbps，音源 CAMCORDER |
| 白平衡 | `CONTROL_AWB_MODE_AUTO`，`AWB_LOCK=false` |
| HDR | 关闭（`CONTROL_SCENE_MODE_DISABLED`） |
| 抗闪烁 | `CONTROL_AE_ANTIBANDING_MODE_50HZ` |
| 防抖 | 视频 EIS / 光学 OIS 均关闭 |
| 对焦 | `CONTROL_AF_MODE_CONTINUOUS_VIDEO` |
| 输出 | `相册/Movies/BgRecorder/BG_yyyyMMdd_HHmmss.mp4` |

## 预编译 APK

`release/BgRecorder-1.0-uw-1080p30-debug.apk`（**debug 签名**，仅供侧载自用，不适合上架）。
校验值见 [`release/README.md`](release/README.md)。

```bash
adb install -r release/BgRecorder-1.0-uw-1080p30-debug.apk
```

## 构建

需要 JDK 17 与 Android SDK（`platforms;android-34`、`build-tools;34.0.0`）。

```bash
git clone <this-repo>
cd BgRecorder
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # 或用 ANDROID_HOME 环境变量
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开本目录，等 Gradle Sync 完点 Run。

> 中国大陆网络如果卡在 `Downloading gradle-8.7-bin.zip`（services.gradle.org 时常 502），
> 把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成镜像即可：
>
> ```properties
> distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip
> ```

发布签名版本：

```bash
./gradlew assembleRelease      # 需先配置 signingConfig，见 app/build.gradle.kts
```

## 上机使用

1. 安装后打开 App，允许「相机」「麦克风」「通知」，系统询问「后台使用相机」时选**始终允许**。
2. 首次启动会自动弹「忽略电池优化」，选允许。
3. 红米 / 小米机型必须再手动设一遍，**否则熄屏几分钟会被杀**：
   - 设置 → 应用设置 → 应用管理 → 后台录像 → **省电策略：无限制**
   - 同页 → **自启动**：允许
   - 同页 → 权限管理 → **后台弹出界面**：允许（HyperOS 有此项）
4. 点红色按钮开始，按 Home 或锁屏都会继续录；停止用通知栏「停止」或回 App 再点一次。

## 目录结构

```
BgRecorder/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/flyd/bgrec/
│       │   ├── MainActivity.kt      # 单按钮 UI + 权限
│       │   ├── RecorderService.kt   # 前台服务：Camera2 + MediaRecorder
│       │   └── RecState.kt          # 服务与界面的状态回调
│       └── res/                     # 布局、颜色、图标
├── gradle/wrapper/                  # Gradle Wrapper（含 jar）
├── release/                         # 预编译 APK
├── .github/workflows/android.yml    # CI：push 时自动构建 debug APK
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── CHANGELOG.md
├── LICENSE
└── README.md
```

## 实现要点

- `RecorderService` 是 `foregroundServiceType="camera|microphone"` 的前台服务，持有摄像头与 `PARTIAL_WAKE_LOCK`；通知栏常驻，带「停止」动作。
- 输出目标只有 `MediaRecorder.surface` 一个，没有 `SurfaceView` / `TextureView`，所以不需要界面存活。
- `MediaRecorder` 的参数顺序：音视频源 → 输出格式 → 编码器 → 尺寸/帧率/码率 → `setOrientationHint` → `prepare()`。
- 请求模板 `TEMPLATE_RECORD`，逐帧下发 AWB / 抗闪烁 / 防抖 / FPS 区间等固定值。
- 录完把 `IS_PENDING` 置 0，相册立即可见；进程被杀时在 `onDestroy` 删除未完成的 pending 条目。

## 已知限制

- 部分 MIUI / HyperOS 版本熄屏后会弹「后台使用相机」确认，选「始终允许」即可。
- 单文件录制，超长录制请手动分段（`MediaRecorder` 一直往同一个 MP4 里写）。
- 屏幕方向在开始录制那一刻锁定，录中途转手机会导致方向不对。
- 偏黄来自超广角模组本身的色温倾向与系统 AWB 调校，Camera2 拿不到厂商私有色彩校正表，自动白平衡基本是上限。

## 许可

[MIT](LICENSE) © 2026 flyd
