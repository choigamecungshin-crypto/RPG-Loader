#include "image_decoder.h"

#include "stdio_compat.h"
#include <stdlib.h>
#include "string_compat.h"
#include <bzlib.h>
#include "log.h"

#include "stb_image.h"

#define QOI_HEADER_SIZE 12
#define COMPRESSED_QOI_HEADER_SIZE_OLD 8
#define COMPRESSED_QOI_HEADER_SIZE_NEW 12

static inline int signExtend(uint32_t val, int bits) {
    uint32_t mask = 1U << (bits - 1);
    return (int)((val ^ mask) - mask);
}

static inline uint8_t addDelta(uint8_t value, int delta) {
    return (uint8_t)(value + delta);
}

static inline int fioqHash(uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    return (r * 3 + g * 5 + b * 7 + a * 11) & 63;
}

static inline uint32_t fioqSar32(uint32_t value, int shift) {
    int32_t v = (int32_t)value;
    return (uint32_t)(v >> shift);
}

static inline uint32_t fioqPatchLane(
    uint32_t px,
    uint32_t value,
    uint32_t mask
) {
    return px ^ (((px + value) ^ px) & mask);
}

static uint8_t* decodeQoi(
    const uint8_t* data,
    size_t dataSize,
    int* outW,
    int* outH
) {
    if (dataSize < QOI_HEADER_SIZE)
        return nullptr;

    if (data[0] != 'f' ||
        data[1] != 'i' ||
        data[2] != 'o' ||
        data[3] != 'q')
        return nullptr;

    int width =
        data[4] |
        (data[5] << 8);

    int height =
        data[6] |
        (data[7] << 8);

    uint32_t length =
        (uint32_t)data[8] |
        ((uint32_t)data[9] << 8) |
        ((uint32_t)data[10] << 16) |
        ((uint32_t)data[11] << 24);

    if (width <= 0 || height <= 0)
        return nullptr;

    if (
        QOI_HEADER_SIZE +
        (size_t)length >
        dataSize
    )
        return nullptr;

    size_t pixelCount =
        (size_t)width *
        (size_t)height;

    if (pixelCount > SIZE_MAX / 4)
        return nullptr;

    size_t rawSize =
        pixelCount * 4;

    uint8_t* raw =
        (uint8_t*)malloc(rawSize);

    if (!raw)
        return nullptr;

    const uint8_t* pixelData =
        data + QOI_HEADER_SIZE;

    size_t pixelDataSize =
        length;

    size_t pos = 0;
    size_t rawPos = 0;

    uint32_t index[64] = {0};

    uint32_t px =
        0xFF000000u;

    uint32_t run = 0;

    while (rawPos < rawSize) {

        if (run) {
            run--;
        } else {

            if (pos >= pixelDataSize) {
                free(raw);
                return nullptr;
            }

            uint8_t b1 =
                pixelData[pos++];

            if (b1 < 0x80) {

                if (b1 < 0x40) {

                    /*
                     * INDEX
                     */
                    px =
                        index[b1];

                } else {

                    /*
                     * RUN
                     */
                    run =
                        b1 & 0x1F;

                    if (b1 & 0x20) {

                        if (pos >= pixelDataSize) {
                            free(raw);
                            return nullptr;
                        }

                        run =
                            (run << 8) |
                            pixelData[pos++];

                        run += 0x20;
                    }
                }

            } else {

                if (!(b1 & 0x40)) {

                    /*
                     * DIFF8
                     *
                     * Only the lower 6 bits
                     * participate in the deltas.
                     */

                    px = fioqPatchLane(
                        px,
                        fioqSar32(
                            ((uint32_t)(b1 & 0x30)) << 26,
                            30
                        ),
                        0x000000FFu
                    );

                    px = fioqPatchLane(
                        px,
                        fioqSar32(
                            ((uint32_t)(b1 & 0x0C)) << 28,
                            22
                        ),
                        0x0000FF00u
                    );

                    px = fioqPatchLane(
                        px,
                        fioqSar32(
                            ((uint32_t)(b1 & 0x03)) << 30,
                            14
                        ),
                        0x00FF0000u
                    );

                } else if (!(b1 & 0x20)) {

                    /*
                     * DIFF16
                     */

                    if (pos >= pixelDataSize) {
                        free(raw);
                        return nullptr;
                    }

                    uint32_t value =
                        ((uint32_t)b1 << 8) |
                        pixelData[pos++];

                    px = fioqPatchLane(
                        px,
                        fioqSar32(
                            (value & 0x1F00) << 19,
                            27
                        ),
                        0x000000FFu
                    );

                    px = fioqPatchLane(
                        px,
                        fioqSar32(
                            (value & 0x00F0) << 24,
                            20
                        ),
                        0x0000FF00u
                    );

                    px = fioqPatchLane(
                        px,
                        fioqSar32(
                            (value & 0x000F) << 28,
                            12
                        ),
                        0x00FF0000u
                    );

                } else if (!(b1 & 0x10)) {

                    /*
                     * DIFF24
                     */

                    if (pos + 1 >= pixelDataSize) {
                        free(raw);
                        return nullptr;
                    }

                    uint32_t value =
                        ((uint32_t)b1 << 16) |
                        ((uint32_t)pixelData[pos++] << 8) |
                        pixelData[pos++];

                    px = fioqPatchLane(
                        px,
                        fioqSar32(
                            (value & 0x0F8000) << 12,
                            27
                        ),
                        0x000000FFu
                    );

                    px = fioqPatchLane(
                        px,
                        fioqSar32(
                            (value & 0x007C00) << 17,
                            19
                        ),
                        0x0000FF00u
                    );

                    px = fioqPatchLane(
                        px,
                        fioqSar32(
                            (value & 0x0003E0) << 22,
                            11
                        ),
                        0x00FF0000u
                    );

                    /*
                     * Alpha delta.
                     */
                    px =
                        (px +
                         (
                            fioqSar32(
                                (value & 0x00001F) << 27,
                                3
                            ) &
                            0xFF000000u
                         ));

                } else {

                    /*
                     * COLOR
                     */

                    if (b1 & 0x08) {
                        if (pos >= pixelDataSize) {
                            free(raw);
                            return nullptr;
                        }

                        px =
                            (px & 0xFFFFFF00u) |
                            pixelData[pos++];
                    }

                    if (b1 & 0x04) {
                        if (pos >= pixelDataSize) {
                            free(raw);
                            return nullptr;
                        }

                        px =
                            (px & 0xFFFF00FFu) |
                            ((uint32_t)pixelData[pos++] << 8);
                    }

                    if (b1 & 0x02) {
                        if (pos >= pixelDataSize) {
                            free(raw);
                            return nullptr;
                        }

                        px =
                            (px & 0xFF00FFFFu) |
                            ((uint32_t)pixelData[pos++] << 16);
                    }

                    if (b1 & 0x01) {
                        if (pos >= pixelDataSize) {
                            free(raw);
                            return nullptr;
                        }

                        px =
                            (px & 0x00FFFFFFu) |
                            ((uint32_t)pixelData[pos++] << 24);
                    }
                }

                /*
                 * FIOQ index:
                 *
                 * (R ^ G ^ B ^ A) & 63
                 */
                index[
                    (
                        px ^
                        (px >> 8) ^
                        (px >> 16) ^
                        (px >> 24)
                    ) & 0x3F
                ] = px;
            }
        }

        raw[rawPos + 0] =
            (uint8_t)(px & 0xFF);

        raw[rawPos + 1] =
            (uint8_t)((px >> 8) & 0xFF);

        raw[rawPos + 2] =
            (uint8_t)((px >> 16) & 0xFF);

        raw[rawPos + 3] =
            (uint8_t)((px >> 24) & 0xFF);

        rawPos += 4;
    }

    *outW = width;
    *outH = height;

    return raw;
}

static uint8_t* decodeBz2Qoi(
    const uint8_t* blob,
    size_t blobSize,
    bool gm2022_5,
    int* outW,
    int* outH
) {
    size_t headerSize =
        gm2022_5
            ? COMPRESSED_QOI_HEADER_SIZE_NEW
            : COMPRESSED_QOI_HEADER_SIZE_OLD;

    if (headerSize > blobSize)
        return nullptr;

    int width =
        blob[4] |
        (blob[5] << 8);

    int height =
        blob[6] |
        (blob[7] << 8);

    if (width <= 0 || height <= 0)
        return nullptr;

    size_t uncompressedCapacity =
        QOI_HEADER_SIZE +
        (size_t)width *
        (size_t)height *
        5;

    uint8_t* uncompressed =
        (uint8_t*)malloc(uncompressedCapacity);

    if (!uncompressed)
        return nullptr;

    unsigned int destLen =
        (unsigned int)uncompressedCapacity;

    int rc =
        BZ2_bzBuffToBuffDecompress(
            (char*)uncompressed,
            &destLen,
            (char*)(blob + headerSize),
            (unsigned int)(blobSize - headerSize),
            0,
            0
        );

    if (rc != BZ_OK) {
        logWarn(
            "ImageDecoder: BZ2 decompress failed (rc=%d)\n",
            rc
        );

        free(uncompressed);
        return nullptr;
    }

    logInfo(
        "BZ2QOI: blob=%zu header=%zu decompressed=%u "
        "HEAD=%02X%02X%02X%02X%02X%02X%02X%02X\n",
        blobSize,
        headerSize,
        destLen,
        destLen > 0 ? uncompressed[0] : 0,
        destLen > 1 ? uncompressed[1] : 0,
        destLen > 2 ? uncompressed[2] : 0,
        destLen > 3 ? uncompressed[3] : 0,
        destLen > 4 ? uncompressed[4] : 0,
        destLen > 5 ? uncompressed[5] : 0,
        destLen > 6 ? uncompressed[6] : 0,
        destLen > 7 ? uncompressed[7] : 0
    );

    if (destLen >= QOI_HEADER_SIZE &&
        uncompressed[0] == 'f' &&
        uncompressed[1] == 'i' &&
        uncompressed[2] == 'o' &&
        uncompressed[3] == 'q') {

        size_t pos = QOI_HEADER_SIZE;
        size_t pixels = (size_t)width * (size_t)height;

        unsigned long indexCount = 0;
        unsigned long diffCount = 0;
        unsigned long lumaCount = 0;
        unsigned long runCount = 0;
        unsigned long rgbCount = 0;
        unsigned long rgbaCount = 0;

        while (pos < destLen && pixels > 0) {
            uint8_t b = uncompressed[pos++];

            if (b == 0xFE) {
                rgbCount++;
                pos += 3;
                pixels--;
            } else if (b == 0xFF) {
                rgbaCount++;
                pos += 4;
                pixels--;
            } else {
                switch (b >> 6) {
                    case 0:
                        indexCount++;
                        pixels--;
                        break;

                    case 1:
                        diffCount++;
                        pixels--;
                        break;

                    case 2:
                        lumaCount++;
                        pixels--;
                        break;

                    case 3:
                        runCount++;
                        break;
                }
            }

            if (pos > destLen)
                break;
        }

        logInfo(
            "FIOQSTAT: size=%zu expected=%zu end=%zu "
            "INDEX=%lu DIFF=%lu LUMA=%lu RUN=%lu "
            "RGB=%lu RGBA=%lu remaining=%zu\n",
            (size_t)destLen,
            (size_t)width * (size_t)height,
            pos,
            indexCount,
            diffCount,
            lumaCount,
            runCount,
            rgbCount,
            rgbaCount,
            pixels
        );
    }

    uint8_t* result =
        decodeQoi(
            uncompressed,
            destLen,
            outW,
            outH
        );

    free(uncompressed);

    return result;
}

uint8_t* ImageDecoder_decodeToRgba(
    const uint8_t* blob,
    size_t blobSize,
    bool gm2022_5,
    int* outW,
    int* outH
) {
    if (4 > blobSize || !blob)
        return nullptr;

    if (blob[0] == 'f' &&
        blob[1] == 'i' &&
        blob[2] == 'o' &&
        blob[3] == 'q') {

        return decodeQoi(
            blob,
            blobSize,
            outW,
            outH
        );
    }

    if (blob[0] == '2' &&
        blob[1] == 'z' &&
        blob[2] == 'o' &&
        blob[3] == 'q') {

        return decodeBz2Qoi(
            blob,
            blobSize,
            gm2022_5,
            outW,
            outH
        );
    }

    int w, h, channels;

    uint8_t* pixels =
        stbi_load_from_memory(
            blob,
            (int)blobSize,
            &w,
            &h,
            &channels,
            4
        );

    if (!pixels)
        return nullptr;

    *outW = w;
    *outH = h;

    return pixels;
}
