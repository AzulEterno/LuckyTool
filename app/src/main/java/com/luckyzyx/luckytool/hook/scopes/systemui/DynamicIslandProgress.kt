package com.luckyzyx.luckytool.hook.scopes.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ProgressBar
import androidx.core.view.children
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.highcapable.yukihookapi.extension.kavaref.toClassOrNull
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import java.lang.reflect.Modifier

internal const val DYNAMIC_ISLAND_RING_KEY = "dynamic_island_progress_ring"
internal const val DYNAMIC_ISLAND_PLAYBACK_ICON_KEY = "dynamic_island_playback_icon"
internal const val DYNAMIC_ISLAND_MIN_OS = 37

/** Follow the selected Live Alert in the stock Dynamic Island. */
internal class DynamicIslandProgress(private val showRing: Boolean, private val maskPlayback: Boolean) :
    YukiBaseHooker() {
    override fun onHook() {
        runCatching { install() }
            .onFailure { YLog.warn("Dynamic Island progress unavailable: ${it.message}") }
    }

    private fun install() {
        val controller = "com.oplus.systemui.statusbar.seeding.CapsulePluginContainerController"
            .toClassOrNull() ?: return
        val hostField = controller.getDeclaredField("capsulePluginContainer").apply { isAccessible = true }
        val resolver = controller.resolve()
        val attach = resolver.firstMethodOrNull { name = "onViewAttached"; parameterCount = 0 } ?: return
        val detach = resolver.firstMethodOrNull { name = "onViewDetached"; parameterCount = 0 } ?: return
        val pluginAdded = resolver.firstMethodOrNull { name = "onPluginAdded"; parameterCount = 2 } ?: return
        val pluginRemoved = resolver.firstMethodOrNull { name = "onPluginRemove"; parameterCount = 1 } ?: return
        val configuration = resolver.firstMethodOrNull { name = "onConfigChanged"; parameterCount = 1 }
        val media = runCatching {
            val mediaController = "com.oplus.systemui.media.seedling.livealert.MediaLiveAlertController"
                .toClassOrNull() ?: return@runCatching null
            val mediaData = "com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData"
                .toClassOrNull() ?: return@runCatching null
            MediaProgress(mediaController, mediaData)
        }.getOrNull()
        var binding: Binding? = null
        val hookedCapsules = mutableSetOf<Class<*>>()

        fun unbind(instance: Any) {
            if (binding?.host !== hostField.get(instance)) return
            binding?.close()
            binding = null
        }

        fun bind(instance: Any) {
            val host = hostField.get(instance) as? ViewGroup ?: return
            if (!host.isAttachedToWindow || host.display?.displayId != Display.DEFAULT_DISPLAY) return
            val root = host.children.singleOrNull { it.javaClass.name == "${CAPSULE_PACKAGE}CapsuleContainerRoot" }
                as? ViewGroup ?: return
            val capsule = root.children.singleOrNull { it.javaClass.name == "${CAPSULE_PACKAGE}CapsuleContainer" }
                ?: return
            if (binding?.capsule === capsule) return
            val next = Binding(host, capsule, media, showRing, maskPlayback)
            binding?.close()
            binding = next
            next.open()
            if (showRing && hookedCapsules.add(capsule.javaClass)) hookIslandFades(capsule.javaClass) { binding }
        }

        fun connect(instance: Any) {
            runCatching { bind(instance) }
                .onFailure { YLog.warn("Dynamic Island progress could not bind: ${it.message}") }
        }

        attach.hook { after { instanceOrNull<Any>()?.let(::connect) } }
        pluginAdded.hook { after { instanceOrNull<Any>()?.let(::connect) } }
        detach.hook { before { instanceOrNull<Any>()?.let { runCatching { unbind(it) } } } }
        pluginRemoved.hook { before { instanceOrNull<Any>()?.let { runCatching { unbind(it) } } } }
        configuration?.hook { after { runCatching { binding?.ring?.updateConfiguration() } } }
    }

    private fun hookIslandFades(capsule: Class<*>, bound: () -> Binding?) {
        // These plugin listeners own the compact portion of card and capsule transitions.
        // shortcut: listener names vary by plugin build; keep the local fade if they cannot be resolved.
        for (name in listOf("u4.c0", "u4.q0", "u4.f")) runCatching {
            val type = Class.forName(name, false, capsule.classLoader)
            if (!AnimatorListenerAdapter::class.java.isAssignableFrom(type)) return@runCatching
            val container = type.declaredFields.single { it.type == capsule }.apply { isAccessible = true }
            val entering = name == "u4.f"
            val variant = if (entering) type.declaredFields.single { it.type == Int::class.javaPrimitiveType }
                .apply { isAccessible = true } else null
            type.getDeclaredMethod("onAnimationStart", Animator::class.java).hook {
                after {
                    val current = bound() ?: return@after
                    val listener = instanceOrNull<Any>() ?: return@after
                    val animator = args[0] as? AnimatorSet ?: return@after
                    if (container.get(listener) !== current.capsule) return@after
                    // The same synthetic listener also animates the side moons and app launches.
                    if (variant != null && variant.getInt(listener) !in 0..2) return@after
                    current.follow(animator, entering)
                }
            }
        }.onFailure { YLog.warn("Dynamic Island transition $name unavailable: ${it.message}") }
    }

    private class Binding(
        val host: ViewGroup,
        val capsule: View,
        private val media: MediaProgress?,
        showRing: Boolean,
        maskPlayback: Boolean,
    ) : ViewTreeObserver.OnPreDrawListener {
        val ring = if (showRing) DynamicIslandRing(host) else null
        private val playback = if (maskPlayback && media != null) runCatching {
            DynamicIslandPlaybackMask(host.context, capsule.javaClass.classLoader)
        }.onFailure { YLog.warn("Dynamic Island playback mask unavailable: ${it.message}") }.getOrNull() else null
        private val observer = host.viewTreeObserver
        private val power = host.context.getSystemService(PowerManager::class.java)
        private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> ring?.position() }
        // The plugin obfuscates the names of the compact state and the selected-view getter.
        private val state = capsule.javaClass.declaredFields.single {
            val states = it.type.enumConstants ?: return@single false
            states.any { value -> (value as Enum<*>).name == "NORMAL" } &&
                states.any { value -> (value as Enum<*>).name == "MIN" }
        }.apply { isAccessible = true }
        private val selectedView = capsule.javaClass.methods.single {
            it.parameterCount == 0 && it.returnType.name == "${CAPSULE_PACKAGE}CapsuleView"
        }
        private val miniView = runCatching {
            capsule.javaClass.getMethod("getMiniView").invoke(capsule) as? ViewGroup
        }.getOrNull()
        private val viewItem = selectedView.returnType.getMethod("getViewItem")
        private val itemKey = viewItem.returnType.declaredFields.single {
            !Modifier.isStatic(it.modifiers) && it.type == String::class.java
        }.apply { isAccessible = true }

        private var lastKey: String? = null
        private var lastProgressView: View? = null
        private var nextReadAt = 0L
        private var progress: IslandProgress? = null
        private var transition: IslandRingTransition? = null
        private var tickPending = false
        private val tick = Runnable {
            tickPending = false
            if (power?.isInteractive == true && host.isShown && host.windowVisibility == View.VISIBLE) host.invalidate()
        }

        fun open() {
            ring?.let {
                host.overlay.add(it)
                host.addOnLayoutChangeListener(layout)
                it.position()
            }
            observer.addOnPreDrawListener(this)
        }

        fun close() {
            transition = null
            host.removeCallbacks(tick)
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            playback?.clear()
            ring?.let {
                host.removeOnLayoutChangeListener(layout)
                it.hideImmediately()
                host.overlay.remove(it)
            }
        }

        fun follow(animator: AnimatorSet, entering: Boolean) {
            val ring = ring ?: return
            transition = IslandRingTransition(animator, entering, ring.fadeAlpha)
            host.postInvalidateOnAnimation()
        }

        override fun onPreDraw(): Boolean {
            val visible = power?.isInteractive == true && host.isShown && host.windowVisibility == View.VISIBLE
            playback?.let { mask ->
                runCatching {
                    val full = if (visible && isNormal()) {
                        (selectedView.invoke(capsule) as? ViewGroup)?.takeIf { it.isShown && it.alpha > 0f }
                    } else null
                    mask.update(full) { keyOf(full)?.let { media?.playingPackage(it) } }
                }.onFailure { mask.clear() }
            }
            val ring = ring ?: return true
            val motion = transition
            val running = motion?.takeIf { it.animator.isStarted }
            val update = if (visible && running?.entering != false) {
                runCatching { readProgress(ring, entering = running != null) }.getOrNull()
            } else null
            when {
                !visible -> ring.hideImmediately()
                running != null -> {
                    val alpha = if (running.entering && update == null) 0 else running.opacity()
                    ring.show(update, stockOpacity = alpha)
                    if (ring.isPositioned) host.postInvalidateOnAnimation()
                }
                motion != null -> ring.show(update, stockOpacity = if (update == null) 0 else 255)
                else -> ring.show(update)
            }
            if (running == null) transition = null
            if (update != null && ring.isPositioned) {
                if (!tickPending) tickPending = host.postDelayed(tick, UPDATE_INTERVAL_MS)
            } else if (tickPending) {
                host.removeCallbacks(tick)
                tickPending = false
            }
            return true
        }

        private fun isNormal() = (state.get(capsule) as? Enum<*>)?.name == "NORMAL"

        private fun keyOf(view: View?): String? {
            val item = view?.let { viewItem.invoke(it) } ?: return null
            return (itemKey.get(item) as? String)?.takeIf(String::isNotBlank)
        }

        private fun readProgress(ring: DynamicIslandRing, entering: Boolean): IslandProgress? {
            // ColorOS can show the mini artwork while the normal capsule state is HIDE.
            val mini = miniView?.takeIf {
                it.isShown && it.alpha > 0f && it.width > 0 && it.height > 0 &&
                    it.getChildAt(0)?.let { content -> content.isShown && content.alpha > 0f } == true
            }
            val hasCompactView = mini != null || ((entering || capsule.isShown) && isNormal())
            val selected = if (hasCompactView) selectedView.invoke(capsule) as? View else null
            val view = mini ?: selected?.takeIf { entering || (it.isShown && it.alpha > 0f) }
            val key = if (view != null) keyOf(selected) else null
            if (key != null) ring.positionForMini(mini)
            val now = SystemClock.uptimeMillis()
            if (key != lastKey || view !== lastProgressView || now >= nextReadAt) {
                progress = if (key != null && view != null) {
                    media?.read(key) ?: view.visibleProgressBars().take(2).toList().singleOrNull()?.let { bar ->
                        if (bar.isIndeterminate) IslandProgress(null)
                        else IslandProgress.from(bar.progress.toLong() - bar.min, bar.max.toLong() - bar.min)
                    }
                } else null
                lastKey = key
                lastProgressView = view
                nextReadAt = now + UPDATE_INTERVAL_MS
            }
            return progress
        }

        private fun View.visibleProgressBars(): Sequence<ProgressBar> = sequence {
            if (visibility != View.VISIBLE || alpha <= 0f) return@sequence
            if (this@visibleProgressBars is ProgressBar) yield(this@visibleProgressBars)
            if (this@visibleProgressBars is ViewGroup) {
                for (child in children) yieldAll(child.visibleProgressBars())
            }
        }
    }

    private class MediaProgress(controller: Class<*>, data: Class<*>) {
        private val instance = controller.getDeclaredField("instance").apply { isAccessible = true }
        private val alert = controller.getDeclaredField("sbn").apply { isAccessible = true }
        private val players = controller.getDeclaredField("mediaDataList").apply { isAccessible = true }
        private val publishedPlayer = controller.getDeclaredField("lastStatusBarPlayerId").apply { isAccessible = true }
        private val playerId = data.getMethod("getPlayerId")
        private val duration = data.getMethod("getDuration")
        private val position = data.getMethod("computerPosition")
        private val packageName = runCatching { data.getMethod("getPackageName") }.getOrNull()

        private fun publishedPlayer(key: String): Any? {
            val service = instance.get(null) ?: return null
            // This is the vendor media Live Alert's exact key and published player, not a nearby media session.
            if ((alert.get(service) as? StatusBarNotification)?.key != key) return null
            val player = (players.get(service) as? List<*>)?.firstOrNull() ?: return null
            return player.takeIf { playerId.invoke(it) == publishedPlayer.get(service) }
        }

        fun playingPackage(key: String): String? {
            val player = publishedPlayer(key) ?: return null
            return (packageName?.invoke(player) as? String)?.takeIf(String::isNotBlank)
        }

        fun read(key: String): IslandProgress? {
            val player = publishedPlayer(key) ?: return null
            val elapsed = position.invoke(player) as? Long ?: return null
            val total = duration.invoke(player) as? Long ?: return null
            return IslandProgress.from(elapsed, total)
        }
    }

    private companion object {
        const val CAPSULE_PACKAGE = "com.oplus.systemui.plugins.seedling.capsule.ui.view."
        const val UPDATE_INTERVAL_MS = 200L
    }
}
