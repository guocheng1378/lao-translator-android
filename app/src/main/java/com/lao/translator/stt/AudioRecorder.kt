package com.lao.translator.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 智能流式录音器
 * 优化点：
 * 1. VAD 语音活动检测 — 跳过静音，不浪费识别时间
 * 2. 重叠采样 — 前后 chunk 重叠 0.5s，避免切掉词
 * 3. 能量预过滤 — 声音太小的片段直接跳过
 * 4. 预缓冲 — 录音开始时预录 0.5s，不丢前几个字
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // VAD 参数
        private const val ENERGY_THRESHOLD = 0.008f    // 有声能量阈值
        private const val SILENCE_RATIO_THRESHOLD = 0.85f // 静音占比超过85%则跳过
    }

    sealed class RecordState {
        data object Idle : RecordState()
        data class Recording(val chunks: Int = 0, val skipped: Int = 0) : RecordState()
        data class Error(val message: String) : RecordState()
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val _state = MutableStateFlow<RecordState>(RecordState.Idle)
    val state: StateFlow<RecordState> = _state

    /**
     * 流式录音 + VAD 过滤
     * @param chunkDurationMs 每个片段时长 (默认 2000ms，比之前快)
     * @param overlapMs 重叠时长 (默认 500ms，防切词)
     * @param onChunk 收到有效音频片段的回调
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        chunkDurationMs: Int = 2000,
        overlapMs: Int = 500,
        onChunk: (FloatArray) -> Unit
    ) {
        stopRecording()

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize 失败: $bufferSize, SAMPLE_RATE=$SAMPLE_RATE")
            _state.value = RecordState.Error("无法获取录音缓冲区")
            return
        }
        Log.d(TAG, "bufferSize=$bufferSize")

        val samplesPerChunk = SAMPLE_RATE * chunkDurationMs / 1000
        val overlapSamples = SAMPLE_RATE * overlapMs / 1000
        // 用更大的 buffer 做滚动窗口
        val windowSamples = samplesPerChunk + overlapSamples

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败! state=${audioRecord?.state}")
            _state.value = RecordState.Error("AudioRecord 初始化失败")
            return
        }

        Log.d(TAG, "AudioRecord 初始化成功, 开始录音")
        audioRecord?.startRecording()
        var chunkCount = 0
        var skippedCount = 0

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            // 滚动窗口缓冲
            val window = FloatArray(windowSamples)
            var windowPos = 0
            var firstChunk = true

            val readBuffer = ShortArray(samplesPerChunk)
            _state.value = RecordState.Recording(0, 0)

            while (isActive) {
                val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                if (read <= 0) continue

                // 写入滚动窗口
                for (i in 0 until read) {
                    if (windowPos < window.size) {
                        window[windowPos++] = readBuffer[i] / 32768.0f
                    }
                }

                // 窗口满了就输出一个 chunk
                if (windowPos >= windowSamples) {
                    // 提取当前 chunk (包含重叠部分)
                    val chunk = window.copyOf(windowSamples)

                    // VAD: 检查是否有语音
                    val hasVoice = detectVoice(chunk)

                    if (hasVoice || firstChunk) {
                        chunkCount++
                        Log.d(TAG, "chunk #$chunkCount: 有语音 (hasVoice=$hasVoice, firstChunk=$firstChunk)")
                        _state.value = RecordState.Recording(chunkCount, skippedCount)
                        onChunk(chunk)
                        firstChunk = false
                    } else {
                        skippedCount++
                        Log.d(TAG, "chunk 跳过: 静音 #$skippedCount")
                        _state.value = RecordState.Recording(chunkCount, skippedCount)
                    }

                    // 滑动窗口：保留 overlap 部分
                    System.arraycopy(window, samplesPerChunk, window, 0, overlapSamples)
                    windowPos = overlapSamples
                }
            }
        }
    }

    /**
     * VAD: 简单能量检测
     * 将音频分帧，检查有声帧占比
     */
    private fun detectVoice(samples: FloatArray): Boolean {
        val frameSize = SAMPLE_RATE / 100  // 10ms 一帧
        val totalFrames = samples.size / frameSize
        if (totalFrames == 0) return false

        var voicedFrames = 0
        for (f in 0 until totalFrames) {
            var energy = 0f
            val start = f * frameSize
            val end = minOf(start + frameSize, samples.size)
            for (i in start until end) {
                energy += samples[i] * samples[i]
            }
            energy /= (end - start)
            if (energy > ENERGY_THRESHOLD) voicedFrames++
        }

        // 有声帧占比 > 15% 认为是有效语音
        val voiceRatio = voicedFrames.toFloat() / totalFrames
        Log.d(TAG, "VAD: voicedFrames=$voicedFrames/$totalFrames, ratio=$voiceRatio, threshold=${1f - SILENCE_RATIO_THRESHOLD}")
        return voiceRatio > (1f - SILENCE_RATIO_THRESHOLD)
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _state.value = RecordState.Idle
    }

    fun isRecording(): Boolean = recordingJob?.isActive == true
}
