package com.carriez.flutter_hbb

/**
 * Handle remote input and dispatch android gesture
 *
 * Modified to use INJECT_EVENTS permission instead of AccessibilityService
 */

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent as KeyEventAndroid
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.hardware.input.InputManager
import android.media.AudioManager
import android.view.KeyCharacterMap
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.util.*
import java.lang.Character
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import kotlin.concurrent.thread
import java.lang.reflect.Method

// const val BUTTON_UP = 2
// const val BUTTON_BACK = 0x08

const val LEFT_DOWN = 9
const val LEFT_MOVE = 8
const val LEFT_UP = 10
const val RIGHT_UP = 18
// (BUTTON_BACK << 3) | BUTTON_UP
const val BACK_UP = 66
const val WHEEL_BUTTON_DOWN = 33
const val WHEEL_BUTTON_UP = 34
const val WHEEL_DOWN = 523331
const val WHEEL_UP = 963

const val TOUCH_SCALE_START = 1
const val TOUCH_SCALE = 2
const val TOUCH_SCALE_END = 3
const val TOUCH_PAN_START = 4
const val TOUCH_PAN_UPDATE = 5
const val TOUCH_PAN_END = 6

const val WHEEL_STEP = 120
const val WHEEL_DURATION = 50L
const val LONG_TAP_DELAY = 200L

// 定义InputManager的常量，以防止编译错误
private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

// 定义KeyboardMode的常量
private val LEGACY_MODE = KeyboardMode.Legacy.number
private val TRANSLATE_MODE = KeyboardMode.Translate.number
private val MAP_MODE = KeyboardMode.Map.number

// InputService类用于处理输入事件
class InputService : Service {

    companion object {
        var ctx: InputService? = null
        val isOpen: Boolean
            get() = ctx != null
    }

    private val logTag = "input service"
    private var leftIsDown = false
    private val touchPath = Path()
    private var lastTouchGestureStartTime = 0L
    private var mouseX = 0
    private var mouseY = 0
    private var timer = Timer()
    private var recentActionTask: TimerTask? = null
    // 100(tap timeout) + 400(long press timeout)
    private val longPressDuration = ViewConfiguration.getTapTimeout().toLong() + ViewConfiguration.getLongPressTimeout().toLong()

    private var isWaitingLongPress = false
    
    // 追踪事件状态，避免重复注入
    private var lastActionType = -1
    private var lastEventTime = 0L
    private var pendingEventRetry = false

    private var lastX = 0
    private var lastY = 0

    private lateinit var volumeController: VolumeController
    private var inputManager: InputManager? = null
    private lateinit var appContext: Context
    private lateinit var handler: Handler
    private lateinit var windowManager: WindowManager
    
    // 事件处理状态
    private var lastDownTime = 0L   // 上次DOWN事件的时间戳
    
    // 屏幕适配相关
    private var screenWidth = 0
    private var screenHeight = 0
    private var navBarHeight = 0
    private var statusBarHeight = 0
    private var screenDensity = 1.0f
    
    // 防抖动和优化相关
    private val NAV_BAR_CLICK_INTERVAL = 500L  // 导航栏点击间隔
    private val APP_ICON_CLICK_INTERVAL = 300L // 应用图标点击间隔
    private val KEYBOARD_INJECT_DELAY = 20L    // 键盘输入延迟
    private val CHINESE_CHAR_INJECT_DELAY = 50L // 中文输入延迟
    private val EVENT_RETRY_DELAY = 10L        // 事件重试延迟
    private val MAX_RETRY_COUNT = 3            // 最大重试次数
    
    // 提供公开的无参构造函数
    constructor() : super() {
        Log.d(logTag, "InputService created with default constructor")
    }
    
    // 提供带Context参数的公开构造函数
    constructor(context: Context) : super() {
        Log.d(logTag, "InputService created with context constructor")
        initializeWithContext(context)
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(logTag, "InputService onCreate called")
        
        // 如果还未初始化，则使用applicationContext初始化
        if (!::appContext.isInitialized) {
            initializeWithContext(applicationContext)
        }
    }

    private fun initializeWithContext(context: Context) {
        ctx = this
        appContext = context.applicationContext
        handler = Handler(Looper.getMainLooper())
        volumeController = VolumeController(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenMetrics(context)
        Log.d(logTag, "InputService initialized with INJECT_EVENTS permission")
        Log.d(logTag, "Screen metrics: ${screenWidth}x${screenHeight}, density: $screenDensity")
    }
    
    // 更新屏幕尺寸参数
    private fun updateScreenMetrics(context: Context) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        screenWidth = size.x
        screenHeight = size.y
        screenDensity = context.resources.displayMetrics.density
        
        // 估算导航栏和状态栏高度
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            navBarHeight = context.resources.getDimensionPixelSize(resourceId)
        } else {
            navBarHeight = (48 * screenDensity).toInt()
        }
        
        val statusBarResourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (statusBarResourceId > 0) {
            statusBarHeight = context.resources.getDimensionPixelSize(statusBarResourceId)
        } else {
            statusBarHeight = (24 * screenDensity).toInt()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 我们不需要绑定，所以返回null
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        disableSelf()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        val x = max(0, _x)
        val y = max(0, _y)

        // 计算实际坐标（适配不同屏幕分辨率）
        mouseX = adaptCoordinateX(x)
        mouseY = adaptCoordinateY(y)

        // 添加事件日志，便于调试
        // Log.d(logTag, "接收到鼠标输入事件: mask=$mask, x=$mouseX, y=$mouseY")

        if (mask == 0 || mask == LEFT_MOVE) {
            // 检查是否已有按下状态
            if (leftIsDown) {
                // 拖拽状态，持续更新拖拽路径
                injectMotionEvent(MotionEvent.ACTION_MOVE, mouseX.toFloat(), mouseY.toFloat())
            } else {
                // 正常移动，无额外处理
            }
            
            // 如果正在等待长按，但鼠标移动了较大距离，则取消长按
            if (isWaitingLongPress) {
                val delta = abs(lastX - mouseX) + abs(lastY - mouseY)
                if (delta > 8) {
                    isWaitingLongPress = false
                }
            }
            
            lastX = mouseX
            lastY = mouseY
            return
        }

        // left button down, was up
        if (mask == LEFT_DOWN) {
            // 防止短时间内重复触发DOWN事件
            val now = SystemClock.uptimeMillis()
            if (lastActionType == MotionEvent.ACTION_DOWN && 
                (now - lastEventTime) < 100) {
                // Log.d(logTag, "跳过重复的LEFT_DOWN事件")
                return
            }
            
            // 检查是否点击在导航栏区域，如果是则使用更长的防重复时间
            if (isNavigationBarArea(mouseX, mouseY) && 
                (now - lastEventTime) < NAV_BAR_CLICK_INTERVAL) {
                // Log.d(logTag, "导航栏区域点击防抖动")
                return
            }
            
            // 检查是否点击在应用图标区域
            if (isLikelyAppIconArea(mouseX, mouseY) && 
                (now - lastEventTime) < APP_ICON_CLICK_INTERVAL) {
                // Log.d(logTag, "应用图标区域点击防抖动")
                return
            }
            
            // 处理长按逻辑
            isWaitingLongPress = true
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (isWaitingLongPress) {
                        isWaitingLongPress = false
                    }
                }
            }, longPressDuration)

            leftIsDown = true
            // Touch down - 优化点击效率
            val result = injectMotionEvent(MotionEvent.ACTION_DOWN, mouseX.toFloat(), mouseY.toFloat())
            if (!result) {
                // 注入失败时进行重试
                retryInjectEvent(MotionEvent.ACTION_DOWN, mouseX.toFloat(), mouseY.toFloat())
            }
            return
        }

        // left up, was down
        if (mask == LEFT_UP) {
            // 防止短时间内重复触发UP事件
            val now = SystemClock.uptimeMillis()
            if (lastActionType == MotionEvent.ACTION_UP && 
                (now - lastEventTime) < 100) {
                // Log.d(logTag, "跳过重复的LEFT_UP事件")
                return
            }
            
            if (leftIsDown) {
                leftIsDown = false
                isWaitingLongPress = false
                
                // 优化导航栏区域的点击响应
                val special = isNavigationBarArea(mouseX, mouseY) || isLikelyAppIconArea(mouseX, mouseY)
                val result = injectMotionEvent(MotionEvent.ACTION_UP, mouseX.toFloat(), mouseY.toFloat(), special)
                
                if (!result) {
                    // UP事件必须注入成功，否则可能导致系统认为触摸仍在持续
                    retryInjectEvent(MotionEvent.ACTION_UP, mouseX.toFloat(), mouseY.toFloat(), true)
                }
                return
            }
        }

        if (mask == RIGHT_UP) {
            // Right click - 模拟长按操作
            injectLongPress(mouseX.toFloat(), mouseY.toFloat())
            return
        }

        if (mask == BACK_UP) {
            // Back button - 返回键
            injectKeyEvent(KeyEventAndroid.KEYCODE_BACK)
            return
        }

        // wheel button actions
        if (mask == WHEEL_BUTTON_DOWN) {
            timer.purge()
            recentActionTask = object : TimerTask() {
                override fun run() {
                    // Recent apps
                    injectKeyEvent(KeyEventAndroid.KEYCODE_APP_SWITCH)
                    recentActionTask = null
                }
            }
            timer.schedule(recentActionTask, LONG_TAP_DELAY)
        }

        if (mask == WHEEL_BUTTON_UP) {
            if (recentActionTask != null) {
                recentActionTask!!.cancel()
                // Home button
                injectKeyEvent(KeyEventAndroid.KEYCODE_HOME)
            }
            return
        }

        // Scroll actions - 优化滚动操作
        if (mask == WHEEL_DOWN) {
            injectScroll(mouseX.toFloat(), mouseY.toFloat(), 0f, -WHEEL_STEP.toFloat())
        }

        if (mask == WHEEL_UP) {
            injectScroll(mouseX.toFloat(), mouseY.toFloat(), 0f, WHEEL_STEP.toFloat())
        }
    }
    
    // 恢复原有的触摸输入方法，避免编译错误
    @RequiresApi(Build.VERSION_CODES.N)
    fun onTouchInput(mask: Int, x: Int, y: Int) {
        // 添加日志便于调试
        Log.d(logTag, "接收到触摸输入事件: mask=$mask, x=$x, y=$y")
        
        // 记录当前系统时间，用于时间间隔计算
        val currentTime = SystemClock.uptimeMillis()
        
        when (mask) {
            TOUCH_SCALE_START -> {
                // Handle pinch to zoom start
                lastX = x
                lastY = y
                Log.d(logTag, "TOUCH_SCALE_START: 初始化缩放开始点")
            }
            TOUCH_SCALE -> {
                // Handle pinch to zoom
                val deltaX = x - lastX
                val deltaY = y - lastY
                lastX = x
                lastY = y
                
                try {
                    // Simulate pinch gesture
                    val downTime = SystemClock.uptimeMillis()
                    val eventTime = SystemClock.uptimeMillis()
                    
                    // 添加调试日志
                    Log.d(logTag, "TOUCH_SCALE: 执行缩放, deltaX=$deltaX, deltaY=$deltaY")
                    
                    // This is a simplified implementation - in a real app you'd need to track multiple pointers
                    val properties = arrayOf(MotionEvent.PointerProperties(), MotionEvent.PointerProperties())
                    properties[0].id = 0
                    properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER
                    properties[1].id = 1
                    properties[1].toolType = MotionEvent.TOOL_TYPE_FINGER
                    
                    val pointerCoords = arrayOf(MotionEvent.PointerCoords(), MotionEvent.PointerCoords())
                    pointerCoords[0].x = x.toFloat() - deltaX
                    pointerCoords[0].y = y.toFloat() - deltaY
                    pointerCoords[0].pressure = 1f
                    pointerCoords[0].size = 1f
                    
                    pointerCoords[1].x = x.toFloat() + deltaX
                    pointerCoords[1].y = y.toFloat() + deltaY
                    pointerCoords[1].pressure = 1f
                    pointerCoords[1].size = 1f
                    
                    val event = MotionEvent.obtain(
                        downTime, eventTime,
                        MotionEvent.ACTION_MOVE, 2, properties,
                        pointerCoords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                    )
                    
                    injectEvent(event)
                    event.recycle()
                } catch (e: Exception) {
                    Log.e(logTag, "Error during touch scale: ${e.message}")
                }
            }
            TOUCH_SCALE_END -> {
                // Handle pinch to zoom end
                Log.d(logTag, "TOUCH_SCALE_END: 缩放手势结束")
            }
            TOUCH_PAN_START -> {
                // 增强防重复触发逻辑
                if (lastActionType == MotionEvent.ACTION_DOWN && 
                    (currentTime - lastEventTime) < 200) {
                    Log.d(logTag, "跳过重复的TOUCH_PAN_START事件，距上次: ${currentTime - lastEventTime}ms")
                    return
                }
                
                // 添加调试日志
                Log.d(logTag, "TOUCH_PAN_START: 开始平移手势 at ($x, $y)")
                
                // Handle pan start
                lastX = x
                lastY = y
                injectMotionEvent(MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
            }
            TOUCH_PAN_UPDATE -> {
                // Handle pan update
                // 添加调试日志
                if (lastX != 0 && lastY != 0) {
                    val deltaX = x - lastX
                    val deltaY = y - lastY
                    Log.d(logTag, "TOUCH_PAN_UPDATE: 平移更新, 移动: ($deltaX, $deltaY)")
                }
                
                injectMotionEvent(MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat())
                lastX = x
                lastY = y
            }
            TOUCH_PAN_END -> {
                // 增强防重复触发逻辑
                if (lastActionType == MotionEvent.ACTION_UP && 
                    (currentTime - lastEventTime) < 200) {
                    Log.d(logTag, "跳过重复的TOUCH_PAN_END事件，距上次: ${currentTime - lastEventTime}ms")
                    return
                }
                
                // 添加调试日志
                Log.d(logTag, "TOUCH_PAN_END: 平移手势结束 at ($x, $y)")
                
                // Handle pan end
                injectMotionEvent(MotionEvent.ACTION_UP, x.toFloat(), y.toFloat())
            }
        }
    }
    
    // 恢复原有的按键处理方法，避免编译错误
    fun onKeyEvent(input: ByteArray) {
        try {
            val keyEvent = KeyEvent.parseFrom(input)
            
            when (keyEvent.getMode().number) {
                LEGACY_MODE -> {
                    // 使用getChr()方法获取键码
                    val keyCode = keyEvent.getChr()
                    val down = keyEvent.getDown()
                    
                    // 处理文本输入
                    if (keyEvent.hasChr() && (down || keyEvent.getPress())) {
                        val chr = keyEvent.getChr()
                        if (chr != 0) {
                            sendText(String(Character.toChars(chr)))
                            return
                        }
                    }
                    
                    // 处理普通按键
                    if (down) {
                        injectKeyEvent(keyCode)
                    } else {
                        injectKeyEvent(keyCode)
                    }
                }
                TRANSLATE_MODE -> {
                    // 处理文本输入
                    if (keyEvent.hasSeq() && keyEvent.getSeq().isNotEmpty()) {
                        sendText(keyEvent.getSeq())
                        return
                    }
                    
                    // 处理普通按键
                    val keyCode = keyEvent.getChr()
                    if (keyEvent.getDown()) {
                        injectKeyEvent(keyCode)
                    } else {
                        injectKeyEvent(keyCode)
                    }
                }
                MAP_MODE -> {
                    // 处理文本输入
                    if (keyEvent.hasSeq() && keyEvent.getSeq().isNotEmpty()) {
                        sendText(keyEvent.getSeq())
                        return
                    }
                    
                    // 处理普通按键
                    val keyCode = keyEvent.getChr()
                    if (keyEvent.getDown()) {
                        injectKeyEvent(keyCode)
                    } else {
                        injectKeyEvent(keyCode)
                    }
                }
                else -> {
                    // Unsupported mode
                    Log.e(logTag, "Unsupported keyboard mode: ${keyEvent.getMode()}")
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Failed to parse key event: ${e.message}")
        }
    }

    // 判断是否在导航栏区域
    private fun isNavigationBarArea(x: Int, y: Int): Boolean {
        // 通常导航栏在屏幕底部
        return y > (screenHeight - navBarHeight)
    }
    
    // 判断是否在状态栏区域
    private fun isStatusBarArea(x: Int, y: Int): Boolean {
        return y < statusBarHeight
    }
    
    // 判断是否可能是应用图标区域（通常在主屏幕的底部或顶部边缘）
    private fun isLikelyAppIconArea(x: Int, y: Int): Boolean {
        val iconSize = (80 * screenDensity).toInt()
        // 检查是否在屏幕底部
        val isBottomArea = y > (screenHeight - iconSize - navBarHeight) && y < (screenHeight - navBarHeight)
        // 检查是否在屏幕顶部但非状态栏
        val isTopArea = y > statusBarHeight && y < (statusBarHeight + iconSize)
        // 检查是否在屏幕左右边缘
        val isEdgeArea = x < iconSize || x > (screenWidth - iconSize)
        
        return isBottomArea || (isTopArea && isEdgeArea)
    }
    
    // 判断是否可能是输入法键盘区域
    private fun isKeyboardArea(y: Int): Boolean {
        // 输入法通常在屏幕下半部分
        return y > (screenHeight / 2)
    }
    
    // 坐标适配 - X坐标
    private fun adaptCoordinateX(x: Int): Int {
        return (x * screenWidth / SCREEN_INFO.width)
    }
    
    // 坐标适配 - Y坐标
    private fun adaptCoordinateY(y: Int): Int {
        return (y * screenHeight / SCREEN_INFO.height)
    }
    
    // 优化的事件注入，添加了重试机制
    private fun retryInjectEvent(action: Int, x: Float, y: Float, isImportant: Boolean = false) {
        var retryCount = 0
        val maxRetries = if (isImportant) MAX_RETRY_COUNT else 1
        
        val retryTask = object : Runnable {
            override fun run() {
                if (retryCount < maxRetries) {
                    val result = injectMotionEvent(action, x, y)
                    if (!result && retryCount < maxRetries - 1) {
                        retryCount++
                        handler.postDelayed(this, EVENT_RETRY_DELAY)
                    }
                }
            }
        }
        
        handler.postDelayed(retryTask, EVENT_RETRY_DELAY)
    }
    
    // 注入事件的辅助方法
    private fun injectEvent(event: InputEvent): Boolean {
        try {
            return inputManager?.injectInputEvent(event, INJECT_INPUT_EVENT_MODE_ASYNC) ?: false
        } catch (e: Exception) {
            Log.e(logTag, "Error injecting event: ${e.message}")
            return false
        }
    }

    // 优化过的触摸事件注入方法
    private fun injectMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        isSpecialArea: Boolean = false
    ): Boolean {
        if (inputManager == null) {
            return false
        }

        val now = SystemClock.uptimeMillis()
        val source = InputDevice.SOURCE_TOUCHSCREEN
        
        // 针对特殊区域的点击进行优化
        val downTime = if (action == MotionEvent.ACTION_DOWN) now else lastDownTime
        
        // 创建触摸事件
        val event = MotionEvent.obtain(
            downTime,
            now,
            action,
            x, y,
            1.0f, // 压力
            1.0f, // 大小
            0, // 元状态
            1.0f, // X精度
            1.0f, // Y精度
            0, // 设备ID
            0  // 边缘标志
        )
        
        event.source = source

        try {
            val result = injectEvent(event)
            if (result) {
                lastEventTime = now
                lastActionType = action
                if (action == MotionEvent.ACTION_DOWN) {
                    lastDownTime = now
                }
            }
            return result
        } catch (e: Exception) {
            Log.e(logTag, "Failed to inject motion event", e)
            return false
        } finally {
            event.recycle()
        }
    }

    private fun injectLongPress(x: Float, y: Float) {
        // 长按操作优化
        val now = SystemClock.uptimeMillis()
        leftIsDown = true
        
        // DOWN事件
        injectMotionEvent(MotionEvent.ACTION_DOWN, x, y)
        
        // 延迟发送UP事件，模拟长按
        handler.postDelayed({
            // UP事件
            injectMotionEvent(MotionEvent.ACTION_UP, x, y)
            leftIsDown = false
        }, longPressDuration)
    }

    private fun injectScroll(x: Float, y: Float, hScroll: Float, vScroll: Float) {
        // 滚动操作优化
        val source = InputDevice.SOURCE_MOUSE
        val now = SystemClock.uptimeMillis()
        
        // 构建滚动事件
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL,
            1, // 指针数量
            x, y,
            0.0f, 0.0f, 0, 0.0f, 0.0f, 0, 0
        )
        
        event.source = source
        
        try {
            injectEvent(event)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to inject scroll event", e)
        } finally {
            event.recycle()
        }
    }

    private fun injectKeyEvent(keyCode: Int) {
        if (inputManager == null) {
            return
        }

        // 优化按键输入
        val now = SystemClock.uptimeMillis()
        val source = InputDevice.SOURCE_KEYBOARD

        // DOWN事件
        val downEvent = KeyEventAndroid(
            now, now, KeyEventAndroid.ACTION_DOWN, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            source
        )

        try {
            injectEvent(downEvent)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to inject key DOWN event", e)
        }

        // UP事件
        val upEvent = KeyEventAndroid(
            now, now, KeyEventAndroid.ACTION_UP, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            source
        )

        try {
            injectEvent(upEvent)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to inject key UP event", e)
        }
    }

    /**
     * 优化中文输入和特殊字符处理
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun sendText(text: String) {
        if (text.isEmpty()) {
            return
        }
        
        thread {
            for (ch in text) {
                sendChar(ch)
                
                // 为中文字符添加额外延迟，提高输入稳定性
                if (Character.isIdeographic(ch.code)) {
                    Thread.sleep(CHINESE_CHAR_INJECT_DELAY)
                } else {
                    Thread.sleep(KEYBOARD_INJECT_DELAY)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun sendChar(ch: Char) {
        try {
            val now = SystemClock.uptimeMillis()
            val source = InputDevice.SOURCE_KEYBOARD
            
            // 优化键盘字符输入
            val deadChar = KeyEventAndroid.getDeadChar(ch.code, 0)
            if (deadChar == 0) {
                // 普通字符输入
                val charVal = ch.code
                
                // DOWN事件
                val downEvent = KeyEventAndroid(
                    now, now, KeyEventAndroid.ACTION_DOWN, KeyEventAndroid.KEYCODE_UNKNOWN, 1,
                    0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEventAndroid.FLAG_SOFT_KEYBOARD,
                    source
                )
                
                injectEvent(downEvent)
                
                // UP事件
                val upEvent = KeyEventAndroid(
                    now, now, KeyEventAndroid.ACTION_UP, KeyEventAndroid.KEYCODE_UNKNOWN, 0,
                    0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEventAndroid.FLAG_SOFT_KEYBOARD,
                    source
                )
                
                injectEvent(upEvent)
            } else {
                // 特殊字符处理
                Log.d(logTag, "Special character: ${ch.code}")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Failed to send character: $ch", e)
        }
    }

    // 公开方法，允许外部调用
    fun disableSelf() {
        ctx = null
        timer.cancel()
    }

    /**
     * 音量控制类
     */
    class VolumeController(private val audioManager: AudioManager) {
        fun changeMicVolume(beIntensity: Int, maxValue: Int) {
            val volume = (beIntensity.toFloat() / maxValue * audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
