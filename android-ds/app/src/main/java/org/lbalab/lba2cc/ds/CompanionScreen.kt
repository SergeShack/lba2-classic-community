package org.lbalab.lba2cc.ds

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.lbalab.lba2cc.DsLink
import org.lbalab.lba2cc.DsLink.Slot
import org.lbalab.lba2cc.ds.GameState.Companion as G
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/* The game's menu colours: a thin cyan frame over darkened ground. */
private val Frame = Color(0xFF84C8C8)
private val FrameLit = Color(0xFFD2F5FF)
private val Shade = Color(0xD8000000)
private val Dim = Color(0xFF6E7878)
private val Gold = Color(0xFFFADC78)
private val LifeRed = Color(0xFFDC3228)
private val LifeOff = Color(0xFF3C1410)
private val MagicBlue = Color(0xFF3C78F0)
private val MagicOff = Color(0xFF1E2850)

private val LocalAssets = staticCompositionLocalOf { GameAssets.EMPTY }

private const val VERSION_LABEL = "v" + org.lbalab.lba2cc.BuildConfig.VERSION_NAME

@Composable
fun CompanionScreen() {
    var state by remember { mutableStateOf(GameState()) }
    var assets by remember { mutableStateOf(GameAssets.EMPTY) }
    val cache = java.io.File(androidx.compose.ui.platform.LocalContext.current.filesDir, "panel")
    LaunchedEffect(Unit) {
        val buf = IntArray(64)
        while (true) {
            val n = DsLink.state(buf)
            val next = GameState(buf.copyOf(n))
            if (next != state) state = next
            if (next.valid && next.assetGeneration != assets.generation && next.assetGeneration > 0) {
                assets = withContext(Dispatchers.Default) {
                    GameAssets.load(next.assetGeneration, next.fontHeight, cache)
                }
            }
            delay(if (state.modal != G.MODAL_NONE) 50 else 100)
        }
    }

    CompositionLocalProvider(LocalAssets provides assets) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                state.valid && state.modal == G.MODAL_HOLOMAP -> HolomapPad(state)
                state.valid && state.modal != G.MODAL_NONE && state.modal != G.MODAL_DIALOG -> ModalScreen(state)
                // Logos, intro and loading: no hero yet, so no panel, only the name.
                !state.valid || !state.inGame -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GameText("LBA2-DS", 40.dp, Gold)
                        GameText(VERSION_LABEL, 16.dp, Dim)
                    }
                }
                else -> PanelScreen(state)
            }
        }
    }
}

/* ------------------------------------------------------------------------ */
/* Drawing in the game's own hand                                            */
/* ------------------------------------------------------------------------ */

/**
 * Text in the game's font, as the engine drew it for us, scaled to [height]
 * and tinted with [color]. Until the font has arrived, a serif stands in.
 */
@Composable
fun GameText(text: String, height: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    val a = LocalAssets.current
    if (!a.hasFont) {
        Text(text, color = color, fontSize = (height.value * 0.8f).sp, fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold, maxLines = 1, modifier = modifier)
        return
    }
    val density = LocalDensity.current
    val glyphs = text.map { a.glyph(it) }
    val natural = glyphs.sumOf { it?.width ?: 0 }.coerceAtLeast(1)
    BoxWithConstraints(modifier) {
        // As tall as asked, unless that would not fit across: then smaller.
        var scale = with(density) { height.toPx() } / a.fontHeight
        if (constraints.hasBoundedWidth && natural * scale > constraints.maxWidth)
            scale = constraints.maxWidth.toFloat() / natural
        val tint = if (color == Color.White) null else ColorFilter.tint(color, BlendMode.Modulate)
        val shadow = ColorFilter.tint(Color.Black)
        val off = (scale * 0.8f).roundToInt().coerceAtLeast(1)
        Canvas(Modifier.width(with(density) { (natural * scale).toDp() }).height(with(density) { (a.fontHeight * scale).toDp() })) {
            for (pass in 0..1) {
                var x = 0f
                for (g in glyphs) {
                    if (g == null) continue
                    val o = if (pass == 0) off else 0
                    drawImage(
                        g, srcSize = IntSize(g.width, g.height),
                        dstOffset = IntOffset(x.roundToInt() + o, o),
                        dstSize = IntSize((g.width * scale).roundToInt(), (g.height * scale).roundToInt()),
                        filterQuality = FilterQuality.None,
                        colorFilter = if (pass == 0) shadow else tint,
                    )
                    x += g.width * scale
                }
            }
        }
    }
}

@Composable
private fun Pixel(img: ImageBitmap?, height: Dp, modifier: Modifier = Modifier, dim: Boolean = false) {
    if (img == null) return
    val aspect = img.width.toFloat() / img.height
    Image(img, null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None,
        modifier = modifier.height(height).width(height * aspect).alpha(if (dim) 0.35f else 1f))
}

/** A box framed like the game's menus; [selected] fills it with the menu's blue. */
@Composable
private fun GameBox(
    modifier: Modifier, selected: Boolean = false, onPress: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.clip(shape)
            .background(Shade)
            .border(if (selected) 3.dp else 2.dp, if (selected) FrameLit else Frame, shape)
            .then(if (onPress != null) Modifier.pressable { haptic.performHapticFeedback(HapticFeedbackType.VirtualKey); onPress() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Image(GameAssets.plasma, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        content()
    }
}

/* ------------------------------------------------------------------------ */
/* The panel                                                                 */
/* ------------------------------------------------------------------------ */

@Composable
private fun PanelScreen(s: GameState) {
    val a = LocalAssets.current
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        StatusBox(s)
        Row(Modifier.fillMaxWidth().weight(1.2f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Behaviour("Normal", s.behaviour == G.C_NORMAL, Slot.NORMAL)
            Behaviour("Sporty", s.behaviour == G.C_SPORTIF, Slot.SPORTY)
            Behaviour("Aggressive", s.behaviour == G.C_AGRESSIF, Slot.AGGRESSIVE)
            Behaviour("Discreet", s.behaviour == G.C_DISCRET, Slot.DISCREET)
            if (s.owns(G.FLAG_PROTOPACK)) {
                Behaviour(if (s.hasJetpack) "Jetpack" else "Protopack",
                    s.behaviour == G.C_JETPACK || s.behaviour == G.C_PROTOPACK, Slot.JETPACK, weight = 0.8f)
            }
        }
        Row(Modifier.fillMaxWidth().weight(1.15f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((flag, name, key) in WEAPONS) {
                val owned = s.owns(flag) && (flag != G.FLAG_PISTOLASER || s.laserUsable)
                val active = when (flag) {
                    G.FLAG_CONQUE -> s.behaviour == G.C_CONQUE
                    else -> s.weapon == flag && owned
                }
                Tile(name, a.item(flag), owned, active) { DsLink.pressSlot(Slot.WEAPON_1 + key - 1) }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tile("Inventory", null, true) { DsLink.pressSlot(Slot.INVENTORY) }
            Tile("Holomap", a.item(G.FLAG_HOLOMAP), s.owns(G.FLAG_HOLOMAP)) { DsLink.pressSlot(Slot.HOLOMAP) }
            Tile(if (s.penguins > 0) "Penguin x${s.penguins}" else "Penguin", a.item(G.FLAG_MECA_PINGOUIN),
                s.owns(G.FLAG_MECA_PINGOUIN)) { DsLink.pressSlot(Slot.PENGUIN) }
            Tile("Protection", a.item(G.FLAG_PROTECTION), s.owns(G.FLAG_PROTECTION)) { DsLink.pressSlot(Slot.PROTECTION) }
            Tile("Lightning", a.item(G.FLAG_ANNEAU_FOUDRE), s.owns(G.FLAG_ANNEAU_FOUDRE)) { DsLink.pressSlot(Slot.LIGHTNING) }
            Tile("Menu", null, true) { DsLink.send("esc") }
        }
        HintBar(s, Modifier.fillMaxWidth().height(58.dp))
    }
}

private val WEAPONS = listOf(
    Triple(G.FLAG_BALLE_MAGIQUE, "Magic Ball", 1),
    Triple(G.FLAG_DART, "Darts", 2),
    Triple(G.FLAG_SARBACANE, "Blowgun", 3),
    Triple(G.FLAG_CONQUE, "Conch", 4),
    Triple(G.FLAG_GANT, "Glove", 5),
    Triple(G.FLAG_PISTOLASER, "Laser", 6),
    Triple(G.FLAG_SABRE, "Saber", 7),
)

@Composable
private fun StatusBox(s: GameState) {
    val a = LocalAssets.current
    GameBox(Modifier.fillMaxWidth().height(66.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) { Pixel(a.sprite(GameAssets.S_LIFE), 20.dp) }
                    Segments(s.life, s.maxLife, 20, LifeRed, LifeOff)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) { Pixel(a.sprite(GameAssets.S_MAGIC), 20.dp) }
                    // The game's magic bar is as long as the magic level allows, out of four.
                    Segments(s.magicPoints, 80, 20, MagicBlue, MagicOff, lit = s.magicLevel * 5)
                }
            }
            Spacer(Modifier.width(14.dp))
            val zlitos = s.planet >= 2
            Counter(a.sprite(if (zlitos) GameAssets.S_ZLITOS else GameAssets.S_KASHES), if (zlitos) s.zlitos else s.kashes)
            Spacer(Modifier.width(14.dp))
            Counter(a.sprite(GameAssets.S_KEY), s.keys)
            Spacer(Modifier.width(14.dp))
            // Up to ten boxes in the game (MAX_CLOVER_BOX); past six they shrink to keep the bars long.
            val boxes = s.cloverBoxes.coerceIn(0, 10)
            val boxSize = if (boxes > 6) 18.dp else 24.dp
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 0 until boxes) {
                    Pixel(a.sprite(if (i < s.clovers) GameAssets.S_CLOVER_BOX_FULL else GameAssets.S_CLOVER_BOX), boxSize)
                }
            }
        }
    }
}

@Composable
private fun Segments(value: Int, max: Int, count: Int, on: Color, off: Color, lit: Int = count) {
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (count - 1)) / count
        val filled = (value.toFloat() / max * count).roundToInt()
        for (i in 0 until count) {
            if (i >= lit) break
            drawRect(if (i < filled) on else off, topLeft = Offset(i * (w + gap), 0f),
                size = androidx.compose.ui.geometry.Size(w, size.height))
        }
    }
}

@Composable
private fun Counter(icon: ImageBitmap?, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pixel(icon, 26.dp)
        GameText("$value", 24.dp, Gold)
    }
}

@Composable
private fun RowScope.Behaviour(name: String, selected: Boolean, slot: Int, weight: Float = 1f) {
    GameBox(Modifier.weight(weight).fillMaxHeight(), selected, onPress = { DsLink.pressSlot(slot) }) {
        GameText(name, 24.dp)
    }
}

@Composable
private fun RowScope.Tile(name: String, icon: ImageBitmap?, enabled: Boolean, selected: Boolean = false, onPress: () -> Unit) {
    GameBox(Modifier.weight(1f).fillMaxHeight(), selected, if (enabled) onPress else null) {
        Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            if (icon != null) {
                Pixel(icon, 44.dp, dim = !enabled)
                Spacer(Modifier.height(4.dp))
            }
            GameText(name, if (icon != null) 13.dp else 17.dp, if (enabled) Color.White else Dim)
        }
    }
}

/* ------------------------------------------------------------------------ */
/* Which buttons do what, right now                                          */
/* ------------------------------------------------------------------------ */

/**
 * Pad buttons by position, as SDL and the game see them, labelled as the
 * Retroid Pocket Duo Lite prints them (Nintendo order): the game's south
 * button is the Duo's B, east is A, west is Y, north is X.
 */
private enum class Pad(val label: String, val color: Color?) {
    // Each letter in its Xbox colour: A green, B red, X blue, Y yellow.
    SOUTH("B", Color(0xFFE65A50)), EAST("A", Color(0xFF6EC85A)), WEST("Y", Color(0xFFF0C846)), NORTH("X", Color(0xFF5A96F0)),
    L1("L1", null), R1("R1", null), L2("L2", null), R2("R2", null), L3("L3", null), R3("R3", null),
    START("Start", null), SELECT("Select", null), STICK("D-pad", null),
    ANY("Any button", null);

    companion object {
        /** K_GAMEPAD_* minus K_GAMEPAD_BASE (KEYBOARD_KEYS.H) to the button it is. */
        fun fromGamepad(k: Int): Pad? = when (k) {
            0 -> SOUTH
            1 -> EAST
            2 -> WEST
            3 -> NORTH
            4 -> SELECT
            6 -> START
            7 -> L3
            8 -> R3
            9 -> L1
            10 -> R1
            23 -> L2
            24 -> R2
            else -> null
        }
    }
}

private fun hintsFor(s: GameState): Pair<String, List<Pair<Pad, String>>> = when (s.modal) {
    G.MODAL_DIALOG -> "Dialogue" to listOf(Pad.ANY to "Next", Pad.START to "Skip")
    G.MODAL_HOLOMAP -> "Holomap" to listOfNotNull(Pad.STICK to "Turn", s.pad(SLOT_ACTION, "Zoom island"), s.pad(SLOT_HOLOMAP, "Close"))
    G.MODAL_INVENTORY -> "Inventory" to listOfNotNull(Pad.STICK to "Choose", s.pad(SLOT_RETURN, "Use"), s.pad(SLOT_INVENTORY, "Close"))
    G.MODAL_BEHAVIOUR -> "Behaviour" to listOfNotNull(s.pad(SLOT_BEHAVIOUR, "Hold"), Pad.STICK to "Choose", s.pad(SLOT_BEHAVIOUR, "Release: pick"))
    G.MODAL_SLATE -> "Slate" to listOfNotNull(Pad.STICK to "Pages", s.pad(SLOT_MENUS, "Close"))
    G.MODAL_MENU, G.MODAL_SAVES -> "Menu" to listOfNotNull(Pad.STICK to "Choose", s.pad(SLOT_ACTION, "OK"), s.pad(SLOT_MENUS, "Back"))
    else -> {
        val x = when (s.behaviour) {
            G.C_SPORTIF -> "Jump"
            G.C_AGRESSIF -> "Fight"
            G.C_DISCRET -> "Hide"
            else -> "Action"
        }
        val weapon = WEAPONS.firstOrNull { it.first == s.weapon && s.owns(it.first) }?.second ?: "Throw"
        BEHAVIOURS[s.behaviour].orEmpty() to listOf(
            s.pad(SLOT_ACTION, x), s.pad(SLOT_ACTION_ALWAYS, "Talk"), s.pad(SLOT_THROW, weapon),
            s.pad(SLOT_INVENTORY, "Inventory"), s.pad(SLOT_BEHAVIOUR, "Behaviour"),
        ).filterNotNull()
    }
}

// Input slots (INPUT_BINDINGS.CPP) the hints name.
private const val SLOT_THROW = 4
private const val SLOT_BEHAVIOUR = 5
private const val SLOT_INVENTORY = 6
private const val SLOT_ACTION = 7
private const val SLOT_ACTION_ALWAYS = 8
private const val SLOT_RETURN = 9
private const val SLOT_MENUS = 10
private const val SLOT_HOLOMAP = 11

/** The button bound to [slot] in the player's config, with [what] it does; null if unbound. */
private fun GameState.pad(slot: Int, what: String): Pair<Pad, String>? =
    Pad.fromGamepad(padFor(slot))?.let { it to what }

private val BEHAVIOURS = mapOf(
    G.C_NORMAL to "Normal", G.C_SPORTIF to "Sporty", G.C_AGRESSIF to "Aggressive", G.C_DISCRET to "Discreet",
    G.C_PROTOPACK to "Protopack", G.C_JETPACK to "Jetpack", G.C_CONQUE to "Conch",
)

@Composable
private fun HintBar(s: GameState, modifier: Modifier) {
    val (title, hints) = hintsFor(s)
    GameBox(modifier) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title.isNotEmpty()) GameText(title, 15.dp, Frame)
            for ((pad, what) in hints) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PadGlyph(pad)
                    GameText(what, 17.dp)
                }
            }
            // The game's menus are where a player looks for which build this is.
            if (s.modal == G.MODAL_MENU) {
                Spacer(Modifier.weight(1f))
                GameText(VERSION_LABEL, 13.dp, Dim)
            }
        }
    }
}

@Composable
private fun PadGlyph(p: Pad) {
    val c = p.color
    if (c != null) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF141414)).border(2.dp, c, CircleShape),
            contentAlignment = Alignment.Center) {
            Text(p.label, color = c, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        Box(Modifier.height(26.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF141414))
            .border(2.dp, Color(0xFFDCDCDC), RoundedCornerShape(7.dp)).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center) {
            Text(p.label, color = Color(0xFFE6E6E6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A tap that fires on the finger going down: no ripple, no long-press wait. */
private fun Modifier.pressable(onPress: () -> Unit): Modifier = pointerInput(onPress) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        onPress()
    }
}

/* ------------------------------------------------------------------------ */
/* A modal screen of the game, drawn here and driven by touch                */
/* ------------------------------------------------------------------------ */

private class FrameHolder {
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(1920 * 1080 * 4).order(ByteOrder.nativeOrder())
    val ints = IntArray(1920 * 1080)
    val size = IntArray(2)
    var seq = 0
    var bitmap: Bitmap? = null
}

@Composable
private fun ModalScreen(s: GameState) {
    val holder = remember { FrameHolder() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val seq = DsLink.frame(holder.seq, holder.buffer, holder.size)
            if (seq != 0) {
                holder.seq = seq
                val w = holder.size[0]
                val h = holder.size[1]
                if (w > 0 && h > 0) {
                    holder.buffer.rewind()
                    holder.buffer.asIntBuffer().get(holder.ints, 0, w * h)
                    for (i in 0 until w * h) holder.ints[i] = holder.ints[i] or -0x1000000
                    var bmp = holder.bitmap
                    if (bmp == null || bmp.width != w || bmp.height != h) {
                        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        holder.bitmap = bmp
                    }
                    bmp.setPixels(holder.ints, 0, w, 0, 0, w, h)
                    tick++
                }
            }
            delay(33)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Read here, so a new frame recomposes this box even before the first one.
            val frameNo = tick
            val bmp = holder.bitmap
            if (bmp != null && frameNo >= 0) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize().modalGestures(s.modal, bmp.width, bmp.height))
            }
        }
        // The pad is right under the screen, so no on-screen keys: only what its buttons do here.
        HintBar(s, Modifier.fillMaxWidth().padding(8.dp).height(52.dp))
    }
}

/** The holomap is on the top screen; this side is a touch pad for its globe. */
@Composable
private fun HolomapPad(s: GameState) {
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GameBox(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.fillMaxSize().modalGestures(G.MODAL_HOLOMAP, 1, 1), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GameText("Holomap", 34.dp, Gold)
                    GameText("Drag to turn the globe", 20.dp)
                    GameText("Pinch to zoom out and back", 20.dp)
                }
            }
        }
        HintBar(s, Modifier.fillMaxWidth().height(58.dp))
    }
}

/**
 * Touch on the mirrored screen. The frame is fitted into the view, so a point
 * is mapped back by the inverse of that fit.
 *
 * - Menus and saved games: the finger is the mouse (hover by dragging, click by
 *   lifting), which GAMEMENU_MOUSE already understands.
 * - Holomap: drag turns the globe, pinch zooms, tap is the action key.
 * - Inventory, behaviour menu, slate: a swipe is an arrow key, a tap the action key.
 */
private fun Modifier.modalGestures(modal: Int, fw: Int, fh: Int): Modifier = pointerInput(modal, fw, fh) {
    fun toFrame(p: Offset, view: IntSize): Pair<Int, Int> {
        val scale = minOf(view.width.toFloat() / fw, view.height.toFloat() / fh)
        val ox = (view.width - fw * scale) / 2f
        val oy = (view.height - fh * scale) / 2f
        val x = ((p.x - ox) / scale).roundToInt().coerceIn(0, fw - 1)
        val y = ((p.y - oy) / scale).roundToInt().coerceIn(0, fh - 1)
        return x to y
    }
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown()
        val start = down.position
        var last = start
        var moved = false
        var pinchBase = 0f
        val pointerMenu = modal == G.MODAL_MENU || modal == G.MODAL_SAVES
        if (pointerMenu) {
            val (x, y) = toFrame(start, size)
            DsLink.send("ptr $x $y 0")
        }
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2 && modal == G.MODAL_HOLOMAP) {
                val d = dist(pressed[0], pressed[1])
                if (pinchBase == 0f) pinchBase = d
                val ratio = d / pinchBase
                if (abs(ratio - 1f) > 0.06f) {
                    DsLink.send("zoom ${(ratio * 1000).roundToInt()}")
                    pinchBase = d
                }
                moved = true
                event.changes.forEach(PointerInputChange::consume)
                continue
            }
            val p = pressed[0].position
            if (!moved && (p - start).getDistance() > slop) moved = true
            if (moved && pinchBase == 0f) {
                when (modal) {
                    G.MODAL_HOLOMAP -> {
                        val k = 2048f / size.width
                        val db = -((p.x - last.x) * k).roundToInt()
                        val da = ((p.y - last.y) * k).roundToInt()
                        if (da != 0 || db != 0) DsLink.send("holo $da $db")
                    }
                    G.MODAL_MENU, G.MODAL_SAVES -> {
                        val (x, y) = toFrame(p, size)
                        DsLink.send("ptr $x $y 0")
                    }
                }
            }
            last = p
            event.changes.forEach(PointerInputChange::consume)
        }
        when {
            pointerMenu -> {
                val (x, y) = toFrame(last, size)
                DsLink.send("ptr $x $y 1")
                // The release goes in a later poll than the press, as a real click would.
                Thread { Thread.sleep(80); DsLink.send("ptr $x $y 0") }.start()
            }
            !moved -> DsLink.pressSlot(Slot.ACTION)
            modal != G.MODAL_HOLOMAP && pinchBase == 0f -> {
                val d = last - start
                if (abs(d.x) > abs(d.y)) DsLink.pressSlot(if (d.x > 0) Slot.RIGHT else Slot.LEFT)
                else DsLink.pressSlot(if (d.y > 0) Slot.DOWN else Slot.UP)
            }
        }
    }
}

private fun dist(a: PointerInputChange, b: PointerInputChange): Float =
    hypot(a.position.x - b.position.x, a.position.y - b.position.y)
