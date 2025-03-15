package com.carriez.flutter_hbb

/**
 * Handle events from flutter
 * Request system permissions for screen capturing
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import ffi.FFI

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import com.hjq.permissions.XXPermissions
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.concurrent.thread
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.annotation.NonNull
import androidx.databinding.Observable
import io.flutter.plugins.GeneratedPluginRegistrant
import java.io.File

class MainActivity : FlutterActivity() {
    companion object {
        var flutterMethodChannel: MethodChannel? = null
        private var _rdClipboardManager: RdClipboardManager? = null
        val rdClipboardManager: RdClipboardManager?
            get() = _rdClipboardManager;
        
        // 系统级权限常量字符串
        const val PERMISSION_CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
        const val PERMISSION_READ_FRAME_BUFFER = "android.permission.READ_FRAME_BUFFER"
        
        // 添加系统区域检测
        private var systemAreaHeight = 0
        
        // 默认系统区域的高度比例（相对于屏幕总高度）
        private const val SYSTEM_AREA_HEIGHT_RATIO = 0.1f
        
        @JvmStatic
        fun isInSystemArea(y: Float, screenHeight: Int): Boolean {
            if (screenHeight <= 0) return false
            
            // 初始化系统区域高度
            if (systemAreaHeight <= 0) {
                systemAreaHeight = (screenHeight * SYSTEM_AREA_HEIGHT_RATIO).toInt()
            }
            
            return y > screenHeight - systemAreaHeight
        }
    }

    private val channelTag = "com.carriez.flutter_hbb/main"
    private val logTag = "MainActivity"
    private var mainService: MainService? = null
    private lateinit var channel: MethodChannel
    private var uiInteractionHandler: Handler? = null
    private var screenHeight = 0

    private var isAudioStart = false
    private val audioRecordHandle = AudioRecordHandle(this, { false }, { isAudioStart })

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        GeneratedPluginRegistrant.registerWith(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelTag)
        flutterMethodChannel = channel
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "init_service" -> result.success(0)
                "start_service" -> result.success(0)
                "stop_service" -> result.success(0)
                "check_service" -> {
                    // 检查输入服务是否存在并正常工作
                    val serviceRunning = inputService != null
                    result.success(serviceRunning)
                }
                "pause_input" -> {
                    // 暂停输入服务
                    inputService?.temporarilyPauseInput()
                    result.success(true)
                }
                "resume_input" -> {
                    // 恢复输入服务
                    inputService?.resumeInputAfterPause()
                    result.success(true)
                }
                "register_input_service" -> {
                    // 注册输入服务到主活动
                    if (inputService == null) {
                        inputService = InputService()
                    }
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
        if (MainService.isReady) {
            Intent(activity, MainService::class.java).also {
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
        thread { setCodecInfo() }
    }

    override fun onResume() {
        super.onResume()
        val inputPer = InputService.isOpen
        activity.runOnUiThread {
            flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to inputPer.toString())
            )
        }
        // 当活动恢复时，确保输入服务不会阻塞UI交互
        inputService?.let {
            it.temporarilyPauseInput()
            Handler(Looper.getMainLooper()).postDelayed({
                it.resumeInputAfterPause()
            }, 500)
        }
    }

    // 检查系统级权限，替代MediaProjection请求
    private fun checkSystemPermissions(): Boolean {
        val captureVideoPermission = checkCallingOrSelfPermission(PERMISSION_CAPTURE_VIDEO_OUTPUT)
        val readFrameBufferPermission = checkCallingOrSelfPermission(PERMISSION_READ_FRAME_BUFFER)
        
        return captureVideoPermission == PackageManager.PERMISSION_GRANTED && 
               readFrameBufferPermission == PackageManager.PERMISSION_GRANTED
    }
    
    // 移除不再需要的requestMediaProjection方法
    // private fun requestMediaProjection() {
    //     val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
    //         action = ACT_REQUEST_MEDIA_PROJECTION
    //     }
    //     startActivityForResult(intent, REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION)
    // }

    // 修改onActivityResult，移除MediaProjection相关处理
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 系统级权限不需要ActivityResult处理
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (_rdClipboardManager == null) {
            _rdClipboardManager = RdClipboardManager(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            FFI.setClipboardManager(_rdClipboardManager!!)
        }
        
        // 应用启动时检查系统级权限状态并通知Flutter端
        if (checkSystemPermissions()) {
            Log.d(logTag, "系统级权限已预授权")
            flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to "true")
            )
        } else {
            Log.d(logTag, "系统级权限未授权")
        }
        
        // 初始化屏幕高度
        screenHeight = resources.displayMetrics.heightPixels
    }

    override fun onDestroy() {
        Log.e(logTag, "onDestroy")
        mainService?.let {
            unbindService(serviceConnection)
        }
        super.onDestroy()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(logTag, "onServiceConnected")
            val binder = service as MainService.LocalBinder
            mainService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(logTag, "onServiceDisconnected")
            mainService = null
        }
    }

    // 修改initFlutterChannel方法中处理MediaProjection的部分
    private fun initFlutterChannel(flutterMethodChannel: MethodChannel) {
        flutterMethodChannel.setMethodCallHandler { method, result ->
            when (method.method) {
                "init_service" -> {
                    if (MainService.isReady) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    
                    // 使用系统级权限直接启动服务，不再使用MediaProjection
                    if (checkSystemPermissions()) {
                        val intent = Intent(this, MainService::class.java).apply {
                            action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        result.success(true)
                    } else {
                        Log.e(logTag, "缺少必要的系统级权限，无法启动服务")
                        result.success(false)
                    }
                }
                "init_service_without_permission" -> {
                    Log.d(logTag, "尝试在定制系统环境下启动服务")
                    try {
                        // 绑定服务
                        Intent(activity, MainService::class.java).also {
                            bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
                        }
                        
                        if (MainService.isReady) {
                            result.success(true)
                            return@setMethodCallHandler
                        }
                        
                        // 在定制系统中，检查系统级权限替代MediaProjection请求
                        if (checkSystemPermissions()) {
                            val intent = Intent(this, MainService::class.java).apply {
                                action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                            result.success(true)
                        } else {
                            Log.e(logTag, "缺少系统级权限，无法启动服务")
                            result.success(false)
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "启动服务失败: ${e.message}")
                        result.success(false)
                    }
                }
                "start_capture" -> {
                    mainService?.let {
                        result.success(it.startCapture())
                    } ?: let {
                        result.success(false)
                    }
                }
                "stop_service" -> {
                    Log.d(logTag, "Stop service")
                    mainService?.let {
                        it.destroy()
                        result.success(true)
                    } ?: let {
                        result.success(false)
                    }
                }
                "check_permission" -> {
                    if (method.arguments is String) {
                        result.success(XXPermissions.isGranted(context, method.arguments as String))
                    } else {
                        result.success(false)
                    }
                }
                "request_permission" -> {
                    if (method.arguments is String) {
                        requestPermission(context, method.arguments as String)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                START_ACTION -> {
                    if (method.arguments is String) {
                        startAction(context, method.arguments as String)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                "check_video_permission" -> {
                    mainService?.let {
                        result.success(it.checkMediaPermission())
                    } ?: let {
                        result.success(false)
                    }
                }
                "check_service" -> {
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "media", "value" to MainService.isReady.toString())
                    )
                    result.success(true)
                }
                "start_input" -> {
                    if (InputService.ctx == null) {
                        if (checkInjectEventsPermission(this)) {
                            try {
                                InputService(this)
                                Companion.flutterMethodChannel?.invokeMethod(
                                    "on_state_changed",
                                    mapOf("name" to "input", "value" to InputService.isOpen.toString())
                                )
                                result.success(true)
                            } catch (e: Exception) {
                                Log.e(logTag, "Error initializing InputService: ${e.message}")
                                result.success(false)
                            }
                        } else {
                            Log.d(logTag, "Requesting INJECT_EVENTS permission")
                            Log.e(logTag, "尝试申请INJECT_EVENTS权限")
                            requestInjectEventsPermission(this) { granted ->
                                if (granted) {
                                    try {
                                        InputService(this)
                                        Log.d(logTag, "INJECT_EVENTS权限获取成功，已初始化InputService")
                                    } catch (e: Exception) {
                                        Log.e(logTag, "Error initializing InputService after permission: ${e.message}")
                                    }
                                } else {
                                    Log.d(logTag, "INJECT_EVENTS permission denied")
                                    Log.e(logTag, "INJECT_EVENTS权限被拒绝")
                                }
                                activity.runOnUiThread {
                                    Companion.flutterMethodChannel?.invokeMethod(
                                        "on_state_changed",
                                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                                    )
                                }
                            }
                            result.success(false)
                        }
                    } else {
                        Companion.flutterMethodChannel?.invokeMethod(
                            "on_state_changed",
                            mapOf("name" to "input", "value" to InputService.isOpen.toString())
                        )
                        result.success(true)
                    }
                }
                "start_input_without_dialog" -> {
                    Log.d(logTag, "尝试在定制系统环境下无需弹窗获取INJECT_EVENTS权限")
                    if (InputService.ctx == null) {
                        try {
                            // 定制系统中，直接初始化InputService，应该无需显示权限请求
                            // 在定制系统中，INJECT_EVENTS权限应该已经预授权
                            InputService(this)
                            Log.d(logTag, "定制系统中成功初始化InputService，无需显示权限弹窗")
                            
                            Companion.flutterMethodChannel?.invokeMethod(
                                "on_state_changed",
                                mapOf("name" to "input", "value" to "true")
                            )
                            result.success(true)
                        } catch (e: Exception) {
                            Log.e(logTag, "定制系统中初始化InputService失败: ${e.message}")
                            // 如果失败，回退到普通方式
                            if (!checkInjectEventsPermission(this)) {
                                requestInjectEventsPermission(this) { granted ->
                                    if (granted) {
                                        try {
                                            InputService(this)
                                            // 成功初始化后更新状态
                                            activity.runOnUiThread {
                                                Companion.flutterMethodChannel?.invokeMethod(
                                                    "on_state_changed",
                                                    mapOf("name" to "input", "value" to "true")
                                                )
                                            }
                                        } catch (e: Exception) {
                                            Log.e(logTag, "Error initializing InputService after permission: ${e.message}")
                                        }
                                    }
                                    activity.runOnUiThread {
                                        Companion.flutterMethodChannel?.invokeMethod(
                                            "on_state_changed",
                                            mapOf(
                                                "name" to "input",
                                                "value" to InputService.isOpen.toString()
                                            )
                                        )
                                    }
                                }
                            }
                            result.success(false)
                        }
                    } else {
                        result.success(true)
                    }
                }
                "stop_input" -> {
                    InputService.ctx?.disableSelf()
                    InputService.ctx = null
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    result.success(true)
                }
                "cancel_notification" -> {
                    if (method.arguments is Int) {
                        val id = method.arguments as Int
                        mainService?.cancelNotification(id)
                    } else {
                        result.success(true)
                    }
                }
                "enable_soft_keyboard" -> {
                    // https://blog.csdn.net/hanye2020/article/details/105553780
                    if (method.arguments as Boolean) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    }
                    result.success(true)

                }
                "try_sync_clipboard" -> {
                    rdClipboardManager?.syncClipboard(true)
                    result.success(true)
                }
                GET_START_ON_BOOT_OPT -> {
                    val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                    result.success(prefs.getBoolean(KEY_START_ON_BOOT_OPT, false))
                }
                SET_START_ON_BOOT_OPT -> {
                    if (method.arguments is Boolean) {
                        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                        val edit = prefs.edit()
                        edit.putBoolean(KEY_START_ON_BOOT_OPT, method.arguments as Boolean)
                        edit.apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                SYNC_APP_DIR_CONFIG_PATH -> {
                    if (method.arguments is String) {
                        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                        val edit = prefs.edit()
                        edit.putString(KEY_APP_DIR_CONFIG_PATH, method.arguments as String)
                        edit.apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                GET_VALUE -> {
                    if (method.arguments is String) {
                        if (method.arguments == KEY_IS_SUPPORT_VOICE_CALL) {
                            result.success(isSupportVoiceCall())
                        } else {
                            result.error("-1", "No such key", null)
                        }
                    } else {
                        result.success(null)
                    }
                }
                "on_voice_call_started" -> {
                    onVoiceCallStarted()
                }
                "on_voice_call_closed" -> {
                    onVoiceCallClosed()
                }
                "ensure_ui_interactive" -> {
                    // 确保本地UI交互能力，即使在被远程控制期间
                    ensureUiInteractive()
                    result.success(true)
                }
                else -> {
                    result.error("-1", "No such method", null)
                }
            }
        }
    }

    private fun setCodecInfo() {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecs = codecList.codecInfos
        val codecArray = JSONArray()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val wh = getScreenSize(windowManager)
        var w = wh.first
        var h = wh.second
        val align = 64
        w = (w + align - 1) / align * align
        h = (h + align - 1) / align * align
        codecs.forEach { codec ->
            val codecObject = JSONObject()
            codecObject.put("name", codec.name)
            codecObject.put("is_encoder", codec.isEncoder)
            var hw: Boolean? = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hw = codec.isHardwareAccelerated
            } else {
                // https://chromium.googlesource.com/external/webrtc/+/HEAD/sdk/android/src/java/org/webrtc/MediaCodecUtils.java#29
                // https://chromium.googlesource.com/external/webrtc/+/master/sdk/android/api/org/webrtc/HardwareVideoEncoderFactory.java#229
                if (listOf("OMX.google.", "OMX.SEC.", "c2.android").any { codec.name.startsWith(it, true) }) {
                    hw = false
                } else if (listOf("c2.qti", "OMX.qcom.video", "OMX.Exynos", "OMX.hisi", "OMX.MTK", "OMX.Intel", "OMX.Nvidia").any { codec.name.startsWith(it, true) }) {
                    hw = true
                }
            }
            if (hw != true) {
                return@forEach
            }
            codecObject.put("hw", hw)
            var mime_type = ""
            codec.supportedTypes.forEach { type ->
                if (listOf("video/avc", "video/hevc").contains(type)) { // "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01"
                    mime_type = type;
                }
            }
            if (mime_type.isNotEmpty()) {
                codecObject.put("mime_type", mime_type)
                val caps = codec.getCapabilitiesForType(mime_type)
                if (codec.isEncoder) {
                    // Encoder's max_height and max_width are interchangeable
                    if (!caps.videoCapabilities.isSizeSupported(w,h) && !caps.videoCapabilities.isSizeSupported(h,w)) {
                        return@forEach
                    }
                }
                codecObject.put("min_width", caps.videoCapabilities.supportedWidths.lower)
                codecObject.put("max_width", caps.videoCapabilities.supportedWidths.upper)
                codecObject.put("min_height", caps.videoCapabilities.supportedHeights.lower)
                codecObject.put("max_height", caps.videoCapabilities.supportedHeights.upper)
                val surface = caps.colorFormats.contains(COLOR_FormatSurface);
                codecObject.put("surface", surface)
                val nv12 = caps.colorFormats.contains(COLOR_FormatYUV420SemiPlanar)
                codecObject.put("nv12", nv12)
                if (!(nv12 || surface)) {
                    return@forEach
                }
                codecObject.put("min_bitrate", caps.videoCapabilities.bitrateRange.lower / 1000)
                codecObject.put("max_bitrate", caps.videoCapabilities.bitrateRange.upper / 1000)
                if (!codec.isEncoder) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        codecObject.put("low_latency", caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                    }
                }
                if (!codec.isEncoder) {
                    return@forEach
                }
                codecArray.put(codecObject)
            }
        }
        val result = JSONObject()
        result.put("version", Build.VERSION.SDK_INT)
        result.put("w", w)
        result.put("h", h)
        result.put("codecs", codecArray)
        FFI.setCodecInfo(result.toString())
    }

    private fun onVoiceCallStarted() {
        var ok = false
        mainService?.let {
            ok = it.onVoiceCallStarted()
        } ?: let {
            isAudioStart = true
            ok = audioRecordHandle.onVoiceCallStarted(null)
        }
        if (!ok) {
            // Rarely happens, So we just add log and msgbox here.
            Log.e(logTag, "onVoiceCallStarted fail")
            flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                "type" to "custom-nook-nocancel-hasclose-error",
                "title" to "Voice call",
                "text" to "Failed to start voice call."))
        } else {
            Log.d(logTag, "onVoiceCallStarted success")
        }
    }

    private fun onVoiceCallClosed() {
        var ok = false
        mainService?.let {
            ok = it.onVoiceCallClosed()
        } ?: let {
            isAudioStart = false
            ok = audioRecordHandle.onVoiceCallClosed(null)
        }
        if (!ok) {
            // Rarely happens, So we just add log and msgbox here.
            Log.e(logTag, "onVoiceCallClosed fail")
            flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                "type" to "custom-nook-nocancel-hasclose-error",
                "title" to "Voice call",
                "text" to "Failed to stop voice call."))
        } else {
            Log.d(logTag, "onVoiceCallClosed success")
        }
    }

    override fun onStop() {
        super.onStop()
        val disableFloatingWindow = FFI.getLocalOption("disable-floating-window") == "Y"
        if (!disableFloatingWindow && MainService.isReady) {
            startService(Intent(this, FloatingWindowService::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        stopService(Intent(this, FloatingWindowService::class.java))
    }

    // 确保应用自身UI在被远程控制期间仍然可交互
    private fun ensureUiInteractive() {
        Log.d(logTag, "确保本地UI交互能力")
        try {
            // 1. 暂时暂停输入服务的事件处理
            InputService.ctx?.let { service ->
                // 记录当前应用窗口位置和前台状态
                window.decorView.post {
                    val appPackageName = packageName
                    val isAppForeground = isAppInForeground(appPackageName)
                    
                    if (isAppForeground) {
                        Log.d(logTag, "应用在前台，临时调整输入事件处理模式")
                        
                        // 临时重置可能阻塞触摸事件的标志
                        window.decorView.setOnTouchListener { _, event ->
                            // 1. 将触摸事件传递给服务并暂停服务处理，确保应用UI可以处理它
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                service.temporarilyResetState()
                                
                                // 根据触摸位置判断是否在系统区域，调整暂停时间
                                val isSystemArea = isInSystemArea(event.y.toFloat(), screenHeight)
                                val pauseTime = if (isSystemArea) 800 else 400
                                
                                // 延迟恢复服务
                                Handler(Looper.getMainLooper()).postDelayed({
                                    service.restoreState()
                                }, pauseTime.toLong()) // 根据区域调整暂停时间
                            }
                            
                            // 返回false，允许事件继续传递给UI处理
                            false
                        }
                        
                        // 2. 确保窗口具有焦点和交互能力
                        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                        
                        // 3. 强制刷新UI重绘，确保视觉状态正确
                        window.decorView.invalidate()
                        
                        // 4. 点击时自动暂停输入服务处理
                        // 在decorView的最外层加一个触摸监听，使整个窗口都能正确响应点击
                        window.decorView.setOnClickListener {
                            // 点击时临时暂停服务
                            service.temporarilyResetState()
                            
                            // 延迟恢复
                            Handler(Looper.getMainLooper()).postDelayed({
                                service.restoreState()
                            }, 300)
                        }
                        
                        // 5. 确保UI渲染优先级
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            window.attributes.layoutInDisplayCutoutMode = 
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                        
                        // 关键修复：临时重置InputService的状态变量
                        // 这会使InputService在短时间内不处理任何事件，让本地UI能够响应
                        service.temporarilyResetState()
                        
                        // 延迟恢复InputService正常工作
                        Handler(Looper.getMainLooper()).postDelayed({
                            service.restoreState()
                            
                            // 再次暂停一次，处理可能的第二次点击
                            Handler(Looper.getMainLooper()).postDelayed({
                                service.temporarilyResetState()
                                
                                Handler(Looper.getMainLooper()).postDelayed({
                                    service.restoreState()
                                }, 200)
                            }, 300)
                        }, 200)
                    } else {
                        Log.d(logTag, "应用不在前台，无需调整UI交互")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "确保UI交互时出错: ${e.message}")
        }
    }
    
    // 检查应用是否在前台
    private fun isAppInForeground(packageName: String): Boolean {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val appProcesses = activityManager.runningAppProcesses ?: return false
            
            for (appProcess in appProcesses) {
                if (appProcess.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && 
                    appProcess.processName == packageName) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "检查应用前台状态时出错: ${e.message}")
        }
        return false
    }
    
    // 重写触摸事件处理
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // 确保UI能响应触摸
                ensureUiInteractive()
            }
        }
        return super.dispatchTouchEvent(ev)
    }
    
    // 重写窗口焦点变化处理
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 当窗口获得焦点时，确保UI能响应触摸
            window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
    }
}
