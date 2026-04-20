package com.lao.translator.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 智能流式录音器
 *
 * 自动检测 AudioRecord 是否正常工作：
 * - 正常 → 使用 AudioRecord 流式录音
 * - 被屏蔽（采集到静音）→ 使用 MediaRecorder 写 WAV 再读取
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val ENERGY_THRESHOLD = 0.008f
        private const val SILENCE_RATIO_THRESHOLD = 0.85f
    }

    sealed class RecordState {
        data object Idle : RecordState()
        data class Recording(val chunks: Int = 0, val skipped: Int = 0) : RecordState()
        data class Error(val message: String) : RecordState()
    }

    private var audioRecord: AudioRecord? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingJob: Job? = null
    private var useMediaRecorder = false
    private val _state = MutableStateFlow<RecordState>(RecordState.Idle)
    val state: StateFlow<RecordState> = _state

    @SuppressLint("MissingPermission")
    fun startRecording(
        chunkDurationMs: Int = 2000,
        overlapMs: Int = 500,
        onChunk: (FloatArray) -> Unit
    ) {
        stopRecording()
        startAudioRecord(chunkDurationMs, overlapMs, onChunk)
    }

    /**
     * AudioRecord 流式录音
     */
    @SuppressLint("MissingPermission")
    private fun startAudioRecord(
        chunkDurationMs: Int,
        overlapMs: Int,
        onChunk: (FloatArray) -> Unit
    ) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize <= 0) {
            Log.e(TAG, "getMinBufferSize 失败: $bufferSize")
            _state.value = RecordState.Error("无法获取录音缓冲区")
            return
        }

        val samplesPerChunk = SAMPLE_RATE * chunkDurationMs / 1000
        val overlapSamples = SAMPLE_RATE * overlapMs / 1000
        val windowSamples = samplesPerChunk + overlapSamples

        // 尝试多个 audio source
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER
        )

        var foundSource = -1
        for (src in sources) {
            try {
                audioRecord = AudioRecord(src, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2)
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                    // 快速测试：读 0.5 秒
                    val testBuf = ShortArray(SAMPLE_RATE / 2)
                    val read = audioRecord?.read(testBuf, 0, testBuf.size) ?: 0
                    if (read > 0) {
                        val energy = calcEnergy(testBuf, read)
                        Log.d(TAG, "AudioRecord test: source=$src, energy=$energy, read=$read")
                        if (energy > 0.00001f) { // 只要不是绝对静音就行
                            foundSource = src
                            audioRecord?.stop()
                            audioRecord?.release()
                            audioRecord = null
                            break
                        }
                    }
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null
                } else {
                    audioRecord?.release()
                    audioRecord = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord source=$src 异常: ${e.message}")
                audioRecord?.release()
                audioRecord = null
            }
        }

        if (foundSource < 0) {
            // 所有 AudioRecord source 都被屏蔽，降级到 MediaRecorder
            Log.w(TAG, "所有 AudioRecord source 被屏蔽，降级到 MediaRecorder")
            startMediaRecorderFallback(chunkDurationMs, overlapMs, onChunk)
            return
        }

        // 用找到的正常 source 重新初始化
        Log.d(TAG, "使用 AudioRecord source=$foundSource")
        audioRecord = AudioRecord(foundSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            _state.value = RecordState.Error("AudioRecord 重新初始化失败")
            return
        }

        audioRecord?.startRecording()
        var chunkCount = 0
        var skippedCount = 0

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

                    if (hasVoice || (firstChunk && energy > 0.003f)) {
                        chunkCount++
                        Log.d(TAG, "chunk #$chunkCount: voice=$hasVoice energy=$energy")
                        _state.value = RecordState.Recording(chunkCount, skippedCount)
                        onChunk(chunk)
                        firstChunk = false
                    } else {
                        skippedCount++
                        Log.d(TAG, "chunk 跳过: 静音 #$skippedCount energy=$energy")
                        _state.value = RecordState.Recording(chunkCount, skippedCount)
                    }

                    System.arraycopy(window, samplesPerChunk, window, 0, overlapSamples)
                    windowPos = overlapSamples
                }
            }
        }
    }

    /**
     * MediaRecorder 降级方案 — 分段录制 WAV 文件，读取 PCM
     *
     * 原理：MediaRecorder 在 HyperOS 上不受屏蔽，通过分段录制短 WAV
     * 文件然后读取 PCM 数据来实现流式录音。
     */
    @SuppressLint("MissingPermission")
    private fun startMediaRecorderFallback(
        chunkDurationMs: Int,
        overlapMs: Int,
        onChunk: (FloatArray) -> Unit
    ) {
        Log.d(TAG, "=== 使用 MediaRecorder 降级模式 ===")
        var chunkCount = 0
        var skippedCount = 0
        var segIdx = 0

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            _state.value = RecordState.Recording(0, 0)

            while (isActive) {
                val tmpFile = java.io.File.createTempFile("rec_${segIdx++}_", ".wav")
                try {
                    // 录制 WAV (PCM 16-bit 16kHz mono)
                    mediaRecorder = MediaRecorder().apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.DEFAULT)
                        setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT)
                        setAudioSamplingRate(SAMPLE_RATE)
                        setAudioEncodingBitRate(256000)
                        setOutputFile(tmpFile.absolutePath)
                        prepare()
                        start()
                    }

                    delay(chunkDurationMs.toLong())

                    mediaRecorder?.apply {
                        stop()
                        release()
                    }
                    mediaRecorder = null

                    // 读取 WAV 中的 PCM 数据
                    val pcm = readWavPcm(tmpFile)
                    if (pcm != null && pcm.isNotEmpty()) {
                        val energy = calcEnergy(pcm)
                        Log.d(TAG, "MediaRecorder chunk: size=${pcm.size}, energy=$energy")

                        if (energy > 0.0005f) {
                            chunkCount++
                            _state.value = RecordState.Recording(chunkCount, skippedCount)
                            onChunk(pcm)
                        } else {
                            skippedCount++
                            _state.value = RecordState.Recording(chunkCount, skippedCount)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MediaRecorder chunk 失败: ${e.message}")
                    try { mediaRecorder?.release() } catch (_: Exception) {}
                    mediaRecorder = null
                    delay(500)
                } finally {
                    tmpFile.delete()
                }
            }
        }
    }

    /**
     * 从 WAV 文件读取 PCM 数据（跳过 44 字节头）
     */
    private fun readWavPcm(file: java.io.File): FloatArray? {
        if (!file.exists() || file.length() < 44) return null
        return try {
            val bytes = file.readBytes()
            val headerSize = if (bytes.size > 44) {
                // 搜索 "data" chunk
                var pos = 12
                while (pos < bytes.size - 8) {
                    val id = String(bytes, pos, 4)
                    val size = (bytes[pos+4].toInt() and 0xFF) or
                            ((bytes[pos+5].toInt() and 0xFF) shl 8) or
                            ((bytes[pos+6].toInt() and 0xFF) shl 16) or
                            ((bytes[pos+7].toInt() and 0xFF) shl 24)
                    if (id == "data") { pos += 8; break }
                    pos += 8 + size
                }
                pos
            } else 44

            val pcmBytes = bytes.copyOfRange(headerSize, bytes.size)
            val shorts = java.nio.ByteBuffer.wrap(pcmBytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            val result = FloatArray(shorts.remaining())
            for (i in result.indices) {
                result[i] = shorts.get() / 32768.0f
            }
            result
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
        Log.d(TAG, "VAD: $voiced/$totalFrames ratio=$ratio")
        return ratio > (1f - SILENCE_RATIO_THRESHOLD)
    }

    private fun calcEnergy(samples: FloatArray): Float {
        var e = 0f
        for (s in samples) e += s * s
        return e / samples.size
    }

    private fun calcEnergy(samples: ShortArray, count: Int): Float {
        var e = 0.0
        for (i in 0 until count) {
            val v = samples[i] / 32768.0
            e += v * v
        }
        return (e / count).toFloat()
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        _state.value = RecordState.Idle
    }

    fun isRecording(): Boolean = recordingJob?.isActive == true
}
