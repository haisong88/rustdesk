package com.carriez.flutter_hbb

import ffi.FFI

import android.Manifest
import android.content.Context
import android.media.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_FLOAT //  ENCODING_OPUS need API 30
const val AUDIO_SAMPLE_RATE = 48000
const val AUDIO_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO

// 添加新的常量
const val SAMPLE_RATE = 48000

class AudioRecordHandle(private var context: Context, private var isVideoStart: ()->Boolean, private var isAudioStart: ()->Boolean) {
    private val logTag = "LOG_AUDIO_RECORD_HANDLE"

    private var audioRecorder: AudioRecord? = null
    private var audioReader: AudioReader? = null
    private var minBufferSize = 0
    private var audioRecordStat = false
    private var audioThread: Thread? = null
    private var usesSystemPermissions = false
    
    // 添加缺少的变量
    private var audioRecord: AudioRecord? = null
    private var audioBufferSize = 0
    val isRecording = AtomicBoolean(false)
    
    // 系统级权限常量字符串
    companion object {
        const val PERMISSION_CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
        const val PERMISSION_READ_FRAME_BUFFER = "android.permission.READ_FRAME_BUFFER"
        const val PERMISSION_ACCESS_SURFACE_FLINGER = "android.permission.ACCESS_SURFACE_FLINGER"
    }

    fun createAudioRecorder(isVoipEnabled: Boolean, mediaProjection: Any?): Boolean {
        Log.d(logTag, "create AudioRecorder, isVoipEnabled: $isVoipEnabled")
        if (isRecording.get()) {
            Log.d(logTag, "AudioRecorder is recording")
            return true
        }
        
        // 使用系统级权限，无需MediaProjection
        try {
            val audioSource =
                if (isVoipEnabled) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.DEFAULT

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuffSize = 2 * AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            // 在定制系统中始终具有录音权限
            // 使用普通的AudioRecord API，但具有系统级权限
            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                Log.d(logTag, "createAudioRecorder failed to initialize AudioRecord")
                return false
            }
            
            // 创建成功
            Log.d(logTag, "createAudioRecorder success with SYSTEM_LEVEL permission")
            
            // 同时设置旧变量和新变量以保持兼容性
            this.audioBufferSize = minBuffSize
            this.minBufferSize = minBuffSize
            this.audioRecorder = audioRecord
            this.audioReader = AudioReader(minBuffSize, 16)
            return true
        } catch (e: Exception) {
            Log.e(logTag, "createAudioRecorder error: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun checkAudioReader() {
        if (audioReader != null && minBufferSize != 0) {
            return
        }
        // read f32 to byte , length * 4
        minBufferSize = 2 * 4 * AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_MASK,
            AUDIO_ENCODING
        )
        if (minBufferSize == 0) {
            Log.d(logTag, "get min buffer size fail!")
            return
        }
        audioReader = AudioReader(minBufferSize, 4)
        Log.d(logTag, "init audioData len:$minBufferSize")
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun startAudioRecorder() {
        checkAudioReader()
        if (audioReader != null && audioRecorder != null && minBufferSize != 0) {
            try {
                FFI.setFrameRawEnable("audio", true)
                audioRecorder!!.startRecording()
                audioRecordStat = true
                isRecording.set(true)
                audioThread = thread {
                    while (audioRecordStat) {
                        audioReader!!.readSync(audioRecorder!!)?.let {
                            FFI.onAudioFrameUpdate(it)
                        }
                    }
                    // let's release here rather than onDestroy to avoid threading issue
                    audioRecorder?.release()
                    audioRecorder = null
                    audioRecord = null
                    minBufferSize = 0
                    isRecording.set(false)
                    FFI.setFrameRawEnable("audio", false)
                    Log.d(logTag, "Exit audio thread")
                }
            } catch (e: Exception) {
                Log.d(logTag, "startAudioRecorder fail:$e")
            }
        } else {
            Log.d(logTag, "startAudioRecorder fail")
        }
    }

    fun onVoiceCallStarted(mediaProjection: MediaProjection?): Boolean {
        if (!isSupportVoiceCall()) {
            return false
        }
        // No need to check if video or audio is started here.
        if (!switchToVoiceCall(mediaProjection)) {
            return false
        }
        return true
    }

    fun onVoiceCallClosed(mediaProjection: MediaProjection?): Boolean {
        // Return true if not supported, because is was not started.
        if (!isSupportVoiceCall()) {
            return true
        }
        if (isVideoStart()) {
            switchOutVoiceCall(mediaProjection)
        }
        tryReleaseAudio()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun switchToVoiceCall(mediaProjection: MediaProjection?): Boolean {
        audioRecorder?.let {
            if (it.getAudioSource() == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                return true
            }
        }
        audioRecordStat = false
        audioThread?.join()
        audioThread = null

        if (!createAudioRecorder(true, mediaProjection)) {
            Log.e(logTag, "createAudioRecorder fail")
            return false
        }
        startAudioRecorder()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun switchOutVoiceCall(mediaProjection: MediaProjection?): Boolean {
        audioRecorder?.let {
            if (it.getAudioSource() != MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                return true
            }
        }
        audioRecordStat = false
        audioThread?.join()

        if (!createAudioRecorder(false, mediaProjection)) {
            Log.e(logTag, "createAudioRecorder fail")
            return false
        }
        startAudioRecorder()
        return true
    }

    fun tryReleaseAudio() {
        if (isAudioStart() || isVideoStart()) {
            return
        }
        audioRecordStat = false
        isRecording.set(false)
        audioThread?.join()
        audioThread = null
    }

    fun destroy() {
        Log.d(logTag, "destroy audio record handle")

        audioRecordStat = false
        isRecording.set(false)
        audioThread?.join()
    }
    
    // 判断是否支持语音通话的辅助方法
    private fun isSupportVoiceCall(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }
}
