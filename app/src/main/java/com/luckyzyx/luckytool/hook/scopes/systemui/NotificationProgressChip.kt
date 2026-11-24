package com.luckyzyx.luckytool.hook.scopes.systemui

import android.animation.ValueAnimator
import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.view.View
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class NotificationProgressTask(
    val key: String,
    val packageName: String,
    val fraction: Float?, // Null means the app reports progress without a total.
    val smallIcon: Icon? = null,
) {
    companion object {
        fun from(key: String, packageName: String, notification: Notification): NotificationProgressTask? {
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
                notification.visibility == Notification.VISIBILITY_SECRET
            ) return null
            val extras = notification.extras ?: return null
            if (extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) {
                return NotificationProgressTask(key, packageName, fraction = null, smallIcon = notification.smallIcon)
            }
            val maximum = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val progress = extras.getInt(Notification.EXTRA_PROGRESS, -1)
            if (maximum <= 0 || progress < 0 || progress >= maximum) return null
            return NotificationProgressTask(key, packageName, progress.toFloat() / maximum, notification.smallIcon)
        }
    }
}

/** Keep the app and the order of its bars stable while its notifications are updated. */
internal class NotificationProgressSelection {
    private var keys = emptyList<String>()
    private var selectedPackage: String? = null

    fun update(active: List<NotificationProgressTask>): List<NotificationProgressTask> {
        val byKey = active.associateByTo(linkedMapOf()) { it.key }
        val ordered = keys.mapNotNull(byKey::remove) + byKey.values
        keys = ordered.map { it.key }
        val selected = ordered.firstOrNull { it.packageName == selectedPackage } ?: ordered.firstOrNull()
        selectedPackage = selected?.packageName
        return if (selected == null) emptyList() else ordered.filter { it.packageName == selected.packageName }
    }
}

/** One notification or app icon and up to four tasks in a native status bar slot. */
internal class NotificationProgressChip(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var iconSize = readIconSize()
    private var stockIconScale = 1f
    private val iconDrawingSize get() = (iconSize * stockIconScale).roundToInt().coerceAtLeast(1)
    private val iconBounds = Rect()
    private var iconInk = Rect(0, 0, iconSize, iconSize)
    private var bars = emptyList<Float?>()
    private var icon: Drawable? = null
    private var displayedApp: String? = null
    private var displayedSmallIcon: Icon? = null
    var measuredBars = 0
        private set
    private var tint = Color.WHITE
    private var accent = readStatusBarProgressAccent(context)

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun show(tasks: List<NotificationProgressTask>, description: CharSequence) {
        val task = tasks.firstOrNull() ?: return
        // Pick using the full task count, even if the available width fits only one bar.
        val smallIcon = tasks.singleOrNull()?.smallIcon
        if (displayedApp != task.packageName || displayedSmallIcon != smallIcon || icon == null) {
            displayedApp = task.packageName
            displayedSmallIcon = smallIcon
            val packages = context.packageManager
            icon = (runCatching { smallIcon?.loadDrawable(context) }.getOrNull()
                ?: runCatching { packages.getApplicationIcon(task.packageName) }
                    .getOrElse { packages.defaultActivityIcon }).mutate()
            iconInk = readIconInk()
            requestLayout()
        }
        val next = tasks.take(MAX_BARS).map { it.fraction }
        if (bars.size != next.size) requestLayout()
        bars = next
        contentDescription = description
        visibility = VISIBLE
        invalidate()
    }

    fun applyTint(color: Int) {
        if (color == tint) return
        tint = color
        invalidate()
    }

    fun applyStockIconScale(scale: Float) {
        val next = if (scale > 0f && scale.isFinite()) scale.coerceAtMost(1f) else 1f
        if (next == stockIconScale) return
        stockIconScale = next
        iconInk = readIconInk()
        requestLayout()
    }

    fun drawInSlot(canvas: Canvas, host: View) {
        draw(canvas)
        // The chip is drawn by the native slot, so only that visible view schedules the next frame.
        if (host.isShown && host.windowVisibility == VISIBLE && shouldAnimate) host.postInvalidateOnAnimation()
    }

    private val drawnBars get() = minOf(measuredBars, bars.size)
    private val shouldAnimate get() = (0 until drawnBars).any { bars[it] == null } && ValueAnimator.areAnimatorsEnabled()
    private val edgeSpace get() = iconSize / 4f
    private val pillHeight get() = iconDrawingSize + edgeSpace
    private val barWidth get() = iconSize * .15f
    private val pitch get() = barWidth * 2f
    private val rtl get() = layoutDirection == LAYOUT_DIRECTION_RTL
    private val iconInset get() = edgeSpace.roundToInt()
    private val barsStart get() = iconInset + (if (rtl) iconDrawingSize - iconInk.left else iconInk.right) + edgeSpace / 2f
    private fun desiredWidth(count: Int) = barsStart + pitch * (count - 1) + barWidth + edgeSpace

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthLimit = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else MeasureSpec.getSize(widthMeasureSpec)
        val rowHeight = resolveSize(ceil(pillHeight).toInt(), heightMeasureSpec)
        measuredBars = if (rowHeight < pillHeight) 0 else
            (bars.size downTo 1).firstOrNull { ceil(desiredWidth(it)).toInt() <= widthLimit } ?: 0
        val width = if (measuredBars == 0) 0 else ceil(desiredWidth(measuredBars)).toInt()
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), rowHeight)
    }

    override fun onDraw(canvas: Canvas) {
        if (measuredBars == 0 || width == 0) return
        val iconTop = ((height - iconDrawingSize) / 2f).roundToInt()
        val top = (iconTop + iconInk.top).toFloat()
        val bottom = (iconTop + iconInk.bottom).toFloat()
        val pillTop = (height - pillHeight) / 2f
        val pillBottom = pillTop + pillHeight
        paint.color = tint
        paint.alpha = Color.alpha(tint) / 10
        canvas.drawRoundRect(0f, pillTop, width.toFloat(), pillBottom, pillHeight / 2f, pillHeight / 2f, paint)
        val iconLeft = if (rtl) width - iconInset - iconDrawingSize else iconInset
        icon?.let {
            it.setBounds(
                iconLeft + iconBounds.left, iconTop + iconBounds.top,
                iconLeft + iconBounds.right, iconTop + iconBounds.bottom,
            )
            it.draw(canvas)
        }
        val radius = barWidth * .36f
        val barHeight = bottom - top
        val phase = if (shouldAnimate) (SystemClock.uptimeMillis() % SWEEP_MS) / SWEEP_MS.toFloat() else .5f
        for (index in 0 until drawnBars) {
            val offset = barsStart + pitch * index
            val x = if (rtl) width - offset - barWidth else offset
            paint.color = tint
            paint.alpha = (Color.alpha(tint) * .22f).roundToInt()
            canvas.drawRoundRect(x, top, x + barWidth, bottom, radius, radius, paint)
            val fraction = bars[index]
            val fillTop: Float
            val fillBottom: Float
            if (fraction == null) {
                val segmentHeight = barHeight * .35f
                val movingBottom = bottom + segmentHeight - (barHeight + segmentHeight) * phase
                fillTop = maxOf(top, movingBottom - segmentHeight)
                fillBottom = minOf(bottom, movingBottom)
            } else {
                fillTop = bottom - barHeight * fraction
                fillBottom = bottom
            }
            val fill = fillBottom - fillTop
            if (fill <= 0f) continue
            val fillRadius = minOf(radius, fill / 2f)
            paint.color = accent
            canvas.drawRoundRect(x, fillTop, x + barWidth, fillBottom, fillRadius, fillRadius, paint)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        iconSize = readIconSize()
        iconInk = readIconInk()
        accent = readStatusBarProgressAccent(context)
        requestLayout()
        invalidate()
    }

    private fun readIconSize(): Int {
        // The notification slot size is unscaled; StatusBarIconView applies its own artwork scale.
        val id = resources.getIdentifier("status_bar_notification_icon_size", "dimen", "com.android.systemui")
        val nativeSize = if (id == 0) 0 else resources.getDimensionPixelSize(id)
        return nativeSize.takeIf { it > 0 }
            ?: (16 * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    }

    private fun readIconInk(): Rect {
        val size = iconDrawingSize
        iconBounds.set(0, 0, size, size)
        val drawable = icon ?: return Rect(iconBounds)
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (width > 0 && height > 0) {
            val fit = size.toFloat() / maxOf(width, height)
            val fittedWidth = (width * fit).roundToInt().coerceIn(1, size)
            val fittedHeight = (height * fit).roundToInt().coerceIn(1, size)
            val left = (size - fittedWidth) / 2
            val top = (size - fittedHeight) / 2
            iconBounds.set(left, top, left + fittedWidth, top + fittedHeight)
        }
        if (size > 256) return Rect(iconBounds)
        return runCatching {
            val oldBounds = drawable.copyBounds()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                drawable.bounds = iconBounds
                drawable.draw(Canvas(bitmap))
                val pixels = IntArray(size * size)
                bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
                val ink = Rect()
                for (index in pixels.indices) {
                    // Exclude faint shadows and antialiased fringes from the alignment bounds.
                    if (Color.alpha(pixels[index]) < 128) continue
                    val x = index % size
                    val y = index / size
                    ink.union(x, y, x + 1, y + 1)
                }
                if (ink.isEmpty) Rect(iconBounds) else ink
            } finally {
                drawable.bounds = oldBounds
                bitmap.recycle()
            }
        }.getOrElse { Rect(iconBounds) }
    }

    private companion object {
        const val MAX_BARS = 4
        const val SWEEP_MS = 1200L
    }
}

internal fun readStatusBarProgressAccent(context: Context): Int {
    fun themeColor(attr: Int): Int? {
        if (attr == 0) return null
        val colors = context.obtainStyledAttributes(intArrayOf(attr))
        return try {
            runCatching { colors.getColor(0, Color.TRANSPARENT) }.getOrNull()?.takeIf { Color.alpha(it) != 0 }
        } finally {
            colors.recycle()
        }
    }
    val colorOsPrimary = context.resources.getIdentifier("couiColorPrimary", "attr", "com.android.systemui")
    return themeColor(colorOsPrimary)
        ?: themeColor(android.R.attr.colorAccent)
        ?: context.getColor(android.R.color.system_accent1_500)
}
