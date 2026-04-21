package com.lao.translator.stt

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ✅ 改版：环形缓冲区 + VAD 语音端点检测
 *
 * 不再固定 2.5s 切片，而是：
 * 1. 持续录音到环形缓冲区
 * 2. 用振幅检测语音开始（类似 RTranslator 的方案）
 * 3. 检测到静音超时（人说完了）→ 整段送入 Whisper
 * 4. 最大录音时长 10 秒
 *
 * 参考：github.com/niedev/RTranslator Recorder.java
 */
class AudioRecorder(private val context: Context? = null) {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // ====== VAD 参数（参考 RTranslator）======
        // 振幅阈值 - 低于此值认为是静音
        private const val AMPLITUDE_THRESHOLD = 0.001f       // RTranslator 用 2000/32768 ≈ 0.06，我们保守一点
        // 语音开始：连续 N 帧超过阈值
        private const val VOICE_START_FRAMES = 3             // 连续3帧 (~150ms) 算语音开始
        // 语音结束：静音超过此时间就切分
        private const val SILENCE_TIMEOUT_MS = 1200L         // 静音 1.2s 认为说完了一句话
        // 最大单段录音时长
        private const val MAX_SEGMENT_MS = 10_000L           // 最长 10 秒
        // 语音前保留的上下文时长
        private const val PRE_VOICE_MS = 800L                // 语音开始前保留 800ms（捕获发音开头）
        // 每次读取的帧大小
        private const val FRAME_DURATION_MS = 50             // 每 50ms 检测一次
        private const val FRAME_SIZE = SAMPLE_RATE * FRAME_DURATION_MS / 1000  // 800 samples

        // 环形缓冲区大小：最大录音 + 预留
        private const val BUFFER_DURATION_MS = MAX_SEGMENT_MS + 2000
        private const val BUFFER_SIZE = (SAMPLE_RATE * (BUFFER_DURATION_MS / 1000)).toInt()
    }

    sealed class RecordState {
        data object Idle : RecordState()
        data class Recording(val segments: Int = 0) : RecordState()
        data class Error(val message: String) : RecordState()
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val _state = MutableStateFlow<RecordState>(RecordState.Idle)
    val state: StateFlow<RecordState> = _state

    @SuppressLint("MissingPermission")
    fun startRecording(onUtterance: (FloatArray) -> Unit) {
        stopRecording()

        requestAudioFocus()

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize <= 0) {
            Log.e(TAG, "getMinBufferSize 失败 ($bufferSize)")
            _state.value = RecordState.Error("录音初始化失败")
            return
        }

        // 尝试音源
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED
        )

        var selectedSource = -1
        for (src in sources) {
            try {
                val ar = AudioRecord(src, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2)
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    ar.release()
                    continue
                }
                ar.startRecording()
                // 快速测试
                val testBuf = ShortArray(FRAME_SIZE)
                val read = ar.read(testBuf, 0, testBuf.size)
                ar.stop()
                ar.release()
                if (read > 0) {
                    selectedSource = src
                    Log.d(TAG, "✅ 选用音源: $src")
                    break
                }
            } catch (_: Exception) {}
        }

        if (selectedSource < 0) {
            Log.e(TAG, "所有音源均不可用")
            _state.value = RecordState.Error("麦克风不可用")
            return
        }

        // 正式创建 AudioRecord
        audioRecord = AudioRecord(selectedSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 二次初始化失败")
            _state.value = RecordState.Error("录音初始化失败")
            return
        }

        audioRecord?.startRecording()
        Log.d(TAG, "🎤 录音已开始 (VAD 模式)")
        _state.value = RecordState.Recording(0)

        recordingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            // 环形缓冲区
            val ringBuffer = FloatArray(BUFFER_SIZE)
            var writePos = 0          // 写入位置
            var voiceStartPos = -1    // 语音开始位置（环形缓冲区索引）
            var voiceActive = false   // 是否正在收集语音
            var silenceFrames = 0     // 连续静音帧数
            var voiceFrames = 0       // 连续语音帧数
            var segmentCount = 0
            var voiceStartTime = 0L   // 语音开始的时间戳

            val readBuf = ShortArray(FRAME_SIZE)

            while (isActive) {
                val read = audioRecord?.read(readBuf, 0, readBuf.size) ?: -1
                if (read <= 0) continue

                // 写入环形缓冲区
                for (i in 0 until read) {
                    ringBuffer[writePos] = readBuf[i] / 32768.0f
                    writePos = (writePos + 1) % BUFFER_SIZE
                }

                // 计算当前帧能量
                var energy = 0f
                for (i in 0 until read) {
                    val v = readBuf[i] / 32768.0f
                    energy += v * v
                }
                energy /= read
                val isVoice = energy > AMPLITUDE_THRESHOLD

                if (!voiceActive) {
                    // ====== 等待语音开始 ======
                    if (isVoice) {
                        voiceFrames++
                        if (voiceFrames >= VOICE_START_FRAMES) {
                            // 语音开始！回溯保留 preVoice
                            voiceActive = true
                            silenceFrames = 0
                            voiceStartTime = System.currentTimeMillis()
                            val preSamples = (SAMPLE_RATE * PRE_VOICE_MS / 1000).toInt()
                            voiceStartPos = (writePos - read * voiceFrames - preSamples + BUFFER_SIZE * 2) % BUFFER_SIZE
                            Log.d(TAG, "🗣️ 语音开始! energy=$energy, 回溯=${PRE_VOICE_MS}ms")
                        }
                    } else {
                        voiceFrames = 0
                    }
                } else {
                    // ====== 正在收集语音 ======
                    if (isVoice) {
                        silenceFrames = 0
                    } else {
                        silenceFrames++
                    }

                    val silenceMs = silenceFrames * FRAME_DURATION_MS
                    val elapsedMs = System.currentTimeMillis() - voiceStartTime

                    // 检查是否该切分了
                    val shouldSegment = silenceMs >= SILENCE_TIMEOUT_MS || elapsedMs >= MAX_SEGMENT_MS

                    if (shouldSegment) {
                        // 提取语音段
                        val endPos = writePos
                        val voiceLength = ringDistance(voiceStartPos, endPos, BUFFER_SIZE)

                        if (voiceLength > SAMPLE_RATE / 2) {  // 至少 0.5 秒才算有效语音
                            segmentCount++
                            val utterance = FloatArray(voiceLength)
                            var pos = voiceStartPos
                            for (i in 0 until voiceLength) {
                                utterance[i] = ringBuffer[pos]
                                pos = (pos + 1) % BUFFER_SIZE
                            }

                            val reason = if (elapsedMs >= MAX_SEGMENT_MS) "超时" else "静音"
                            Log.d(TAG, "✅ 语音段 #$segmentCount: ${voiceLength} samples (${voiceLength * 1000 / SAMPLE_RATE}ms), 原因=$reason, energy=$energy")
                            _state.value = RecordState.Recording(segmentCount)
                            onUtterance(utterance)
                        } else {
                            Log.d(TAG, "⏭ 语音太短 (${voiceLength} samples)，跳过")
                        }

                        // 重置状态
                        voiceActive = false
                        voiceFrames = 0
                        silenceFrames = 0
                        voiceStartPos = -1
                    }
                }
            }
        }
    }

    /** 计算环形缓冲区中两个位置之间的距离 */
    private fun ringDistance(from: Int, to: Int, size: Int): Int {
        return if (to >= from) to - from else size - from + to
    }

    private fun requestAudioFocus() {
        try {
            audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                val result = audioManager?.requestAudioFocus(focusReq)
                focusRequest = focusReq
                Log.d(TAG, "音频焦点请求: $result")
            }
        } catch (e: Exception) {
            Log.w(TAG, "音频焦点请求失败: ${e.message}")
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        try { focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        _state.value = RecordState.Idle
        Log.d(TAG, "⏹ 录音已停止")
    }

    fun isRecording(): Boolean = recordingJob?.isActive == true
}
