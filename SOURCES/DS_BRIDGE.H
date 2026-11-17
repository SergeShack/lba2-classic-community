#pragma once

#include <SYSTEM/ADELINE_TYPES.H>

/* Game side of the dual-screen companion (Android, Retroid Pocket Duo Lite).
 *
 * The companion is a separate UI on the second display. It reads the hero's
 * state from here and sends commands that become ordinary key presses, so no
 * game rule changes: switching behaviour or weapon from the panel is the same
 * F5-F8 / 1-7 press the original takes.
 *
 * While a modal screen (game menu, inventory, behaviour menu, slate, saved
 * games) runs, its frames go to the second display and the game display keeps
 * the last gameplay frame. The holomap stays on the game display; the panel
 * then turns and zooms it. Nothing here does anything until the
 * companion is running (DsLink_IsActive), so single-screen play is unchanged.
 *
 * Wire format of the commands and of the state vector: DS_BRIDGE.CPP. */

#ifdef __cplusplus
extern "C" {
#endif

enum {
    DS_MODAL_NONE = 0,
    DS_MODAL_MENU = 1,
    DS_MODAL_HOLOMAP = 2,
    DS_MODAL_INVENTORY = 3,
    DS_MODAL_BEHAVIOUR = 4,
    DS_MODAL_SLATE = 5,
    DS_MODAL_SAVES = 6,
    /* Reported to the panel only: a dialogue stays on the game display. */
    DS_MODAL_DIALOG = 7
};

void DsBridge_ModalEnter(S32 kind);
void DsBridge_ModalLeave(void);
void DsBridge_DialogEnter(void);
void DsBridge_DialogLeave(void);
/* From the gameplay input poll: the hero is in a scene. */
void DsBridge_NoteGameplay(void);

/* From the PrePresent chain, before anything else draws on the frame. */
void DsBridge_PrePresent(U32 *frameBuffer, U32 pitch, U32 width, U32 height, const U8 *palette_rgb);

#ifdef __cplusplus
}

/* One line at the top of a modal loop puts it on the second display for as
   long as it runs, whichever way it returns. */
class DsModalScope {
  public:
    explicit DsModalScope(S32 kind) { DsBridge_ModalEnter(kind); }
    ~DsModalScope() { DsBridge_ModalLeave(); }
};

class DsDialogScope {
  public:
    DsDialogScope() { DsBridge_DialogEnter(); }
    ~DsDialogScope() { DsBridge_DialogLeave(); }
};
#endif
