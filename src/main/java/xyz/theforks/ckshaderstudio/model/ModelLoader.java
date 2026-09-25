package xyz.theforks.ckshaderstudio.model;

import heronarts.lx.LX;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.model.LXView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads Chromatik model files (.lxm) with a headless LX instance, so every fixture type Chromatik
 * understands (JSON fixtures from ~/Chromatik/Fixtures, built-in strip/grid/arc fixtures, and
 * fixture classes from installed packages) produces exactly the same points and normalization.
 */
public class ModelLoader {

  /** A loaded model with the views that can be previewed. */
  static public class Loaded {
    public final File file;
    public final LXModel model;
    /** Label -> LX view selector (null selector means the whole model). */
    public final Map<String, String> views;

    Loaded(File file, LXModel model, Map<String, String> views) {
      this.file = file;
      this.model = model;
      this.views = views;
    }
  }

  /** The points a shader runs on: display positions plus the normalized coordinates it sees. */
  static public class ViewPoints {
    public final String label;
    public final String selector;
    /** Raw model-space x,y,z of the view's points, for drawing. */
    public final float[] xyz;
    /** xn,yn,zn in the view's own normalization, exactly what CkVShader feeds as `position`. */
    public final float[] xyzn;
    /** Raw positions of points outside the view, drawn dimmed for context. */
    public final float[] otherXyz;
    public final float xRange, yRange, zRange;

    ViewPoints(String label, String selector, float[] xyz, float[] xyzn, float[] otherXyz,
               float xRange, float yRange, float zRange) {
      this.label = label;
      this.selector = selector;
      this.xyz = xyz;
      this.xyzn = xyzn;
      this.otherXyz = otherXyz;
      this.xRange = xRange;
      this.yRange = yRange;
      this.zRange = zRange;
    }

    public int size() {
      return xyz.length / 3;
    }
  }

  private final File mediaPath;
  private LX lx;

  /** @param mediaPath the Chromatik folder (normally ~/Chromatik) holding Fixtures/ and Packages/ */
  public ModelLoader(File mediaPath) {
    this.mediaPath = mediaPath;
  }

  private synchronized LX lx() {
    if (lx == null) {
      LX.Flags flags = new LX.Flags();
      flags.mediaPath = mediaPath.getAbsolutePath();
      flags.loadPreferences = false;
      flags.zeroconf = false;
      flags.autosave = false;
      lx = new LX(flags);
    }
    return lx;
  }

  /** Blocking.  Loads an .lxm file and lists its views. */
  public synchronized Loaded load(File lxmFile) throws Exception {
    LX lx = lx();
    while (lx.getError() != null) lx.popError();
    lx.structure.importModel(lxmFile);
    LXModel model = lx.getModel();
    LX.Error err = lx.getError();
    if (err != null) {
      LX.log("Model import reported: " + err.message);
    }
    if (model.points.length == 0) {
      String detail = err != null ? ": " + err.message : "";
      throw new Exception("Model " + lxmFile.getName() + " has no points" + detail
        + ". JSON fixtures are looked up in " + new File(mediaPath, "Fixtures"));
    }
    Map<String, String> views = new LinkedHashMap<>();
    views.put("Whole model (" + model.points.length + " points)", null);
    // Offer each tag used by the model's top-level fixtures as a view, like Chromatik view selectors.
    Map<String, Integer> tagCounts = new LinkedHashMap<>();
    for (LXModel child : model.children) {
      for (String tag : child.tags) {
        tagCounts.merge(tag, child.points.length, Integer::sum);
      }
    }
    for (Map.Entry<String, Integer> e : tagCounts.entrySet()) {
      if (e.getValue() < model.points.length) {
        views.put("View \"" + e.getKey() + "\" (" + e.getValue() + " points)", e.getKey());
      }
    }
    return new Loaded(lxmFile, model, views);
  }

  /** Blocking.  Builds the view's points, using LXView with relative normalization as Chromatik does. */
  public synchronized ViewPoints view(Loaded loaded, String label, String selector) {
    LXModel model = loaded.model;
    LXModel target = model;
    LXView view = null;
    if (selector != null && !selector.isBlank()) {
      view = LXView.create(model, selector, LXView.Normalization.RELATIVE, LXView.Orientation.GLOBAL);
      target = view;
    }
    LXPoint[] pts = target.points;
    float[] xyz = new float[pts.length * 3];
    float[] xyzn = new float[pts.length * 3];
    boolean[] inView = new boolean[model.points.length];
    for (int i = 0; i < pts.length; i++) {
      LXPoint p = pts[i];
      xyz[i * 3] = p.x;
      xyz[i * 3 + 1] = p.y;
      xyz[i * 3 + 2] = p.z;
      xyzn[i * 3] = p.xn;
      xyzn[i * 3 + 1] = p.yn;
      xyzn[i * 3 + 2] = p.zn;
      if (p.index >= 0 && p.index < inView.length) inView[p.index] = true;
    }
    List<Float> others = new ArrayList<>();
    if (target != model) {
      for (LXPoint p : model.points) {
        if (!inView[p.index]) {
          others.add(p.x);
          others.add(p.y);
          others.add(p.z);
        }
      }
    }
    float[] otherXyz = new float[others.size()];
    for (int i = 0; i < otherXyz.length; i++) otherXyz[i] = others.get(i);
    ViewPoints vp = new ViewPoints(label, selector, xyz, xyzn, otherXyz,
      target.xRange, target.yRange, target.zRange);
    if (view != null) view.dispose();
    return vp;
  }

  /** Built-in stand-in geometry for when no .lxm file is loaded. */
  static public final String[] SYNTHETIC = { "Built-in: 32x32 panel", "Built-in: cylinder", "Built-in: 16x16x16 cube" };

  static public ViewPoints synthetic(String name) {
    List<float[]> pts = new ArrayList<>();
    if (name.equals(SYNTHETIC[1])) {
      // 48 vertical strips of 120 LEDs around a cylinder 100 units wide and 200 tall.
      for (int s = 0; s < 48; s++) {
        double a = 2 * Math.PI * s / 48;
        for (int i = 0; i < 120; i++) {
          pts.add(new float[] { (float) (50 * Math.cos(a)), 200f * i / 119, (float) (50 * Math.sin(a)) });
        }
      }
    } else if (name.equals(SYNTHETIC[2])) {
      for (int x = 0; x < 16; x++)
        for (int y = 0; y < 16; y++)
          for (int z = 0; z < 16; z++) pts.add(new float[] { x * 10, y * 10, z * 10 });
    } else {
      for (int y = 0; y < 32; y++)
        for (int x = 0; x < 32; x++) pts.add(new float[] { x * 10, y * 10, 0 });
    }
    float[] min = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
    float[] max = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
    for (float[] p : pts) {
      for (int a = 0; a < 3; a++) {
        min[a] = Math.min(min[a], p[a]);
        max[a] = Math.max(max[a], p[a]);
      }
    }
    float[] xyz = new float[pts.size() * 3];
    float[] xyzn = new float[pts.size() * 3];
    for (int i = 0; i < pts.size(); i++) {
      for (int a = 0; a < 3; a++) {
        float v = pts.get(i)[a];
        float range = max[a] - min[a];
        xyz[i * 3 + a] = v;
        // LX normalizes a zero-range axis to 0.5.
        xyzn[i * 3 + a] = range > 0 ? (v - min[a]) / range : 0.5f;
      }
    }
    return new ViewPoints(name, null, xyz, xyzn, new float[0], max[0] - min[0], max[1] - min[1], max[2] - min[2]);
  }

  public synchronized void dispose() {
    if (lx != null) {
      try {
        lx.dispose();
      } catch (Exception ignored) {
      }
      lx = null;
    }
  }
}
