#include "DS_BRIDGE.H"

#include "C_EXTERN.H"
#include "INPUT_BINDINGS.H"
#include "TOUCH_INPUT.H"
#include "CONSOLE/CONSOLE.H"
#include "INTEXT.H"
#include "INVENT.H"
#include "OBJECT.H"

#include <3D/LIGHT.H>
#include <SVGA/CLIP.H>
#include <SVGA/FONT.H>

#include <SVGA/DIRTYBOX.H>
#include <SVGA/SCREEN.H>
#include <SYSTEM/DS_LINK.H>
#include <SYSTEM/KEYBOARD.H>
#include <SYSTEM/KEYBOARD_KEYS.H>
#include <SYSTEM/MOUSE.H>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern S32 ZoomPlanet;
extern S32 ZoomPlanetDest;

/* ---------------------------------------------------------------------------
 * Commands, one per line, from the companion:
 *
 *   key <slot>          press the key bound to input slot <slot> (INPUT_BINDINGS)
 *   esc                 press Escape
 *   ptr <x> <y> <0|1>   finger at surface (x, y), up or down
 *   holo <da> <db>      turn the holomap globe by (HoloAlpha, HoloBeta) units
 *   zoom <permille>     scale the holomap globe's distance by 1000/<permille>
 *   con <line>          run a console command (adb testing, see GameActivity)
 *
 * State vector (DsLink_CopyState), index: meaning
 *   0 format (1)    1 modal kind (DS_MODAL_*)   2 life   3 max life
 *   4 magic level   5 magic points   6 kashes   7 zlitos   8 little keys
 *   9 clovers       10 clover boxes  11 behaviour (C_*)   12 weapon (FLAG_*)
 *   13 owned items 0..31 (bit n = ListVarGame[n] != 0)    14 owned 32..63
 *   15 planet       16 jetpack (1) or protopack (0)       17 laser usable
 *   18 meca-penguins    19 frame width   20 frame height
 *   21 asset generation (changes when new pictures are ready)
 *   22 font line height, in pixels of the font assets
 *   23 in game: the hero has been in a scene (the panel has nothing to show before)
 *   24..31 the pad button bound to slots 4..11 (throw, behaviour, inventory,
 *          action, action always, return, menus, holomap): K_GAMEPAD_* minus
 *          K_GAMEPAD_BASE, or -1. Read from the live bindings, so the hints
 *          follow whatever the player's config says.
 *
 * Assets (DsLink_CopyAsset), all in the game's normal palette:
 *   0..94     the game font, characters 32..126, each its advance wide
 *   100..107  sprites: life, magic, kashes, zlitos, key, clover box empty and
 *             full, clover
 *   200+n     inventory item n (FLAG_*) as its 3D model, as the inventory shows it
 * ------------------------------------------------------------------------- */

#define DS_ASSET_GLYPH 0
#define DS_ASSET_SPRITE 100
#define DS_ASSET_ITEM 200
#define DS_CELL 160

/* TabKeys layout, as KEYBOARD.CPP folds keys into it: a plain key is the top
   bit of its own byte, a pad key one bit of a byte past the first 256. */
#define DS_KEY_INDEX(k) ((k) < 256 ? (k) : 256 - 32 + ((k) >> 3))
#define DS_KEY_BIT(k) ((k) < 256 ? 0x80 : (1 << ((k) & 7)))

#define DS_STATE_FORMAT 1
#define DS_PULSE_POLLS 4
#define DS_MAX_PULSES 8
#define DS_MODAL_DEPTH 8

typedef struct {
    U32 key;
    U32 index;
    U8 bit;
    S32 polls;
} T_DS_PULSE;

static T_DS_PULSE s_pulses[DS_MAX_PULSES];
static S32 s_modalStack[DS_MODAL_DEPTH];
static S32 s_modalDepth = 0;
static S32 s_overlayOff = 0;

static U32 *s_snapshot = NULL;
static U32 s_snapW = 0;
static U32 s_snapH = 0;
static U32 s_frameW = 0;
static U32 s_frameH = 0;

static S32 s_assetGen = 0;
static S32 s_fontHeight = 0;
static S32 s_glyphsDone = 0;
static S32 s_itemsWanted = 1;
/* Set by the first modal; the asset pass also waits for the banks it draws from. */
static S32 s_resourcesReady = 0;

static S32 CurrentModal(void) {
    if (s_modalDepth <= 0)
        return DS_MODAL_NONE;
    return s_modalStack[(s_modalDepth > DS_MODAL_DEPTH ? DS_MODAL_DEPTH : s_modalDepth) - 1];
}

/* The holomap's own distance on opening, the closest the panel may zoom to. */
static S32 s_holoBaseZoom = 0;

void DsBridge_ModalEnter(S32 kind) {
    if (kind == DS_MODAL_HOLOMAP)
        s_holoBaseZoom = 0;
    s_resourcesReady = 1;
    /* The inventory knows best what each item looks like now (the magic
       ball's level, the laser's state), so its opening redraws them. */
    if (kind == DS_MODAL_INVENTORY)
        s_itemsWanted = 1;
    if (s_modalDepth < DS_MODAL_DEPTH)
        s_modalStack[s_modalDepth] = kind;
    s_modalDepth++;
}

static S32 s_dialogDepth = 0;
static S32 s_inGame = 0;

void DsBridge_NoteGameplay(void) { s_inGame = 1; }

void DsBridge_DialogEnter(void) { s_dialogDepth++; }

void DsBridge_DialogLeave(void) {
    if (s_dialogDepth > 0)
        s_dialogDepth--;
}

void DsBridge_ModalLeave(void) {
    if (s_modalDepth > 0)
        s_modalDepth--;
}

static void PressKey(U32 key) {
    if (key == 0)
        return;
    const U32 index = DS_KEY_INDEX(key);
    if (index >= TABKEYS_NUM_KEYS)
        return;
    for (S32 i = 0; i < DS_MAX_PULSES; i++) {
        if (s_pulses[i].polls <= 0) {
            s_pulses[i].key = key;
            s_pulses[i].index = index;
            s_pulses[i].bit = (U8)DS_KEY_BIT(key);
            s_pulses[i].polls = DS_PULSE_POLLS;
            return;
        }
    }
}

static void PressSlot(S32 slot) {
    U32 keys[4];
    if (Bindings_KeysForSlot(slot, keys, 4) > 0)
        PressKey(keys[0]);
}

static void RunCommand(const char *line) {
    S32 a = 0, b = 0, c = 0;
    if (sscanf(line, "key %d", &a) == 1) {
        PressSlot(a);
    } else if (strcmp(line, "esc") == 0) {
        PressKey(K_ESC);
    } else if (sscanf(line, "ptr %d %d %d", &a, &b, &c) == 3) {
        if (a >= 0 && b >= 0)
            InjectPointer((U32)a, (U32)b, c);
    } else if (strncmp(line, "con ", 4) == 0) {
        Console_EnsureRegistered();
        Console_Execute(line + 4);
    } else if (sscanf(line, "holo %d %d", &a, &b) == 2) {
        if (CurrentModal() == DS_MODAL_HOLOMAP) {
            HoloAlpha = (HoloAlpha + a) & 4095;
            HoloBeta = (HoloBeta + b) & 4095;
        }
    } else if (sscanf(line, "zoom %d", &a) == 1) {
        /* Only once the opening zoom has settled: before that the loop is
           still driving ZoomPlanet towards ZoomPlanetDest on its own clock. */
        if (CurrentModal() == DS_MODAL_HOLOMAP && a > 0 && ZoomPlanet == ZoomPlanetDest) {
            /* No closer than the game's own view: nearer, the globe reaches the
               caption strip under it, which the loop does not redraw. */
            if (s_holoBaseZoom == 0)
                s_holoBaseZoom = ZoomPlanetDest;
            S32 z = (S32)((S64)ZoomPlanetDest * 1000 / a);
            if (z < s_holoBaseZoom)
                z = s_holoBaseZoom;
            if (z > s_holoBaseZoom * 2)
                z = s_holoBaseZoom * 2;
            ZoomPlanet = ZoomPlanetDest = z;
        }
    }
}

static S32 OwnedMask(S32 first) {
    S32 mask = 0;
    for (S32 i = 0; i < 32; i++) {
        if (ListVarGame[first + i] != 0)
            mask |= (S32)(1u << i);
    }
    return mask;
}

static void PublishState(void) {
    S32 st[32];
    st[0] = DS_STATE_FORMAT;
    st[1] = CurrentModal() != DS_MODAL_NONE ? CurrentModal() : (s_dialogDepth > 0 ? DS_MODAL_DIALOG : DS_MODAL_NONE);
    st[2] = ListObjet[NUM_PERSO].LifePoint;
    st[3] = MAX_LIFE_POINTS;
    st[4] = MagicLevel;
    st[5] = MagicPoint;
    st[6] = NbGoldPieces;
    st[7] = NbZlitosPieces;
    st[8] = NbLittleKeys;
    st[9] = ListVarGame[FLAG_CLOVER];
    st[10] = NbCloverBox;
    st[11] = Comportement;
    st[12] = Weapon;
    st[13] = OwnedMask(0);
    st[14] = OwnedMask(32);
    st[15] = Planet;
    st[16] = TabInv[FLAG_PROTOPACK].IdObj3D == 1 ? 1 : 0;
    st[17] = TabInv[FLAG_PISTOLASER].IdObj3D == 2 ? 1 : 0;
    st[18] = ListVarGame[FLAG_MECA_PINGOUIN];
    st[19] = (S32)s_frameW;
    st[20] = (S32)s_frameH;
    st[21] = s_assetGen;
    st[22] = s_fontHeight;
    st[23] = s_inGame;
    for (S32 i = 0; i < 8; i++) {
        const U32 k = GamepadKeys[4 + i].Key1 ? GamepadKeys[4 + i].Key1 : GamepadKeys[4 + i].Key2;
        st[24 + i] = (k >= K_GAMEPAD_BASE && k < K_GAMEPAD_BASE + K_GAMEPAD_COUNT) ? (S32)(k - K_GAMEPAD_BASE) : -1;
    }
    DsLink_PublishState(st, 32);
}

/* ---------------------------------------------------------------------------
 * Pictures for the panel. Each is drawn by the game's own routines into the
 * top-left corner of Log, read back through the normal palette, and the corner
 * is put back as it was, so nothing of this reaches the screen.
 * ------------------------------------------------------------------------- */

static U32 s_cellArgb[DS_CELL * DS_CELL];

static void ClearCell(void) {
    for (S32 y = 0; y < DS_CELL; y++)
        memset((U8 *)Log + TabOffLine[y], 0, DS_CELL);
}

/* Publishes the cell's (x0..x1, y0..y1) as asset `id`; colour 0 is clear. */
static void PublishCell(S32 id, S32 x0, S32 y0, S32 x1, S32 y1) {
    const U8 *pal = PtrPalNormal ? PtrPalNormal : PtrPal;
    S32 w = x1 - x0 + 1, h = y1 - y0 + 1;
    if (w <= 0 || h <= 0 || pal == NULL)
        return;
    for (S32 y = 0; y < h; y++) {
        const U8 *row = (const U8 *)Log + TabOffLine[y0 + y] + x0;
        for (S32 x = 0; x < w; x++) {
            U8 c = row[x];
            s_cellArgb[y * w + x] = c == 0 ? 0 : (0xFF000000u | ((U32)pal[c * 3] << 16) |
                                                  ((U32)pal[c * 3 + 1] << 8) | pal[c * 3 + 2]);
        }
    }
    DsLink_PublishAsset(id, s_cellArgb, w, h);
}

/* The bounding box of what was drawn in the cell; false when nothing was. */
static S32 CellBounds(S32 *x0, S32 *y0, S32 *x1, S32 *y1) {
    *x0 = DS_CELL;
    *y0 = DS_CELL;
    *x1 = -1;
    *y1 = -1;
    for (S32 y = 0; y < DS_CELL; y++) {
        const U8 *row = (const U8 *)Log + TabOffLine[y];
        for (S32 x = 0; x < DS_CELL; x++) {
            if (row[x]) {
                if (x < *x0)
                    *x0 = x;
                if (x > *x1)
                    *x1 = x;
                if (y < *y0)
                    *y0 = y;
                if (y > *y1)
                    *y1 = y;
            }
        }
    }
    return *x1 >= 0;
}

static void RenderGlyphs(void) {
    if (PtrFont == NULL)
        return;
    S32 bottom = 0;
    for (S32 c = 33; c < 127; c++) {
        char str[2] = {(char)c, 0};
        ClearCell();
        ColorFont(LBAWHITE);
        Font(0, 0, str);
        S32 x0, y0, x1, y1;
        if (CellBounds(&x0, &y0, &x1, &y1) && y1 > bottom)
            bottom = y1;
    }
    s_fontHeight = bottom + 1;
    for (S32 c = 32; c < 127; c++) {
        char str[2] = {(char)c, 0};
        ClearCell();
        ColorFont(LBAWHITE);
        Font(0, 0, str);
        S32 adv = SizeFont(str);
        if (adv < 1)
            adv = 1;
        if (adv > DS_CELL)
            adv = DS_CELL;
        PublishCell(DS_ASSET_GLYPH + c - 32, 0, 0, adv - 1, s_fontHeight - 1);
    }
}

static void RenderSprites(void) {
    static const S32 sprites[8] = {SPRITE_COEUR, SPRITE_MAGIE, SPRITE_KASHES, SPRITE_ZLITOS,
                                   SPRITE_CLE, SPRITE_CLOVER_BOX, SPRITE_FULL_CLOVER_BOX, SPRITE_CLOVER};
    const S32 scale = ScaleFactorSprite;
    ScaleFactorSprite = DEF_SCALE_FACTOR;
    for (S32 i = 0; i < 8; i++) {
        ClearCell();
        PtrAffGraph(DS_CELL / 4, DS_CELL / 4, sprites[i]);
        S32 x0, y0, x1, y1;
        if (CellBounds(&x0, &y0, &x1, &y1))
            PublishCell(DS_ASSET_SPRITE + i, x0, y0, x1, y1);
    }
    ScaleFactorSprite = scale;
}

/* Only from the inventory's loop, which sets up its own 3D view for every
   object it draws: this changes the projection and the camera, and nothing
   here puts them back. */
static void RenderItems(void) {
    const S32 alpha = AlphaLight, beta = BetaLight, gamma = GammaLight;
    SaveCamera();
    SetLightVector(OBJ_ALPHA_LIGHT, OBJ_BETA_LIGHT, 0);
    for (S32 n = 0; n <= FLAG_PROTECTION; n++) {
        const T_INV_OBJ *inv = &TabInv[n];
        if (inv->IdObj3D < 0 || inv->IdObj3D > 3)
            continue;
        S16 *obj = (S16 *)GivePtrObjFix(inv->TabObj3D[inv->IdObj3D]);
        if (obj == NULL)
            continue;
        ClearCell();
        DrawObjInventory(DS_CELL / 2, DS_CELL / 2, obj, inv->BetaObj, 400, 15000);
        S32 x0, y0, x1, y1;
        if (CellBounds(&x0, &y0, &x1, &y1))
            PublishCell(DS_ASSET_ITEM + n, x0, y0, x1, y1);
    }
    RestoreCamera();
    SetLightVector(alpha, beta, gamma);
}

static void RenderAssets(void) {
    /* The logos run before the sprite and object banks are loaded. */
    if (!s_resourcesReady || HQRPtrSpriteRaw == NULL || HQR_ObjFix == NULL)
        return;
    const S32 wantGlyphs = !s_glyphsDone;
    /* The item models need the inventory's textures and 3D view, so they are
       drawn from its loop only; the panel keeps the last ones between runs. */
    const S32 wantItems = s_itemsWanted && CurrentModal() == DS_MODAL_INVENTORY;
    if (!wantGlyphs && !wantItems)
        return;
    if (Log == NULL || ModeDesiredX < DS_CELL || ModeDesiredY < DS_CELL)
        return;

    /* Keep what the game has in that corner of Log, and its drawing state. */
    static U8 saved[DS_CELL * DS_CELL];
    for (S32 y = 0; y < DS_CELL; y++)
        memcpy(saved + y * DS_CELL, (U8 *)Log + TabOffLine[y], DS_CELL);
    const U8 colour = ColMask;
    MemoClip();
    SetClip(0, 0, DS_CELL - 1, DS_CELL - 1);

    if (wantGlyphs) {
        RenderGlyphs();
        RenderSprites();
        s_glyphsDone = 1;
    }
    if (wantItems) {
        RenderItems();
        s_itemsWanted = 0;
    }

    RestoreClip();
    ColMask = colour;
    for (S32 y = 0; y < DS_CELL; y++)
        memcpy((U8 *)Log + TabOffLine[y], saved + y * DS_CELL, DS_CELL);
    s_assetGen++;
}

/* Called once per input poll from UpdateKeyboardState, in every loop the game
   runs, modal or not. */
extern "C" void ApplyCompanionKeys(void) {
    static S32 s_wasActive = 0;
    const S32 active = DsLink_IsActive();
    if (active != s_wasActive) {
        s_wasActive = active;
        /* A modal that is already up only presents what changes; a full
           redraw sends it to whichever display it now belongs on. */
        BoxStaticAdd(0, 0, (S32)ModeDesiredX - 1, (S32)ModeDesiredY - 1);
        BoxCleanClip = TRUE;
    }
    if (!active)
        return;

    /* The panel replaces the on-screen pad: the game display stays clean. */
    if (!s_overlayOff) {
        TouchOverlay_Shutdown();
        s_overlayOff = 1;
    }

    char line[DS_CMD_MAX];
    while (DsLink_PopCommand(line, sizeof(line)))
        RunCommand(line);

    for (S32 i = 0; i < DS_MAX_PULSES; i++) {
        if (s_pulses[i].polls > 0) {
            TabKeys[s_pulses[i].index] |= s_pulses[i].bit;
            /* A held keyboard key is also the poll's Key, which the loops that
               test MyKey (Escape in the holomap and the menus) read. */
            if (Key == 0 && s_pulses[i].key < 256)
                Key = (S32)s_pulses[i].key;
            s_pulses[i].polls--;
        }
    }

    RenderAssets();
    PublishState();
}

void DsBridge_PrePresent(U32 *frameBuffer, U32 pitch, U32 width, U32 height, const U8 *palette_rgb) {
    (void)palette_rgb;
    if (!DsLink_IsActive() || frameBuffer == NULL || width == 0 || height == 0)
        return;
    s_frameW = width;
    s_frameH = height;

    /* The holomap stays on the game display, where the globe has room; the
       panel turns and zooms it. */
    if (CurrentModal() == DS_MODAL_HOLOMAP)
        return;

    if (CurrentModal() == DS_MODAL_NONE) {
        if (s_snapW != width || s_snapH != height) {
            free(s_snapshot);
            s_snapshot = (U32 *)malloc(sizeof(U32) * width * height);
            s_snapW = s_snapshot ? width : 0;
            s_snapH = s_snapshot ? height : 0;
        }
        if (s_snapshot) {
            for (U32 y = 0; y < height; y++)
                memcpy(s_snapshot + y * width, (U8 *)frameBuffer + y * pitch, sizeof(U32) * width);
        }
        return;
    }

    DsLink_PublishFrame(frameBuffer, pitch, (S32)width, (S32)height);

    /* The game display holds the last gameplay frame while the modal runs
       below, or stays dark if there has been none yet (the title menu). */
    for (U32 y = 0; y < height; y++) {
        U32 *row = (U32 *)((U8 *)frameBuffer + y * pitch);
        if (s_snapshot && s_snapW == width && s_snapH == height)
            memcpy(row, s_snapshot + y * width, sizeof(U32) * width);
        else
            memset(row, 0, sizeof(U32) * width);
    }
}
