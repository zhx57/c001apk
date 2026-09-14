package com.example.c001apk.util

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.transition.Transition
import com.example.c001apk.R
import com.example.c001apk.constant.Constants.USER_AGENT
import com.example.c001apk.ui.others.viewer.ViewerActivity
import com.example.c001apk.util.ClipboardUtil.copyText
import com.example.c001apk.util.FileUtil.copyFile
import com.example.c001apk.util.FileUtil.createFileByDeleteOldFile
import com.example.c001apk.view.FileTarget
import com.example.c001apk.view.ninegridimageview.NineGridImageView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jp.wasabeef.glide.transformations.ColorFilterTransformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.core.util.ResourceUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest


object ImageUtil {

    private var filename = ""
    private lateinit var imagesDir: File
    private lateinit var imageCheckDir: File

    fun saveOriginalToGallery(context: Context, imageUrl: String) {
        val fileName = imageUrl.substringAfterLast('/')
        CoroutineScope(Dispatchers.IO).launch {
            downloadPicture(context, imageUrl.http2https, fileName, isEnd = true)
        }
    }

    @SuppressLint("CheckResult")
    fun showIMG(view: ImageView, url: String?, isCover: Boolean = false) {
        if (!url.isNullOrEmpty()) {
            val newUrl = GlideUrl(
                url.http2https,
                LazyHeaders.Builder().addHeader("User-Agent", USER_AGENT).build()
            )
            Glide
                .with(view).apply {
                    if (url.endsWith(".gif"))
                        asGif()
                }
                .load(newUrl).apply {
                    if (isCover) {
                        transform(ColorFilterTransformation(Color.parseColor("#8A000000")))
                    } else if (ResourceUtils.isNightMode(view.context.resources.configuration)
                        && PrefManager.isColorFilter
                    ) {
                        transform(
                            CenterCrop(),
                            ColorFilterTransformation(Color.parseColor("#2D000000"))
                        )
                    } else {
                        transform(CenterCrop())
                    }
                }
                .transition(withCrossFade())
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(false)
                .into(view)
        }
    }

    private suspend fun saveImageToGallery(ctx: Context, imageUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            var success = false

            if (!imagesDir.exists())
                imagesDir.mkdirs()

            try {
                val file = Glide.with(ctx)
                    .asFile()
                    .load(imageUrl.http2https)
                    .submit()
                    .get()
                val image = File(imagesDir, filename)
                file.copyTo(image, overwrite = true)

                // 将图片插入图库
//                MediaStore.Images.Media.insertImage(
//                    ctx.contentResolver,
//                    image.absolutePath,
//                    image.name,
//                    image.name
//                )

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.TITLE, image.name)
                    put(MediaStore.Images.Media.DESCRIPTION, image.name)
                    // 在 Android 10 及更高版本，需要使用 MediaStore.Images.Media.RELATIVE_PATH
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
                    }
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") // 根据实际情况设置 MIME 类型
                }
                // 将图像插入 MediaStore，并获取插入后的 URI
                ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

                success = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
            success
        }

    private suspend fun saveImage(context: Context, url: String, isEnd: Boolean) {
        val index = url.lastIndexOf('/')
        filename = url.substring(index + 1)
        imagesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            context.getString(R.string.app_name)
        )
        imageCheckDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "${context.getString(R.string.app_name)}/$filename"
        )
        if (imageCheckDir.exists()) {
            if (isEnd)
                withContext(Dispatchers.Main) {
                    context.makeToast("文件已存在")
                }
        } else {
            downloadPicture(context, url.http2https, filename, isEnd)
        }
    }

    /**
     * 九宫格长按菜单：保存图片 / 保存全部 / 分享 / 复制地址，每项带图标。
     */
    fun showSaveImgDialog(context: Context, url: String, urlList: List<String>?) {
        val actions = listOf(
            ImageMenuAction(R.string.save_image, R.drawable.ic_save_original) {
                CoroutineScope(Dispatchers.IO).launch {
                    saveImage(context, url, true)
                }
            },
            ImageMenuAction(R.string.save_all_image, R.drawable.ic_save_original) {
                CoroutineScope(Dispatchers.IO).launch {
                    if (urlList.isNullOrEmpty()) {
                        saveImage(context, url, true)
                    } else {
                        var isEnd = false
                        urlList.forEach {
                            if (urlList.indexOf(it) == urlList.size - 1)
                                isEnd = true
                            saveImage(context, it, isEnd)
                        }
                    }
                }
            },
            ImageMenuAction(R.string.share_image, R.drawable.ic_share) {
                CoroutineScope(Dispatchers.IO).launch {
                    val index = url.lastIndexOf('/')
                    filename = url.substring(index + 1)
                    imagesDir = File(context.externalCacheDir, "imageShare")
                    imageCheckDir = File(context.externalCacheDir, "imageShare/$filename")
                    if (imageCheckDir.exists()) {
                        withContext(Dispatchers.Main) {
                            shareImage(
                                context, File(
                                    context.externalCacheDir,
                                    "imageShare/$filename",
                                ), null
                            )
                        }
                    } else if (saveImageToGallery(context, url)) {
                        withContext(Dispatchers.Main) {
                            shareImage(
                                context, File(
                                    context.externalCacheDir,
                                    "imageShare/$filename",
                                ), null
                            )
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            context.makeToast("分享失败")
                        }
                    }
                }
            },
            ImageMenuAction(R.string.copy_image_url, R.drawable.ic_link) {
                copyText(context, url)
            }
        )
        MaterialAlertDialogBuilder(context)
            .setAdapter(ImageMenuAdapter(context, actions)) { _, position ->
                actions[position].action()
            }
            .show()
    }

    private class ImageMenuAction(
        @StringRes val titleRes: Int,
        @DrawableRes val iconRes: Int,
        val action: () -> Unit
    )

    /**
     * 带图标的菜单适配器，避免「纯文字」的观感。
     */
    private class ImageMenuAdapter(
        context: Context,
        private val actions: List<ImageMenuAction>
    ) : BaseAdapter() {

        private val textSize = 16f
        private val paddingHorizontal = (20 * context.resources.displayMetrics.density).toInt()
        private val paddingVertical = (14 * context.resources.displayMetrics.density).toInt()
        private val iconSize = (20 * context.resources.displayMetrics.density).toInt()
        private val iconPadding = (10 * context.resources.displayMetrics.density).toInt()

        override fun getCount(): Int = actions.size

        override fun getItem(position: Int): Any = actions[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val textView = convertView as? TextView ?: TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val action = actions[position]
            val icon = ContextCompat.getDrawable(textView.context, action.iconRes)?.mutate()
            icon?.setBounds(0, 0, iconSize, iconSize)
            icon?.setTint(
                MaterialColors.getColor(
                    textView,
                    com.google.android.material.R.attr.colorOnSurface,
                    0
                )
            )
            textView.text = textView.context.getString(action.titleRes)
            textView.setCompoundDrawables(icon, null, null, null)
            textView.compoundDrawablePadding = iconPadding
            textView.isClickable = false
            return textView
        }
    }

    fun startBigImgView(
        nineGridView: NineGridImageView,
        imageView: ImageView,
        urlList: List<String>,
        position: Int
    ) {
        val displayList: MutableList<String> = ArrayList()
        val originList: MutableList<String> = ArrayList()
        urlList.forEach {
            val displayUrl = it.stripThumbSuffix()
            val originalUrl = displayUrl.toOriginalUrl()
            displayList.add(displayUrl)
            originList.add(originalUrl)
        }
        ViewerActivity.start(
            imageView.context,
            displayList,
            originList,
            position
        )

    }

    fun startBigImgViewSimple(
        context: Context,
        imgList: List<String>
    ) {
        val displayList = ArrayList<String>()
        val originList = ArrayList<String>()
        imgList.forEach {
            val displayUrl = it.stripThumbSuffix()
            val originalUrl = displayUrl.toOriginalUrl()
            displayList.add(displayUrl)
            originList.add(originalUrl)
        }
        ViewerActivity.start(context, displayList, originList, 0)
    }

    fun startBigImgViewSimple(
        imageView: ImageView,
        url: String
    ) {
        val displayUrl = url.stripThumbSuffix()
        ViewerActivity.start(
            imageView.context,
            listOf(displayUrl),
            listOf(displayUrl.toOriginalUrl()),
            0
        )
    }

    /**
     * 去掉酷安缩略图后缀（`xxx.s.jpg` -> `xxx.jpg`），拿回楼里那张清晰图。
     * 注意：酷安的缩略图后缀是插在扩展名"之前"的 `.s.`，不是文件名结尾，
     * 所以不能只看最后一个点，要匹配结尾的 `.s.<ext>`。
     */
    private fun String.stripThumbSuffix(): String {
        val dotIndex = lastIndexOf('.')
        if (dotIndex <= 0) return http2https
        val ext = substring(dotIndex)            // 例如 ".jpg"
        val head = substring(0, dotIndex)        // 例如 ".../1.s"
        val stripped = if (head.endsWith(".s", ignoreCase = true)) {
            head.dropLast(2) + ext
        } else {
            this
        }
        return stripped.http2https
    }

    /**
     * 在原图基础上再摘掉扩展名，得到酷安真正的原图地址。
     */
    private fun String.toOriginalUrl(): String {
        val lower = lowercase()
        return if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            substringBeforeLast(".").http2https
        } else {
            this
        }
    }

    fun startBigImgViewSimple(
        context: Context,
        url: String
    ) {
        startBigImgViewSimple(context, listOf(url.http2https))
    }


    // 将File 转化为 content://URI
    private fun getFileProvider(context: Context, file: File): Uri {
        val authority = context.packageName + ".fileprovider"
        return FileProvider.getUriForFile(
            context, authority,
            file
        )
    }

    private fun shareImage(context: Context, file: File, title: String?) {
        val contentUri = getFileProvider(context, file)
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_STREAM, contentUri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, title))
    }

    private fun downloadPicture(context: Context, url: String?, fileName: String, isEnd: Boolean) {
        val newUrl = GlideUrl(
            url?.http2https,
            LazyHeaders.Builder().addHeader("User-Agent", USER_AGENT).build()
        )
        Glide.with(context).downloadOnly().load(newUrl).into(object : FileTarget() {
            override fun onLoadFailed(errorDrawable: Drawable?) {
                super.onLoadFailed(errorDrawable)
                Toast.makeText(context, "download failed", Toast.LENGTH_SHORT).show()
            }

            override fun onResourceReady(resource: File, transition: Transition<in File>?) {
                super.onResourceReady(resource, transition)
                save(context, resource, fileName, isEnd)
            }
        })
    }

    //from BigImageViewPager
    private fun save(context: Context, resource: File, fileName: String, isEnd: Boolean) {
        val mimeType = getImageTypeWithMime(resource.absolutePath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 大于等于29版本的保存方法
            val resolver = context.contentResolver
            // 设置文件参数到ContentValues中
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            values.put(MediaStore.Images.Media.DESCRIPTION, fileName)
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/$mimeType")
            values.put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/c001apk/"
            )
            val insertUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            var inputStream: BufferedInputStream? = null
            var os: OutputStream? = null
            try {
                inputStream = BufferedInputStream(FileInputStream(resource.absolutePath))
                os = insertUri?.let { resolver.openOutputStream(it) }
                os?.let {
                    val buffer = ByteArray(1024 * 4)
                    var len: Int
                    while (inputStream.read(buffer).also { len = it } != -1) {
                        os.write(buffer, 0, len)
                    }
                    os.flush()
                }
                if (isEnd)
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                e.printStackTrace()
                if (isEnd)
                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            } finally {
                try {
                    os?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                try {
                    inputStream?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        } else {
            // 低于29版本的保存方法
            val path = Environment.getExternalStorageDirectory()
                .toString() + "/c001apk/"
            createFileByDeleteOldFile(path + filename)
            val result = copyFile(resource, path, filename)
            if (result) {
                if (isEnd)
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            } else {
                if (isEnd)
                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getImageTypeWithMime(path: String): String {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, options)
        var type = options.outMimeType
        Log.d("getImageTypeWithMime", "getImageTypeWithMime: path = $path, type1 = $type")
        // ”image/png”、”image/jpeg”、”image/gif”
        type = if (TextUtils.isEmpty(type)) {
            ""
        } else {
            type.substring(6)
        }
        Log.d("getImageTypeWithMime", "getImageTypeWithMime: path = $path, type2 = $type")
        return type
    }

    fun getImageLp(url: String): Pair<Int, Int> {
        var imgWidth = 1
        var imgHeight = 1
        val at = url.lastIndexOf("@")
        val x = url.lastIndexOf("x")
        val dot = url.lastIndexOf(".")
        if (at != -1 && x != -1 && dot != -1) {
            imgWidth = url.substring(at + 1, x).toInt()
            imgHeight = url.substring(x + 1, dot).toInt()
        }
        return Pair(imgWidth, imgHeight)
    }

    fun ByteArray.toHex(): String {
        val hexString = StringBuilder()
        for (byte in this) {
            hexString.append(String.format("%02x", byte))
        }
        return hexString.toString()
    }

    fun getImageDimensionsAndMD5(
        contentResolver: ContentResolver,
        uri: Uri
    ): Pair<Triple<Int, Int, String>?, ByteArray?> {
        var dimensions: Triple<Int, Int, String>? = null
        var md5Hash: ByteArray? = null

        try {
            dimensions = contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)
                var width = options.outWidth
                var height = options.outHeight
                with(getRotation(contentResolver, uri)) {
                    if (this == 90 || this == 270) {
                        width = height.apply {
                            height = width
                        }
                    }
                }
                Triple(width, height, options.outMimeType)
            }
            md5Hash = contentResolver.openInputStream(uri)?.use { inputStream ->
                calculateMD5(inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(dimensions, md5Hash)
    }

    private fun getRotation(
        contentResolver: ContentResolver,
        uri: Uri
    ): Int {
        var rotation = 0
        try {
            val exif = contentResolver.openInputStream(uri)?.let { ExifInterface(it) }
            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return rotation
    }

    private fun calculateMD5(input: InputStream): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        var bytesRead = input.read(buffer)
        while (bytesRead > 0) {
            md.update(buffer, 0, bytesRead)
            bytesRead = input.read(buffer)
        }
        return md.digest()
    }

}
