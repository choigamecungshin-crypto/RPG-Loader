#ifndef ANDROID_VIDEO_H
#define ANDROID_VIDEO_H

#include <stdbool.h>
#include <stdint.h>

typedef struct AndroidVideo AndroidVideo;

AndroidVideo* AndroidVideo_open(const char* path);
void AndroidVideo_close(AndroidVideo* video);
void AndroidVideo_update(AndroidVideo* video);
void AndroidVideo_draw(AndroidVideo* video);
int AndroidVideo_getStatus(AndroidVideo* video);
int64_t AndroidVideo_getPosition(AndroidVideo* video);
int64_t AndroidVideo_getDuration(AndroidVideo* video);
void AndroidVideo_pause(AndroidVideo* video);
void AndroidVideo_resume(AndroidVideo* video);
int AndroidVideo_getFormat(AndroidVideo* video);
void AndroidVideo_setLoop(AndroidVideo* video, bool loop);
void AndroidVideo_setVolume(AndroidVideo* video, float volume);

#endif
