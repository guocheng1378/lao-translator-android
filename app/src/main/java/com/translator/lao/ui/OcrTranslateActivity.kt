package com.translator.lao.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.translator.lao.R
import com.translator.lao.api.TranslationApi
import com.translator.lao.data.DictionaryStore
import com.translator.lao.databinding.ActivityOcrTranslateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OcrTranslateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrTranslateBinding
    private var capturedImageUri: Uri? = null
    private var isLaoToChinese = true
    private var isOfflineMode = true

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && capturedImageUri != null) {
            loadImageAndRecognize(capturedImageUri!!)
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { loadImageAndRecognize(it) }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCamera()
        else showToast(getString(R.string.ocr_camera_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrTranslateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }
        updateDirectionUI()
        updateModeUI()

        binding.btnOffline.setOnClickListener {
            if (!isOfflineMode) {
                isOfflineMode = true
                updateModeUI()
                val recognized = binding.tvOcrResult.text.toString()
                if (recognized.isNotEmpty() && recognized != "正在识别文字...") performTranslation(recognized)
            }
        }
        binding.btnOnline.setOnClickListener {
            if (isOfflineMode) {
                isOfflineMode = false
                updateModeUI()
                val recognized = binding.tvOcrResult.text.toString()
                if (recognized.isNotEmpty() && recognized != "正在识别文字...") performTranslation(recognized)
            }
        }

        binding.btnCamera.setOnClickListener { checkCameraPermission() }
        binding.btnGallery.setOnClickListener { pickImageLauncher.launch("image/*") }

        binding.btnSwitch.setOnClickListener {
            isLaoToChinese = !isLaoToChinese
            updateDirectionUI()
            val recognized = binding.tvOcrResult.text.toString()
            if (recognized.isNotEmpty() && recognized != "正在识别文字...") {
                performTranslation(recognized)
            }
        }

        binding.btnTranslate.setOnClickListener {
            val text = binding.tvOcrResult.text.toString().trim()
            if (text.isNotEmpty() && text != "正在识别文字...") {
                performTranslation(text)
            }
        }

        binding.btnCopyResult.setOnClickListener {
            val text = binding.tvTranslationResult.text.toString()
            if (text.isNotEmpty()) {
                val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("ocr", text))
                showToast(getString(R.string.copied))
            }
        }
    }

    private fun updateDirectionUI() {
        binding.tvDirection.text = if (isLaoToChinese) getString(R.string.ocr_direction_lao_zh) else getString(R.string.ocr_direction_zh_lao)
    }

    private fun updateModeUI() {
        if (isOfflineMode) {
            binding.btnOffline.alpha = 1f
            binding.btnOnline.alpha = 0.5f
        } else {
            binding.btnOffline.alpha = 0.5f
            binding.btnOnline.alpha = 1f
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED -> openCamera()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        val photoFile = File(cacheDir, "ocr_${System.currentTimeMillis()}.jpg")
        capturedImageUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
        takePictureLauncher.launch(capturedImageUri)
    }

    private fun loadImageAndRecognize(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.ivPreview.setImageURI(uri)
        binding.ivPreview.visibility = View.VISIBLE
        binding.tvOcrResult.text = getString(R.string.ocr_recognizing)
        binding.tvTranslationResult.text = ""
        binding.cardTranslation.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
                if (bitmap == null) {
                    binding.tvOcrResult.text = getString(R.string.ocr_image_load_failed)
                    binding.progressBar.visibility = View.GONE
                    return@launch
                }

                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        binding.progressBar.visibility = View.GONE
                        val text = visionText.text
                        if (text.isBlank()) {
                            binding.tvOcrResult.text = getString(R.string.ocr_no_text)
                        } else {
                            binding.tvOcrResult.text = text
                            performTranslation(text)
                        }
                    }
                    .addOnFailureListener { e ->
                        binding.progressBar.visibility = View.GONE
                        binding.tvOcrResult.text = getString(R.string.ocr_recognize_failed) + "：${e.message}"
                        Log.e("OcrTranslate", "OCR failed", e)
                    }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.tvOcrResult.text = getString(R.string.ocr_image_process_failed) + "：${e.message}"
            }
        }
    }

    private fun performTranslation(text: String) {
        binding.cardTranslation.visibility = View.VISIBLE
        binding.tvTranslationResult.text = getString(R.string.ocr_translating)
        binding.progressBar.visibility = View.VISIBLE

        if (isOfflineMode) {
            // 离线词典翻译
            val results = DictionaryStore.translate(text, isLaoToChinese)
            binding.progressBar.visibility = View.GONE
            if (results.isNotEmpty()) {
                binding.tvTranslationResult.text = results.joinToString("\n")
            } else {
                binding.tvTranslationResult.text = getString(R.string.ocr_offline_result_empty)
            }
        } else {
            // 在线翻译
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    if (isLaoToChinese) TranslationApi.laoToChinese(text)
                    else TranslationApi.chineseToLao(text)
                }
                binding.progressBar.visibility = View.GONE
                result.onSuccess { binding.tvTranslationResult.text = it }
                    .onFailure { binding.tvTranslationResult.text = getString(R.string.no_result_online) + "：${it.message}" }
            }
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, OcrTranslateActivity::class.java))
        }
    }
}
