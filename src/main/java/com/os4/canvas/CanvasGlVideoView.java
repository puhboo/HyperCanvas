package com.os4.canvas;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * An isolated GLES video surface.  SystemUI may keep its Vulkan renderer: only the Canvas
 * decoder output and its presentation pass live in this EGL context.
 */
final class CanvasGlVideoView extends GLSurfaceView implements GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {
    interface Callback { void ready(Surface surface); void gone(); }
    private static final float[] QUAD = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
    private static final float[] UV = {0f,1f, 1f,1f, 0f,0f, 1f,0f};
    private static final String VERTEX = "attribute vec4 a; attribute vec2 u; varying vec2 v; uniform float sx; uniform float sy; void main(){ gl_Position=vec4(a.x*sx,a.y*sy,0.,1.); v=u; }";
    private static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v; uniform samplerExternalOES t; void main(){ gl_FragColor=texture2D(t,v); }";
    private final Callback callback;
    private final FloatBuffer quad = buffer(QUAD), uv = buffer(UV);
    private SurfaceTexture texture;
    private Surface surface;
    private int program, oes, width, height;
    private volatile int videoW, videoH;
    private volatile boolean frame;

    CanvasGlVideoView(Context context, Callback callback) {
        super(context); this.callback = callback;
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        // This is a normal background SurfaceView, not a media overlay over keyguard cards.
        setZOrderMediaOverlay(false);
    }
    void setVideoSize(int w, int h) { videoW = w; videoH = h; requestRender(); }
    Surface videoSurface() { return surface; }
    void releaseVideoSurface() {
        queueEvent(() -> {
            if (surface != null) { surface.release(); surface = null; }
            if (texture != null) { texture.setOnFrameAvailableListener(null); texture.release(); texture = null; }
            if (oes != 0) { GLES20.glDeleteTextures(1, new int[]{oes}, 0); oes = 0; }
        });
    }
    @Override public void onSurfaceCreated(GL10 ignored, EGLConfig config) {
        program = program(VERTEX, FRAGMENT);
        int[] ids = new int[1]; GLES20.glGenTextures(1, ids, 0); oes = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(oes); texture.setOnFrameAvailableListener(this);
        surface = new Surface(texture);
        post(() -> callback.ready(surface));
    }
    @Override public void onSurfaceChanged(GL10 ignored, int w, int h) { width = w; height = h; GLES20.glViewport(0, 0, w, h); }
    @Override public void onDrawFrame(GL10 ignored) {
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (texture == null || program == 0) return;
        if (frame) { try { texture.updateTexImage(); } catch (RuntimeException ignored2) { return; } frame = false; }
        float sx = 1f, sy = 1f;
        if (videoW > 0 && videoH > 0 && width > 0 && height > 0) {
            float video = videoW / (float) videoH, view = width / (float) height;
            if (video > view) sx = video / view; else sy = view / video;
        }
        GLES20.glUseProgram(program);
        int pa = GLES20.glGetAttribLocation(program, "a"), pu = GLES20.glGetAttribLocation(program, "u");
        GLES20.glEnableVertexAttribArray(pa); GLES20.glVertexAttribPointer(pa, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(pu); GLES20.glVertexAttribPointer(pu, 2, GLES20.GL_FLOAT, false, 0, uv);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "sx"), sx);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "sy"), sy);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "t"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
    @Override public void onFrameAvailable(SurfaceTexture ignored) { frame = true; requestRender(); }
    @Override protected void onDetachedFromWindow() { callback.gone(); releaseVideoSurface(); super.onDetachedFromWindow(); }
    private static FloatBuffer buffer(float[] values) {
        FloatBuffer out = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        out.put(values); out.position(0); return out;
    }
    private static int program(String vertex, String fragment) {
        int vs = shader(GLES20.GL_VERTEX_SHADER, vertex), fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p); return p;
    }
    private static int shader(int kind, String source) { int s = GLES20.glCreateShader(kind); GLES20.glShaderSource(s, source); GLES20.glCompileShader(s); return s; }
}
