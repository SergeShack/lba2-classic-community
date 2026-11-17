package org.lbalab.lba2cc.ds

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * The launcher entry. It only starts [GameActivity] on the top screen and goes.
 *
 * The icon is usually tapped on the bottom screen's launcher, and Android opens
 * the tapped app on that display. SDLActivity cannot be moved once it has
 * started its native thread, so the display is chosen here, before the game
 * exists. A game already running is brought back to the top screen the same
 * way (GameActivity is singleTask, so this reaches the existing one).
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "launched on display ${display?.displayId}; starting the game on ${Displays.GAME}")
        val intent = Intent(this, GameActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent, Displays.on(Displays.GAME)) }
            .onFailure {
                Log.e(TAG, "could not start on the top screen, starting where we are", it)
                startActivity(intent)
            }
        finish()
    }

    private companion object { const val TAG = "LBA2DS" }
}
