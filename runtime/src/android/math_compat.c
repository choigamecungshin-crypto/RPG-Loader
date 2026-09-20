#include <math.h>
#include <stdint.h>

__attribute__((visibility("hidden")))
double sqrt(double x) {
    return __builtin_sqrt(x);
}

__attribute__((visibility("hidden")))
double fabs(double x) {
    union {
        double d;
        uint64_t u;
    } v;
    v.d = x;
    v.u &= UINT64_C(0x7fffffffffffffff);
    return v.d;
}

__attribute__((visibility("hidden")))
float sqrtf(float x) { return (float)sqrt((double)x); }

__attribute__((visibility("hidden")))
float sinf(float x) { return (float)sin((double)x); }

__attribute__((visibility("hidden")))
float cosf(float x) { return (float)cos((double)x); }

__attribute__((visibility("hidden")))
float tanf(float x) { return (float)tan((double)x); }

__attribute__((visibility("hidden")))
float atanf(float x) { return (float)atan((double)x); }

__attribute__((visibility("hidden")))
float atan2f(float y, float x) { return (float)atan2((double)y, (double)x); }

__attribute__((visibility("hidden")))
float powf(float x, float y) { return (float)pow((double)x, (double)y); }

__attribute__((visibility("hidden")))
float logf(float x) { return (float)log((double)x); }

__attribute__((visibility("hidden")))
float expf(float x) { return (float)exp((double)x); }

__attribute__((visibility("hidden")))
float floorf(float x) { return (float)floor((double)x); }

__attribute__((visibility("hidden")))
float ceilf(float x) { return (float)ceil((double)x); }

__attribute__((visibility("hidden")))
float fmodf(float x, float y) { return (float)fmod((double)x, (double)y); }

__attribute__((visibility("hidden")))
float fabsf(float x) {
    union {
        float f;
        uint32_t u;
    } v;
    v.f = x;
    v.u &= UINT32_C(0x7fffffff);
    return v.f;
}
