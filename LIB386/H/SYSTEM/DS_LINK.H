#pragma once

#include <SYSTEM/ADELINE_TYPES.H>

/* Mailbox between the game thread and a second-screen companion UI (the
 * Android dual-screen build). The companion runs on its own thread: it posts
 * command lines and reads back a state vector and, while a modal screen is
 * shown on the second display, that screen's pixels.
 *
 * Nothing here is active until the companion says so (DsLink_SetActive), so a
 * build or a device without a companion behaves exactly as before. The game
 * side lives in SOURCES/DS_BRIDGE.CPP; this layer only moves data across the
 * thread boundary and owns no game knowledge. */

#ifdef __cplusplus
extern "C" {
#endif

#define DS_STATE_MAX 64
#define DS_CMD_MAX 96

/* Called from the companion's thread. */
void DsLink_SetActive(S32 active);
void DsLink_PostCommand(const char *line);
S32 DsLink_CopyState(S32 *out, S32 max);
/* Copies the latest second-screen frame (0x00RRGGBB) if it is newer than
 * `haveSeq`. Returns the frame's sequence number, or 0 when nothing newer. */
U32 DsLink_CopyFrame(U32 haveSeq, U32 *out, S32 maxPixels, S32 *w, S32 *h);

/* Pictures the panel draws with (the game's font, sprites, item models),
 * rendered once by the game and kept here. 0x00RRGGBB with alpha in the top
 * byte. CopyAsset returns 0 when `id` has none. */
#define DS_ASSET_MAX 256
S32 DsLink_CopyAsset(S32 id, U32 *out, S32 maxPixels, S32 *w, S32 *h);

/* Called from the game thread. */
void DsLink_PublishAsset(S32 id, const U32 *argb, S32 w, S32 h);
S32 DsLink_IsActive(void);
S32 DsLink_PopCommand(char *out, S32 max);
void DsLink_PublishState(const S32 *state, S32 count);
void DsLink_PublishFrame(const U32 *pixels, U32 pitchBytes, S32 w, S32 h);

#ifdef __cplusplus
}
#endif
