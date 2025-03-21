package com.carriez.flutter_hbb

import android.Manifest.permission.*
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.AudioRecord.READ_BLOCKING
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.provider.Settings.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import ffi.FFI
import java.nio.ByteBuffer
import java.util.*


// intent action, extra
const val ACT_REQUEST_MEDIA_PROJECTION = "REQUEST_MEDIA_PROJECTION"
const val ACT_INIT_MEDIA_PROJECTION_AND_SERVICE = "INIT_MEDIA_PROJECTION_AND_SERVICE"
const val ACT_LOGIN_REQ_NOTIFY = "LOGIN_REQ_NOTIFY"
const val EXT_INIT_FROM_BOOT = "EXT_INIT_FROM_BOOT"
const val EXT_MEDIA_PROJECTION_RES_INTENT = "MEDIA_PROJECTION_RES_INTENT"
const val EXT_LOGIN_REQ_NOTIFY = "LOGIN_REQ_NOTIFY"

// Activity requestCode
const val REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION = 101
const val REQ_REQUEST_MEDIA_PROJECTION = 201

// Activity responseCode
const val RES_FAILED = -100

// Flutter channel
const val START_ACTION = "start_action"
const val GET_START_ON_BOOT_OPT = "get_start_on_boot_opt"
const val SET_START_ON_BOOT_OPT = "set_start_on_boot_opt"
const val SYNC_APP_DIR_CONFIG_PATH = "sync_app_dir"
const val GET_VALUE = "get_value"

const val KEY_APP_DIR_CONFIG_PATH = "app_dir_config_path"
const val KEY_IS_SUPPORT_VOICE_CALL = "is_support_voice_call"
const val PERMISSION_INJECT_EVENTS = "android.permission.INJECT_EVENTS"

const val KEY_SHARED_PREFERENCES = "KEY_SHARED_PREFERENCES"
const val KEY_START_ON_BOOT_OPT = "KEY_START_ON_BOOT_OPT"

@SuppressLint("ConstantLocale")
val LOCAL_NAME = Locale.getDefault().toString()
val SCREEN_INFO = Info(0, 0, 1, 200)

data class Info(
    var width: Int, var height: Int, var scale: Int, var dpi: Int
)

fun isSupportVoiceCall(): Boolean {
    // https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#VOICE_COMMUNICATION
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}

fun requestPermission(context: Context, type: String) {
    XXPermissions.with(context)
        .permission(type)
        .request { _, all ->
            if (all) {
                Handler(Looper.getMainLooper()).post {
                    MainActivity.flutterMethodChannel?.invokeMethod(
                        "on_android_permission_result",
                        mapOf("type" to type, "result" to all)
                    )
                }
            }
        }
}

fun checkInjectEventsPermission(context: Context): Boolean {
    // 在定制系统中，INJECT_EVENTS权限应该可以通过常规权限检查
    val result = XXPermissions.isGranted(context, PERMISSION_INJECT_EVENTS)
    Log.d("InjectEvents", "Checking INJECT_EVENTS permission: $result")
    return result
}

fun requestInjectEventsPermission(context: Context, callback: (Boolean) -> Unit) {
    Log.d("InjectEvents", "Requesting INJECT_EVENTS permission")
    XXPermissions.with(context)
        .permission(PERMISSION_INJECT_EVENTS)
        .request { _, all ->
            Log.d("InjectEvents", "INJECT_EVENTS permission result: $all")
            callback(all)
        }
}

fun startAction(context: Context, action: String) {
    try {
        context.startActivity(Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // don't pass package name when launch ACTION_ACCESSIBILITY_SETTINGS
            if (ACTION_ACCESSIBILITY_SETTINGS != action) {
                data = Uri.parse("package:" + context.packageName)
            }
        })
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

class AudioReader(val bufSize: Int, private val maxFrames: Int) {
    private var currentPos = 0
    private val bufferPool: Array<ByteBuffer>

    init {
        if (maxFrames < 0 || maxFrames > 32) {
            throw Exception("Out of bounds")
        }
        if (bufSize <= 0) {
            throw Exception("Wrong bufSize")
        }
        bufferPool = Array(maxFrames) {
            ByteBuffer.allocateDirect(bufSize)
        }
    }

    private fun next() {
        currentPos++
        if (currentPos >= maxFrames) {
            currentPos = 0
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun readSync(audioRecord: AudioRecord): ByteBuffer? {
        val buffer = bufferPool[currentPos]
        val res = audioRecord.read(buffer, bufSize, READ_BLOCKING)
        return if (res > 0) {
            next()
            buffer
        } else {
            null
        }
    }
}


fun getScreenSize(windowManager: WindowManager) : Pair<Int, Int>{
    var w = 0
    var h = 0
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val m = windowManager.maximumWindowMetrics
        w = m.bounds.width()
        h = m.bounds.height()
    } else {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        w = dm.widthPixels
        h = dm.heightPixels
    }
    return Pair(w, h)
}

 fun translate(input: String): String {
    Log.d("common", "translate:$LOCAL_NAME")
    return FFI.translateLocale(LOCAL_NAME, input)
}

/**
 * 获取商米设备SN号
 */
@SuppressLint("HardwareIds")
fun getDeviceSN(context: Context): String {
    var serial = "Unknown"
    try {
        // 尝试通过反射获取SystemProperties类的get方法
        val c = Class.forName("android.os.SystemProperties")
        val get = c.getMethod("get", String::class.java, String::class.java)
        
        // 尝试获取不同属性名下的SN
        serial = get.invoke(c, "ro.serialno", "Unknown") as String
        
        if (serial == "Unknown" || serial.isEmpty()) {
            serial = get.invoke(c, "ro.boot.serialno", "Unknown") as String
        }
        
        // 如果通过反射未获取到，尝试通过Build类获取
        if (serial == "Unknown" || serial.isEmpty()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                // Android 8.0以下
                @Suppress("DEPRECATION")
                serial = Build.SERIAL
            } else {
                // Android 8.0及以上，需要权限
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) 
                    == PackageManager.PERMISSION_GRANTED) {
                    serial = Build.getSerial()
                }
            }
        }
        
        // 确保serial不为空字符串
        if (serial.isEmpty()) {
            serial = "Unknown"
        }
    } catch (e: Exception) {
        Log.e("SunmiSN", "获取SN异常: ${e.message}")
    }
    
    return serial
}