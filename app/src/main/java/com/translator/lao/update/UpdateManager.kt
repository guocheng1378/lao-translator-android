package com.translator.lao.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在线更新管理器
 * 通过 version.json 检查新版本，镜像加速下载 APK
 */
object UpdateManager {

    private const val REPO_OWNER = "guocheng1378"
    private const val REPO_NAME = "lao-translator-android"

    // GitHub 镜像加速列表（按速度排序，逐个尝试）
    private val MIRRORS = listOf(
        "",  // 优先直连
        "https://ghproxy.cn",
        "https://gh-proxy.com",
        "https://ghps.cc",
    )

    // version.json 检测地址（jsdelivr 加时间戳防缓存）
    private val VERSION_URLS: List<String>
        get() {
            val ts = System.currentTimeMillis() / 60000 // 每分钟变一次，避免频繁请求
            return listOf(
                "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/version.json",
                "https://cdn.jsdelivr.net/gh/$REPO_OWNER/$REPO_NAME@main/version.json?t=$ts",
                "https://gcore.jsdelivr.net/gh/$REPO_OWNER/$REPO_NAME@main/version.json?t=$ts",
            )
        }

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

    suspend fun checkForUpdate(context: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            var body = ""
            var success = false
            for (url in VERSION_URLS) {
                try {
                    val resp = client.newCall(
                        Request.Builder().url(url)
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

            val json = JSONObject(body)
            val remoteVersionCode = json.optInt("versionCode", 0)
            val currentVersionCode = getCurrentVersionCode(context)
            val versionName = json.optString("versionName", "")
            val apkUrl = json.optString("apkUrl", "")
            val changelog = json.optString("changelog", "无更新说明")
            val fileSize = json.optLong("fileSize", 0)

            if (apkUrl.isEmpty()) {
                return@withContext Result.failure(Exception("未找到 APK 下载地址"))
            }

            if (remoteVersionCode > currentVersionCode) {
                Result.success(UpdateInfo(versionName, remoteVersionCode, apkUrl, changelog, fileSize))
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(Exception("检查更新失败: ${e.message}"))
        }
    }

    fun downloadApk(context: Context, url: String, versionName: String): Long {
        val fileName = "lao-translator-$versionName.apk"
        // 优先用镜像下载 GitHub 资源
        val downloadUrl = if (url.contains("github.com") || url.contains("githubusercontent.com")) {
            // 找到第一个可用镜像前缀
            val mirror = MIRRORS.firstOrNull { it.isNotEmpty() } ?: ""
            if (mirror.isNotEmpty()) "$mirror/$url" else url
        } else url
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

    fun installApk(context: Context, downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setDataAndType(uri, "application/vnd.android.package-archive")
            } else {
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
        try { context.startActivity(intent) } catch (_: Exception) { }
    }

    fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
