package com.luckyzyx.luckytool.hook.scopes.systemui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Rect
import android.graphics.RenderEffect
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.descendants

/** Use the stock waveform's animated alpha without replacing or restarting the animation. */
internal class DynamicIslandPlaybackMask(context: Context, loader: ClassLoader) {
    private val packageManager = context.packageManager
    private val lottieView = loader.loadClass("com.airbnb.lottie.LottieAnimationView")
    private val lottieDrawable = loader.loadClass("com.airbnb.lottie.LottieDrawable")
    private val getComposition = lottieView.getMethod("getComposition")
    private val isAnimating = lottieView.getMethod("isAnimating")
    private val cacheType = loader.loadClass("com.airbnb.lottie.model.LottieCompositionCache")
    private val cache = cacheType.getMethod("getInstance").invoke(null)
    private val getCachedComposition = cacheType.getMethod("get", String::class.java)
    private val waveformKey = packageManager.getResourcesForApplication(PLUGIN).let { res ->
        val id = res.getIdentifier("voice_print_dark", "raw", PLUGIN)
        // The stock PictureModule caches parsed JSON under this key.
        res.openRawResource(id).bufferedReader().use { "lottie_${it.readText().hashCode()}" }
    }
    private var seenWaveform: Any? = null
    private var iconPackage: String? = null
    private var icon: Bitmap? = null
    private var target: ImageView? = null
    private val targetBounds = Rect()
    private val stockAlpha = RenderEffect.createOffsetEffect(0f, 0f)

    fun update(capsule: ViewGroup?, playingPackage: () -> String?) {
        if (capsule == null || !capsule.isHardwareAccelerated) return clear()
        val cached = getCachedComposition.invoke(cache, waveformKey)
        if (cached == null && seenWaveform == null) return clear()
        val wave = capsule.descendants.filterIsInstance<ImageView>().singleOrNull {
            if (!it.isShown || it.alpha <= 0f || it.width <= 0 || it.height <= 0 ||
                !lottieView.isInstance(it) || !lottieDrawable.isInstance(it.drawable)
            ) return@singleOrNull false
            val composition = getComposition.invoke(it) ?: return@singleOrNull false
            (composition === cached || composition === seenWaveform) && isAnimating.invoke(it) == true
        } ?: return clear()
        seenWaveform = getComposition.invoke(wave)
        val pkg = playingPackage() ?: return clear()
        if (pkg != iconPackage) {
            clear()
            iconPackage = pkg
            icon = runCatching { packageManager.getApplicationIcon(pkg).toBitmap(ICON_SIZE, ICON_SIZE) }.getOrNull()
        }
        val source = icon ?: return clear()
        if (target === wave && targetBounds.width() == wave.width && targetBounds.height() == wave.height) return
        clear()
        targetBounds.set(0, 0, wave.width, wave.height)
        val appArtwork = RenderEffect.createBitmapEffect(source, null, targetBounds)
        wave.setRenderEffect(RenderEffect.createBlendModeEffect(stockAlpha, appArtwork, BlendMode.SRC_IN))
        target = wave
    }

    fun clear() {
        target?.setRenderEffect(null)
        target = null
    }

    private companion object {
        const val PLUGIN = "com.oplus.systemui.plugins"
        const val ICON_SIZE = 96
    }
}
