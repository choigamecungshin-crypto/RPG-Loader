package com.rpgloader.butterscotch

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileInputStream

object ButterscotchNative {

    const val BUTTERSCOTCH_DROID_CONTINUE = 0
    const val BUTTERSCOTCH_DROID_SHOULD_EXIT = 1
    const val BUTTERSCOTCH_DROID_CONTINUE_NO_SWAP = 2

    val stdioListener = mutableListOf<(String) -> Unit>()

    init {
        System.loadLibrary("butterscotch")
        redirectStdioToLogcat()
        init()
    }

    external fun init()

    fun registerStdioListener(
        callback: (String) -> Unit
    ): (String) -> Unit {
        stdioListener.add(callback)
        return callback
    }

    fun unregisterStdioListener(
        callback: (String) -> Unit
    ) {
        stdioListener.remove(callback)
    }

    private fun redirectStdioToLogcat() {
        try {
            val pipe = Os.pipe()

            Os.dup2(
                pipe[1],
                OsConstants.STDOUT_FILENO
            )

            Os.dup2(
                pipe[1],
                OsConstants.STDERR_FILENO
            )

            Thread {
                FileInputStream(pipe[0])
                    .bufferedReader()
                    .forEachLine {
                        Log.i(
                            "Butterscotch",
                            it
                        )

                        for (
                            listener in
                            stdioListener.toList()
                        ) {
                            listener.invoke(it)
                        }
                    }
            }.apply {
                name = "ButterscotchLogPump"
                isDaemon = true
                start()
            }

        } catch (e: ErrnoException) {
            Log.w(
                "Butterscotch",
                "Could not redirect stdio to logcat",
                e
            )
        }
    }

    // Data.win

    @JvmStatic
    external fun dataWinParseLight(
        wadPath: String
    ): Long

    @JvmStatic
    external fun dataWinThumbnailPng(
        handle: Long
    ): ByteArray?

    @JvmStatic
    external fun dataWinFree(
        handle: Long
    )

    @JvmStatic
    external fun dataWinName(
        handle: Long
    ): String?

    @JvmStatic
    external fun dataWinDisplayName(
        handle: Long
    ): String?

    external fun dataWinWadVersion(
        handle: Long
    ): Int

    external fun dataWinGmsVersion(
        handle: Long
    ): String

    external fun dataWinDetectedGmsVersion(
        handle: Long
    ): String

    external fun getRunningDataWinHandle(): Long

    external fun getRunnerFrameCount(): Long

    external fun getProfilerStartedAtFrame(): Long

    external fun isProfilerEnabled(): Boolean

    external fun setProfilerEnabled(
        enabled: Boolean
    )

    external fun getProfilerEntriesCount(): Long

    external fun getProfilerEntryKey(
        index: Long
    ): String

    external fun getProfilerEntryNanos(
        index: Long
    ): Long

    external fun getProfilerEntryOps(
        index: Long
    ): Long

    // Runner

    external fun startRunner(
        dataWinPath: String,
        savesPath: String,
        osType: Int,
        hostFramebuffer: Int
    ): Boolean

    external fun beginFrame()

    external fun onKeyDown(
        keyCode: Int
    )

    external fun onKeyUp(
        keyCode: Int
    )

    external fun onCharacter(
        codePoint: Int
    )

    external fun gamepadConnected(
        device: Int,
        name: String?
    )

    external fun gamepadDisconnected(
        device: Int
    )

    external fun gamepadButton(
        device: Int,
        button: Int,
        isDown: Boolean
    )

    external fun gamepadAxis(
        device: Int,
        axis: Int,
        value: Float
    )

    external fun stepAndDraw(
        winW: Int,
        winH: Int,
        deltaTimeSeconds: Float
    ): Int

    external fun getTargetFrameHz(): Int

    external fun stopRunner()

    external fun suspendAudio()

    external fun resumeAudio()

    external fun getRoomCount(): Int

    external fun getRoomName(
        roomIndex: Int
    ): String

    external fun gotoRoom(
        roomIndex: Int
    )

    external fun setWidescreenHackAspectRatio(
        aspectRatio: Float
    )

    external fun setNormalizedCursorPosition(
        x: Float,
        y: Float
    )

    external fun setMouseButtonState(
        button: Int,
        down: Boolean
    )

    external fun setFreeCamera(
        panX: Float,
        panY: Float,
        zoom: Float
    )

    // Native -> Kotlin callbacks

    var currentTitle: String? = null
        private set

    @JvmStatic
    fun onTitleChanged(
        title: String
    ) {
        currentTitle = title
    }

    var currentGameWidth: Int = 0
        private set

    var currentGameHeight: Int = 0
        private set

    @JvmStatic
    fun onGameSizeChanged(
        width: Int,
        height: Int
    ) {
        currentGameWidth = width
        currentGameHeight = height
    }

    var hasExited: Boolean = false
        private set

    internal fun markExited() {
        hasExited = true
    }

    fun resetExitLatch() {
        hasExited = false
    }
}
