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
import androidx.annotation.RequiresApi
import java.util.*
import java.lang.Character
import kotlin.math.abs
import kotlin.math.max
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
    
    // 事件处理状态
    private var lastDownTime = 0L   // 上次DOWN事件的时间戳
    
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
        Log.d(logTag, "InputService initialized with INJECT_EVENTS permission")
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

        // 添加事件日志，便于调试
        Log.d(logTag, "接收到鼠标输入事件: mask=$mask, x=$x, y=$y")

        if (mask == 0 || mask == LEFT_MOVE) {
            val oldX = mouseX
            val oldY = mouseY
            mouseX = x * SCREEN_INFO.scale
            mouseY = y * SCREEN_INFO.scale
            if (isWaitingLongPress) {
                val delta = abs(oldX - mouseX) + abs(oldY - mouseY)
                Log.d(logTag,"delta:$delta")
                if (delta > 8) {
                    isWaitingLongPress = false
                }
            }
            
            // Move mouse pointer
            injectMotionEvent(MotionEvent.ACTION_MOVE, mouseX.toFloat(), mouseY.toFloat())
        }

        // left button down, was up
        if (mask == LEFT_DOWN) {
            // 防止短时间内重复触发DOWN事件
            if (lastActionType == MotionEvent.ACTION_DOWN && 
                (SystemClock.uptimeMillis() - lastEventTime) < 100) {
                Log.d(logTag, "跳过重复的LEFT_DOWN事件")
                return
            }
            
            isWaitingLongPress = true
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (isWaitingLongPress) {
                        isWaitingLongPress = false
                        // Long press
                        injectMotionEvent(MotionEvent.ACTION_DOWN, mouseX.toFloat(), mouseY.toFloat(), true)
                    }
                }
            }, longPressDuration)

            leftIsDown = true
            // Touch down
            injectMotionEvent(MotionEvent.ACTION_DOWN, mouseX.toFloat(), mouseY.toFloat())
            return
        }

        // left down, was down
        if (leftIsDown) {
            // Continue touch/drag
            injectMotionEvent(MotionEvent.ACTION_MOVE, mouseX.toFloat(), mouseY.toFloat())
        }

        // left up, was down
        if (mask == LEFT_UP) {
            // 防止短时间内重复触发UP事件
            if (lastActionType == MotionEvent.ACTION_UP && 
                (SystemClock.uptimeMillis() - lastEventTime) < 100) {
                Log.d(logTag, "跳过重复的LEFT_UP事件")
                return
            }
            
            if (leftIsDown) {
                leftIsDown = false
                isWaitingLongPress = false
                // Touch up
                injectMotionEvent(MotionEvent.ACTION_UP, mouseX.toFloat(), mouseY.toFloat())
                return
            }
        }

        if (mask == RIGHT_UP) {
            // Right click - simulate long press
            injectLongPress(mouseX.toFloat(), mouseY.toFloat())
            return
        }

        if (mask == BACK_UP) {
            // Back button
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

        // Scroll actions
        if (mask == WHEEL_DOWN) {
            injectScroll(mouseX.toFloat(), mouseY.toFloat(), 0f, -WHEEL_STEP.toFloat())
        }

        if (mask == WHEEL_UP) {
            injectScroll(mouseX.toFloat(), mouseY.toFloat(), 0f, WHEEL_STEP.toFloat())
        }
    }

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
                            injectText(String(Character.toChars(chr)))
                            return
                        }
                    }
                    
                    // 处理普通按键
                    if (down) {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_DOWN)
                    } else {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                    }
                }
                TRANSLATE_MODE -> {
                    // 处理文本输入
                    if (keyEvent.hasSeq() && keyEvent.getSeq().isNotEmpty()) {
                        injectText(keyEvent.getSeq())
                        return
                    }
                    
                    // 处理普通按键
                    // 使用getChr()方法获取键码
                    val keyCode = keyEvent.getChr()
                    if (keyEvent.getDown()) {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_DOWN)
                    } else {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                    }
                }
                MAP_MODE -> {
                    // 处理文本输入
                    if (keyEvent.hasSeq() && keyEvent.getSeq().isNotEmpty()) {
                        injectText(keyEvent.getSeq())
                        return
                    }
                    
                    // 处理普通按键
                    // 使用getChr()方法获取键码
                    val keyCode = keyEvent.getChr()
                    if (keyEvent.getDown()) {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_DOWN)
                    } else {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
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

    // Helper methods for event injection
    
    private fun injectEvent(event: InputEvent): Boolean {
        try {
            // 由于无法使用 InputManager.injectInputEvent 方法，我们使用反射来调用它
            inputManager?.let { manager ->
                val method = InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
                return (method.invoke(manager, event, INJECT_INPUT_EVENT_MODE_ASYNC) as Boolean)
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error injecting event via reflection: ${e.message}")
        }
        return false
    }
    
    private fun injectMotionEvent(action: Int, x: Float, y: Float, isLongPress: Boolean = false): Boolean {
        try {
            // 添加更多详细的日志信息
            val actionName = when (action) {
                MotionEvent.ACTION_DOWN -> "ACTION_DOWN"
                MotionEvent.ACTION_UP -> "ACTION_UP"
                MotionEvent.ACTION_MOVE -> "ACTION_MOVE"
                else -> "未知动作($action)"
            }
            
            Log.d(logTag, "注入动作事件: $actionName at ($x, $y), isLongPress=$isLongPress")
            
            // 检查事件防重复（相同类型的事件在短时间内只处理一次）
            val now = SystemClock.uptimeMillis()
            
            // 对于ACTION_DOWN和ACTION_UP做更严格的检查，因为这些可能导致重复点击问题
            if (!isLongPress && 
                (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) && 
                action == lastActionType && 
                (now - lastEventTime) < 250) {
                Log.d(logTag, "跳过重复的动作事件: $actionName, 距上次: ${now - lastEventTime}ms")
                return true
            }
            
            // 对于MOVE事件，我们允许更频繁的注入，但仍然防止过于频繁
            if (action == MotionEvent.ACTION_MOVE && 
                action == lastActionType && 
                (now - lastEventTime) < 16) { // 约60fps
                // 不记录日志，避免日志过多
                return true
            }
            
            lastActionType = action
            lastEventTime = now
            
            val downTime = if (action == MotionEvent.ACTION_DOWN) now else lastDownTime
            if (action == MotionEvent.ACTION_DOWN) {
                lastDownTime = downTime
            }
            
            val eventTime = now
            
            // 创建MotionEvent
            val event = MotionEvent.obtain(
                downTime, eventTime, action, x, y, 0
            )
            
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            
            // 注入事件
            val result = injectEvent(event)
            event.recycle()
            
            if (!result) {
                Log.e(logTag, "注入动作事件失败: $actionName")
                
                // 如果是UP事件，我们需要确保它被发送，尝试再次发送
                if (action == MotionEvent.ACTION_UP) {
                    Log.d(logTag, "尝试再次发送UP事件")
                    
                    // 短暂延迟后再次尝试
                    Thread.sleep(10)
                    
                    val retryEvent = MotionEvent.obtain(
                        downTime, SystemClock.uptimeMillis(), action, x, y, 0
                    )
                    retryEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                    val retryResult = injectEvent(retryEvent)
                    retryEvent.recycle()
                    
                    return retryResult
                }
            }
            
            return result
            
        } catch (e: Exception) {
            Log.e(logTag, "Error injecting motion event: ${e.message}")
            return false
        }
    }
    
    private fun injectLongPress(x: Float, y: Float): Boolean {
        // Simulate a long press by sending down, waiting, then up
        val downResult = injectMotionEvent(MotionEvent.ACTION_DOWN, x, y)
        
        handler.postDelayed({
            injectMotionEvent(MotionEvent.ACTION_UP, x, y)
        }, longPressDuration)
        
        return downResult
    }
    
    private fun injectScroll(x: Float, y: Float, hScroll: Float, vScroll: Float): Boolean {
        try {
            val downTime = SystemClock.uptimeMillis()
            val eventTime = SystemClock.uptimeMillis()
            
            val properties = arrayOf(MotionEvent.PointerProperties().apply { 
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE 
            })
            
            val coords = arrayOf(MotionEvent.PointerCoords().apply { 
                this.x = x
                this.y = y
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            })
            
            val event = MotionEvent.obtain(
                downTime, eventTime, MotionEvent.ACTION_SCROLL, 1, 
                properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            
            val result = injectEvent(event)
            event.recycle()
            return result
        } catch (e: Exception) {
            Log.e(logTag, "Error injecting scroll event: ${e.message}")
            return false
        }
    }
    
    private fun injectKeyEvent(keyCode: Int, action: Int = KeyEventAndroid.ACTION_DOWN): Boolean {
        try {
            val downTime = SystemClock.uptimeMillis()
            val eventTime = SystemClock.uptimeMillis()
            
            val event = KeyEventAndroid(
                downTime, eventTime, action, keyCode, 0, 0,
                KeyEventAndroid.KEYCODE_UNKNOWN, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
            
            val result = injectEvent(event)
            
            // If this is a key down, automatically send a key up after a short delay
            if (action == KeyEventAndroid.ACTION_DOWN) {
                handler.postDelayed({
                    injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                }, 10)
            }
            
            return result
        } catch (e: Exception) {
            Log.e(logTag, "Error injecting key event: ${e.message}")
            return false
        }
    }
    
    private fun injectText(text: String): Boolean {
        if (text.isEmpty()) return false
        
        Log.d(logTag, "Injecting text: $text")
        
        // 在后台线程中处理文本注入，避免阻塞主线程
        thread(start = true) {
            try {
                // 使用KeyCharacterMap将文本转换为一系列KeyEvent
                val charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                
                for (char in text) {
                    try {
                        val events = charMap.getEvents(charArrayOf(char))
                        if (events != null) {
                            for (event in events) {
                                injectEvent(event)
                                // 添加短暂延迟以确保事件按顺序处理
                                Thread.sleep(5)
                            }
                        } else {
                            // 如果无法获取事件，尝试使用Unicode输入
                            val unicodeEvents = getEventsForChar(char)
                            for (event in unicodeEvents) {
                                injectEvent(event)
                                Thread.sleep(5)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Error processing character '$char': ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "Error in text injection: ${e.message}")
            }
        }
        
        return true
    }
    
    private fun getEventsForChar(char: Char): Array<KeyEventAndroid> {
        val code = char.code
        
        // 对于基本ASCII字符，我们可以直接映射
        if (code < 128) {
            val keyCode = when (char) {
                in 'a'..'z' -> KeyEventAndroid.KEYCODE_A + (char - 'a')
                in 'A'..'Z' -> KeyEventAndroid.KEYCODE_A + (char - 'A')
                in '0'..'9' -> KeyEventAndroid.KEYCODE_0 + (char - '0')
                ' ' -> KeyEventAndroid.KEYCODE_SPACE
                '.' -> KeyEventAndroid.KEYCODE_PERIOD
                ',' -> KeyEventAndroid.KEYCODE_COMMA
                '\n' -> KeyEventAndroid.KEYCODE_ENTER
                else -> KeyEventAndroid.KEYCODE_UNKNOWN
            }
            
            if (keyCode != KeyEventAndroid.KEYCODE_UNKNOWN) {
                val time = SystemClock.uptimeMillis()
                return arrayOf(
                    KeyEventAndroid(time, time, KeyEventAndroid.ACTION_DOWN, keyCode, 0, 0),
                    KeyEventAndroid(time, time, KeyEventAndroid.ACTION_UP, keyCode, 0, 0)
                )
            }
        }
        
        // 只使用简单的方式，避免使用Builder
        val time = SystemClock.uptimeMillis()
        return createFallbackKeyEvents(code)
    }
    
    private fun createFallbackKeyEvents(code: Int): Array<KeyEventAndroid> {
        val time = SystemClock.uptimeMillis()
        return try {
            arrayOf(
                KeyEventAndroid(time, time, KeyEventAndroid.ACTION_DOWN, 
                              KeyEventAndroid.KEYCODE_UNKNOWN, 1, 0, 0, code, 0),
                KeyEventAndroid(time, time, KeyEventAndroid.ACTION_UP, 
                              KeyEventAndroid.KEYCODE_UNKNOWN, 0, 0, 0, 0, 0)
            )
        } catch (e: Exception) {
            Log.e(logTag, "Error creating fallback key events: ${e.message}")
            // 最后的备选方案 - 只发送基本按键
            arrayOf(
                KeyEventAndroid(time, time, KeyEventAndroid.ACTION_DOWN, 
                              KeyEventAndroid.KEYCODE_UNKNOWN, 0, 0),
                KeyEventAndroid(time, time, KeyEventAndroid.ACTION_UP, 
                              KeyEventAndroid.KEYCODE_UNKNOWN, 0, 0)
            )
        }
    }

    fun disableSelf() {
        try {
            handler.removeCallbacksAndMessages(null)
            timer.cancel()
            ctx = null
        } catch (e: Exception) {
            Log.e(logTag, "Error in disableSelf: ${e.message}")
        }
    }
}
