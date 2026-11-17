package org.lbalab.lba2cc.ds

import android.app.ActivityOptions
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display

/**
 * Which panel is which on the Retroid Pocket Duo Lite.
 *
 * The top 5.5" 1920x1080 panel is the default display (id 0) and carries the
 * game. The bottom 4.2" 1280x960 panel is the one Android lists in the
 * PRESENTATION category, and carries the panel. This is the only arrangement
 * this build supports; with no second display the game simply runs alone.
 */
object Displays {
    const val GAME = Display.DEFAULT_DISPLAY

    fun companion(context: Context): Display? =
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.displayId != GAME }

    fun on(displayId: Int): Bundle =
        ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
}
