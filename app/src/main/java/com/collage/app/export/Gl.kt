package com.collage.app.export

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** EGL context bound to an encoder's input surface. Must be used from a single thread. */
class EglEncoderSurface(surface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "No EGL config" }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    fun swap(presentationTimeNs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeNs)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/** Draws textured quads in output pixel coordinates, clipped to a scissor rect. */
class QuadRenderer(private val outW: Int, private val outH: Int) {
    private val oesProgram = Program(FRAG_OES)
    private val texProgram = Program(FRAG_2D)
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Flips t so that bitmaps uploaded top-row-first appear upright. */
    val flipY = FloatArray(16).also {
        Matrix.setIdentityM(it, 0)
        Matrix.translateM(it, 0, 0f, 1f, 0f)
        Matrix.scaleM(it, 0, 1f, -1f, 1f)
    }

    fun clear() {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Draws [texId] into the pixel rect (l,t,r,b) (y down), clipped to the integer scissor rect
     * (sl,st,sr,sb) (y down).
     */
    fun draw(
        texId: Int, external: Boolean, texMatrix: FloatArray,
        l: Float, t: Float, r: Float, b: Float,
        sl: Int, st: Int, sr: Int, sb: Int,
    ) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(sl, outH - sb, sr - sl, sb - st)

        val x0 = l / outW * 2f - 1f
        val x1 = r / outW * 2f - 1f
        val y0 = 1f - b / outH * 2f // bottom
        val y1 = 1f - t / outH * 2f // top
        vertices.clear()
        vertices.put(
            floatArrayOf(
                x0, y0, 0f, 0f,
                x1, y0, 1f, 0f,
                x0, y1, 0f, 1f,
                x1, y1, 1f, 1f,
            ),
        )
        val p = if (external) oesProgram else texProgram
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(p.uTex, 0)
        GLES20.glUniformMatrix4fv(p.uTexMatrix, 1, false, texMatrix, 0)
        vertices.position(0)
        GLES20.glVertexAttribPointer(p.aPosition, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(p.aPosition)
        vertices.position(2)
        GLES20.glVertexAttribPointer(p.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(p.aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("draw")
    }

    fun release() {
        GLES20.glDeleteProgram(oesProgram.id)
        GLES20.glDeleteProgram(texProgram.id)
    }

    private class Program(fragment: String) {
        val id: Int = link(VERTEX, fragment)
        val aPosition = GLES20.glGetAttribLocation(id, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord")
        val uTexMatrix = GLES20.glGetUniformLocation(id, "uTexMatrix")
        val uTex = GLES20.glGetUniformLocation(id, "sTex")
    }

    companion object {
        private const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPosition;
                vTex = (uTexMatrix * aTexCoord).xy;
            }
        """
        private const val FRAG_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
        private const val FRAG_2D = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src.trimIndent())
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                error("Shader compile failed: $log")
            }
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "Program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
            return p
        }

        fun createTexture(external: Boolean): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            GLES20.glBindTexture(target, ids[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        fun checkGl(op: String) {
            val err = GLES20.glGetError()
            check(err == GLES20.GL_NO_ERROR) { "$op: glError 0x${Integer.toHexString(err)}" }
        }
    }
}
