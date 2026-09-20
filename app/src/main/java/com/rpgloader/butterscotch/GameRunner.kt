package com.rpgloader.butterscotch

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

class GameRunner(
    private val dataWinPath: String,
    private val savesPath: String,
    private val osType: Int = 0
) {
    companion object {
        private const val TAG = "RPGLoaderRunner"
    }

    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "RPGLoader-Game")
    }

    private var future: Future<*>? = null
    private val stopping = AtomicBoolean(false)

    private data class KeyEvent(
        val keyCode: Int,
        val down: Boolean
    )

    private val inputQueue = ConcurrentLinkedQueue<KeyEvent>()


    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    private var fbo = 0
    private var texture = 0
    private var fboWidth = 0
    private var fboHeight = 0

    private var program = 0
    private var vao = 0

    fun start(surface: Surface) {
        Log.d("RPGLoader", "GameRunner.start()");
        if (future != null) return

        stopping.set(false)

        future = executor.submit {
            try {
                run(surface)
            } catch (e: Throwable) {
                Log.e(TAG, "Runner crashed", e)
            } finally {
                cleanup()
            }
        }
    }

    fun keyDown(keyCode: Int) {
        if (!stopping.get()) {
            inputQueue.offer(KeyEvent(keyCode, true))
        }
    }

    fun keyUp(keyCode: Int) {
        if (!stopping.get()) {
            inputQueue.offer(KeyEvent(keyCode, false))
        }
    }

    private fun drainInput() {
        while (true) {
            val event = inputQueue.poll() ?: break

            try {
                Log.d(
                    "RPGLoaderInput",
                    "NATIVE ${if (event.down) "DOWN" else "UP"} key=${event.keyCode}"
                )

                if (event.down) {
                    ButterscotchNative.onKeyDown(event.keyCode)
                } else {
                    ButterscotchNative.onKeyUp(event.keyCode)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "input failed", e)
            }
        }
    }

    fun stop() {
        stopping.set(true)

        try {
            future?.get()
        } catch (_: Throwable) {
        }

        future = null
    }

    private fun run(surface: Surface) {
        Log.d("RPGLoader", "GameRunner thread started");
        if (!initEgl(surface)) {
            Log.e(TAG, "EGL initialization failed")
            return
        }

        createBlitResources()

        val initialWidth = eglWidth()
        val initialHeight = eglHeight()

        if (initialWidth <= 0 || initialHeight <= 0) {
            Log.e(TAG, "Invalid EGL surface size: ${initialWidth}x${initialHeight}")
            return
        }

        ensureFramebuffer(initialWidth, initialHeight)

        Log.i(
            TAG,
            "Game framebuffer: ${initialWidth}x${initialHeight}, FBO=$fbo, texture=$texture"
        )

        ButterscotchNative.resetExitLatch()

        Log.i(TAG, "Starting native GameMaker runner")

        Log.d("RPGLoader", "Calling native startRunner")

        val started = ButterscotchNative.startRunner(
            dataWinPath,
            savesPath,
            osType,
            fbo
        )

        Log.d("RPGLoader", "native startRunner returned: $started")

        if (!started) {
            Log.e(TAG, "Butterscotch startRunner() failed")
            return
        }

        Log.i(TAG, "GameMaker runner started")

        ButterscotchNative.resumeAudio()

        var lastNs = System.nanoTime()
        var frameCount = 0

        while (!stopping.get()) {
            val nowNs = System.nanoTime()
            val delta =
                ((nowNs - lastNs) / 1_000_000_000.0f)
                    .coerceAtMost(0.25f)

            lastNs = nowNs

            val width = eglWidth()
            val height = eglHeight()

            if (width <= 0 || height <= 0) {
                Thread.sleep(10)
                continue
            }

            ensureFramebuffer(width, height)

            ButterscotchNative.beginFrame()

            drainInput()

            val status = ButterscotchNative.stepAndDraw(
                width,
                height,
                delta
            )

            
            if (frameCount <= 5) {
                val pixel = java.nio.ByteBuffer.allocateDirect(4)

                GLES30.glBindFramebuffer(
                    GLES30.GL_FRAMEBUFFER,
                    fbo
                )

                GLES30.glReadPixels(
                    width / 2,
                    height / 2,
                    1,
                    1,
                    GLES30.GL_RGBA,
                    GLES30.GL_UNSIGNED_BYTE,
                    pixel
                )

                pixel.rewind()

                val r = pixel.get().toInt() and 0xff
                val g = pixel.get().toInt() and 0xff
                val b = pixel.get().toInt() and 0xff
                val a = pixel.get().toInt() and 0xff

                Log.d(
                    TAG,
                    "FBO PIXEL frame=$frameCount rgba=($r,$g,$b,$a)"
                )

                GLES30.glBindFramebuffer(
                    GLES30.GL_FRAMEBUFFER,
                    0
                )
            }

frameCount++

            if (frameCount <= 10 || frameCount % 60 == 0) {
                Log.d(
                    TAG,
                    "FRAME #$frameCount status=$status size=${width}x${height} fbo=$fbo texture=$texture"
                )
            }

            when (status) {
                ButterscotchNative.BUTTERSCOTCH_DROID_CONTINUE -> {
                    blitToScreen(width, height)

                    ButterscotchNative.resumeAudio()

                    paceFrame(
                        ButterscotchNative.getTargetFrameHz(),
                        lastNs
                    )
                }

                ButterscotchNative.BUTTERSCOTCH_DROID_CONTINUE_NO_SWAP -> {
                    // Native runner intentionally doesn't request a present.
                }

                ButterscotchNative.BUTTERSCOTCH_DROID_SHOULD_EXIT -> {
                    Log.i(TAG, "Game requested exit")
                    break
                }

                else -> {
                    Log.e(TAG, "Unknown runner status: $status")
                    break
                }
            }
        }

        ButterscotchNative.suspendAudio()
        ButterscotchNative.stopRunner()
        ButterscotchNative.markExited()
    }

    private fun paceFrame(hz: Int, frameStartNs: Long) {
        if (hz <= 0) return

        val targetNs = 1_000_000_000L / hz
        val deadline = frameStartNs + targetNs

        val remaining = deadline - System.nanoTime()

        if (remaining > 2_000_000L) {
            try {
                Thread.sleep(
                    (remaining - 1_000_000L) / 1_000_000L
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        while (
            !stopping.get() &&
            System.nanoTime() < deadline
        ) {
            Thread.yield()
        }
    }

    private fun initEgl(surface: Surface): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)

        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return false
        }

        val version = IntArray(2)

        check(
            EGL14.eglInitialize(display, version, 0, version, 1)
        ) {
            "eglInitialize failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        Log.i(TAG, "EGL ${version[0]}.${version[1]}")

        val configAttribs = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR

            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,

            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,

            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(32)
        val count = IntArray(1)

        check(
            EGL14.eglChooseConfig(
                display,
                configAttribs,
                0,
                configs,
                0,
                configs.size,
                count,
                0
            )
        ) {
            "eglChooseConfig failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        check(count[0] > 0) {
            "No suitable EGLConfig"
        }

        config = configs[0]

        val visualId = IntArray(1)
        EGL14.eglGetConfigAttrib(
            display,
            config,
            EGL14.EGL_NATIVE_VISUAL_ID,
            visualId,
            0
        )

        Log.i(
            TAG,
            "EGL config count=${count[0]} nativeVisualId=0x${Integer.toHexString(visualId[0])}"
        )

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )

        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            contextAttribs,
            0
        )

        check(context != EGL14.EGL_NO_CONTEXT) {
            "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        /*
         * Let EGL choose the native window buffer format from the Surface.
         * Do not force a separate visual format through SurfaceView.
         */
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_NONE
        )

        eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            surfaceAttribs,
            0
        )

        check(eglSurface != EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        check(
            EGL14.eglMakeCurrent(
                display,
                eglSurface,
                eglSurface,
                context
            )
        ) {
            "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        Log.i(TAG, "EGL window surface created")

        Log.i(
            TAG,
            "GL_VENDOR=${GLES20.glGetString(GLES20.GL_VENDOR)}"
        )
        Log.i(
            TAG,
            "GL_RENDERER=${GLES20.glGetString(GLES20.GL_RENDERER)}"
        )
        Log.i(
            TAG,
            "GL_VERSION=${GLES20.glGetString(GLES20.GL_VERSION)}"
        )

        return true
    }

    private fun eglWidth(): Int {
        val value = IntArray(1)

        EGL14.eglQuerySurface(
            display,
            eglSurface,
            EGL14.EGL_WIDTH,
            value,
            0
        )

        return value[0]
    }

    private fun eglHeight(): Int {
        val value = IntArray(1)

        EGL14.eglQuerySurface(
            display,
            eglSurface,
            EGL14.EGL_HEIGHT,
            value,
            0
        )

        return value[0]
    }

    private fun ensureFramebuffer(
        width: Int,
        height: Int
    ) {
        if (
            fbo != 0 &&
            texture != 0 &&
            fboWidth == width &&
            fboHeight == height
        ) {
            return
        }

        if (texture == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]

            GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                texture
            )

            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )

            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )

            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )

            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
        }

        if (fbo == 0) {
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            fbo = ids[0]

            GLES20.glBindFramebuffer(
                GLES20.GL_FRAMEBUFFER,
                fbo
            )

            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                texture,
                0
            )
        }

        GLES20.glBindTexture(
            GLES20.GL_TEXTURE_2D,
            texture
        )

        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )

        fboWidth = width
        fboHeight = height

        GLES20.glBindFramebuffer(
            GLES20.GL_FRAMEBUFFER,
            fbo
        )

        val status =
            GLES20.glCheckFramebufferStatus(
                GLES20.GL_FRAMEBUFFER
            )

        check(
            status ==
                GLES20.GL_FRAMEBUFFER_COMPLETE
        ) {
            "Game FBO incomplete: $status"
        }
    }

    private fun createBlitResources() {
        val vertexShader = compileShader(
            GLES30.GL_VERTEX_SHADER,
            """
                #version 300 es

                out vec2 vTexCoord;

                void main() {
                    vec2 pos;

                    if (gl_VertexID == 0) {
                        pos = vec2(-1.0, -1.0);
                    } else if (gl_VertexID == 1) {
                        pos = vec2(3.0, -1.0);
                    } else {
                        pos = vec2(-1.0, 3.0);
                    }

                    gl_Position = vec4(pos, 0.0, 1.0);
                    vTexCoord = pos * 0.5 + 0.5;
                }
            """.trimIndent()
        )

        val fragmentShader = compileShader(
            GLES30.GL_FRAGMENT_SHADER,
            """
                #version 300 es

                precision mediump float;

                uniform sampler2D uTexture;
                in vec2 vTexCoord;
                out vec4 fragColor;

                void main() {
                    fragColor = texture(uTexture, vTexCoord);
                }
            """.trimIndent()
        )

        program = GLES30.glCreateProgram()

        GLES30.glAttachShader(
            program,
            vertexShader
        )

        GLES30.glAttachShader(
            program,
            fragmentShader
        )

        GLES30.glLinkProgram(program)

        val status = IntArray(1)

        GLES30.glGetProgramiv(
            program,
            GLES30.GL_LINK_STATUS,
            status,
            0
        )

        check(status[0] != 0) {
            GLES30.glGetProgramInfoLog(program)
        }

        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        val vaos = IntArray(1)

        GLES30.glGenVertexArrays(
            1,
            vaos,
            0
        )

        vao = vaos[0]
    }

    private fun compileShader(
        type: Int,
        source: String
    ): Int {
        val shader =
            GLES30.glCreateShader(type)

        GLES30.glShaderSource(
            shader,
            source
        )

        GLES30.glCompileShader(shader)

        val status = IntArray(1)

        GLES30.glGetShaderiv(
            shader,
            GLES30.GL_COMPILE_STATUS,
            status,
            0
        )

        check(status[0] != 0) {
            GLES30.glGetShaderInfoLog(shader)
        }

        return shader
    }

    private fun blitToScreen(
        width: Int,
        height: Int
    ) {
        GLES30.glBindFramebuffer(
            GLES30.GL_FRAMEBUFFER,
            0
        )

        GLES30.glViewport(
            0,
            0,
            width,
            height
        )

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glClearColor(
            0f,
            0f,
            0f,
            1f
        )

        GLES30.glClear(
            GLES30.GL_COLOR_BUFFER_BIT
        )

        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(
            GLES30.GL_TEXTURE0
        )

        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_2D,
            texture
        )

        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR
        )

        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR
        )

        val location =
            GLES30.glGetUniformLocation(
                program,
                "uTexture"
            )

        GLES30.glUniform1i(
            location,
            0
        )

        GLES30.glBindVertexArray(vao)

        GLES30.glDrawArrays(
            GLES30.GL_TRIANGLES,
            0,
            3
        )

        GLES30.glBindVertexArray(0)

        val glError = GLES30.glGetError()

        if (glError != GLES30.GL_NO_ERROR) {
            Log.e(
                TAG,
                "GL error after blit: 0x${Integer.toHexString(glError)}"
            )
        }

        val swapped =
            EGL14.eglSwapBuffers(
                display,
                eglSurface
            )

        if (!swapped) {
            val eglError = EGL14.eglGetError()

            Log.e(
                TAG,
                "eglSwapBuffers FAILED: 0x${Integer.toHexString(eglError)}"
            )
        }
    }

    private fun cleanup() {
        try {
            if (context != EGL14.EGL_NO_CONTEXT) {
                try {
                    ButterscotchNative.suspendAudio()
                } catch (_: Throwable) {
                }

                try {
                    ButterscotchNative.stopRunner()
                } catch (_: Throwable) {
                }
            }

            if (program != 0) {
                GLES30.glDeleteProgram(program)
                program = 0
            }

            if (vao != 0) {
                GLES30.glDeleteVertexArrays(
                    1,
                    intArrayOf(vao),
                    0
                )
                vao = 0
            }

            if (texture != 0) {
                GLES30.glDeleteTextures(
                    1,
                    intArrayOf(texture),
                    0
                )
                texture = 0
            }

            if (fbo != 0) {
                GLES30.glDeleteFramebuffers(
                    1,
                    intArrayOf(fbo),
                    0
                )
                fbo = 0
            }

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )

                EGL14.eglDestroySurface(
                    display,
                    eglSurface
                )

                eglSurface =
                    EGL14.EGL_NO_SURFACE
            }

            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(
                    display,
                    context
                )

                context =
                    EGL14.EGL_NO_CONTEXT
            }

            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglTerminate(display)
                display =
                    EGL14.EGL_NO_DISPLAY
            }
        } catch (e: Throwable) {
            Log.e(
                TAG,
                "EGL cleanup failed",
                e
            )
        }
    }
}
