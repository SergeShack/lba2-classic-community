package org.lbalab.lba2cc.ds

/** The engine's state vector (SOURCES/DS_BRIDGE.CPP), with names. */
data class GameState(val raw: IntArray = IntArray(0)) {
    private fun at(i: Int) = raw.getOrElse(i) { 0 }

    val valid get() = raw.isNotEmpty() && at(0) == 1
    val modal get() = at(1)
    val life get() = at(2)
    val maxLife get() = at(3).coerceAtLeast(1)
    val magicLevel get() = at(4)
    val magicPoints get() = at(5)
    val kashes get() = at(6)
    val zlitos get() = at(7)
    val keys get() = at(8)
    val clovers get() = at(9)
    val cloverBoxes get() = at(10)
    val behaviour get() = at(11)
    val weapon get() = at(12)
    val planet get() = at(15)
    val hasJetpack get() = at(16) == 1
    val laserUsable get() = at(17) == 1
    val penguins get() = at(18)
    val frameWidth get() = at(19)
    val frameHeight get() = at(20)
    val assetGeneration get() = at(21)
    val fontHeight get() = at(22)
    val inGame get() = at(23) == 1

    /** The pad button (K_GAMEPAD_* - base) bound to input slot [slot], 4..11; -1 if none. */
    fun padFor(slot: Int): Int = if (slot in 4..11 && raw.size > 24 + slot - 4) at(24 + slot - 4) else -1

    /** Whether inventory flag [flag] (COMMON.H FLAG_*) is held. */
    fun owns(flag: Int): Boolean = when {
        flag < 32 -> (at(13) ushr flag) and 1 == 1
        flag < 64 -> (at(14) ushr (flag - 32)) and 1 == 1
        else -> false
    }

    override fun equals(other: Any?) = other is GameState && raw.contentEquals(other.raw)
    override fun hashCode() = raw.contentHashCode()

    companion object {
        const val MODAL_NONE = 0
        const val MODAL_MENU = 1
        const val MODAL_HOLOMAP = 2
        const val MODAL_INVENTORY = 3
        const val MODAL_BEHAVIOUR = 4
        const val MODAL_SLATE = 5
        const val MODAL_SAVES = 6
        /** A dialogue: it stays on the top screen; only the hints change. */
        const val MODAL_DIALOG = 7

        // COMMON.H behaviours
        const val C_NORMAL = 0
        const val C_SPORTIF = 1
        const val C_AGRESSIF = 2
        const val C_DISCRET = 3
        const val C_PROTOPACK = 4
        const val C_CONQUE = 6
        const val C_JETPACK = 8

        // COMMON.H inventory flags
        const val FLAG_HOLOMAP = 0
        const val FLAG_BALLE_MAGIQUE = 1
        const val FLAG_DART = 2
        const val FLAG_PISTOLASER = 9
        const val FLAG_SABRE = 10
        const val FLAG_GANT = 11
        const val FLAG_PROTOPACK = 12
        const val FLAG_MECA_PINGOUIN = 14
        const val FLAG_ANNEAU_FOUDRE = 19
        const val FLAG_CONQUE = 22
        const val FLAG_SARBACANE = 23
        const val FLAG_PROTECTION = 39
    }
}
