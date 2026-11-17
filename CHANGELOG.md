# Changelog

所有值得记录的变更都在这里。版本号沿用 `versionName`。

## v1.0 — 2026-11-17

首个可用版本，只针对红米 Note 12 Turbo。

### 功能

- 超广角后台录像：写死摄像头 ID 2，1080p30，H.265/HEVC，MP4。
- 无预览层的 Camera2 + MediaRecorder 直录，按 Home / 锁屏继续录制。
- 前台服务（`camera|microphone`）+ `PARTIAL_WAKE_LOCK`，通知栏常驻并可从通知停止。
- 文件写入 `相册/Movies/BgRecorder`，用 `IS_PENDING` 保证相册里不会出现半截坏文件。
- 界面只有一个红色圆形按钮，首次启动自动申请忽略电池优化。

### 拍摄参数

- HDR 关闭，视频/光学防抖关闭，抗闪烁固定 50Hz。
- 白平衡显式自动且不锁定（改善超广角偏黄）。
- 视频码率固定 8 Mbps（原先取系统默认 17~20 Mbps，约 2.9 GB/小时）。
- 音频 AAC 48 kHz 双声道 128 kbps，音源 CAMCORDER。

### 迭代记录

- v1：通用枚举 + 自动挑超广角 → 实际录到的是主摄。
- v2/v3：探测隐藏 camera ID + 候选链自动降级 → 定位到本机超广角就是 ID 2。
- v4：按单机收敛，写死 ID 2，降码率，开 AWB，精简 UI。
