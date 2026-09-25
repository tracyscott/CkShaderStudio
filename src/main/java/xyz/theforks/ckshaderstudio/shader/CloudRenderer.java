package xyz.theforks.ckshaderstudio.shader;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.GLBuffers;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static com.jogamp.opengl.GL.GL_ARRAY_BUFFER;
import static com.jogamp.opengl.GL.GL_BLEND;
import static com.jogamp.opengl.GL.GL_COLOR_BUFFER_BIT;
import static com.jogamp.opengl.GL.GL_DEPTH_BUFFER_BIT;
import static com.jogamp.opengl.GL.GL_DEPTH_TEST;
import static com.jogamp.opengl.GL.GL_FLOAT;
import static com.jogamp.opengl.GL.GL_FRAMEBUFFER;
import static com.jogamp.opengl.GL.GL_POINTS;
import static com.jogamp.opengl.GL.GL_STATIC_DRAW;

/**
 * Draws the LEDs as an OpenGL point cloud into an offscreen multisampled framebuffer and reads the
 * picture back for Swing to display.  Runs on the GLRunner thread in the same context as the
 * shader, so the transform-feedback buffer holding the shader's output is used directly as the
 * color attribute; colors never round-trip through the CPU.
 *
 * Rendering offscreen (rather than in a GLCanvas/GLJPanel) avoids AWT/OpenGL window integration,
 * which is the fragile part of JOGL on macOS.
 */
public class CloudRenderer {

  /** Everything needed to draw one frame. */
  static public class View {
    public int width, height;
    /** Column-major 4x4 matrix from model space to clip space; y is already flipped for readback. */
    public float[] mvp;
    public float pointSize;
    public float alphaThreshold;
    public float glow;
    public boolean hasColors;
  }

  static private final String VERT = """
    #version 330
    layout(location = 0) in vec3 pos;
    layout(location = 1) in vec3 col;
    uniform mat4 mvp;
    uniform float psize;
    uniform float alphaTh;
    uniform int useColor;
    uniform vec3 constColor;
    uniform float floorLevel;
    out vec3 vColor;
    out vec3 vLight;
    void main() {
      gl_Position = mvp * vec4(pos, 1.0);
      gl_PointSize = psize;
      vec3 c = useColor == 1 ? col : constColor;
      if (any(isnan(c)) || any(isinf(c))) {
        c = vec3(1.0, 0.0, 1.0);
      }
      c = clamp(c, 0.0, 1.0);
      // CkVShader: LEDs below the alpha threshold (by LX luminosity) fade to transparent.
      float lum = (3.0 * c.r + 4.0 * c.g + c.b) / 8.0;
      float a = (lum < alphaTh && alphaTh > 0.0) ? lum / alphaTh : 1.0;
      vLight = c * a;
      // Unlit LEDs stay faintly visible so the shape of the model reads.
      vColor = max(vLight, vec3(floorLevel, floorLevel, floorLevel * 1.15));
    }
    """;

  static private final String FRAG = """
    #version 330
    in vec3 vColor;
    in vec3 vLight;
    uniform int glowPass;
    uniform float glow;
    out vec4 fragColor;
    void main() {
      vec2 d = gl_PointCoord * 2.0 - 1.0;
      float r2 = dot(d, d);
      if (r2 > 1.0) discard;
      if (glowPass == 1) {
        float g = exp(-r2 * 3.5) * glow;
        fragColor = vec4(vLight * g, 1.0);
      } else {
        float edge = smoothstep(1.0, 0.55, r2);
        fragColor = vec4(vColor * (0.8 + 0.2 * edge), 1.0);
      }
    }
    """;

  static private final float[] BACKGROUND = { 0x0c / 255f, 0x0c / 255f, 0x10 / 255f };
  static private final int SAMPLES = 4;

  private final GL3 gl;
  private int program = 0;
  private int locMvp, locSize, locAlpha, locUseColor, locConst, locFloor, locGlowPass, locGlow;
  private final int[] vao = new int[2];
  private final int[] vbo = new int[2];
  private int pointCount = 0, otherCount = 0;

  // Framebuffers: multisampled render target, and a resolve target to read back from.
  private final int[] fbo = new int[2];
  private final int[] rbo = new int[3];
  private int fbWidth = 0, fbHeight = 0;
  private IntBuffer pixels;
  private final BufferedImage[] images = new BufferedImage[3];
  private int nextImage = 0;

  CloudRenderer(GL3 gl) {
    this.gl = gl;
  }

  void init() {
    program = GLRunner.buildProgram(gl, VERT, FRAG);
    locMvp = gl.glGetUniformLocation(program, "mvp");
    locSize = gl.glGetUniformLocation(program, "psize");
    locAlpha = gl.glGetUniformLocation(program, "alphaTh");
    locUseColor = gl.glGetUniformLocation(program, "useColor");
    locConst = gl.glGetUniformLocation(program, "constColor");
    locFloor = gl.glGetUniformLocation(program, "floorLevel");
    locGlowPass = gl.glGetUniformLocation(program, "glowPass");
    locGlow = gl.glGetUniformLocation(program, "glow");
    gl.glGenVertexArrays(2, vao, 0);
    gl.glGenBuffers(2, vbo, 0);
  }

  /** Display positions of the view's LEDs and of the dimmed context points around them. */
  void setPositions(float[] xyz, float[] otherXyz) {
    pointCount = xyz.length / 3;
    otherCount = otherXyz.length / 3;
    upload(vbo[0], xyz);
    upload(vbo[1], otherXyz);
  }

  private void upload(int buffer, float[] data) {
    FloatBuffer fb = GLBuffers.newDirectFloatBuffer(data.length == 0 ? new float[3] : data);
    gl.glBindBuffer(GL_ARRAY_BUFFER, buffer);
    gl.glBufferData(GL_ARRAY_BUFFER, (long) fb.capacity() * Float.BYTES, fb, GL_STATIC_DRAW);
    gl.glBindBuffer(GL_ARRAY_BUFFER, 0);
  }

  /**
   * Renders the cloud and returns it as an image.
   * @param colorBuffer the transform-feedback buffer holding r,g,b per LED
   */
  BufferedImage render(View v, int colorBuffer, int colorCount) {
    ensureFramebuffers(v.width, v.height);
    gl.glBindFramebuffer(GL_FRAMEBUFFER, fbo[0]);
    gl.glViewport(0, 0, v.width, v.height);
    gl.glDisable(GL3.GL_RASTERIZER_DISCARD);
    gl.glClearColor(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], 1f);
    gl.glClearDepth(1.0);
    gl.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    gl.glEnable(GL3.GL_PROGRAM_POINT_SIZE);
    gl.glUseProgram(program);
    gl.glUniformMatrix4fv(locMvp, 1, false, v.mvp, 0);
    gl.glUniform1f(locAlpha, v.alphaThreshold);
    gl.glUniform1f(locGlow, v.glow);

    // Context points outside the view: small, dim, no color.
    if (otherCount > 0) {
      bindPositions(vao[1], vbo[1], 0, 0);
      gl.glUniform1i(locUseColor, 0);
      gl.glUniform3f(locConst, 0f, 0f, 0f);
      gl.glUniform1f(locFloor, 0.16f);
      gl.glUniform1f(locSize, Math.max(1f, v.pointSize * 0.5f));
      gl.glUniform1i(locGlowPass, 0);
      gl.glEnable(GL_DEPTH_TEST);
      gl.glDrawArrays(GL_POINTS, 0, otherCount);
    }

    boolean colors = v.hasColors && colorCount >= pointCount && pointCount > 0;
    bindPositions(vao[0], vbo[0], colors ? colorBuffer : 0, pointCount);
    gl.glUniform1i(locUseColor, colors ? 1 : 0);
    gl.glUniform3f(locConst, 0f, 0f, 0f);
    gl.glUniform1f(locFloor, 0.11f);

    // Pass 1: solid LED discs with depth.
    gl.glEnable(GL_DEPTH_TEST);
    gl.glDepthMask(true);
    gl.glDisable(GL_BLEND);
    gl.glUniform1f(locSize, v.pointSize);
    gl.glUniform1i(locGlowPass, 0);
    gl.glDrawArrays(GL_POINTS, 0, pointCount);

    // Pass 2: additive halo, like light blooming around a real LED.
    if (v.glow > 0f && colors) {
      gl.glDepthMask(false);
      gl.glEnable(GL_BLEND);
      gl.glBlendFunc(GL.GL_ONE, GL.GL_ONE);
      gl.glUniform1f(locSize, v.pointSize * 3.2f);
      gl.glUniform1i(locGlowPass, 1);
      gl.glDrawArrays(GL_POINTS, 0, pointCount);
      gl.glDepthMask(true);
      gl.glDisable(GL_BLEND);
    }
    gl.glDisable(GL_DEPTH_TEST);
    gl.glBindVertexArray(0);
    gl.glUseProgram(0);

    // Resolve the multisampled image and read it back.
    gl.glBindFramebuffer(GL3.GL_READ_FRAMEBUFFER, fbo[0]);
    gl.glBindFramebuffer(GL3.GL_DRAW_FRAMEBUFFER, fbo[1]);
    gl.glBlitFramebuffer(0, 0, v.width, v.height, 0, 0, v.width, v.height, GL_COLOR_BUFFER_BIT, GL.GL_NEAREST);
    gl.glBindFramebuffer(GL3.GL_READ_FRAMEBUFFER, fbo[1]);
    pixels.clear();
    gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 4);
    gl.glReadPixels(0, 0, v.width, v.height, GL.GL_BGRA, GL3.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
    gl.glBindFramebuffer(GL_FRAMEBUFFER, 0);

    // Reuse a small ring of images so a frame being displayed is never overwritten.
    BufferedImage img = images[nextImage];
    if (img == null || img.getWidth() != v.width || img.getHeight() != v.height) {
      img = new BufferedImage(v.width, v.height, BufferedImage.TYPE_INT_RGB);
      images[nextImage] = img;
    }
    nextImage = (nextImage + 1) % images.length;
    int[] dst = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    pixels.rewind();
    pixels.get(dst, 0, Math.min(dst.length, pixels.remaining()));
    return img;
  }

  private void bindPositions(int vaoId, int posBuffer, int colorBuffer, int count) {
    gl.glBindVertexArray(vaoId);
    gl.glBindBuffer(GL_ARRAY_BUFFER, posBuffer);
    gl.glEnableVertexAttribArray(0);
    gl.glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
    if (colorBuffer != 0) {
      gl.glBindBuffer(GL_ARRAY_BUFFER, colorBuffer);
      gl.glEnableVertexAttribArray(1);
      gl.glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0);
    } else {
      gl.glDisableVertexAttribArray(1);
      gl.glVertexAttrib3f(1, 0f, 0f, 0f);
    }
    gl.glBindBuffer(GL_ARRAY_BUFFER, 0);
  }

  private void ensureFramebuffers(int w, int h) {
    if (w == fbWidth && h == fbHeight && fbo[0] != 0) return;
    if (fbo[0] != 0) {
      gl.glDeleteFramebuffers(2, fbo, 0);
      gl.glDeleteRenderbuffers(3, rbo, 0);
    }
    gl.glGenFramebuffers(2, fbo, 0);
    gl.glGenRenderbuffers(3, rbo, 0);
    int samples = Math.min(SAMPLES, maxSamples());

    gl.glBindRenderbuffer(GL.GL_RENDERBUFFER, rbo[0]);
    gl.glRenderbufferStorageMultisample(GL.GL_RENDERBUFFER, samples, GL.GL_RGBA8, w, h);
    gl.glBindRenderbuffer(GL.GL_RENDERBUFFER, rbo[1]);
    gl.glRenderbufferStorageMultisample(GL.GL_RENDERBUFFER, samples, GL.GL_DEPTH_COMPONENT24, w, h);
    gl.glBindFramebuffer(GL_FRAMEBUFFER, fbo[0]);
    gl.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_RENDERBUFFER, rbo[0]);
    gl.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, rbo[1]);
    check("multisample");

    gl.glBindRenderbuffer(GL.GL_RENDERBUFFER, rbo[2]);
    gl.glRenderbufferStorage(GL.GL_RENDERBUFFER, GL.GL_RGBA8, w, h);
    gl.glBindFramebuffer(GL_FRAMEBUFFER, fbo[1]);
    gl.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_RENDERBUFFER, rbo[2]);
    check("resolve");
    gl.glBindFramebuffer(GL_FRAMEBUFFER, 0);
    gl.glBindRenderbuffer(GL.GL_RENDERBUFFER, 0);

    fbWidth = w;
    fbHeight = h;
    pixels = ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
  }

  private int maxSamples() {
    int[] v = new int[1];
    gl.glGetIntegerv(GL3.GL_MAX_SAMPLES, v, 0);
    return Math.max(1, v[0]);
  }

  private void check(String what) {
    int status = gl.glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL.GL_FRAMEBUFFER_COMPLETE) {
      gl.glBindFramebuffer(GL_FRAMEBUFFER, 0);
      throw new IllegalStateException("Preview framebuffer (" + what + ") incomplete: 0x" + Integer.toHexString(status));
    }
  }
}
