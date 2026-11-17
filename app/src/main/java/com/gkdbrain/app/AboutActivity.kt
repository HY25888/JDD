package com.gkdbrain.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

/**
 * 关于页面：作者、简介、QQ 交流群二维码（可保存到相册）、GitHub 开源链接。
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        /** GitHub 仓库地址（开源发布后替换成实际地址） */
        private const val GITHUB_URL = "https://github.com/HY25888/JDD"
        private const val REQ_WRITE_STORAGE = 1001
    }

    private var pendingBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // 版本号
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
        }.getOrDefault("-")
        findViewById<TextView>(R.id.about_version).text = "版本 $version"

        // 二维码缩略图：点击放大查看并保存
        findViewById<ImageView>(R.id.about_qrcode).setOnClickListener { showQrDialog() }

        // GitHub 链接：点击跳转浏览器
        findViewById<TextView>(R.id.about_github).setOnClickListener { openGithub() }
    }

    /** 弹出大图，提供「保存到相册」 */
    private fun showQrDialog() {
        val image = ImageView(this).apply {
            setImageResource(R.drawable.qrcode_group)
            setPadding(24, 24, 24, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("QQ 交流群")
            .setMessage("点击下方按钮保存二维码到相册")
            .setView(image)
            .setPositiveButton("保存到相册") { _, _ -> saveToGallery() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openGithub() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        }.onFailure {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    /** 保存二维码到系统相册 */
    private fun saveToGallery() {
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.qrcode_group)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveQ(bitmap)
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                saveQ(bitmap)
            } else {
                pendingBitmap = bitmap
                requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQ_WRITE_STORAGE,
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingBitmap?.let { saveQ(it) }
            } else {
                Toast.makeText(this, "需要相册权限才能保存", Toast.LENGTH_SHORT).show()
            }
            pendingBitmap = null
        }
    }

    /** Android 10+ 走 MediaStore；Android 9 及以下走文件路径 */
    private fun saveQ(bitmap: Bitmap) {
        try {
            val name = "JDD_QQ群_${System.currentTimeMillis()}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert 失败")
                contentResolver.openOutputStream(uri)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, os)
                } ?: throw IllegalStateException("无法打开输出流")
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                if (!dir.exists()) dir.mkdirs()
                val file = java.io.File(dir, name)
                file.outputStream().use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, os)
                }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
            }
            Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
