package com.example.imagemarker

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private lateinit var btnSavePath: Button

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private var originalImageUri: Uri? = null
    // Android 11+ 的系统删除对话框会触发 onResume，用此标志跳过那次自动加载
    private var skipNextAutoLoad = false

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadImage(uri)
            }
        }
    }

    // 选择保存文件夹（系统文件夹选择界面）
    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString(KEY_SAVE_PATH, uri.toString()).apply()
                showToast(R.string.save_path_set)
            } catch (e: SecurityException) {
                e.printStackTrace()
                showToast(R.string.save_path_invalid)
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
    }

    override fun onResume() {
        super.onResume()
        if (skipNextAutoLoad) {
            skipNextAutoLoad = false
            return
        }
        // 没有图片时（初次打开 / 保存后 / 切换回来），自动加载最近的一张
        if (!drawingView.hasImage()) {
            checkPermissionAndLoadImage()
        }
    }

    private fun initViews() {
        drawingView = findViewById(R.id.drawingView)
        tvHint = findViewById(R.id.tvHint)
        btnOpen = findViewById(R.id.btnOpen)
        btnUndo = findViewById(R.id.btnUndo)
        btnClear = findViewById(R.id.btnClear)
        btnSave = findViewById(R.id.btnSave)
        btnSavePath = findViewById(R.id.btnSavePath)
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

        btnSavePath.setOnClickListener {
            showSavePathDialog()
        }
    }

    // ---------------- 保存路径设置 ----------------

    private fun showSavePathDialog() {
        val current = getSaveFolderUri()
        val desc = if (current == null) getString(R.string.save_path_default) else describeTreeUri(current)
        AlertDialog.Builder(this)
            .setTitle(R.string.save_path_title)
            .setMessage(getString(R.string.save_path_current, desc))
            .setPositiveButton(R.string.save_path_choose) { _, _ ->
                pickFolderLauncher.launch(current)
            }
            .setNeutralButton(R.string.save_path_reset) { _, _ ->
                clearSaveFolder()
                showToast(R.string.save_path_reset_done)
            }
            .setNegativeButton(R.string.save_path_cancel, null)
            .show()
    }

    // 读取已保存的文件夹，若授权已失效则清除并返回 null（回退到默认目录）
    private fun getSaveFolderUri(): Uri? {
        val saved = prefs.getString(KEY_SAVE_PATH, null) ?: return null
        val uri = Uri.parse(saved)
        val granted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!granted) {
            prefs.edit().remove(KEY_SAVE_PATH).apply()
            return null
        }
        return uri
    }

    private fun clearSaveFolder() {
        val saved = prefs.getString(KEY_SAVE_PATH, null)
        if (saved != null) {
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(saved),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
        prefs.edit().remove(KEY_SAVE_PATH).apply()
    }

    // 把 tree Uri 转换成便于阅读的路径文字
    private fun describeTreeUri(uri: Uri): String {
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            null
        } ?: return uri.toString()
        val parts = docId.split(":")
        val root = if (parts[0] == "primary") "内部存储" else parts[0]
        val path = if (parts.size > 1) parts[1] else ""
        return if (path.isEmpty()) root else root + "/" + path
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
        val filename = "IMG_ANNOTATED_${System.currentTimeMillis()}.png"
        val folderUri = getSaveFolderUri()
        val newUri = if (folderUri != null) {
            saveBitmapToFolder(folderUri, bitmap, filename)
        } else {
            saveBitmapToGallery(bitmap, filename)
        }
        if (newUri != null) {
            showToast(R.string.save_success)
            // 请求删除原图片
            val oldUri = originalImageUri
            // 清除主界面图片，显示提示文字
            drawingView.clearImage()
            originalImageUri = null
            tvHint.visibility = View.VISIBLE
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
                skipNextAutoLoad = true
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

    // 保存到用户指定的文件夹
    private fun saveBitmapToFolder(treeUri: Uri, bitmap: Bitmap, filename: String): Uri? {
        return try {
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val newFileUri = DocumentsContract.createDocument(
                contentResolver,
                parentDocUri,
                "image/png",
                filename
            ) ?: return null

            val written = contentResolver.openOutputStream(newFileUri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            } ?: false

            if (written) {
                newFileUri
            } else {
                try {
                    DocumentsContract.deleteDocument(contentResolver, newFileUri)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, filename: String): Uri? {
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

    companion object {
        private const val PREFS_NAME = "image_marker_prefs"
        private const val KEY_SAVE_PATH = "save_path_uri"
    }
}
