#include <SYSTEM/DS_LINK.H>

#include <stdlib.h>
#include <string.h>

/* Only Android has a companion thread to race with. Elsewhere nothing calls
   the companion half of this file, so the lock compiles away. */
#if defined(__ANDROID__)
#include <jni.h>
#include <pthread.h>
static pthread_mutex_t s_lock = PTHREAD_MUTEX_INITIALIZER;
#define DS_LOCK() pthread_mutex_lock(&s_lock)
#define DS_UNLOCK() pthread_mutex_unlock(&s_lock)
#else
#define DS_LOCK()
#define DS_UNLOCK()
#endif

#define DS_QUEUE_LEN 32
#define DS_FRAME_MAX_PIXELS (1920 * 1080)

static volatile S32 s_active = 0;

static char s_queue[DS_QUEUE_LEN][DS_CMD_MAX];
static S32 s_qHead = 0;
static S32 s_qCount = 0;

static S32 s_state[DS_STATE_MAX];
static S32 s_stateCount = 0;

typedef struct {
    U32 *pixels;
    S32 w, h;
} T_DS_ASSET;
static T_DS_ASSET s_assets[DS_ASSET_MAX];

static U32 s_frame[DS_FRAME_MAX_PIXELS];
static S32 s_frameW = 0;
static S32 s_frameH = 0;
static U32 s_frameSeq = 0;

extern "C" {

void DsLink_SetActive(S32 active) { s_active = active ? 1 : 0; }

S32 DsLink_IsActive(void) { return s_active; }

void DsLink_PostCommand(const char *line) {
    if (line == NULL)
        return;
    DS_LOCK();
    if (s_qCount < DS_QUEUE_LEN) {
        S32 slot = (s_qHead + s_qCount) % DS_QUEUE_LEN;
        strncpy(s_queue[slot], line, DS_CMD_MAX - 1);
        s_queue[slot][DS_CMD_MAX - 1] = '\0';
        s_qCount++;
    }
    DS_UNLOCK();
}

S32 DsLink_PopCommand(char *out, S32 max) {
    S32 got = 0;
    if (out == NULL || max <= 0)
        return 0;
    DS_LOCK();
    if (s_qCount > 0) {
        strncpy(out, s_queue[s_qHead], (size_t)max - 1);
        out[max - 1] = '\0';
        s_qHead = (s_qHead + 1) % DS_QUEUE_LEN;
        s_qCount--;
        got = 1;
    }
    DS_UNLOCK();
    return got;
}

void DsLink_PublishState(const S32 *state, S32 count) {
    if (count > DS_STATE_MAX)
        count = DS_STATE_MAX;
    DS_LOCK();
    memcpy(s_state, state, sizeof(S32) * (size_t)count);
    s_stateCount = count;
    DS_UNLOCK();
}

S32 DsLink_CopyState(S32 *out, S32 max) {
    S32 n;
    DS_LOCK();
    n = s_stateCount < max ? s_stateCount : max;
    memcpy(out, s_state, sizeof(S32) * (size_t)n);
    DS_UNLOCK();
    return n;
}

void DsLink_PublishFrame(const U32 *pixels, U32 pitchBytes, S32 w, S32 h) {
    if (pixels == NULL || w <= 0 || h <= 0 || (S64)w * h > DS_FRAME_MAX_PIXELS)
        return;
    DS_LOCK();
    for (S32 y = 0; y < h; y++)
        memcpy(s_frame + (size_t)y * w, (const U8 *)pixels + (size_t)y * pitchBytes,
               sizeof(U32) * (size_t)w);
    s_frameW = w;
    s_frameH = h;
    s_frameSeq++;
    if (s_frameSeq == 0)
        s_frameSeq = 1;
    DS_UNLOCK();
}

U32 DsLink_CopyFrame(U32 haveSeq, U32 *out, S32 maxPixels, S32 *w, S32 *h) {
    U32 seq = 0;
    DS_LOCK();
    if (s_frameSeq != haveSeq && s_frameW * s_frameH <= maxPixels) {
        memcpy(out, s_frame, sizeof(U32) * (size_t)(s_frameW * s_frameH));
        *w = s_frameW;
        *h = s_frameH;
        seq = s_frameSeq;
    }
    DS_UNLOCK();
    return seq;
}

void DsLink_PublishAsset(S32 id, const U32 *argb, S32 w, S32 h) {
    if (id < 0 || id >= DS_ASSET_MAX || argb == NULL || w <= 0 || h <= 0)
        return;
    U32 *copy = (U32 *)malloc(sizeof(U32) * (size_t)w * h);
    if (copy == NULL)
        return;
    memcpy(copy, argb, sizeof(U32) * (size_t)w * h);
    DS_LOCK();
    free(s_assets[id].pixels);
    s_assets[id].pixels = copy;
    s_assets[id].w = w;
    s_assets[id].h = h;
    DS_UNLOCK();
}

S32 DsLink_CopyAsset(S32 id, U32 *out, S32 maxPixels, S32 *w, S32 *h) {
    S32 ok = 0;
    if (id < 0 || id >= DS_ASSET_MAX)
        return 0;
    DS_LOCK();
    const T_DS_ASSET *a = &s_assets[id];
    if (a->pixels != NULL && a->w * a->h <= maxPixels) {
        memcpy(out, a->pixels, sizeof(U32) * (size_t)(a->w * a->h));
        *w = a->w;
        *h = a->h;
        ok = 1;
    }
    DS_UNLOCK();
    return ok;
}

#if defined(__ANDROID__)
/* org.lbalab.lba2cc.DsLink, the companion's view of this mailbox. Kept beside
   the mailbox so the exports link in with the functions the game uses. */
JNIEXPORT void JNICALL Java_org_lbalab_lba2cc_DsLink_nativeSetActive(JNIEnv *, jclass, jboolean on) {
    DsLink_SetActive(on ? 1 : 0);
}

JNIEXPORT void JNICALL Java_org_lbalab_lba2cc_DsLink_nativeSend(JNIEnv *env, jclass, jstring line) {
    const char *s = env->GetStringUTFChars(line, NULL);
    if (s != NULL) {
        DsLink_PostCommand(s);
        env->ReleaseStringUTFChars(line, s);
    }
}

JNIEXPORT jint JNICALL Java_org_lbalab_lba2cc_DsLink_nativeGetState(JNIEnv *env, jclass, jintArray out) {
    S32 tmp[DS_STATE_MAX];
    jsize len = env->GetArrayLength(out);
    S32 n = DsLink_CopyState(tmp, len < DS_STATE_MAX ? len : DS_STATE_MAX);
    env->SetIntArrayRegion(out, 0, n, (const jint *)tmp);
    return n;
}

/* `out` is a direct ByteBuffer of 0x00RRGGBB ints; `size` receives {w, h}. */
JNIEXPORT jint JNICALL Java_org_lbalab_lba2cc_DsLink_nativeGetFrame(JNIEnv *env, jclass, jint haveSeq,
                                                                    jobject out, jintArray size) {
    U32 *dst = (U32 *)env->GetDirectBufferAddress(out);
    jlong cap = env->GetDirectBufferCapacity(out);
    if (dst == NULL || cap <= 0)
        return 0;
    S32 w = 0, h = 0;
    U32 seq = DsLink_CopyFrame((U32)haveSeq, dst, (S32)(cap / 4), &w, &h);
    if (seq != 0) {
        jint wh[2] = {w, h};
        env->SetIntArrayRegion(size, 0, 2, wh);
    }
    return (jint)seq;
}

/* `out` as for nativeGetFrame. Returns 1 and fills `size` when the asset exists. */
JNIEXPORT jint JNICALL Java_org_lbalab_lba2cc_DsLink_nativeGetAsset(JNIEnv *env, jclass, jint id, jobject out,
                                                                    jintArray size) {
    U32 *dst = (U32 *)env->GetDirectBufferAddress(out);
    jlong cap = env->GetDirectBufferCapacity(out);
    if (dst == NULL || cap <= 0)
        return 0;
    S32 w = 0, h = 0;
    if (!DsLink_CopyAsset(id, dst, (S32)(cap / 4), &w, &h))
        return 0;
    jint wh[2] = {w, h};
    env->SetIntArrayRegion(size, 0, 2, wh);
    return 1;
}
#endif

} // extern "C"
