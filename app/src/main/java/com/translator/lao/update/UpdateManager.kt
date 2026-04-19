package com.translator.lao.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在线更新管理器
 * 通过 GitHub Releases 检查并下载新版本 APK
 */
object UpdateManager {

    // ===== 修改为你的 GitHub 仓库地址 =====
    private const val REPO_OWNER = "guocheng1378"
    private const val REPO_NAME = "lao-translator-android"
    private val GITHUB_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    // 国内加速镜像（gh-proxy）
    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val changelog: String,
        val fileSize: Long,
    )

    /**
     * 获取当前 app 版本号
     */
    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        } catch (e: Exception) {
            0
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    /**
     * 检查是否有新版本
     * @return UpdateInfo 如果有更新，null 如果已是最新
     */
    suspend fun checkForUpdate(context: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            // 先尝试直连，失败后用镜像
            val urls = listOf(
                GITHUB_API_URL,
                MIRROR_PREFIX + GITHUB_API_URL,
            )
            var body = ""
            var success = false
            for (url in urls) {
                try {
                    val resp = client.newCall(
                        Request.Builder()
                            .url(url)
                            .header("Accept", "application/vnd.github.v3+json")
                            .header("User-Agent", "LaoTranslator-Android")
                            .get().build()
                    ).execute()
                    body = resp.body?.string() ?: ""
                    if (resp.isSuccessful && body.isNotEmpty()) {
                        success = true
                        break
                    }
                } catch (_: Exception) { }
            }

            if (!success || body.isEmpty()) {
                return@withContext Result.failure(Exception("检查更新失败，请检查网络"))
            }

            val json = org.json.JSONObject(body)
            val tagName = json.optString("tag_name", "")
            val remoteVersionCode = parseVersionCode(tagName)
            val currentVersionCode = getCurrentVersionCode(context)

            // 找到 APK 下载链接
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var fileSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        fileSize = asset.optLong("size", 0)
                        break
                    }
                }
            }

            // 如果没有 APK 资产，尝试用 zipball_url 或跳过
            if (apkUrl.isEmpty()) {
                return@withContext Result.failure(Exception("未找到 APK 文件"))
            }

            val changelog = json.optString("body", "无更新说明")

            if (remoteVersionCode > currentVersionCode) {
                Result.success(
                    UpdateInfo(
                        versionName = tagName,
                        versionCode = remoteVersionCode,
                        apkUrl = apkUrl,
                        changelog = changelog,
                        fileSize = fileSize,
                    )
                )
            } else {
                Result.success(null) // 已是最新
            }
        } catch (e: Exception) {
            Result.failure(Exception("检查更新失败: ${e.message}"))
        }
    }

    /**
     * 从 tag_name 解析版本号，如 "v1.2" → 120, "1.0" → 100
     */
    private fun parseVersionCode(tag: String): Int {
        val clean = tag.replace("v", "").replace("V", "").trim()
        val parts = clean.split(".")
        return try {
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            major * 10000 + minor * 100 + patch
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 下载 APK（使用系统 DownloadManager）
     * @return 下载任务 ID
     */
    fun downloadApk(context: Context, url: String, versionName: String): Long {
        val fileName = "lao-translator-$versionName.apk"
        // 国内使用镜像加速下载
        val downloadUrl = if (url.contains("github.com")) {
            MIRROR_PREFIX + url
        } else {
            url
        }
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("老挝语翻译器 更新")
            .setDescription("正在下载版本 $versionName")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    /**
     * 安装 APK
     */
    fun installApk(context: Context, downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return

        val intent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 从 content URI 直接安装
                setDataAndType(uri, "application/vnd.android.package-archive")
            } else {
                // 旧版本用文件路径
                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor != null && cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (path != null) {
                            setDataAndType(Uri.fromFile(File(path)), "application/vnd.android.package-archive")
                        }
                    }
                    cursor.close()
                }
            }
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
