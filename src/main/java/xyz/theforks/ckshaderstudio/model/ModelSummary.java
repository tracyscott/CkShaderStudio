package xyz.theforks.ckshaderstudio.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * Describes the geometry a shader will run on in terms that help a language model design for it:
 * how many LEDs, how the normalized space is stretched, whether the points form a plane or wrap
 * around a vertical axis, and how far apart neighbouring LEDs are in normalized units.
 */
public class ModelSummary {

  static public String describe(String modelName, ModelLoader.ViewPoints vp) {
    StringBuilder sb = new StringBuilder();
    int n = vp.size();
    sb.append(String.format(Locale.US, "- Model file %s, %s: %d LEDs.%n", modelName,
      vp.selector == null ? "whole model" : "view selecting \"" + vp.selector + "\"", n));
    float max = Math.max(vp.xRange, Math.max(vp.yRange, vp.zRange));
    if (max <= 0) {
      sb.append("- All points are at the same location.\n");
      return sb.toString();
    }
    sb.append(String.format(Locale.US,
      "- Real extent (model units): x %.1f, y %.1f (vertical), z %.1f. `position` is normalized to [0,1] on EACH axis "
        + "independently, so the normalized space is stretched: one normalized unit is %.1f units in x, %.1f in y and %.1f in z. "
        + "Multiply by the aspect (%s) before measuring distances if round shapes should look round.%n",
      vp.xRange, vp.yRange, vp.zRange, vp.xRange, vp.yRange, vp.zRange,
      String.format(Locale.US, "x:y:z = %.2f : %.2f : %.2f", vp.xRange / max, vp.yRange / max, vp.zRange / max)));

    String[] axes = { "x", "y", "z" };
    float[] ranges = { vp.xRange, vp.yRange, vp.zRange };
    int flatAxes = 0;
    StringBuilder flat = new StringBuilder();
    for (int a = 0; a < 3; a++) {
      if (ranges[a] < 0.02f * max) {
        flatAxes++;
        flat.append(axes[a]);
      }
    }
    if (flatAxes == 1) {
      sb.append("- The LEDs lie in a plane: the ").append(flat).append(" axis is essentially constant, so treat this as a 2D ")
        .append("canvas using the other two coordinates (a zero-range axis normalizes to 0.5).\n");
    } else if (flatAxes == 2) {
      sb.append("- The LEDs form a line along one axis; only one coordinate varies.\n");
    }

    // Distribution along each axis (how evenly LEDs cover [0,1]).
    for (int a = 0; a < 3; a++) {
      if (ranges[a] < 0.02f * max) continue;
      int[] hist = new int[10];
      for (int i = 0; i < n; i++) {
        int b = Math.min(9, Math.max(0, (int) (vp.xyzn[i * 3 + a] * 10)));
        hist[b]++;
      }
      sb.append("- LED count per tenth of normalized ").append(axes[a]).append(": ").append(Arrays.toString(hist)).append('\n');
    }

    // Is it wrapped around a vertical axis?  Look at the spread of horizontal radius around the centre.
    if (flatAxes == 0 && vp.xRange > 0 && vp.zRange > 0) {
      double sum = 0, sum2 = 0;
      for (int i = 0; i < n; i++) {
        double dx = (vp.xyzn[i * 3] - 0.5) * 2, dz = (vp.xyzn[i * 3 + 2] - 0.5) * 2;
        double r = Math.sqrt(dx * dx + dz * dz);
        sum += r;
        sum2 += r * r;
      }
      double mean = sum / n;
      double sd = Math.sqrt(Math.max(0, sum2 / n - mean * mean));
      if (mean > 0.3 && sd / mean < 0.3) {
        sb.append(String.format(Locale.US,
          "- The LEDs wrap around the vertical (y) axis like a cylinder or tower (mean horizontal radius %.2f, "
            + "spread %.0f%% in centred coordinates). Map designs onto the surface with angle = atan(z-0.5, x-0.5) "
            + "and height = y (uvwrap() does this) rather than using x and z directly.%n", mean, 100 * sd / mean));
        sb.append("- Around the circumference: ").append(angularCoverage(vp)).append('\n');
      }
    }

    double[] spacing = neighbourSpacing(vp);
    if (spacing != null) {
      StringBuilder sp = new StringBuilder();
      for (int a = 0; a < 3; a++) {
        if (ranges[a] < 0.02f * max || spacing[a] <= 0) continue;
        if (sp.length() > 0) sp.append(", ");
        sp.append(String.format(Locale.US, "%s %.3f", axes[a], spacing[a]));
      }
      if (sp.length() > 0) {
        sb.append("- Typical distance from an LED to its nearest neighbour in each direction (normalized units): ")
          .append(sp).append(". Features narrower than about twice that spacing in a direction will alias or vanish, ")
          .append("so size stripes, lines and dots accordingly.\n");
      }
    }
    return sb.toString();
  }

  static private String angularCoverage(ModelLoader.ViewPoints vp) {
    int bins = 36;
    int[] hist = new int[bins];
    int n = vp.size();
    for (int i = 0; i < n; i++) {
      double a = Math.atan2(vp.xyzn[i * 3 + 2] - 0.5, vp.xyzn[i * 3] - 0.5);
      if (a < 0) a += 2 * Math.PI;
      hist[Math.min(bins - 1, (int) (a / (2 * Math.PI) * bins))]++;
    }
    int empty = 0;
    for (int h : hist) if (h == 0) empty++;
    if (empty == 0) return "LEDs are spread all the way around.";
    return empty + " of " + bins + " ten-degree sectors have no LEDs, so angular detail is coarse; keep features broad around the axis.";
  }

  /**
   * For a sample of LEDs, finds the nearest other LED whose offset is mostly along x, along y and
   * along z, and returns the median distance for each axis (0 if none found).  Brute force over
   * all points for a few hundred samples, which is fast enough for very large models.
   */
  static double[] neighbourSpacing(ModelLoader.ViewPoints vp) {
    int n = vp.size();
    if (n < 2) return null;
    int samples = Math.min(n, 300);
    double[][] best = new double[3][samples];
    for (int s = 0; s < samples; s++) {
      int i = (int) ((long) s * n / samples);
      double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
      float xi = vp.xyzn[i * 3], yi = vp.xyzn[i * 3 + 1], zi = vp.xyzn[i * 3 + 2];
      for (int j = 0; j < n; j++) {
        if (j == i) continue;
        double dx = vp.xyzn[j * 3] - xi, dy = vp.xyzn[j * 3 + 1] - yi, dz = vp.xyzn[j * 3 + 2] - zi;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < 1e-12) continue;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        int axis = ax >= ay && ax >= az ? 0 : (ay >= az ? 1 : 2);
        if (d2 < min[axis]) min[axis] = d2;
      }
      for (int a = 0; a < 3; a++) best[a][s] = min[a] == Double.MAX_VALUE ? -1 : Math.sqrt(min[a]);
    }
    double[] out = new double[3];
    for (int a = 0; a < 3; a++) {
      double[] vals = Arrays.stream(best[a]).filter(v -> v >= 0).sorted().toArray();
      out[a] = vals.length == 0 ? 0 : vals[vals.length / 2];
    }
    return out;
  }

}
