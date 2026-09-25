package xyz.theforks.ckshaderstudio.shader;

import com.jogamp.opengl.DefaultGLCapabilitiesChooser;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.util.GLBuffers;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.awt.AWTTextureIO;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.jogamp.opengl.GL.GL_ARRAY_BUFFER;
import static com.jogamp.opengl.GL.GL_FLOAT;
import static com.jogamp.opengl.GL.GL_POINTS;
import static com.jogamp.opengl.GL.GL_STATIC_DRAW;
import static com.jogamp.opengl.GL.GL_TEXTURE_2D;
import static com.jogamp.opengl.GL.GL_UNSIGNED_BYTE;
import static com.jogamp.opengl.GL2ES2.GL_COMPILE_STATUS;
import static com.jogamp.opengl.GL2ES2.GL_INFO_LOG_LENGTH;
import static com.jogamp.opengl.GL2ES2.GL_LINK_STATUS;
import static com.jogamp.opengl.GL2ES2.GL_VERTEX_SHADER;
import static com.jogamp.opengl.GL2ES3.GL_INTERLEAVED_ATTRIBS;
import static com.jogamp.opengl.GL2ES3.GL_R8;
import static com.jogamp.opengl.GL2ES3.GL_RASTERIZER_DISCARD;
import static com.jogamp.opengl.GL2ES3.GL_RED;
import static com.jogamp.opengl.GL2ES3.GL_STATIC_READ;
import static com.jogamp.opengl.GL2ES3.GL_TRANSFORM_FEEDBACK_BUFFER;

/**
 * Runs CkVShader-style shaders exactly the way the plugin does: an offscreen GL3 context, the
 * normalized LED positions fed as the "position" attribute, rasterization disabled, and "outColor"
 * captured with transform feedback.
 *
 * All GL work happens on one dedicated thread that keeps the context current.  Public methods are
 * safe to call from any thread; they block until the GL thread has done the work.
 */
public class GLRunner {

  /** A linked program and the uniform locations CkVShader would set. */
  static public class Program {
    final int id;
    final int fTimeLoc;
    final int textureLoc;
    final int audioLoc;
    final Map<String, Integer> paramLocs = new HashMap<>();

    Program(int id, int fTimeLoc, int textureLoc, int audioLoc) {
      this.id = id;
      this.fTimeLoc = fTimeLoc;
      this.textureLoc = textureLoc;
      this.audioLoc = audioLoc;
    }

    public boolean usesTexture() {
      return textureLoc >= 0;
    }

    public boolean usesAudio() {
      return audioLoc >= 0;
    }
  }

  /** Outcome of compiling a shader: a program, or the errors that prevented one. */
  static public class CompileResult {
    public final Program program;
    public final String errors;
    public final String warnings;
    public final String glInfo;

    CompileResult(Program program, String errors, String warnings, String glInfo) {
      this.program = program;
      this.errors = errors;
      this.warnings = warnings;
      this.glInfo = glInfo;
    }

    public boolean ok() {
      return program != null;
    }
  }

  static public final int AUDIO_TEX_WIDTH = 512;
  static public final int AUDIO_TEX_HEIGHT = 2;

  private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "GLRunner");
    t.setDaemon(true);
    return t;
  });

  private GLOffscreenAutoDrawable drawable;
  private GL3 gl;
  private String glInfo = "";
  private final int[] buffers = new int[2];
  private final int[] audioTex = new int[1];
  private Texture texture;
  private int pointCount = 0;
  private FloatBuffer vertexBuffer;
  private FloatBuffer tfbBuffer;
  private final byte[] audioBytes = new byte[AUDIO_TEX_WIDTH * AUDIO_TEX_HEIGHT];

  /** Creates the context.  Throws if OpenGL 3 is not available. */
  public void init() throws Exception {
    call(() -> {
      GLProfile glp = GLProfile.get(GLProfile.GL3);
      GLCapabilities caps = new GLCapabilities(glp);
      caps.setHardwareAccelerated(true);
      caps.setDoubleBuffered(false);
      caps.setAlphaBits(8);
      caps.setRedBits(8);
      caps.setGreenBits(8);
      caps.setBlueBits(8);
      caps.setOnscreen(false);
      GLDrawableFactory factory = GLDrawableFactory.getFactory(glp);
      drawable = factory.createOffscreenAutoDrawable(factory.getDefaultDevice(), caps,
        new DefaultGLCapabilitiesChooser(), 64, 64);
      drawable.display();
      GLContext ctx = drawable.getContext();
      if (ctx.makeCurrent() == GLContext.CONTEXT_NOT_CURRENT) {
        throw new IllegalStateException("Could not make the OpenGL context current");
      }
      gl = drawable.getGL().getGL3();
      glInfo = gl.glGetString(GL.GL_RENDERER) + " / OpenGL " + gl.glGetString(GL.GL_VERSION);
      gl.glGenBuffers(2, buffers, 0);
      gl.glGenTextures(1, audioTex, 0);
      gl.glBindTexture(GL_TEXTURE_2D, audioTex[0]);
      gl.glTexParameteri(GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
      gl.glTexParameteri(GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
      gl.glTexParameteri(GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_MIRRORED_REPEAT);
      gl.glTexParameteri(GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_MIRRORED_REPEAT);
      gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
      uploadAudio();
      return null;
    });
  }

  public String glInfo() {
    return glInfo;
  }

  /** Sets the LED positions, as normalized xn, yn, zn triples. */
  public void setPoints(float[] normalizedXyz) throws Exception {
    call(() -> {
      pointCount = normalizedXyz.length / 3;
      vertexBuffer = GLBuffers.newDirectFloatBuffer(normalizedXyz);
      tfbBuffer = GLBuffers.newDirectFloatBuffer(Math.max(3, normalizedXyz.length));
      return null;
    });
  }

  /**
   * Compiles and links an already include-expanded shader.  The returned program is not freed
   * automatically; pass it to {@link #delete} when replaced.
   */
  public CompileResult compile(ShaderSource.Expanded expanded) throws Exception {
    return call(() -> {
      int programId = gl.glCreateProgram();
      int shaderId = gl.glCreateShader(GL_VERTEX_SHADER);
      gl.glShaderSource(shaderId, 1, new String[] { expanded.source }, null);
      gl.glCompileShader(shaderId);
      if (getStatus(shaderId, GL_COMPILE_STATUS, true) != GL.GL_TRUE) {
        String log = infoLog(shaderId, true);
        gl.glDeleteShader(shaderId);
        gl.glDeleteProgram(programId);
        return new CompileResult(null, expanded.mapErrors(log.isEmpty() ? "Compile failed (no log)" : log), null, glInfo);
      }
      String warnings = infoLog(shaderId, true);
      gl.glAttachShader(programId, shaderId);
      gl.glTransformFeedbackVaryings(programId, 1, new String[] { "outColor" }, GL_INTERLEAVED_ATTRIBS);
      gl.glLinkProgram(programId);
      gl.glDeleteShader(shaderId);
      String linkLog = infoLog(programId, false);
      if (getStatus(programId, GL_LINK_STATUS, false) != GL.GL_TRUE) {
        gl.glDeleteProgram(programId);
        return new CompileResult(null, "Link failed: " + expanded.mapErrors(linkLog.isEmpty() ? "(no log)" : linkLog), null, glInfo);
      }
      if (!linkLog.isBlank()) warnings = (warnings + "\n" + linkLog).trim();
      Program p = new Program(programId,
        gl.glGetUniformLocation(programId, "fTime"),
        gl.glGetUniformLocation(programId, "textureSampler"),
        gl.glGetUniformLocation(programId, "audioTexture"));
      return new CompileResult(p, null, expanded.mapErrors(warnings.isBlank() ? null : warnings.trim()), glInfo);
    });
  }

  public void delete(Program p) {
    if (p == null) return;
    thread.submit(() -> gl.glDeleteProgram(p.id));
  }

  /** Loads a PNG as the textureSampler image, the same way CkVShaderTex does.  Null clears it. */
  public void setTexture(File png) throws Exception {
    BufferedImage img = png == null ? null : ImageIO.read(png);
    call(() -> {
      if (texture != null) {
        texture.destroy(gl);
        texture = null;
      }
      if (img != null) {
        texture = AWTTextureIO.newTexture(drawable.getGLProfile(), img, false);
        texture.bind(gl);
        gl.glTexParameteri(GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
        gl.glTexParameteri(GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
        gl.glTexParameteri(GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_MIRRORED_REPEAT);
        gl.glTexParameteri(GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_MIRRORED_REPEAT);
      }
      return null;
    });
  }

  /**
   * Fills the audio texture from 16 band levels (0..1), with the layout CkVShaderTex produces:
   * a 512x2 R8 texture where even texel i holds band (i % 16) and odd texels are zero.
   */
  public void setAudioBands(float[] bands16) {
    synchronized (audioBytes) {
      for (int i = 0; i < audioBytes.length; i += 2) {
        float v = Math.max(0f, Math.min(1f, bands16[i % 16]));
        audioBytes[i] = (byte) (int) (v * 255f);
        audioBytes[i + 1] = 0;
      }
    }
  }

  private void uploadAudio() {
    ByteBuffer buf;
    synchronized (audioBytes) {
      buf = ByteBuffer.wrap(audioBytes.clone());
    }
    gl.glBindTexture(GL_TEXTURE_2D, audioTex[0]);
    gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, AUDIO_TEX_WIDTH, AUDIO_TEX_HEIGHT, 0, GL_RED, GL_UNSIGNED_BYTE, buf);
  }

  /**
   * Runs the program for every point and returns r,g,b triples (one per point, in point order).
   * @param time value for fTime (already multiplied by speed, as in CkVShader)
   */
  public float[] run(Program p, float time, Map<String, Float> params) throws Exception {
    return call(() -> {
      float[] out = new float[pointCount * 3];
      if (p == null || pointCount == 0) return out;
      gl.glBindBuffer(GL_ARRAY_BUFFER, buffers[0]);
      gl.glBufferData(GL_ARRAY_BUFFER, (long) vertexBuffer.capacity() * Float.BYTES, vertexBuffer, GL_STATIC_DRAW);
      int inputAttrib = gl.glGetAttribLocation(p.id, "position");
      if (inputAttrib >= 0) {
        gl.glEnableVertexAttribArray(inputAttrib);
        gl.glVertexAttribPointer(inputAttrib, 3, GL_FLOAT, false, 0, 0);
      }
      tfbBuffer.clear();
      gl.glBindBuffer(GL_ARRAY_BUFFER, buffers[1]);
      gl.glBufferData(GL_ARRAY_BUFFER, (long) tfbBuffer.capacity() * Float.BYTES, null, GL_STATIC_READ);
      gl.glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, buffers[1]);

      gl.glEnable(GL_RASTERIZER_DISCARD);
      gl.glUseProgram(p.id);
      if (p.fTimeLoc >= 0) gl.glUniform1f(p.fTimeLoc, time);
      for (Map.Entry<String, Float> e : params.entrySet()) {
        Integer loc = p.paramLocs.computeIfAbsent(e.getKey(), k -> gl.glGetUniformLocation(p.id, k));
        if (loc >= 0) gl.glUniform1f(loc, e.getValue());
      }
      if (p.textureLoc >= 0) {
        gl.glActiveTexture(GL.GL_TEXTURE0);
        if (texture != null) texture.bind(gl); else gl.glBindTexture(GL_TEXTURE_2D, 0);
        gl.glUniform1i(p.textureLoc, 0);
      }
      if (p.audioLoc >= 0) {
        gl.glActiveTexture(GL.GL_TEXTURE1);
        uploadAudio();
        gl.glUniform1i(p.audioLoc, 1);
        gl.glActiveTexture(GL.GL_TEXTURE0);
      }
      gl.glBeginTransformFeedback(GL_POINTS);
      gl.glDrawArrays(GL_POINTS, 0, pointCount);
      gl.glEndTransformFeedback();
      gl.glFlush();
      gl.glGetBufferSubData(GL_TRANSFORM_FEEDBACK_BUFFER, 0, (long) out.length * Float.BYTES, tfbBuffer);
      gl.glUseProgram(0);
      gl.glDisable(GL_RASTERIZER_DISCARD);
      tfbBuffer.rewind();
      tfbBuffer.get(out, 0, out.length);
      return out;
    });
  }

  public void dispose() {
    thread.submit(() -> {
      if (drawable != null) {
        drawable.getContext().release();
        drawable.destroy();
      }
    });
    thread.shutdown();
  }

  private int getStatus(int id, int pname, boolean shader) {
    IntBuffer b = IntBuffer.allocate(1);
    if (shader) gl.glGetShaderiv(id, pname, b); else gl.glGetProgramiv(id, pname, b);
    return b.get(0);
  }

  private String infoLog(int id, boolean shader) {
    IntBuffer len = IntBuffer.allocate(1);
    if (shader) gl.glGetShaderiv(id, GL_INFO_LOG_LENGTH, len); else gl.glGetProgramiv(id, GL_INFO_LOG_LENGTH, len);
    int size = len.get(0);
    if (size <= 1) return "";
    ByteBuffer bytes = ByteBuffer.allocate(size);
    if (shader) gl.glGetShaderInfoLog(id, size, len, bytes); else gl.glGetProgramInfoLog(id, size, len, bytes);
    return new String(bytes.array(), 0, Math.max(0, Math.min(size, len.get(0)))).trim();
  }

  private <T> T call(Callable<T> c) throws Exception {
    Future<T> f = thread.submit(c);
    try {
      return f.get();
    } catch (java.util.concurrent.ExecutionException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw new RuntimeException(cause);
    }
  }
}
