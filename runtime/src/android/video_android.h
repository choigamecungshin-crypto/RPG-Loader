#ifndef BS_VIDEO_ANDROID_H
#define BS_VIDEO_ANDROID_H

#include <jni.h>
#include "vm.h"

void AndroidVideo_setJavaVM(JavaVM* vm);

RValue AndroidVideo_open(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_close(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_end(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_draw(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_setVolume(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_pause(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_resume(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_enableLoop(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_seekTo(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_isLooping(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_getVolume(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_getDuration(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_getPosition(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_getStatus(VMContext* ctx, RValue* args, int32_t argCount);
RValue AndroidVideo_getFormat(VMContext* ctx, RValue* args, int32_t argCount);

void AndroidVideo_shutdown(void);

#endif
