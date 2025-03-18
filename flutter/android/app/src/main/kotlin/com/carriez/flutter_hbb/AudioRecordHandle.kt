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

const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_FLOAT //  ENCODING_OPUS need API 30
const val AUDIO_SAMPLE_RATE = 48000
const val AUDIO_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO

class AudioRecordHandle(private var context: Context, private var isVideoStart: ()->Boolean, private var isAudioStart: ()->Boolean) {
    private val logTag = "LOG_AUDIO_RECORD_HANDLE"

    private var audioRecorder: AudioRecord? = null
    private var audioReader: AudioReader? = null
    private var minBufferSize = 0
    private var audioRecordStat = false
    private var audioThread: Thread? = null
    private var usesSystemPermissions = false
    
    // 系统级权限常量字符串
    companion object {
        const val PERMISSION_CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
        const val PERMISSION_READ_FRAME_BUFFER = "android.permission.READ_FRAME_BUFFER"
        const val PERMISSION_ACCESS_SURFACE_FLINGER = "android.permission.ACCESS_SURFACE_FLINGER"
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun createAudioRecorder(inVoiceCall: Boolean, mediaProjection: MediaProjection?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(logTag, "createAudioRecorder failed, no RECORD_AUDIO permission")
            return false
        }

        // 检查系统级权限
        val captureVideoPermission = context.checkCallingOrSelfPermission(PERMISSION_CAPTURE_VIDEO_OUTPUT)
        val readFrameBufferPermission = context.checkCallingOrSelfPermission(PERMISSION_READ_FRAME_BUFFER)
        val accessSurfaceFlingerPermission = context.checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER)
        usesSystemPermissions = captureVideoPermission == PackageManager.PERMISSION_GRANTED && 
                                readFrameBufferPermission == PackageManager.PERMISSION_GRANTED &&
                                accessSurfaceFlingerPermission == PackageManager.PERMISSION_GRANTED
        
        var builder = AudioRecord.Builder()
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AUDIO_ENCODING)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AUDIO_CHANNEL_MASK).build()
        );
        if (inVoiceCall) {
            builder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        } else if (usesSystemPermissions) {
            // 使用系统权限时，直接设置为系统音频源
            Log.d(logTag, "Using system permissions for audio capture")
            builder.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
        } else if (mediaProjection != null) {
            // 兼容旧的MediaProjection方式
            var apcc = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_ALARM)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            builder.setAudioPlaybackCaptureConfig(apcc);
        } else {
            Log.d(logTag, "createAudioRecorder failed, no mediaProjection and no system permissions")
            return false
        }
        
        audioRecorder = builder.build()
        Log.d(logTag, "createAudioRecorder done,minBufferSize:$minBufferSize")
        return true
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
                audioThread = thread {
                    while (audioRecordStat) {
                        audioReader!!.readSync(audioRecorder!!)?.let {
                            FFI.onAudioFrameUpdate(it)
                        }
                    }
                    // let's release here rather than onDestroy to avoid threading issue
                    audioRecorder?.release()
                    audioRecorder = null
                    minBufferSize = 0
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
        audioThread?.join()
        audioThread = null
    }

    fun destroy() {
        Log.d(logTag, "destroy audio record handle")

        audioRecordStat = false
        audioThread?.join()
    }
    
    // 判断是否支持语音通话的辅助方法
    private fun isSupportVoiceCall(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }
}
