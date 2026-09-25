package xyz.theforks.ckshaderstudio.ui;

import heronarts.lx.color.LXColor;
import xyz.theforks.ckshaderstudio.model.ModelLoader;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;

/**
 * 3D point preview of the LEDs, colored by the shader output.  Drag to orbit, shift-drag (or
 * right-drag) to pan, scroll to zoom, double-click to reset the camera.  Drawing is done into an
 * int[] raster back to front, which comfortably handles tens of thousands of LEDs per frame.
 */
public class PreviewPanel extends JPanel {

  static private final int BACKGROUND = 0x0c0c10;
  static private final int OTHER_POINT = 0x2a2a30;

  private ModelLoader.ViewPoints points;
  private float[] rgb;
  private float alphaThreshold = 0.1f;
  private float pointSize = 3f;
  private String overlay = "";

  // Camera
  private double yaw = -0.5, pitch = 0.35, zoom = 1.0, panX = 0, panY = 0;
  private float cx, cy, cz, radius = 1;
  private int lastX, lastY;

  private BufferedImage image;
  private int[] raster;
  private Integer[] order = new Integer[0];
  private float[] depth = new float[0];
  private float[] sx = new float[0], sy = new float[0];
  private boolean orderDirty = true;

  public PreviewPanel() {
    setPreferredSize(new Dimension(640, 480));
    setBackground(new Color(BACKGROUND));
    MouseAdapter mouse = new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        lastX = e.getX();
        lastY = e.getY();
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        int dx = e.getX() - lastX, dy = e.getY() - lastY;
        lastX = e.getX();
        lastY = e.getY();
        if (e.isShiftDown() || javax.swing.SwingUtilities.isRightMouseButton(e)) {
          panX += dx;
          panY += dy;
        } else {
          yaw += dx * 0.01;
          pitch = Math.max(-1.55, Math.min(1.55, pitch + dy * 0.01));
          orderDirty = true;
        }
        repaint();
      }

      @Override
      public void mouseWheelMoved(MouseWheelEvent e) {
        zoom *= Math.pow(1.1, -e.getPreciseWheelRotation());
        zoom = Math.max(0.1, Math.min(50, zoom));
        repaint();
      }

      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) resetCamera();
      }
    };
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
    addMouseWheelListener(mouse);
  }

  public void resetCamera() {
    yaw = -0.5;
    pitch = 0.35;
    zoom = 1.0;
    panX = panY = 0;
    orderDirty = true;
    repaint();
  }

  /** Front view, looking down the z axis, which suits flat panels. */
  public void frontCamera() {
    yaw = 0;
    pitch = 0;
    zoom = 1.0;
    panX = panY = 0;
    orderDirty = true;
    repaint();
  }

  public void setPoints(ModelLoader.ViewPoints vp) {
    this.points = vp;
    this.rgb = null;
    int n = vp == null ? 0 : vp.size();
    order = new Integer[n];
    for (int i = 0; i < n; i++) order[i] = i;
    depth = new float[n];
    sx = new float[n];
    sy = new float[n];
    if (vp != null) {
      float[] min = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
      float[] max = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
      for (float[] arr : new float[][] { vp.xyz }) {
        for (int i = 0; i + 2 < arr.length; i += 3) {
          for (int a = 0; a < 3; a++) {
            min[a] = Math.min(min[a], arr[i + a]);
            max[a] = Math.max(max[a], arr[i + a]);
          }
        }
      }
      cx = (min[0] + max[0]) / 2;
      cy = (min[1] + max[1]) / 2;
      cz = (min[2] + max[2]) / 2;
      radius = (float) Math.max(1e-3, 0.5 * Math.sqrt(sq(max[0] - min[0]) + sq(max[1] - min[1]) + sq(max[2] - min[2])));
      boolean flatZ = vp.zRange < 0.02f * Math.max(vp.xRange, vp.yRange);
      if (flatZ) frontCamera(); else resetCamera();
    }
    orderDirty = true;
    repaint();
  }

  static private float sq(float v) {
    return v * v;
  }

  /** Sets the shader output (r,g,b per point) and repaints. */
  public void setColors(float[] rgb) {
    this.rgb = rgb;
    repaint();
  }

  public void setAlphaThreshold(float t) {
    this.alphaThreshold = t;
  }

  public void setPointSize(float s) {
    this.pointSize = s;
    repaint();
  }

  public float getPointSize() {
    return pointSize;
  }

  public void setOverlay(String s) {
    this.overlay = s == null ? "" : s;
  }

  @Override
  protected void paintComponent(Graphics g) {
    int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
    if (image == null || image.getWidth() != w || image.getHeight() != h) {
      image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      raster = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }
    render(w, h);
    g.drawImage(image, 0, 0, null);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
    g2.setColor(new Color(0x9090a0));
    int y = h - 8;
    String hint = "drag: orbit   shift/right-drag: pan   wheel: zoom   double-click: reset";
    g2.drawString(hint, 8, y);
    if (!overlay.isEmpty()) {
      g2.drawString(overlay, 8, 16);
    }
    if (points == null) {
      g2.drawString("No model loaded", w / 2 - 50, h / 2);
    }
  }

  private void render(int w, int h) {
    Arrays.fill(raster, BACKGROUND);
    if (points == null) return;
    double scale = 0.45 * Math.min(w, h) / radius * zoom;
    double cyaw = Math.cos(yaw), syaw = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
    double ox = w / 2.0 + panX, oy = h / 2.0 + panY;

    // Context points outside the view.
    float[] other = points.otherXyz;
    for (int i = 0; i + 2 < other.length; i += 3) {
      double x = other[i] - cx, y = other[i + 1] - cy, z = other[i + 2] - cz;
      double x1 = x * cyaw - z * syaw, z1 = x * syaw + z * cyaw;
      double y1 = y * cp - z1 * sp;
      int px = (int) (ox + x1 * scale), py = (int) (oy - y1 * scale);
      if (px >= 0 && px < w && py >= 0 && py < h) raster[py * w + px] = OTHER_POINT;
    }

    int n = points.size();
    float[] xyz = points.xyz;
    for (int i = 0; i < n; i++) {
      double x = xyz[i * 3] - cx, y = xyz[i * 3 + 1] - cy, z = xyz[i * 3 + 2] - cz;
      double x1 = x * cyaw - z * syaw, z1 = x * syaw + z * cyaw;
      double y1 = y * cp - z1 * sp;
      double z2 = y * sp + z1 * cp;
      sx[i] = (float) (ox + x1 * scale);
      sy[i] = (float) (oy - y1 * scale);
      depth[i] = (float) z2;
    }
    if (orderDirty) {
      // Far (larger depth, away from the viewer) first so near points overwrite them.
      Arrays.sort(order, (a, b) -> Float.compare(depth[b], depth[a]));
      orderDirty = false;
    }
    int r = Math.max(0, Math.round(pointSize / 2f - 0.5f));
    float[] c = rgb;
    for (int k = 0; k < n; k++) {
      int i = order[k];
      int color;
      if (c == null || c.length < (i + 1) * 3) {
        color = 0x303038;
      } else {
        color = ledColor(c[i * 3], c[i * 3 + 1], c[i * 3 + 2]);
      }
      int px = (int) sx[i], py = (int) sy[i];
      for (int dy = -r; dy <= r; dy++) {
        int yy = py + dy;
        if (yy < 0 || yy >= h) continue;
        for (int dx = -r; dx <= r; dx++) {
          if (r > 1 && dx * dx + dy * dy > r * r + r) continue;
          int xx = px + dx;
          if (xx >= 0 && xx < w) raster[yy * w + xx] = color;
        }
      }
      if (r == 0 && px >= 0 && px < w && py >= 0 && py < h) raster[py * w + px] = color;
    }
  }

  /** Mimics CkVShader.run(): colors below the alpha threshold fade out; shown over black. */
  private int ledColor(float r, float g, float b) {
    if (!Float.isFinite(r) || !Float.isFinite(g) || !Float.isFinite(b)) return 0xff00ff;
    int color = LXColor.rgbf(clamp(r), clamp(g), clamp(b));
    float bright = LXColor.luminosity(color) / 100f;
    float alpha = 1f;
    if (bright < alphaThreshold) alpha = alphaThreshold > 0 ? bright / alphaThreshold : 1f;
    int rr = (int) (((color >> 16) & 0xff) * alpha), gg = (int) (((color >> 8) & 0xff) * alpha), bb = (int) ((color & 0xff) * alpha);
    // Keep "off" LEDs faintly visible so the model's shape stays readable.
    rr = Math.max(rr, 0x1c);
    gg = Math.max(gg, 0x1c);
    bb = Math.max(bb, 0x22);
    return (rr << 16) | (gg << 8) | bb;
  }

  static private float clamp(float v) {
    return v < 0 ? 0 : (v > 1 ? 1 : v);
  }

  /** PNG snapshot of the current view as a data URL, scaled to at most maxSize pixels. */
  public String snapshotDataUrl(int maxSize) {
    if (image == null) return null;
    BufferedImage src = image;
    double s = Math.min(1.0, maxSize / (double) Math.max(src.getWidth(), src.getHeight()));
    int w = Math.max(1, (int) (src.getWidth() * s)), h = Math.max(1, (int) (src.getHeight() * s));
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
    g.dispose();
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      ImageIO.write(out, "png", bytes);
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    } catch (Exception ex) {
      return null;
    }
  }
}
