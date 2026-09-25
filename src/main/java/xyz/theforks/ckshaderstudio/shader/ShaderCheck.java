package xyz.theforks.ckshaderstudio.shader;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full validation of a shader: header checks, include expansion, GLSL compile/link on the preview
 * context and a short test run to catch NaNs or a completely dark result.
 */
public class ShaderCheck {

  public final IsfHeader header;
  /** Linked program if everything compiled, else null.  Owned by the caller. */
  public final GLRunner.Program program;
  /** Blocking problems, one per line, or null. */
  public final String errors;
  /** Non-blocking notes, or null. */
  public final String warnings;

  private ShaderCheck(IsfHeader header, GLRunner.Program program, String errors, String warnings) {
    this.header = header;
    this.program = program;
    this.errors = errors;
    this.warnings = warnings;
  }

  public boolean ok() {
    return errors == null;
  }

  /** A result for when OpenGL is unavailable: header checks only, never a program. */
  static public ShaderCheck headerOnly(IsfHeader header) {
    String errors = header.errors.isEmpty() ? null : String.join("\n", header.errors);
    List<String> w = new ArrayList<>(header.warnings);
    w.add("OpenGL is not available, so the shader was not compiled.");
    return new ShaderCheck(header, null, errors, join(w));
  }

  static public ShaderCheck error(IsfHeader header, String error) {
    return new ShaderCheck(header, null, error, null);
  }

  static public ShaderCheck run(GLRunner runner, File shaderDir, String name, String source) {
    IsfHeader header = IsfHeader.parse(source);
    List<String> warnings = new ArrayList<>(header.warnings);
    if (!header.errors.isEmpty()) {
      return new ShaderCheck(header, null, String.join("\n", header.errors), join(warnings));
    }
    ShaderSource.Expanded expanded;
    try {
      expanded = ShaderSource.expand(shaderDir, name + ".vtx", source);
    } catch (Exception ex) {
      return new ShaderCheck(header, null, ex.getMessage(), join(warnings));
    }
    GLRunner.CompileResult cr;
    try {
      cr = runner.compile(expanded);
    } catch (Exception ex) {
      return new ShaderCheck(header, null, "OpenGL error: " + ex.getMessage(), join(warnings));
    }
    if (!cr.ok()) {
      return new ShaderCheck(header, null, cr.errors, join(warnings));
    }
    if (cr.warnings != null) warnings.add(cr.warnings);

    // Test run with default slider values at a few times.
    Map<String, Float> params = new LinkedHashMap<>();
    for (IsfHeader.Input in : header.inputs) params.put(in.name, in.def);
    int bad = 0, total = 0, lit = 0;
    try {
      for (float t : new float[] { 0.37f, 2.9f, 11.3f }) {
        float[] rgb = runner.run(cr.program, t, params);
        for (int i = 0; i + 2 < rgb.length; i += 3) {
          total++;
          float r = rgb[i], g = rgb[i + 1], b = rgb[i + 2];
          if (!Float.isFinite(r) || !Float.isFinite(g) || !Float.isFinite(b)) {
            bad++;
          } else if (r + g + b > 0.03f) {
            lit++;
          }
        }
      }
    } catch (Exception ex) {
      runner.delete(cr.program);
      return new ShaderCheck(header, null, "Test run failed: " + ex.getMessage(), join(warnings));
    }
    if (total > 0 && bad > 0) {
      runner.delete(cr.program);
      return new ShaderCheck(header, null,
        "Runtime check: " + bad + " of " + total + " LED outputs were NaN or infinite with the default slider values "
          + "(check for division by zero, sqrt/pow/log of negative numbers, normalize(vec3(0)), atan(0,0)).",
        join(warnings));
    }
    if (total > 0 && lit == 0) {
      warnings.add("Runtime check: every LED was black at t=0.4, 2.9 and 11.3 with the default slider values.");
    }
    return new ShaderCheck(header, cr.program, null, join(warnings));
  }

  static private String join(List<String> list) {
    return list.isEmpty() ? null : String.join("\n", list);
  }
}
