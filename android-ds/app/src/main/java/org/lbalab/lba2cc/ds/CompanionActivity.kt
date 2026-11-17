package org.lbalab.lba2cc.ds

import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLControllerManager
import org.lbalab.lba2cc.DsLink

/**
 * The panel on the bottom screen, as an Activity of its own on that display.
 *
 * Ported from OpenMW-DS (duo-lite branch), where the focus rules below were
 * measured on this device:
 *
 * - The window must be focusable while it comes up, or the display has a
 *   focused app and no focusable window and the input dispatcher ANRs it.
 * - Once up it must not stay focusable, or every tap makes this the focused
 *   display and the controller follows it away from the game.
 * - So a 1x1 untouchable but focusable panel window (the focus anchor) is
 *   added first, and only then is the main window made non-focusable. If the
 *   anchor cannot be added, focusability is kept: a lost controller is
 *   recoverable, an ANR is not.
 * - Whatever key input still reaches this display is relayed to SDL, which
 *   accepts it while the game is unfocused because GameActivity sets
 *   SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS.
 *
 * A Presentation is not used: on the Duo Lite it cannot hide the bottom
 * screen's navigation bar, because the bar follows the focused window and a
 * Presentation never has focus.
 */
class CompanionActivity : ComponentActivity() {

    private var focusabilityDropped = false
    private var focusAnchorView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        instance = this
        applyImmersive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.setOnApplyWindowInsetsListener { _, insets ->
                if (insets.isVisible(WindowInsets.Type.systemBars())) applyImmersive()
                insets
            }
        }
        setContent { CompanionScreen() }
    }

    override fun onStart() {
        super.onStart()
        started = true
        focusabilityDropped = false
        runCatching { window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) }
        DsLink.setActive(true)
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersive()
            dropFocusabilityOnceFocused()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        forwardKeyToGame(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        forwardMotionToGame(event) || super.dispatchGenericMotionEvent(event)

    private fun dropFocusabilityOnceFocused() {
        if (focusabilityDropped) return
        focusabilityDropped = true
        window.decorView.post {
            // Anchor first, and stay focusable if it did not land.
            if (!addFocusAnchor()) {
                focusabilityDropped = false
                Log.e(TAG, "no focus anchor; staying focusable rather than risking an ANR")
                return@post
            }
            runCatching {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                applyImmersive()
            }.onFailure {
                focusabilityDropped = false
                Log.e(TAG, "could not drop focusability", it)
            }
        }
    }

    private fun addFocusAnchor(): Boolean {
        focusAnchorView?.let { return true }
        val token = window.decorView.windowToken ?: return false
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        ).apply {
            this.token = token
            gravity = Gravity.TOP or Gravity.START
        }
        val view = object : View(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                forwardKeyToGame(event) || super.dispatchKeyEvent(event)

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
                forwardMotionToGame(event) || super.dispatchGenericMotionEvent(event)
        }
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                insets.isVisible(WindowInsets.Type.systemBars())
            ) hideBarsFor(v)
            insets
        }
        return runCatching { windowManager.addView(view, lp) }
            .onSuccess {
                focusAnchorView = view
                view.post { hideBarsFor(view) }
            }
            .onFailure { Log.e(TAG, "focus anchor addView failed", it) }
            .isSuccess
    }

    private fun applyImmersive() = hideBarsFor(window.decorView)

    private fun hideBarsFor(view: View) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.windowInsetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            @Suppress("DEPRECATION")
            view.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    override fun onStop() {
        super.onStop()
        started = false
        DsLink.setActive(false)
        if (!isFinishing) onBackgrounded?.invoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        focusAnchorView?.let { runCatching { windowManager.removeView(it) } }
        focusAnchorView = null
        if (instance === this) instance = null
    }

    companion object {
        private const val TAG = "LBA2DS"

        @Volatile var instance: CompanionActivity? = null
            private set

        @Volatile var started: Boolean = false
            private set

        @Volatile var onBackgrounded: (() -> Unit)? = null

        fun finishIfRunning() {
            started = false
            instance?.finish()
            instance = null
        }

        private fun forwardKeyToGame(event: KeyEvent): Boolean {
            if (SDLActivity.mBrokenLibraries) return false
            return runCatching {
                SDLControllerManager.isDeviceSDLJoystick(event.deviceId) &&
                    SDLActivity.handleKeyEvent(null, event.keyCode, event, null)
            }.getOrDefault(false)
        }

        private fun forwardMotionToGame(event: MotionEvent): Boolean {
            if (SDLActivity.mBrokenLibraries) return false
            if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
            return runCatching { SDLControllerManager.handleJoystickMotionEvent(event) }.getOrDefault(false)
        }
    }
}
