package com.example.imagemarker

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingImageView
    private lateinit var tvHint: TextView
    private lateinit var btnOpen: Button
    private lateinit var btnUndo: Button
    private lateinit var btnClear: Button
    private lateinit var btnSave: Button

    private var originalImageUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadImage(uri)
            }
        }
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            showToast(R.string.delete_success)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadLatestImage()
        } else {
            showToast(R.string.permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()

        // 应用启动时自动加载最近的一张照片
        checkPermissionAndLoadImage()
    }

    private fun initViews() {
        drawingView = findViewById(R.id.drawingView)
        tvHint = findViewById(R.id.tvHint)
        btnOpen = findViewById(R.id.btnOpen)
        btnUndo = findViewById(R.id.btnUndo)
        btnClear = findViewById(R.id.btnClear)
        btnSave = findViewById(R.id.btnSave)
    }

    private fun setupListeners() {
        btnOpen.setOnClickListener {
            checkPermissionAndLoadImage()
        }

        btnUndo.setOnClickListener {
            if (!drawingView.hasImage()) {
                showToast(R.string.no_image_loaded)
                return@setOnClickListener
            }
            if (drawingView.undo()) {
                showToast(R.string.undo_done)
            } else {
                showToast(R.string.nothing_to_undo)
            }
        }

        btnClear.setOnClickListener {
            if (!drawingView.hasImage()) {
                showToast(R.string.no_image_loaded)
                return@setOnClickListener
            }
            drawingView.clearAll()
            showToast(R.string.cleared)
        }

        btnSave.setOnClickListener {
            saveImage()
        }
    }

    private fun checkPermissionAndLoadImage() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                loadLatestImage()
            }
            else -> {
                requestPermissionLauncher.launch(permission)
            }
        }
    }

    private fun loadLatestImage() {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idColumn)
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                loadImage(uri)
            } else {
                showToast(R.string.no_image_found)
            }
        } ?: showToast(R.string.no_image_found)
    }

    private fun loadImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                }
                val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                bitmap?.let {
                    drawingView.setImage(it)
                    originalImageUri = uri
                    tvHint.visibility = View.GONE
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            showToast(R.string.save_failed)
        }
    }

    private fun saveImage() {
        if (!drawingView.hasImage()) {
            showToast(R.string.no_image_loaded)
            return
        }

        if (!drawingView.hasPaths()) {
            showToast(R.string.no_annotations)
            return
        }

        // 将当前标注烘焙到图片中
        if (!drawingView.bakeAnnotationsToImage()) {
            showToast(R.string.save_failed)
            return
        }

        // 保存当前图片到相册
        val bitmap = drawingView.getCurrentBitmap()
        if (bitmap == null) {
            showToast(R.string.save_failed)
            return
        }

        // 保存新图片，获取新图片的 URI
        val newUri = saveBitmapToGallery(bitmap)
        if (newUri != null) {
            showToast(R.string.save_success)
            // 请求删除原图片
            val oldUri = originalImageUri
            // 先更新为新图片的 URI，这样下次保存时可以删除这张
            originalImageUri = newUri
            // 删除旧图片
            oldUri?.let { requestDeleteOriginal(it) }
        } else {
            showToast(R.string.save_failed)
        }
    }

    private fun requestDeleteOriginal(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用系统删除对话框
            try {
                val deleteRequest = MediaStore.createDeleteRequest(
                    contentResolver,
                    listOf(uri)
                )
                val intentSenderRequest = IntentSenderRequest.Builder(deleteRequest.intentSender).build()
                deleteLauncher.launch(intentSenderRequest)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            // Android 10 及以下直接删除
            try {
                contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getOriginalImageRelativePath(): String? {
        val uri = originalImageUri ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.Images.Media.RELATIVE_PATH)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    if (pathIndex >= 0) {
                        return cursor.getString(pathIndex)
                    }
                }
            }
        }
        return null
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        val filename = "IMG_ANNOTATED_${System.currentTimeMillis()}.png"

        // 获取原图片的目录路径，如果获取不到则使用默认的 Pictures 目录
        val relativePath = getOriginalImageRelativePath() ?: Environment.DIRECTORY_PICTURES

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        return try {
            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }
                it
            }
        } catch (e: IOException) {
            e.printStackTrace()
            uri?.let { resolver.delete(it, null, null) }
            null
        }
    }

    private fun showToast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
