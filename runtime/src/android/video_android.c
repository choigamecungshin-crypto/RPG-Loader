#include "video_android.h"

#include "common.h"
#include "runner.h"
#include "renderer.h"
#include "gml_array.h"
#include "rvalue.h"
#include "log.h"

#include "gl_common.h"

#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>

#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <stdint.h>

typedef struct AndroidVideo {
    bool active;
    bool paused;
    bool looping;
    bool eos;
    bool started;

    float volume;

    int32_t surfaceId;
    int32_t width;
    int32_t height;

    int64_t durationUs;
    int64_t positionUs;

    AMediaExtractor* extractor;
    AMediaCodec* codec;
    ANativeWindow* window;

    jobject surfaceTexture;
    jobject surface;

    GLuint externalTexture;

    GLuint blitProgram;
    GLuint blitVao;
    GLuint blitVbo;
    GLint blitTexLoc;
    GLint blitMatrixLoc;

    float texMatrix[16];
} AndroidVideo;

static JavaVM* gJvm = nullptr;

static jclass gSurfaceTextureClass = nullptr;
static jmethodID gSurfaceTextureCtor = nullptr;
static jmethodID gSurfaceTextureUpdate = nullptr;
static jmethodID gSurfaceTextureMatrix = nullptr;
static jmethodID gSurfaceTextureRelease = nullptr;

static jclass gSurfaceClass = nullptr;
static jmethodID gSurfaceCtor = nullptr;
static jmethodID gSurfaceRelease = nullptr;

static AndroidVideo gVideo = {0};

static JNIEnv* getJNIEnv(void) {
    if (gJvm == nullptr) return nullptr;

    JNIEnv* env = nullptr;

    if ((*gJvm)->GetEnv(
            gJvm,
            (void**) &env,
            JNI_VERSION_1_6
        ) != JNI_OK) {
        return nullptr;
    }

    return env;
}

void AndroidVideo_setJavaVM(JavaVM* vm) {
    gJvm = vm;

    JNIEnv* env = nullptr;
    if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_6) != JNI_OK)
        return;

    jclass stLocal = (*env)->FindClass(env, "android/graphics/SurfaceTexture");
    jclass surfaceLocal = (*env)->FindClass(env, "android/view/Surface");

    if (stLocal != nullptr) {
        gSurfaceTextureClass = (*env)->NewGlobalRef(env, stLocal);
        gSurfaceTextureCtor =
            (*env)->GetMethodID(env, gSurfaceTextureClass, "<init>", "(I)V");
        gSurfaceTextureUpdate =
            (*env)->GetMethodID(env, gSurfaceTextureClass, "updateTexImage", "()V");
        gSurfaceTextureMatrix =
            (*env)->GetMethodID(env, gSurfaceTextureClass, "getTransformMatrix", "([F)V");
        gSurfaceTextureRelease =
            (*env)->GetMethodID(env, gSurfaceTextureClass, "release", "()V");
        (*env)->DeleteLocalRef(env, stLocal);
    }

    if (surfaceLocal != nullptr) {
        gSurfaceClass = (*env)->NewGlobalRef(env, surfaceLocal);
        gSurfaceCtor =
            (*env)->GetMethodID(env, gSurfaceClass, "<init>", "(Landroid/graphics/SurfaceTexture;)V");
        gSurfaceRelease =
            (*env)->GetMethodID(env, gSurfaceClass, "release", "()V");
        (*env)->DeleteLocalRef(env, surfaceLocal);
    }
}

static GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);

    if (!ok) {
        char logBuf[2048];
        GLsizei len = 0;
        glGetShaderInfoLog(shader, sizeof(logBuf), &len, logBuf);
        logError("AndroidVideo shader compile failed: %s", logBuf);
        glDeleteShader(shader);
        return 0;
    }

    return shader;
}

static GLuint createBlitProgram(void) {
    static const char* vs =
        "#version 300 es\n"
        "in vec2 aPos;\n"
        "in vec2 aUv;\n"
        "uniform mat4 uTexMatrix;\n"
        "out vec2 vUv;\n"
        "void main() {\n"
        "    gl_Position = vec4(aPos, 0.0, 1.0);\n"
        "    vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;\n"
        "}\n";

    static const char* fs =
        "#version 300 es\n"
        "#extension GL_OES_EGL_image_external_essl3 : require\n"
        "precision mediump float;\n"
        "uniform samplerExternalOES uVideo;\n"
        "in vec2 vUv;\n"
        "out vec4 fragColor;\n"
        "void main() {\n"
        "    fragColor = texture(uVideo, vUv);\n"
        "}\n";

    GLuint vert = compileShader(GL_VERTEX_SHADER, vs);
    GLuint frag = compileShader(GL_FRAGMENT_SHADER, fs);

    if (vert == 0 || frag == 0) {
        if (vert) glDeleteShader(vert);
        if (frag) glDeleteShader(frag);
        return 0;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vert);
    glAttachShader(program, frag);

    glBindAttribLocation(program, 0, "aPos");
    glBindAttribLocation(program, 1, "aUv");

    glLinkProgram(program);

    glDeleteShader(vert);
    glDeleteShader(frag);

    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);

    if (!ok) {
        char logBuf[2048];
        GLsizei len = 0;
        glGetProgramInfoLog(program, sizeof(logBuf), &len, logBuf);
        logError("AndroidVideo shader link failed: %s", logBuf);
        glDeleteProgram(program);
        return 0;
    }

    return program;
}

static bool ensureBlitter(void) {
    if (gVideo.blitProgram != 0)
        return true;

    gVideo.blitProgram = createBlitProgram();
    if (gVideo.blitProgram == 0)
        return false;

    gVideo.blitTexLoc =
        glGetUniformLocation(gVideo.blitProgram, "uVideo");

    gVideo.blitMatrixLoc =
        glGetUniformLocation(gVideo.blitProgram, "uTexMatrix");

    static const float vertices[] = {
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 0.0f,
         1.0f,  1.0f, 1.0f, 0.0f
    };

    glGenVertexArrays(1, &gVideo.blitVao);
    glGenBuffers(1, &gVideo.blitVbo);

    glBindVertexArray(gVideo.blitVao);
    glBindBuffer(GL_ARRAY_BUFFER, gVideo.blitVbo);
    glBufferData(
        GL_ARRAY_BUFFER,
        sizeof(vertices),
        vertices,
        GL_STATIC_DRAW
    );

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(
        0, 2, GL_FLOAT, GL_FALSE,
        4 * sizeof(float),
        (void*) 0
    );

    glEnableVertexAttribArray(1);
    glVertexAttribPointer(
        1, 2, GL_FLOAT, GL_FALSE,
        4 * sizeof(float),
        (void*) (2 * sizeof(float))
    );

    glBindVertexArray(0);

    return true;
}

static void releaseJavaObjects(void) {
    JNIEnv* env = getJNIEnv();
    if (env == nullptr) return;

    if (gVideo.surface != nullptr) {
        (*env)->CallVoidMethod(
            env,
            gVideo.surface,
            gSurfaceRelease
        );
        (*env)->DeleteGlobalRef(env, gVideo.surface);
        gVideo.surface = nullptr;
    }

    if (gVideo.surfaceTexture != nullptr) {
        (*env)->CallVoidMethod(
            env,
            gVideo.surfaceTexture,
            gSurfaceTextureRelease
        );
        (*env)->DeleteGlobalRef(env, gVideo.surfaceTexture);
        gVideo.surfaceTexture = nullptr;
    }
}

static void destroyCodec(void) {
    if (gVideo.codec != nullptr) {
        if (gVideo.started)
            AMediaCodec_stop(gVideo.codec);

        AMediaCodec_delete(gVideo.codec);
        gVideo.codec = nullptr;
    }

    if (gVideo.extractor != nullptr) {
        AMediaExtractor_delete(gVideo.extractor);
        gVideo.extractor = nullptr;
    }

    if (gVideo.window != nullptr) {
        ANativeWindow_release(gVideo.window);
        gVideo.window = nullptr;
    }
}

void AndroidVideo_shutdown(void) {
    if (gVideo.surfaceId >= 0) {
        // Renderer owns the surface lifecycle; close is normally called while runner exists.
    }

    destroyCodec();
    releaseJavaObjects();

    if (gVideo.externalTexture != 0) {
        glDeleteTextures(1, &gVideo.externalTexture);
        gVideo.externalTexture = 0;
    }

    if (gVideo.blitVbo != 0) {
        glDeleteBuffers(1, &gVideo.blitVbo);
        gVideo.blitVbo = 0;
    }

    if (gVideo.blitVao != 0) {
        glDeleteVertexArrays(1, &gVideo.blitVao);
        gVideo.blitVao = 0;
    }

    if (gVideo.blitProgram != 0) {
        glDeleteProgram(gVideo.blitProgram);
        gVideo.blitProgram = 0;
    }

    memset(&gVideo, 0, sizeof(gVideo));
    gVideo.surfaceId = -1;
}

static bool updateSurfaceTexture(void) {
    JNIEnv* env = getJNIEnv();
    if (env == nullptr || gVideo.surfaceTexture == nullptr)
        return false;

    (*env)->CallVoidMethod(
        env,
        gVideo.surfaceTexture,
        gSurfaceTextureUpdate
    );

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return false;
    }

    (*env)->CallVoidMethod(
        env,
        gVideo.surfaceTexture,
        gSurfaceTextureMatrix,
        nullptr
    );

    return true;
}

static bool decodeOneFrame(void) {
    if (gVideo.codec == nullptr || gVideo.extractor == nullptr)
        return false;

    if (!gVideo.eos) {
        ssize_t inputIndex =
            AMediaCodec_dequeueInputBuffer(gVideo.codec, 0);

        if (inputIndex >= 0) {
            size_t capacity = 0;
            uint8_t* buffer =
                AMediaCodec_getInputBuffer(
                    gVideo.codec,
                    (size_t) inputIndex,
                    &capacity
                );

            ssize_t sampleSize =
                AMediaExtractor_readSampleData(
                    gVideo.extractor,
                    buffer,
                    capacity
                );

            if (sampleSize < 0) {
                AMediaCodec_queueInputBuffer(
                    gVideo.codec,
                    (size_t) inputIndex,
                    0,
                    0,
                    0,
                    AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
                );

                gVideo.eos = true;
            } else {
                int64_t pts =
                    AMediaExtractor_getSampleTime(
                        gVideo.extractor
                    );

                AMediaCodec_queueInputBuffer(
                    gVideo.codec,
                    (size_t) inputIndex,
                    0,
                    (size_t) sampleSize,
                    (uint64_t) pts,
                    0
                );

                AMediaExtractor_advance(gVideo.extractor);
            }
        }
    }

    AMediaCodecBufferInfo info;
    memset(&info, 0, sizeof(info));

    ssize_t outputIndex =
        AMediaCodec_dequeueOutputBuffer(
            gVideo.codec,
            &info,
            0
        );

    if (outputIndex >= 0) {
        if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
            gVideo.eos = true;
            AMediaCodec_releaseOutputBuffer(
                gVideo.codec,
                (size_t) outputIndex,
                false
            );
            return false;
        }

        gVideo.positionUs = info.presentationTimeUs;

        AMediaCodec_releaseOutputBuffer(
            gVideo.codec,
            (size_t) outputIndex,
            true
        );

        return updateSurfaceTexture();
    }

    return false;
}

static bool createVideoSurface(Renderer* renderer) {
    if (gVideo.surfaceId >= 0)
        return true;

    gVideo.surfaceId =
        renderer->vtable->createSurface(
            renderer,
            gVideo.width,
            gVideo.height
        );

    return gVideo.surfaceId >= 0;
}

static bool blitVideoToSurface(Renderer* renderer) {
    if (!ensureBlitter())
        return false;

    if (gVideo.surfaceId < 0)
        return false;

    GLRenderer* gl = (GLRenderer*) renderer;

    if ((uint32_t) gVideo.surfaceId >= gl->surfaceCount)
        return false;

    GLuint fbo = gl->surfaces[gVideo.surfaceId];
    if (fbo == 0)
        return false;

    GLint oldFbo = 0;
    GLint oldViewport[4];
    GLint oldProgram = 0;
    GLint oldTexture = 0;
    GLint oldActiveTexture = 0;
    GLint oldVao = 0;

    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &oldFbo);
    glGetIntegerv(GL_VIEWPORT, oldViewport);
    glGetIntegerv(GL_CURRENT_PROGRAM, &oldProgram);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &oldActiveTexture);
    glGetIntegerv(GL_TEXTURE_BINDING_EXTERNAL_OES, &oldTexture);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &oldVao);

    GLboolean blend = glIsEnabled(GL_BLEND);
    GLboolean depth = glIsEnabled(GL_DEPTH_TEST);
    GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);

    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);

    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glViewport(0, 0, gVideo.width, gVideo.height);

    glUseProgram(gVideo.blitProgram);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, gVideo.externalTexture);
    glUniform1i(gVideo.blitTexLoc, 0);

    float identity[16] = {
        1,0,0,0,
        0,1,0,0,
        0,0,1,0,
        0,0,0,1
    };

    glUniformMatrix4fv(
        gVideo.blitMatrixLoc,
        1,
        GL_FALSE,
        identity
    );

    glBindVertexArray(gVideo.blitVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glBindVertexArray((GLuint) oldVao);
    glUseProgram((GLuint) oldProgram);

    glActiveTexture((GLenum) oldActiveTexture);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, (GLuint) oldTexture);

    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint) oldFbo);
    glViewport(
        oldViewport[0],
        oldViewport[1],
        oldViewport[2],
        oldViewport[3]
    );

    if (blend) glEnable(GL_BLEND);
    if (depth) glEnable(GL_DEPTH_TEST);
    if (scissor) glEnable(GL_SCISSOR_TEST);

    return true;
}

RValue AndroidVideo_open(VMContext* ctx, RValue* args, int32_t argCount) {
    if (argCount < 1)
        return RValue_makeBool(false);

    AndroidVideo_shutdown();

    char* path =
        RValue_toString(
            args[0],
            ctx->runner->dataWin
        );

    if (path == nullptr)
        return RValue_makeBool(false);

    FileSystem* fs = ctx->runner->fileSystem;
    const char* resolved =
        fs->vtable->resolvePath(fs, path);

    if (resolved == nullptr) {
        free(path);
        return RValue_makeBool(false);
    }

    gVideo.surfaceId = -1;
    gVideo.volume = 1.0f;
    gVideo.looping = false;

    gVideo.extractor = AMediaExtractor_new();

    if (gVideo.extractor == nullptr ||
        AMediaExtractor_setDataSource(
            gVideo.extractor,
            resolved
        ) != AMEDIA_OK) {
        free(path);
        AndroidVideo_shutdown();
        return RValue_makeBool(false);
    }

    size_t trackCount =
        AMediaExtractor_getTrackCount(
            gVideo.extractor
        );

    AMediaFormat* format = nullptr;
    int videoTrack = -1;

    for (size_t i = 0; i < trackCount; i++) {
        AMediaFormat* f =
            AMediaExtractor_getTrackFormat(
                gVideo.extractor,
                i
            );

        const char* mime = nullptr;

        if (f != nullptr &&
            AMediaFormat_getString(
                f,
                "mime",
                &mime
            ) &&
            mime != nullptr &&
            strncmp(mime, "video/", 6) == 0) {
            format = f;
            videoTrack = (int) i;
            break;
        }

        if (f != nullptr)
            AMediaFormat_delete(f);
    }

    if (videoTrack < 0 || format == nullptr) {
        free(path);
        AndroidVideo_shutdown();
        return RValue_makeBool(false);
    }

    AMediaFormat_getInt32(
        format,
        "width",
        &gVideo.width
    );

    AMediaFormat_getInt32(
        format,
        "height",
        &gVideo.height
    );

    AMediaFormat_getInt64(
        format,
        "duration",
        &gVideo.durationUs
    );

    const char* mime = nullptr;
    AMediaFormat_getString(
        format,
        "mime",
        &mime
    );

    if (mime == nullptr) {
        AMediaFormat_delete(format);
        free(path);
        AndroidVideo_shutdown();
        return RValue_makeBool(false);
    }

    AMediaExtractor_selectTrack(
        gVideo.extractor,
        (size_t) videoTrack
    );

    JNIEnv* env = getJNIEnv();
    if (env == nullptr) {
        AMediaFormat_delete(format);
        free(path);
        AndroidVideo_shutdown();
        return RValue_makeBool(false);
    }

    glGenTextures(1, &gVideo.externalTexture);
    glBindTexture(
        GL_TEXTURE_EXTERNAL_OES,
        gVideo.externalTexture
    );

    glTexParameteri(
        GL_TEXTURE_EXTERNAL_OES,
        GL_TEXTURE_MIN_FILTER,
        GL_LINEAR
    );
    glTexParameteri(
        GL_TEXTURE_EXTERNAL_OES,
        GL_TEXTURE_MAG_FILTER,
        GL_LINEAR
    );
    glTexParameteri(
        GL_TEXTURE_EXTERNAL_OES,
        GL_TEXTURE_WRAP_S,
        GL_CLAMP_TO_EDGE
    );
    glTexParameteri(
        GL_TEXTURE_EXTERNAL_OES,
        GL_TEXTURE_WRAP_T,
        GL_CLAMP_TO_EDGE
    );

    jobject st =
        (*env)->NewObject(
            env,
            gSurfaceTextureClass,
            gSurfaceTextureCtor,
            (jint) gVideo.externalTexture
        );

    if (st == nullptr) {
        AMediaFormat_delete(format);
        free(path);
        AndroidVideo_shutdown();
        return RValue_makeBool(false);
    }

    gVideo.surfaceTexture =
        (*env)->NewGlobalRef(env, st);

    jobject surface =
        (*env)->NewObject(
            env,
            gSurfaceClass,
            gSurfaceCtor,
            st
        );

    (*env)->DeleteLocalRef(env, st);

    if (surface == nullptr) {
        AMediaFormat_delete(format);
        free(path);
        AndroidVideo_shutdown();
        return RValue_makeBool(false);
    }

    gVideo.surface =
        (*env)->NewGlobalRef(env, surface);

    gVideo.window =
        ANativeWindow_fromSurface(
            env,
            surface
        );

    (*env)->DeleteLocalRef(env, surface);

    if (gVideo.window == nullptr) {
        AMediaFormat_delete(format);
        free(path);
        AndroidVideo_shutdown();
        return RValue_makeBool(false);
    }

    gVideo.codec =
        AMediaCodec_createDecoderByType(mime);

    if (gVideo.codec == nullptr ||
        AMediaCodec_configure(
            gVideo.codec,
            format,
            gVideo.window,
            nullptr,
            0
        ) != AMEDIA_OK ||
        AMediaCodec_start(gVideo.codec) != AMEDIA_OK) {
        AMediaFormat_delete(format);
        free(path);
        AndroidVideo_shutdown();
        return RValue_makeBool(false);
    }

    gVideo.started = true;
    gVideo.active = true;

    AMediaFormat_delete(format);
    free(path);

    return RValue_makeBool(true);
}

RValue AndroidVideo_close(VMContext* ctx, RValue* args, int32_t argCount) {
    AndroidVideo_shutdown();
    return RValue_makeUndefined();
}

RValue AndroidVideo_draw(VMContext* ctx, RValue* args, int32_t argCount) {
    GMLArray* out = GMLArray_create(ctx->dataWin, 2);

    if (!gVideo.active) {
        *GMLArray_slot(out, 0) = RValue_makeReal(-1.0);
        *GMLArray_slot(out, 1) = RValue_makeReal(-1.0);
        return RValue_makeArray(out);
    }

    if (!gVideo.paused) {
        decodeOneFrame();
    }

    if (gVideo.eos && !gVideo.looping) {
        *GMLArray_slot(out, 0) = RValue_makeReal(-2.0);
        *GMLArray_slot(out, 1) = RValue_makeReal(gVideo.surfaceId);
        return RValue_makeArray(out);
    }

    if (gVideo.eos && gVideo.looping) {
        AMediaExtractor_seekTo(
            gVideo.extractor,
            0,
            AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC
        );

        AMediaCodec_flush(gVideo.codec);
        AMediaCodec_start(gVideo.codec);

        gVideo.eos = false;
        gVideo.positionUs = 0;
    }

    if (gVideo.surfaceId < 0) {
        if (!createVideoSurface(ctx->runner->renderer)) {
            *GMLArray_slot(out, 0) = RValue_makeReal(-1.0);
            *GMLArray_slot(out, 1) = RValue_makeReal(-1.0);
            return RValue_makeArray(out);
        }
    }

    blitVideoToSurface(ctx->runner->renderer);

    *GMLArray_slot(out, 0) = RValue_makeReal(0.0);
    *GMLArray_slot(out, 1) = RValue_makeReal(gVideo.surfaceId);

    return RValue_makeArray(out);
}

RValue AndroidVideo_setVolume(VMContext* ctx, RValue* args, int32_t argCount) {
    if (argCount > 0)
        gVideo.volume = (float) RValue_toReal(args[0]);

    return RValue_makeUndefined();
}

RValue AndroidVideo_pause(VMContext* ctx, RValue* args, int32_t argCount) {
    gVideo.paused = true;
    return RValue_makeUndefined();
}

RValue AndroidVideo_resume(VMContext* ctx, RValue* args, int32_t argCount) {
    gVideo.paused = false;
    return RValue_makeUndefined();
}

RValue AndroidVideo_enableLoop(VMContext* ctx, RValue* args, int32_t argCount) {
    gVideo.looping =
        argCount > 0 &&
        RValue_toBool(args[0]);

    return RValue_makeUndefined();
}

RValue AndroidVideo_seekTo(VMContext* ctx, RValue* args, int32_t argCount) {
    if (!gVideo.active || argCount < 1)
        return RValue_makeUndefined();

    int64_t position =
        (int64_t) RValue_toReal(args[0]);

    if (position < 0)
        position = 0;

    AMediaExtractor_seekTo(
        gVideo.extractor,
        position,
        AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC
    );

    AMediaCodec_flush(gVideo.codec);
    AMediaCodec_start(gVideo.codec);

    gVideo.positionUs = position;
    gVideo.eos = false;

    return RValue_makeUndefined();
}

RValue AndroidVideo_isLooping(VMContext* ctx, RValue* args, int32_t argCount) {
    return RValue_makeBool(gVideo.looping);
}

RValue AndroidVideo_getVolume(VMContext* ctx, RValue* args, int32_t argCount) {
    return RValue_makeReal(gVideo.volume);
}

RValue AndroidVideo_getDuration(VMContext* ctx, RValue* args, int32_t argCount) {
    return RValue_makeReal(
        (double) gVideo.durationUs / 1000000.0
    );
}

RValue AndroidVideo_getPosition(VMContext* ctx, RValue* args, int32_t argCount) {
    return RValue_makeReal(
        (double) gVideo.positionUs / 1000000.0
    );
}

RValue AndroidVideo_getStatus(VMContext* ctx, RValue* args, int32_t argCount) {
    if (!gVideo.active)
        return RValue_makeReal(-1.0);

    if (gVideo.eos)
        return RValue_makeReal(2.0);

    if (gVideo.paused)
        return RValue_makeReal(1.0);

    return RValue_makeReal(0.0);
}

RValue AndroidVideo_getFormat(VMContext* ctx, RValue* args, int32_t argCount) {
    // Android/GameMaker uses RGBA video surfaces.
    return RValue_makeReal(0.0);
}


RValue AndroidVideo_end(VMContext* ctx, RValue* args, int32_t argCount) {
    return AndroidVideo_close(ctx, args, argCount);
}
