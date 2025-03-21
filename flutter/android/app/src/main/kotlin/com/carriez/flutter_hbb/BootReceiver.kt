package com.carriez.flutter_hbb

import android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.Manifest.permission.SYSTEM_ALERT_WINDOW
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.hjq.permissions.XXPermissions
import io.flutter.embedding.android.FlutterActivity
import android.os.Handler
import android.os.Looper

const val DEBUG_BOOT_COMPLETED = "com.carriez.flutter_hbb.DEBUG_BOOT_COMPLETED"

class BootReceiver : BroadcastReceiver() {
    private val logTag = "tagBootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(logTag, "onReceive ${intent.action}")

        if (Intent.ACTION_BOOT_COMPLETED == intent.action || DEBUG_BOOT_COMPLETED == intent.action) {
            // 默认开启，不检查SharedPreferences
            // 即使用户之前未设置，也自动启用开机自启动功能
            val prefs = context.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
            
            // 设置默认值为true
            if (!prefs.contains(KEY_START_ON_BOOT_OPT)) {
                val edit = prefs.edit()
                edit.putBoolean(KEY_START_ON_BOOT_OPT, true)
                edit.apply()
                Log.d(logTag, "设置开机自启动默认值为true")
            }
            
            // 检查是否启用开机自启动（默认为true）
            if (!prefs.getBoolean(KEY_START_ON_BOOT_OPT, true)) {
                Log.d(logTag, "开机自启动已被用户关闭")
                return
            }
            
            // check pre-permission
            if (!XXPermissions.isGranted(context, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, SYSTEM_ALERT_WINDOW)){
                Log.d(logTag, "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS or SYSTEM_ALERT_WINDOW is not granted")
                return
            }

            // 启动MainService
            val it = Intent(context, MainService::class.java).apply {
                action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
                putExtra(EXT_INIT_FROM_BOOT, true)
            }
            Toast.makeText(context, "RustDesk is Open", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(it)
            } else {
                context.startService(it)
            }
            
            // 延迟几秒后初始化InputService
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // 检查是否有INJECT_EVENTS权限
                    if (checkInjectEventsPermission(context)) {
                        Log.d(logTag, "开机自启动后，检测到INJECT_EVENTS权限，初始化InputService")
                        InputService(context)
                    } else {
                        Log.d(logTag, "开机自启动后，未检测到INJECT_EVENTS权限，尝试请求权限")
                        // 尝试请求权限
                        requestInjectEventsPermission(context) { granted ->
                            if (granted) {
                                Log.d(logTag, "开机自启动后，成功获取INJECT_EVENTS权限")
                                try {
                                    InputService(context)
                                } catch (e: Exception) {
                                    Log.e(logTag, "开机自启动后，初始化InputService失败: ${e.message}")
                                }
                            } else {
                                Log.d(logTag, "开机自启动后，INJECT_EVENTS权限请求被拒绝")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "开机自启动后初始化InputService出错: ${e.message}")
                }
            }, 5000) // 延迟5秒，确保MainService已完全启动
        }
    }
}
