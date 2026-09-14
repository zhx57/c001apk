package com.example.c001apk.ui.others.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.c001apk.R
import com.example.c001apk.constant.Constants.USER_AGENT
import com.example.c001apk.util.ImageUtil.saveOriginalToGallery
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.progressindicator.CircularProgressIndicator

class ViewerActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(
            WindowInsetsCompat.Type.statusBars()
        )
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        window.setBackgroundDrawableResource(android.R.color.black)

        val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: emptyList()
        val targets = intent.getStringArrayListExtra(EXTRA_TARGETS) ?: emptyList()
        if (urls.isEmpty()) {
            finish()
            return
        }

        viewPager = ViewPager2(this)
        viewPager.adapter = ViewerPageAdapter(urls, targets)
        viewPager.setCurrentItem(intent.getIntExtra(EXTRA_POSITION, 0), false)
        setContentView(
            viewPager,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_TARGETS = "targets"
        private const val EXTRA_POSITION = "position"

        fun start(
            context: Context,
            urls: List<String>,
            targets: List<String>,
            position: Int
        ) {
            context.startActivity(
                Intent(context, ViewerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                    .putStringArrayListExtra(EXTRA_TARGETS, ArrayList(targets))
                    .putExtra(EXTRA_POSITION, position)
            )
        }
    }

    private class ViewerPageAdapter(
        private val urls: List<String>,
        private val targets: List<String>
    ) : RecyclerView.Adapter<ViewerPageHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewerPageHolder {
            val page = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_viewer_page, parent, false) as ConstraintLayout
            val photoView = page.findViewById<PhotoView>(R.id.photo_view)
            val progress = page.findViewById<CircularProgressIndicator>(R.id.progress)
            val retryText = page.findViewById<LinearLayout>(R.id.retry)
            val loadOriginal = page.findViewById<View>(R.id.load_original)
            return ViewerPageHolder(page, photoView, progress, retryText, loadOriginal)
        }

        override fun onBindViewHolder(holder: ViewerPageHolder, position: Int) {
            holder.bind(urls[position], targets.getOrNull(position))
        }

        override fun onViewRecycled(holder: ViewerPageHolder) {
            holder.cleanup()
        }

        override fun getItemCount(): Int = urls.size
    }

    private class ViewerPageHolder(
        private val page: ConstraintLayout,
        private val photoView: PhotoView,
        private val progress: CircularProgressIndicator,
        private val retryText: LinearLayout,
        private val loadOriginal: View
    ) : RecyclerView.ViewHolder(page) {

        private var boundUrl: String? = null
        private var targetUrl: String? = null
        private var originalUrl: String? = null
        private var displayedUrl: String? = null
        private var requestSequence = 0
        private var latestRequestToken = 0
        private var latestRequestUrl: String? = null
        private var fallbackAttempted = false
        private val handler = Handler(Looper.getMainLooper())
        private var touchSlopSquare = 0
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private var downX = 0f
        private var downY = 0f

        private var gesturesInitialized = false

        private fun setupGestures() {
            if (gesturesInitialized) return
            gesturesInitialized = true
            val touchSlop = ViewConfiguration.get(photoView.context).scaledTouchSlop
            touchSlopSquare = touchSlop * touchSlop
            retryText.setOnClickListener {
                val retryUrl = targetUrl ?: displayedUrl ?: boundUrl ?: return@setOnClickListener
                latestRequestToken = ++requestSequence
                progress.isVisible = true
                retryText.isVisible = false
                loadOriginal.isVisible = false
                loadInto(
                    retryUrl,
                    requestToken = latestRequestToken,
                    showRetryOnFailure = true
                )
            }
            loadOriginal.setOnClickListener {
                val original = originalUrl ?: return@setOnClickListener
                latestRequestToken = ++requestSequence
                progress.isVisible = true
                retryText.isVisible = false
                loadOriginal.isVisible = false
                loadInto(
                    original,
                    forceOriginalSize = true,
                    requestToken = latestRequestToken,
                    showRetryOnFailure = true
                )
            }
            photoView.setScaleType(ImageView.ScaleType.FIT_CENTER)
            photoView.setMinimumScale(1f)
            photoView.setMaximumScale(10f)
            photoView.setMediumScale(3f)
            photoView.setZoomable(true)
            photoView.setAllowParentInterceptOnEdge(false)
            photoView.setOnViewTapListener { _, _, _ ->
                (photoView.context as? AppCompatActivity)?.finish()
            }

            val longPressRunnable = Runnable { showSaveDialog() }
            page.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        handler.postDelayed(longPressRunnable, longPressTimeout)
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> handler.removeCallbacks(longPressRunnable)
                    MotionEvent.ACTION_MOVE -> {
                        val pointerIndex = if (event.pointerCount > 0) 0 else -1
                        if (pointerIndex >= 0) {
                            val deltaX = event.getX(pointerIndex) - downX
                            val deltaY = event.getY(pointerIndex) - downY
                            if (deltaX * deltaX + deltaY * deltaY > touchSlopSquare) {
                                handler.removeCallbacks(longPressRunnable)
                            }
                        } else {
                            handler.removeCallbacks(longPressRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPressRunnable)
                }
                view.performClick()
                false
            }
        }

        fun bind(url: String, target: String?) {
            setupGestures()
            boundUrl = url
            // 直接加载去缩略图后缀的原图地址（即无扩展名基址），
            // 这样不用点「查看原图」就是清晰的。
            // 注意：酷安的原图就是基址本身，不存在 `.jpg` 形态，
            // 所以这里和 originalUrl 通常相同，此时不再显示「查看原图」按钮。
            val sharpUrl = stripThumbSuffix(url)
            val sharpTarget = target?.let { stripThumbSuffix(it) }
            targetUrl = sharpUrl
            originalUrl = sharpTarget?.takeIf { it != sharpUrl }
            displayedUrl = null
            fallbackAttempted = false
            latestRequestToken = ++requestSequence
            photoView.scale = 1f
            retryText.isVisible = false
            loadOriginal.isVisible = false
            progress.isVisible = true
            loadInto(sharpUrl, requestToken = latestRequestToken, showRetryOnFailure = true)
        }

        /**
         * 把九宫格拼出来的缩略图地址还原成酷安真正的清晰图地址。
         *
         * 酷安的缩略图是在"无扩展名基址"后拼 `.s.jpg`（`.../abc123.s.jpg`），
         * 而清晰图/原图就是**基址本身**（`.../abc123`）。
         * **不存在** `.../abc123.jpg`，请求它必定 404 —— 这正是"所有图片无法加载"的原因。
         */
        private fun stripThumbSuffix(raw: String): String {
            return if (raw.endsWith(".s.jpg", ignoreCase = true)) {
                raw.dropLast(".s.jpg".length)
            } else {
                raw
            }
        }

        private fun loadInto(
            requestUrl: String,
            forceOriginalSize: Boolean = false,
            requestToken: Int,
            showRetryOnFailure: Boolean,
            upgradeFromUrl: String? = null
        ) {
            latestRequestUrl = requestUrl
            progress.isVisible = true
            retryText.isVisible = false
            if (upgradeFromUrl == null) {
                loadOriginal.isVisible = false
            }
            val glideUrl = GlideUrl(
                requestUrl,
                LazyHeaders.Builder().addHeader("User-Agent", USER_AGENT).build()
            )
            Glide.with(photoView)
                .load(glideUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .apply {
                    if (forceOriginalSize) override(Target.SIZE_ORIGINAL)
                }
                .transition(DrawableTransitionOptions.withCrossFade())
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (!isLatestRequest(requestToken, requestUrl)) return true
                        progress.isVisible = false
                        // 清晰图（基址）万一失败，退一步加载九宫格那张缩略图，
                        // 至少让用户看到图，而不是白屏 + 重试按钮。
                        val fallbackUrl = if (upgradeFromUrl == null && !fallbackAttempted &&
                            !requestUrl.endsWith(".s.jpg", ignoreCase = true)
                        ) {
                            "$requestUrl.s.jpg"
                        } else {
                            null
                        }
                        if (fallbackUrl != null) {
                            fallbackAttempted = true
                            latestRequestToken = ++requestSequence
                            loadInto(
                                fallbackUrl,
                                requestToken = latestRequestToken,
                                showRetryOnFailure = true,
                                upgradeFromUrl = requestUrl
                            )
                        } else if (showRetryOnFailure) {
                            retryText.isVisible = true
                        }
                        return true
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (!isLatestRequest(requestToken, requestUrl)) return true
                        progress.isVisible = false
                        retryText.isVisible = false
                        if (photoView.drawable == null || forceOriginalSize) {
                            photoView.scale = 1f
                        }
                        photoView.setImageDrawable(resource)
                        displayedUrl = requestUrl
                        loadOriginal.isVisible = originalUrl != null && originalUrl != requestUrl
                        return true
                    }
                })
                .into(photoView)
        }

        /**
         * 长按弹菜单：保存原图到相册（带图标）
         */
        private fun showSaveDialog() {
            val activity = photoView.context as? Activity ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            val saveUrl = originalUrl ?: displayedUrl ?: targetUrl ?: boundUrl ?: return
            val actions = listOf(
                SaveAction(R.string.save_original_to_gallery, R.drawable.ic_save_original) {
                    saveOriginalToGallery(photoView.context.applicationContext, saveUrl)
                }
            )
            val dialog = MaterialAlertDialogBuilder(photoView.context)
                .setTitle(R.string.long_press_action)
                .setAdapter(IconTextAdapter(photoView.context, actions)) { _, position ->
                    actions[position].action()
                }
                .create()
            if (activity.isFinishing || activity.isDestroyed) return
            dialog.show()
        }

        private class SaveAction(
            @StringRes val titleRes: Int,
            @DrawableRes val iconRes: Int,
            val action: () -> Unit
        )

        /**
         * 带图标的纯文字菜单适配器：左边一个小图标，右边文字。
         */
        private class IconTextAdapter(
            context: Context,
            private val actions: List<SaveAction>
        ) : BaseAdapter() {
            private val textSize = 16f
            private val horizontal = (20 * context.resources.displayMetrics.density).toInt()
            private val vertical = (14 * context.resources.displayMetrics.density).toInt()
            private val iconSize = (20 * context.resources.displayMetrics.density).toInt()
            private val iconPadding = (10 * context.resources.displayMetrics.density).toInt()

            override fun getCount(): Int = actions.size

            override fun getItem(position: Int): Any = actions[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val textView = convertView as? TextView ?: TextView(parent.context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                    setPadding(horizontal, vertical, horizontal, vertical)
                    layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                val action = actions[position]
                // 图标颜色跟随对话框正文，避免主题缺色时退回 Material3 基线的紫色
                val labelColor = textView.currentTextColor
                textView.setTextColor(labelColor)
                val icon = ContextCompat.getDrawable(textView.context, action.iconRes)?.mutate()
                icon?.setBounds(0, 0, iconSize, iconSize)
                icon?.setTint(labelColor)
                textView.text = textView.context.getString(action.titleRes)
                textView.setCompoundDrawables(icon, null, null, null)
                textView.compoundDrawablePadding = iconPadding
                textView.isClickable = false
                return textView
            }
        }

        private fun isLatestRequest(token: Int, requestUrl: String): Boolean {
            val activity = photoView.context as? Activity
            return token == latestRequestToken &&
                requestUrl == latestRequestUrl &&
                activity?.isFinishing != true &&
                activity?.isDestroyed != true
        }

        fun cleanup() {
            handler.removeCallbacksAndMessages(null)
            latestRequestToken = ++requestSequence
        }
    }
}
