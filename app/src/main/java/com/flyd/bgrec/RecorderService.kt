package com.flyd.bgrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 红米 Note 12 Turbo 专用 · 超广角后台录像。
 *
 * 只录 ID 2（这台机器的超广角），1080p30 / H.265 / 无 HDR / 无防抖 / 抗闪烁 50Hz / AWB 自动。
 * 没有预览层，只用 MediaRecorder 的 Surface，所以界面退到后台、锁屏都能继续录。
 */
class RecorderService : Service() {

    companion object {
        private const val TAG = "BgRec"
        private const val CHANNEL_ID = "bgrec_channel"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.flyd.bgrec.START"
        const val ACTION_STOP = "com.flyd.bgrec.STOP"
        const val EXTRA_AUDIO = "audio"

        /** 红米 Note 12 Turbo 的超广角 */
        private const val CAMERA_ID = "2"

        /** 1080p30 H.265 固定 8 Mbps（原来走系统默认 17~20 Mbps，体积大一半多） */
        private const val VIDEO_BITRATE = 8_000_000
    }

    private lateinit var manager: CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var pendingUri: Uri? = null
    private var chars: CameraCharacteristics? = null
    private var fpsRange: Range<Int>? = null
    private var videoSize: Size = Size(1920, 1080)
    private var wakeLock: PowerManager.WakeLock? = null

    private var finishing = false
    private var withAudio = true
    private var lensFocal = 0f

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val t = HandlerThread("bgrec-camera")
        t.start()
        thread = t
        handler = Handler(t.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                withAudio = intent.getBooleanExtra(EXTRA_AUDIO, true)
                startForeground(NOTIF_ID, buildNotification("正在启动…"))
                if (device == null && recorder == null) begin()
            }

            ACTION_STOP -> finish(save = true, message = "已保存")
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- 启动

    private fun begin() {
        finishing = false
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bgrec:recording")
        lock.setReferenceCounted(false)
        lock.acquire(12 * 60 * 60 * 1000L)
        wakeLock = lock

        RecState.recording = false
        RecState.publish("正在打开超广角（ID $CAMERA_ID）…")
        handler?.post {
            try {
                manager.openCamera(CAMERA_ID, deviceCallback, handler)
            } catch (e: Throwable) {
                finish(save = false, message = "打开超广角失败：${e.message}")
            }
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            try {
                prepare(camera)
            } catch (e: Throwable) {
                Log.w(TAG, "prepare 失败: ${e.message}")
                finish(save = false, message = "初始化录制失败：${e.message}")
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            try {
                camera.close()
            } catch (ignored: Throwable) {
            }
            if (device === camera) device = null
            if (!finishing) finish(save = false, message = "摄像头被其它应用抢占，录制中断")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            try {
                camera.close()
            } catch (ignored: Throwable) {
            }
            if (device === camera) device = null
            if (!finishing) finish(save = false, message = "摄像头错误码 $error")
        }
    }

    @Suppress("DEPRECATION")
    private fun prepare(camera: CameraDevice) {
        val ch = manager.getCameraCharacteristics(camera.id)
        chars = ch
        val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        lensFocal = if (focals != null && focals.isNotEmpty()) (focals.minOrNull() ?: 0f) else 0f

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("该摄像头没有可用的输出配置")
        videoSize = chooseSize(map.getOutputSizes(MediaRecorder::class.java), 1920, 1080)
        fpsRange = chooseFps(ch)

        // MediaStore 先建 pending 记录，停止后转正
        val name = "BG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BgRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri: Uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法在相册中创建文件")
        pendingUri = uri
        val pfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("无法写入媒体库文件")
        descriptor = pfd

        val hevc = hasHevcEncoder()
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        if (withAudio) mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (withAudio) {
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(2)
            mr.setAudioSamplingRate(48_000)
            mr.setAudioEncodingBitRate(128_000)
        }
        mr.setVideoEncoder(if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
        mr.setVideoSize(videoSize.width, videoSize.height)
        mr.setVideoFrameRate(30)
        mr.setVideoEncodingBitRate(VIDEO_BITRATE)
        mr.setOrientationHint(orientationHint(ch))
        mr.setOutputFile(pfd.fileDescriptor)
        mr.prepare()
        recorder = mr

        RecState.codec = if (hevc) "H.265" else "H.264"
        RecState.publish("正在启动编码器…")
        camera.createCaptureSession(listOf(mr.surface), sessionCallback, handler)
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            if (finishing) return
            session = s
            val cam = device ?: return
            val mr = recorder ?: return
            try {
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                req.addTarget(mr.surface)

                req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                // 关场景模式 => 不触发厂商 HDR / 夜景
                req.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                // 白平衡：显式走自动，并且不锁定
                req.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                req.set(CaptureRequest.CONTROL_AWB_LOCK, false)

                // 抗闪烁固定 50Hz（国内电网），压掉 LED / 日光灯的横条滚动
                val abModes = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
                if (abModes != null && abModes.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ)) {
                    req.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ)
                } else if (abModes != null && abModes.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)) {
                    req.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
                }

                // 无视频防抖、无光学防抖（不裁切画面）
                req.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                val oisModes = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                if (oisModes != null && oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
                    req.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                }

                fpsRange?.let { req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

                val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                if (afModes != null && afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                    req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }

                s.setRepeatingRequest(req.build(), null, handler)
                mr.start()

                RecState.recording = true
                val camInfo = "ID $CAMERA_ID" +
                        (if (lensFocal > 0f) String.format(Locale.US, " · %.1fmm", lensFocal) else "")
                val msg = "录制中 · $camInfo · ${videoSize.width}×${videoSize.height} · 30fps · ${RecState.codec}" +
                        " · ${VIDEO_BITRATE / 1_000_000}Mbps · AWB自动" + if (withAudio) " · 含麦克风" else ""
                RecState.publish(msg)
                notify(msg)
            } catch (e: Throwable) {
                Log.w(TAG, "启动录制失败: ${e.message}")
                finish(save = false, message = "启动录制失败：${e.message}")
            }
        }

        override fun onConfigureFailed(s: CameraCaptureSession) {
            finish(save = false, message = "该摄像头不支持录制输出（会话配置失败）")
        }
    }

    // ---------------------------------------------------------------- 停止 / 清理

    private fun finish(save: Boolean, message: String) {
        if (finishing) return
        finishing = true
        val body = Runnable {
            var ok = false
            try {
                session?.stopRepeating()
            } catch (ignored: Throwable) {
            }
            try {
                session?.close()
            } catch (ignored: Throwable) {
            }
            session = null

            if (save && recorder != null) {
                try {
                    recorder?.stop()
                    ok = true
                } catch (e: Throwable) {
                    Log.w(TAG, "MediaRecorder.stop() 失败: ${e.message}")
                }
            }
            try {
                recorder?.reset()
            } catch (ignored: Throwable) {
            }
            try {
                recorder?.release()
            } catch (ignored: Throwable) {
            }
            recorder = null
            try {
                device?.close()
            } catch (ignored: Throwable) {
            }
            device = null
            try {
                descriptor?.close()
            } catch (ignored: Throwable) {
            }
            descriptor = null
            chars = null

            val uri = pendingUri
            pendingUri = null
            if (uri != null) {
                if (ok) {
                    val v = ContentValues()
                    v.put(MediaStore.Video.Media.IS_PENDING, 0)
                    try {
                        contentResolver.update(uri, v, null, null)
                    } catch (ignored: Throwable) {
                    }
                    RecState.lastFile = uri.toString()
                } else {
                    try {
                        contentResolver.delete(uri, null, null)
                    } catch (ignored: Throwable) {
                    }
                }
            }

            RecState.recording = false
            RecState.publish(if (ok) "$message → 相册/Movies/BgRecorder" else message)
            releaseAll()
        }
        val h = handler
        if (h != null) h.post(body) else body.run()
    }

    private fun releaseAll() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Throwable) {
        }
        wakeLock = null
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        } catch (ignored: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        try {
            session?.close()
        } catch (ignored: Throwable) {
        }
        session = null
        try {
            recorder?.release()
        } catch (ignored: Throwable) {
        }
        recorder = null
        try {
            device?.close()
        } catch (ignored: Throwable) {
        }
        device = null
        try {
            descriptor?.close()
        } catch (ignored: Throwable) {
        }
        descriptor = null
        // 进程被系统杀掉时清掉半截 pending 记录，避免相册里出现坏文件
        val uri = pendingUri
        pendingUri = null
        if (uri != null) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (ignored: Throwable) {
            }
        }
        RecState.recording = false
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Throwable) {
        }
        wakeLock = null
        thread?.quitSafely()
        thread = null
        handler = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 参数

    private fun chooseSize(sizes: Array<Size>?, w: Int, h: Int): Size {
        if (sizes == null || sizes.isEmpty()) return Size(w, h)
        sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
        val target = w.toLong() * h
        return sizes.filter { it.width.toLong() * it.height >= target }
            .minByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }!!
    }

    private fun chooseFps(ch: CameraCharacteristics): Range<Int>? {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        ranges.firstOrNull { it.lower == 30 && it.upper == 30 }?.let { return it }
        return ranges.filter { it.upper >= 30 }.minByOrNull { it.upper - it.lower }
    }

    private fun hasHevcEncoder(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) }
        }
    } catch (ignored: Throwable) {
        false
    }

    private fun orientationHint(ch: CameraCharacteristics): Int {
        val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val rot = dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        val deviceDeg = when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + deviceDeg) % 360
        } else {
            (sensor - deviceDeg + 360) % 360
        }
    }

    // ---------------------------------------------------------------- 通知

    private fun ensureChannel(): String {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "录制状态", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
        return CHANNEL_ID
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_stat_rec)
        return Notification.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("后台录像 · 超广角 1080p30")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "停止", stop).build())
            .build()
    }

    private fun notify(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (ignored: Throwable) {
        }
    }
}
