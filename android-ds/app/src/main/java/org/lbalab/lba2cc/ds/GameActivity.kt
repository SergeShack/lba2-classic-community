package org.lbalab.lba2cc.ds

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.util.Log
import org.libsdl.app.SDLActivity
import org.lbalab.lba2cc.DsLink

/**
 * The game on the top screen, plus the lifecycle of the panel on the bottom one.
 *
 * The display handling follows OpenMW-DS (duo-lite branch), where each piece
 * was found on this same device:
 * - the game is put back on its own display if Android moved its task
 *   ([returnToGameDisplayIfMoved]),
 * - the panel is (re)launched until it is actually up, since a refused start
 *   is silent ([ensureCompanion]),
 * - the panel's task being sent home on its own screen sends the game home
 *   too, rather than fighting the post-home app-switch lock
 *   ([onCompanionBackgrounded]).
 */
class GameActivity : SDLActivity() {

    private var stopped = false
    private var gameDisplayRequestAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // SDL drops pad events while its window is unfocused unless told not
        // to, and the panel's focus anchor holds focus on the other display.
        runCatching { Os.setenv("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS", "1", true) }
        super.onCreate(savedInstanceState)
        CompanionActivity.onBackgrounded = { onCompanionBackgrounded() }
        window.decorView.post { ensureCompanion("create") }
        registerDebugCommands()
    }

    /**
     * `adb shell am broadcast -a io.github.sergeshack.lba2ds.CMD --es line "key 14"`
     * sends one bridge command (DS_BRIDGE.CPP), for testing the panel's paths
     * without a finger. Guarded by DUMP, which the adb shell holds and an app
     * cannot get.
     */
    private val debugCommands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("line")?.let { DsLink.send(it) }
        }
    }

    private fun registerDebugCommands() {
        runCatching {
            registerReceiver(debugCommands, IntentFilter("io.github.sergeshack.lba2ds.CMD"),
                android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        }
    }

    override fun onStart() {
        super.onStart()
        stopped = false
    }

    override fun onResume() {
        super.onResume()
        returnToGameDisplayIfMoved("resume")
        window.decorView.post { ensureCompanion("resume") }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
    }

    /**
     * With the panel up, this window loses focus whenever the other display
     * takes it: at the panel's launch, and on taps there. SDL reads a lost
     * focus as "the app is going away" and pauses the native thread, and the
     * engine stops its clock and its audio. Neither is true here, so a focus
     * loss while the panel is on screen is not passed on. Leaving the app still
     * pauses the game, through onPause.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus && !isFinishing && Displays.companion(this) != null) {
            Log.d(TAG, "focus went to the other display; the game keeps running")
            return
        }
        super.onWindowFocusChanged(hasFocus)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        returnToGameDisplayIfMoved("configuration")
        window.decorView.post { ensureCompanion("configuration") }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(debugCommands) }
        DsLink.setActive(false)
        CompanionActivity.onBackgrounded = null
        CompanionActivity.finishIfRunning()
        super.onDestroy()
    }

    private fun onGameDisplay(): Boolean = (display?.displayId ?: Displays.GAME) == Displays.GAME

    private fun returnToGameDisplayIfMoved(reason: String) {
        if (isFinishing || isDestroyed || onGameDisplay()) return
        val now = SystemClock.uptimeMillis()
        if (now - gameDisplayRequestAt < 2000) return
        gameDisplayRequestAt = now
        val appTask = getSystemService(ActivityManager::class.java)?.appTasks
            ?.firstOrNull { runCatching { it.taskInfo?.taskId }.getOrNull() == taskId }
            ?: return
        Log.w(TAG, "game is on display ${display?.displayId}; moving it back ($reason)")
        val intent = Intent(this, GameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { appTask.startActivity(this, intent, Displays.on(Displays.GAME)) }
            .onFailure { Log.e(TAG, "could not move the game back", it) }
    }

    private fun ensureCompanion(reason: String, attempt: Int = 0) {
        if (isFinishing || isDestroyed || stopped) return
        if (!onGameDisplay()) return
        if (CompanionActivity.started) {
            DsLink.setActive(true)
            return
        }
        val target = Displays.companion(this) ?: run {
            Log.i(TAG, "no second display; single screen")
            DsLink.setActive(false)
            return
        }
        if (attempt >= 5) {
            Log.w(TAG, "gave up bringing the panel up ($reason)")
            return
        }
        Log.d(TAG, "starting the panel on display ${target.displayId} ($reason, try ${attempt + 1})")
        val intent = Intent(this, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent, Displays.on(target.displayId)) }
            .onFailure { Log.e(TAG, "panel launch failed", it) }
        window.decorView.postDelayed({ ensureCompanion(reason, attempt + 1) }, 600)
    }

    private fun onCompanionBackgrounded() {
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed || stopped || CompanionActivity.started) return@postDelayed
            Log.d(TAG, "panel sent home on its screen; sending the game home too")
            runCatching { moveTaskToBack(true) }
        }, 400)
    }

    private companion object { const val TAG = "LBA2DS" }
}
