package org.lbalab.lba2cc

import java.nio.ByteBuffer

/**
 * The companion's side of the engine mailbox (LIB386/SYSTEM/DS_LINK.CPP).
 *
 * The functions live in libmain.so, which SDLActivity loads in its onCreate, so
 * every call goes through [safe]: until the library is in, they do nothing
 * rather than throw UnsatisfiedLinkError.
 */
object DsLink {
    @JvmStatic external fun nativeSetActive(on: Boolean)
    @JvmStatic external fun nativeSend(line: String)
    @JvmStatic external fun nativeGetState(out: IntArray): Int
    @JvmStatic external fun nativeGetFrame(haveSeq: Int, out: ByteBuffer, size: IntArray): Int
    @JvmStatic external fun nativeGetAsset(id: Int, out: ByteBuffer, size: IntArray): Int

    private inline fun <T> safe(fallback: T, block: () -> T): T =
        try { block() } catch (_: UnsatisfiedLinkError) { fallback }

    fun setActive(on: Boolean) = safe(Unit) { nativeSetActive(on) }
    fun send(line: String) = safe(Unit) { nativeSend(line) }
    fun state(out: IntArray): Int = safe(0) { nativeGetState(out) }
    fun frame(haveSeq: Int, out: ByteBuffer, size: IntArray): Int = safe(0) { nativeGetFrame(haveSeq, out, size) }
    fun asset(id: Int, out: ByteBuffer, size: IntArray): Int = safe(0) { nativeGetAsset(id, out, size) }

    /** Input slots, as INPUT_BINDINGS.CPP numbers them. */
    object Slot {
        const val INVENTORY = 6
        const val ACTION = 7
        const val RETURN = 9
        const val MENUS = 10
        const val HOLOMAP = 11
        const val NORMAL = 14
        const val SPORTY = 15
        const val AGGRESSIVE = 16
        const val DISCREET = 17
        const val SAVE = 19
        const val LOAD = 20
        const val OPTIONS = 21
        const val WEAPON_1 = 25
        const val PENGUIN = 32
        const val JETPACK = 33
        const val PROTECTION = 34
        const val LIGHTNING = 35
        const val UP = 0
        const val DOWN = 1
        const val LEFT = 2
        const val RIGHT = 3
    }

    fun pressSlot(slot: Int) = send("key $slot")
}
