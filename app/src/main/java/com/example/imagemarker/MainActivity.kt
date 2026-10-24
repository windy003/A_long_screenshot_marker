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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingImageView
    private lateinit var tvHint: TextView
    private lateinit var tvSavePathBar: TextView
    private lateinit var btnOpen: Button
    private lateinit var btnLine: Button
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
                setSaveFolder(uri)
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
        updateSavePathBar()
    }

    override fun onResume() {
        super.onResume()
        // 授权可能在应用外被撤销，回到前台时同步一次底部保存路径
        updateSavePathBar()
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
        tvSavePathBar = findViewById(R.id.tvSavePathBar)
        btnOpen = findViewById(R.id.btnOpen)
        btnLine = findViewById(R.id.btnLine)
        btnUndo = findViewById(R.id.btnUndo)
        btnClear = findViewById(R.id.btnClear)
        btnSave = findViewById(R.id.btnSave)
        btnSavePath = findViewById(R.id.btnSavePath)
    }

    private fun setupListeners() {
        btnOpen.setOnClickListener {
            checkPermissionAndLoadImage()
        }

        btnLine.setOnClickListener {
            if (!drawingView.hasImage()) {
                showToast(R.string.no_image_loaded)
                return@setOnClickListener
            }
            val enterLineMode = drawingView.getDrawMode() != DrawingImageView.DrawMode.LINE
            drawingView.setDrawMode(
                if (enterLineMode) DrawingImageView.DrawMode.LINE
                else DrawingImageView.DrawMode.FREE
            )
            // 按钮文字始终表示"点击后切换到的模式"
            btnLine.setText(if (enterLineMode) R.string.free_draw else R.string.two_point_line)
            showToast(if (enterLineMode) R.string.line_mode_on else R.string.line_mode_off)
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
        val recents = getValidRecentFolders()
        val desc = if (current == null) getString(R.string.save_path_default) else describeTreeUri(current)

        // 使用自定义布局，保证"最近使用"列表在任何情况下都可见、可滚动
        val content = layoutInflater.inflate(R.layout.dialog_save_path, null)
        val tvCurrent = content.findViewById<TextView>(R.id.tvSavePathCurrent)
        val tvRecentTitle = content.findViewById<TextView>(R.id.tvRecentTitle)
        val tvNoRecent = content.findViewById<TextView>(R.id.tvNoRecent)
        val container = content.findViewById<LinearLayout>(R.id.layoutRecentFolders)

        tvCurrent.text = getString(R.string.save_path_current, desc)

        lateinit var dialog: AlertDialog

        if (recents.isEmpty()) {
            tvRecentTitle.visibility = View.GONE
            tvNoRecent.visibility = View.VISIBLE
        } else {
            // 最近使用的文件夹按最近使用顺序排列，点击即切换
            recents.forEach { uri ->
                val name = describeTreeUri(uri)
                val item = layoutInflater.inflate(
                    R.layout.item_save_folder, container, false
                ) as TextView
                item.text = if (uri == current) {
                    getString(R.string.save_path_current_item, name)
                } else {
                    name
                }
                item.setOnClickListener {
                    setSaveFolder(uri)
                    showToast(R.string.save_path_set)
                    dialog.dismiss()
                }
                container.addView(item)
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.save_path_title)
            .setView(content)
            .setPositiveButton(R.string.save_path_choose) { _, _ ->
                pickFolderLauncher.launch(current)
            }
            .setNeutralButton(R.string.save_path_reset) { _, _ ->
                clearSaveFolder()
                showToast(R.string.save_path_reset_done)
            }
            .setNegativeButton(R.string.save_path_cancel, null)
            .create()
        dialog.show()
    }

    // 读取当前使用的保存文件夹，若授权已失效则清除并返回 null（回退到默认目录）
    private fun getSaveFolderUri(): Uri? {
        val saved = prefs.getString(KEY_SAVE_PATH, null) ?: return null
        val uri = Uri.parse(saved)
        if (!hasWritePermission(uri)) {
            prefs.edit().remove(KEY_SAVE_PATH).apply()
            removeRecentFolder(uri)
            return null
        }
        return uri
    }

    // 切换当前保存文件夹，并把这个文件夹放到最近使用列表的最前面
    private fun setSaveFolder(uri: Uri) {
        prefs.edit().putString(KEY_SAVE_PATH, uri.toString()).apply()
        addRecentFolder(uri)
        updateSavePathBar()
    }

    // 底部常驻横条：显示当前保存路径
    private fun updateSavePathBar() {
        val uri = getSaveFolderUri()
        tvSavePathBar.text = if (uri == null) {
            getString(R.string.save_path_bar_default)
        } else {
            getString(R.string.save_path_bar, describeTreeUri(uri))
        }
    }

    private fun hasWritePermission(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }

    // 最近使用过的文件夹（最多 3 个，最近使用的排在前面）
    private fun getRecentFolders(): List<Uri> {
        val saved = prefs.getString(KEY_RECENT_PATHS, null) ?: return emptyList()
        return try {
            val array = JSONArray(saved)
            (0 until array.length()).map { Uri.parse(array.getString(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 只返回授权仍然有效的文件夹，并顺带清理已失效的记录
    private fun getValidRecentFolders(): List<Uri> {
        val stored = getRecentFolders()
        val valid = stored.filter { hasWritePermission(it) }.toMutableList()

        // 当前保存路径始终包含在列表中（兼容旧版本只记录了一个路径的情况）
        val current = prefs.getString(KEY_SAVE_PATH, null)?.let { Uri.parse(it) }
        if (current != null && hasWritePermission(current) && valid.none { it == current }) {
            valid.add(0, current)
        }

        // 兼容旧版本：系统中仍有持久化授权的文件夹也补进列表，
        // 这样升级前用过的保存路径也能出现在"最近使用"里
        contentResolver.persistedUriPermissions
            .filter { it.isWritePermission }
            .map { it.uri }
            .forEach { uri ->
                if (valid.size < MAX_RECENT_FOLDERS && valid.none { it == uri }) {
                    valid.add(uri)
                }
            }

        val result = valid.take(MAX_RECENT_FOLDERS)
        if (result != stored) {
            storeRecentFolders(result)
        }
        return result
    }

    private fun addRecentFolder(uri: Uri) {
        val list = getRecentFolders().filter { it != uri }.toMutableList()
        list.add(0, uri)
        storeRecentFolders(list.take(MAX_RECENT_FOLDERS))
    }

    private fun removeRecentFolder(uri: Uri) {
        val list = getRecentFolders().filter { it != uri }
        storeRecentFolders(list)
    }

    private fun storeRecentFolders(uris: List<Uri>) {
        val array = JSONArray()
        uris.forEach { array.put(it.toString()) }
        prefs.edit().putString(KEY_RECENT_PATHS, array.toString()).apply()
    }

    private fun clearSaveFolder() {
        // 释放当前及历史保存路径的持久化授权
        val uris = (getRecentFolders() + listOfNotNull(
            prefs.getString(KEY_SAVE_PATH, null)?.let { Uri.parse(it) }
        )).distinct()
        uris.forEach { uri ->
            try {
                contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
        prefs.edit().remove(KEY_SAVE_PATH).remove(KEY_RECENT_PATHS).apply()
        updateSavePathBar()
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
        private const val KEY_RECENT_PATHS = "recent_save_paths"
        private const val MAX_RECENT_FOLDERS = 3
    }
}
