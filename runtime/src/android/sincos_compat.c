#include <math.h>

__attribute__((visibility("hidden")))
void sincos(double x, double *sinp, double *cosp) {
    *sinp = sin(x);
    *cosp = cos(x);
}
