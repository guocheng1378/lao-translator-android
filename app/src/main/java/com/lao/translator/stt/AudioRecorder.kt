package com.lao.translator.stt

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorder(private val context: Context? = null) {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // ✅ FIX: 大幅降低能量阈值，小米/HyperOS 下 MediaRecorder 输出能量偏低
        private const val ENERGY_THRESHOLD = 0.0001f
        private const val SILENCE_RATIO_THRESHOLD = 0.85f
        private const val TEST_ENERGY_THRESHOLD = 0.0f  // ✅ FIX: 不再用能量过滤 AudioRecord 源
    }

    sealed class RecordState {
        data object Idle : RecordState()
        data class Recording(val chunks: Int = 0, val skipped: Int = 0) : RecordState()
        data class Error(val message: String) : RecordState()
    }

    private var audioRecord: AudioRecord? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingJob: Job? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val _state = MutableStateFlow<RecordState>(RecordState.Idle)
    val state: StateFlow<RecordState> = _state

    private enum class RecordMode { AUDIO_RECORD, MEDIA_RECORDER }
    private var activeMode = RecordMode.AUDIO_RECORD

    @SuppressLint("MissingPermission")
    private var scope: CoroutineScope? = null

    fun startRecording(
        chunkDurationMs: Int = 2000,
        overlapMs: Int = 500,
        onChunk: (FloatArray) -> Unit
    ) {
        stopRecording()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // 请求音频焦点
        requestAudioFocus()

        scope?.launch {
            val sources = listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )

            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize <= 0) {
                Log.e(TAG, "getMinBufferSize 失败 ($bufferSize)，直接走 MediaRecorder")
                startMediaRecorderMode(chunkDurationMs, onChunk)
                return@launch
            }

            Log.d(TAG, "AudioRecord bufferSize=$bufferSize, 尝试 ${sources.size} 个音源...")

            for (src in sources) {
                try {
                    val ar = AudioRecord(src, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2)
                    if (ar.state != AudioRecord.STATE_INITIALIZED) {
                        Log.w(TAG, "AudioRecord source=$src 初始化失败")
                        ar.release()
                        continue
                    }
                    ar.startRecording()
                    // 读 1 秒测试
                    var maxEnergy = 0f
                    var totalRead = 0
                    for (attempt in 0..2) {
                        val test = ShortArray(SAMPLE_RATE)
                        val read = ar.read(test, 0, test.size)
                        if (read > 0) {
                            totalRead += read
                            val energy = calcEnergyShort(test, read)
                            if (energy > maxEnergy) maxEnergy = energy
                        }
                    }
                    Log.d(TAG, "AudioRecord test: source=$src maxEnergy=$maxEnergy totalRead=$totalRead")

                    // ✅ FIX: 只要能读到数据就用这个源，不再用能量阈值过滤
                    if (totalRead > 0) {
                        Log.d(TAG, "✅ AudioRecord 可用 (source=$src, energy=$maxEnergy)")
                        ar.stop(); ar.release()
                        activeMode = RecordMode.AUDIO_RECORD
                        startAudioRecord(src, bufferSize, chunkDurationMs, overlapMs, onChunk)
                        return@launch
                    } else {
                        ar.stop(); ar.release()
                        Log.w(TAG, "AudioRecord source=$src: 无法读取数据")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord source=$src 异常: ${e.message}")
                }
            }

            // 全部失败 → MediaRecorder 降级
            Log.w(TAG, "所有 AudioRecord 源均不可用，降级到 MediaRecorder")
            startMediaRecorderMode(chunkDurationMs, onChunk)
        }
    }

    private fun requestAudioFocus() {
        try {
            audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            // ✅ FIX: 改为 USAGE_MEDIA，HyperOS 对 VOICE_COMMUNICATION 限制更严
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

    @SuppressLint("MissingPermission")
    private fun startAudioRecord(
        audioSource: Int,
        bufferSize: Int,
        chunkDurationMs: Int,
        overlapMs: Int,
        onChunk: (FloatArray) -> Unit
    ) {
        val samplesPerChunk = SAMPLE_RATE * chunkDurationMs / 1000
        val overlapSamples = SAMPLE_RATE * overlapMs / 1000
        val windowSamples = samplesPerChunk + overlapSamples

        audioRecord = AudioRecord(audioSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            _state.value = RecordState.Error("AudioRecord 初始化失败")
            Log.e(TAG, "AudioRecord 二次初始化失败")
            return
        }
        audioRecord?.startRecording()
        Log.d(TAG, "AudioRecord 录音开始 (source=$audioSource)")

        var chunkCount = 0
        var skippedCount = 0
        var consecutiveSilence = 0

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val window = FloatArray(windowSamples)
            var windowPos = 0
            var firstChunk = true
            val readBuffer = ShortArray(samplesPerChunk)
            _state.value = RecordState.Recording(0, 0)

            while (isActive) {
                val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                if (read <= 0) continue

                for (i in 0 until read) {
                    if (windowPos < window.size) {
                        window[windowPos++] = readBuffer[i] / 32768.0f
                    }
                }

                if (windowPos >= windowSamples) {
                    val chunk = window.copyOf(windowSamples)
                    val hasVoice = detectVoice(chunk)
                    val energy = calcEnergy(chunk)

                    // ✅ FIX: AudioRecord 模式下，前几个 chunk 直接发送（等价于关闭静音过滤）
                    // 只在连续很多个纯静音时才跳过
                    if (hasVoice || firstChunk || energy > 0.0001f) {
                        chunkCount++
                        consecutiveSilence = 0
                        Log.d(TAG, "AudioRecord chunk #$chunkCount: voice=$hasVoice energy=$energy")
                        _state.value = RecordState.Recording(chunkCount, skippedCount)
                        onChunk(chunk)
                        firstChunk = false
                    } else {
                        skippedCount++
                        consecutiveSilence++
                        Log.d(TAG, "AudioRecord 静音 #$skippedCount energy=$energy")
                        _state.value = RecordState.Recording(chunkCount, skippedCount)

                        // 连续 15 个静音 chunk 且从未检测到语音 → 降级到 MediaRecorder
                        if (consecutiveSilence >= 15 && chunkCount == 0) {
                            Log.w(TAG, "连续 $consecutiveSilence 个静音 chunk 且无语音，降级到 MediaRecorder")
                            audioRecord?.stop(); audioRecord?.release(); audioRecord = null
                            activeMode = RecordMode.MEDIA_RECORDER
                            startMediaRecorderMode(chunkDurationMs) { audio -> onChunk(audio) }
                            return@launch
                        }
                    }

                    System.arraycopy(window, samplesPerChunk, window, 0, overlapSamples)
                    windowPos = overlapSamples
                }
            }
        }
    }

    /**
     * MediaRecorder 降级模式
     * ✅ FIX: 移除能量阈值过滤，所有 chunk 直接发送给 Whisper 判断
     */
    @SuppressLint("MissingPermission")
    private fun startMediaRecorderMode(
        chunkDurationMs: Int,
        onChunk: (FloatArray) -> Unit
    ) {
        Log.d(TAG, "=== MediaRecorder 模式 (chunk=${chunkDurationMs}ms) ===")
        activeMode = RecordMode.MEDIA_RECORDER
        var chunkCount = 0
        var segIdx = 0

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            _state.value = RecordState.Recording(0, 0)

            while (isActive) {
                val tmpFile = File(context?.cacheDir ?: File("/tmp"), "mr_seg_${segIdx++}.wav")
                try {
                    mediaRecorder = MediaRecorder().apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.DEFAULT)
                        setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT)
                        setAudioSamplingRate(SAMPLE_RATE)
                        setAudioEncodingBitRate(128000)
                        setOutputFile(tmpFile.absolutePath)
                        prepare()
                        start()
                    }

                    delay(chunkDurationMs.toLong())

                    mediaRecorder?.apply { stop(); release() }
                    mediaRecorder = null

                    val pcm = readWavPcm(tmpFile)
                    if (pcm != null && pcm.isNotEmpty()) {
                        val energy = calcEnergy(pcm)
                        chunkCount++
                        // ✅ FIX: 移除能量阈值判断，直接发送给 Whisper
                        // Whisper 自己会判断是否有语音内容
                        Log.d(TAG, "✅ MR chunk #$chunkCount: size=${pcm.size} energy=$energy → 发送给 Whisper")
                        _state.value = RecordState.Recording(chunkCount, 0)
                        onChunk(pcm)
                    } else {
                        Log.w(TAG, "MR chunk: PCM 为空或读取失败")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MR 录制异常: ${e.message}", e)
                    try { mediaRecorder?.release() } catch (_: Exception) {}
                    mediaRecorder = null
                    delay(500)
                } finally {
                    tmpFile.delete()
                }
            }
        }
    }

    private fun readWavPcm(file: File): FloatArray? {
        if (!file.exists() || file.length() < 44) {
            Log.w(TAG, "readWavPcm: 文件不存在或太小 (${file.length()} bytes)")
            return null
        }
        return try {
            val bytes = file.readBytes()
            var pos = 12
            while (pos < bytes.size - 8) {
                val id = String(bytes, pos, 4)
                val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "data") { pos += 8; break }
                pos += 8 + size
                if (size <= 0) break
            }
            if (pos >= bytes.size - 2) return null
            val pcmBytes = bytes.copyOfRange(pos, bytes.size)
            val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            val shorts = ShortArray(pcmBytes.size / 2)
            buf.asShortBuffer().get(shorts)
            FloatArray(shorts.size) { shorts[it] / 32768.0f }
        } catch (e: Exception) {
            Log.e(TAG, "读取 WAV 失败: ${e.message}")
            null
        }
    }

    private fun detectVoice(samples: FloatArray): Boolean {
        val frameSize = SAMPLE_RATE / 100
        val totalFrames = samples.size / frameSize
        if (totalFrames == 0) return false
        var voiced = 0
        for (f in 0 until totalFrames) {
            var e = 0f
            val s = f * frameSize
            val end = minOf(s + frameSize, samples.size)
            for (i in s until end) e += samples[i] * samples[i]
            e /= (end - s)
            if (e > ENERGY_THRESHOLD) voiced++
        }
        val ratio = voiced.toFloat() / totalFrames
        return ratio > (1f - SILENCE_RATIO_THRESHOLD)
    }

    private fun calcEnergy(s: FloatArray): Float {
        var e = 0f; for (v in s) e += v * v; return e / s.size
    }

    private fun calcEnergyShort(s: ShortArray, count: Int): Float {
        var e = 0.0; for (i in 0 until count) { val v = s[i] / 32768.0; e += v * v }; return (e / count).toFloat()
    }

    fun stopRecording() {
        scope?.cancel(); scope = null
        recordingJob?.cancel(); recordingJob = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        _state.value = RecordState.Idle
        Log.d(TAG, "录音已停止")
    }

    fun isRecording(): Boolean = recordingJob?.isActive == true
}
