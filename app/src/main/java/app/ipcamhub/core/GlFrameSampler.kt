package app.ipcamhub.core

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Takes a few tiny brightness samples per second from any video producer (a decoder, the UVC
 * renderer). The frame goes through the GPU, which understands every vendor-specific pixel
 * format. Reading the decoder output on the CPU (ImageReader) crashes on some phones.
 */
class GlFrameSampler(
    private val intervalMs: Long = 400L,
    private val onSample: (IntArray) -> Unit,
) {
    private val thread = HandlerThread("gl-sampler").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var texture: SurfaceTexture? = null
    private var producerSurface: Surface? = null
    private var lastSampleMs = 0L
    private var released = false

    private val matrix = FloatArray(16)
    private val pixels: ByteBuffer =
        ByteBuffer.allocateDirect(MotionMath.GRID_COLS * MotionMath.GRID_ROWS * 4).order(ByteOrder.nativeOrder())
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD).position(0) }

    /** The Surface the video is drawn into, once [start] has run. */
    val surface: Surface? get() = producerSurface

    /** Creates the GPU side and returns the Surface the video should be drawn into. */
    fun start(): Surface {
        val ready = CountDownLatch(1)
        handler.post {
            try {
                setUp()
            } catch (e: Exception) {
                Log.e(TAG, "GL setup failed", e)
            }
            ready.countDown()
        }
        ready.await()
        return producerSurface ?: throw IllegalStateException("GL sampler could not start")
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, MotionMath.GRID_COLS, EGL14.EGL_HEIGHT, MotionMath.GRID_ROWS, EGL14.EGL_NONE), 0
        )
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent" }

        program = buildProgram()
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener({ onFrame() }, handler)
        texture = st
        producerSurface = Surface(st)
    }

    private fun onFrame() {
        val st = texture ?: return
        if (released) return
        try {
            st.updateTexImage() // always latch the newest frame so the producer is never blocked
        } catch (e: Exception) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleMs < intervalMs) return
        lastSampleMs = now
        try {
            st.getTransformMatrix(matrix)
            GLES20.glViewport(0, 0, MotionMath.GRID_COLS, MotionMath.GRID_ROWS)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMatrix"), 1, false, matrix, 0)
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            val tex = GLES20.glGetAttribLocation(program, "aTex")
            quad.position(0)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(pos)
            quad.position(2)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(tex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            pixels.position(0)
            GLES20.glReadPixels(0, 0, MotionMath.GRID_COLS, MotionMath.GRID_ROWS, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val luma = IntArray(MotionMath.GRID_COLS * MotionMath.GRID_ROWS)
            for (i in luma.indices) {
                val r = pixels.get(i * 4).toInt() and 0xFF
                val g = pixels.get(i * 4 + 1).toInt() and 0xFF
                val b = pixels.get(i * 4 + 2).toInt() and 0xFF
                luma[i] = (r * 299 + g * 587 + b * 114) / 1000
            }
            onSample(luma)
        } catch (e: Exception) {
            Log.w(TAG, "sampling failed", e)
        }
    }

    fun release() {
        handler.post {
            released = true
            producerSurface?.release()
            texture?.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
        }
    }

    private fun buildProgram(): Int {
        val vertex = compile(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uMatrix; varying vec2 vTex;" +
                "void main(){ gl_Position = aPos; vTex = (uMatrix * aTex).xy; }"
        )
        val fragment = compile(
            GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;" +
                "void main(){ gl_FragColor = texture2D(sTex, vTex); }"
        )
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vertex)
        GLES20.glAttachShader(p, fragment)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    private companion object {
        const val TAG = "GlFrameSampler"

        // x, y, u, v for a full-screen strip
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
    }
}
