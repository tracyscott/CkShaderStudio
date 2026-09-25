package xyz.theforks.ckshaderstudio.ai;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the system prompt that teaches a general purpose LLM how CkVShader transform-feedback
 * vertex shaders work, and extracts shader source from its replies.
 */
public class ShaderPrompt {

  static final String EXAMPLE_SHADER = """
/*{
  "DESCRIPTION": "palnoise",
  "CREDIT": "by tracyscott",
  "ISFVSN": "2.0",
  "CATEGORIES": [ "VERTEX SDF" ],
  "INPUTS": [
    { "NAME": "palval", "TYPE": "float", "DEFAULT": 0.0, "MIN": 0.0, "MAX": 20.0 },
    { "NAME": "pald",   "TYPE": "float", "DEFAULT": 1.0, "MIN": 0.1, "MAX": 20.0 },
    { "NAME": "palspd", "TYPE": "float", "DEFAULT": 1.0, "MIN": 0.0, "MAX": 20.0 },
    { "NAME": "scale",  "TYPE": "float", "DEFAULT": 0.3, "MIN": 0.01, "MAX": 20.0 },
    { "NAME": "brt",    "TYPE": "float", "DEFAULT": 1.0, "MIN": 0.01, "MAX": 2.0 }
  ]
}*/

#version 330

uniform float fTime;
uniform float palval;
uniform float pald;
uniform float palspd;
uniform float scale;
uniform float brt;

layout(location = 0) in vec3 position;
out vec3 outColor;

#include <consts.vti>
#include <palettes.vti>
#include <uvwrap.vti>

vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec2 mod289(vec2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec3 permute(vec3 x) { return mod289(((x*34.0)+10.0)*x); }

float snoise(vec2 v) {
  const vec4 C = vec4(0.211324865405187, 0.366025403784439,
                     -0.577350269189626, 0.024390243902439);
  vec2 i  = floor(v + dot(v, C.yy));
  vec2 x0 = v - i + dot(i, C.xx);
  vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
  vec4 x12 = x0.xyxy + C.xxzz;
  x12.xy -= i1;
  i = mod289(i);
  vec3 p = permute(permute(i.y + vec3(0.0, i1.y, 1.0)) + i.x + vec3(0.0, i1.x, 1.0));
  vec3 m = max(0.5 - vec3(dot(x0,x0), dot(x12.xy,x12.xy), dot(x12.zw,x12.zw)), 0.0);
  m = m*m; m = m*m;
  vec3 x = 2.0 * fract(p * C.www) - 1.0;
  vec3 h = abs(x) - 0.5;
  vec3 ox = floor(x + 0.5);
  vec3 a0 = x - ox;
  m *= 1.79284291400159 - 0.85373472095314 * (a0*a0 + h*h);
  vec3 g;
  g.x  = a0.x  * x0.x  + h.x  * x0.y;
  g.yz = a0.yz * x12.xz + h.yz * x12.yw;
  return 130.0 * dot(m, g);
}

void main() {
  // Wrap the LED position onto a cylinder: p.x = angle around the vertical axis [0,1), p.y = height [0,1].
  vec2 p = uvwrap(position);
  p.x += fTime;
  p *= scale;
  float n = 0.5 + 0.5 * snoise(p * 4.0);
  float b = (1.0 - brt) + brt * n;
  outColor = clamp(b * paletteN(n * pald + fTime * palspd, palval), 0.0, 1.0);
}
""";

  static private final String INTRO = """
You are an expert GLSL shader author writing LED lighting shaders for CkVShader, a plugin for the
Chromatik lighting platform. CkVShader uses a CUSTOM shader system that is NOT a normal
fragment/pixel shader. Read these rules carefully.

## How CkVShader works
- Each shader is a GLSL 330 *vertex shader* run with OpenGL Transform Feedback. The rasterizer
  is disabled; there is no fragment shader, no framebuffer, and no pixels.
- The shader runs once per physical LED. The input attribute `position` is that LED's 3D
  position, normalized to [0,1] on each axis over the bounds of the model (or view) being rendered.
  y is vertical (up), x and z are horizontal.
- The shader writes `outColor` (vec3 RGB, each channel 0..1). That value is read back to the CPU
  and becomes the LED color. Always clamp the output to [0,1].
- Because it is evaluated at 3D points, the shader can be truly volumetric (3D SDFs, 3D noise,
  planes/spheres sweeping through space), can treat a flat installation as a 2D canvas, or can
  wrap a 2D design around a vertical axis with uvwrap().
- The pattern has an alpha threshold slider (alfTh, default 0.1): LEDs darker than it fade to
  transparent, so black means "off / let lower layers show through".
- The pattern also has a speed slider; fTime is elapsed seconds multiplied by speed. Motion reads
  better on LEDs than static detail.

## File format (MUST follow exactly)
1. The file begins with an ISF-style JSON header inside a /* ... */ comment. It MUST be the first
   comment in the file, and there must not be any other /* */ comment before it. It must be valid
   JSON (no trailing commas, no comments inside).
2. Every entry in "INPUTS" MUST have "NAME", "TYPE", "DEFAULT", "MIN", "MAX". Only
   "TYPE": "float" is supported. Each becomes a slider in the UI. Use short NAMEs (<= 7 chars,
   valid GLSL identifiers, not "speed" or "alfTh") because slider labels are tiny. Use 2 to 6
   inputs with sensible ranges, and DEFAULTs that look good immediately.
3. Next line: `#version 330`
4. Declare `uniform float fTime;` and one `uniform float <NAME>;` for EVERY input in the JSON
   header, spelled identically.
5. Declare exactly:
     layout(location = 0) in vec3 position;
     out vec3 outColor;
6. Optional `#include <file.vti>` lines (see available includes below) go AFTER the uniform
   declarations, one per line, starting at column 0, always with angle brackets. Include consts.vti
   before uvwrap.vti. Do not redefine anything an include already defines (e.g. M_PI, PI, palette,
   uvwrap).
7. `void main()` must assign `outColor`.

## Things that do NOT exist here (do not use them)
gl_FragCoord, gl_FragColor, fragColor, mainImage, iTime, iResolution, iMouse, iChannel0,
dFdx/dFdy/fwidth, discard, texture bias/derivative functions, extensions, or any Shadertoy
wrapper. If adapting a Shadertoy idea, derive a 2D coordinate from `position` (or uvwrap(position))
and use fTime instead of iTime. Keep loops bounded with constant limits. Avoid NaNs: guard
divisions, sqrt/pow/log arguments, and normalize() of possibly-zero vectors.
""";

  static private final String TEXTURE_SECTION = """

## Textures (CkVShaderTex pattern)
This shader will run in the CkVShaderTex pattern, which additionally provides:
- `uniform sampler2D textureSampler;` a user-chosen RGB image (nearest filtering, mirrored repeat).
  Sample with texture(textureSampler, uv).
- `uniform sampler2D audioTexture;` live audio levels in the red channel (0..1), with nearest
  filtering. Only 8 bands are usable, stored at even texels of row 0. Read them with exactly:
    float audioBand(int b) { return texelFetch(audioTexture, ivec2(2 * b, 0), 0).r; } // b = 0 (bass) .. 7 (treble)
  Smooth or combine bands for stable motion; for example, bass = 0.5 * (audioBand(0) + audioBand(1)).
Declare whichever of these you use. They are optional.
""";

  static private final String NO_TEXTURE_SECTION = """

## Target pattern
This shader will run in the plain CkVShader pattern: no textures or audio are available, so do not
declare any sampler uniforms.
""";

  static private final String OUTPUT_RULES = """

## Response format
Reply with a single fenced code block tagged glsl containing the COMPLETE shader file
(header through main), followed by at most 3 short sentences describing the effect and its
sliders. Never omit parts of the file or use placeholders like "...". When asked to revise,
start from the current shader the user provides and return the full updated file again,
keeping slider names stable unless asked to change them.
If the user only asks a question and no change to the shader is needed, answer briefly without a
code block.
""";

  /**
   * Builds the system prompt.
   * @param shaderDir directory holding the .vti include files so their API can be described.
   * @param textureMode whether the shader targets CkVShaderTex.
   * @param geometry description of the model/view the shader will run on, may be null.
   * @param notes free-form notes about the installation from the user, may be empty.
   */
  static public String systemPrompt(File shaderDir, boolean textureMode, String geometry, String notes) {
    StringBuilder sb = new StringBuilder(INTRO);
    if (geometry != null && !geometry.isBlank()) {
      sb.append("\n## The LED model this shader is previewed on\n").append(geometry);
    }
    if (notes != null && !notes.isBlank()) {
      sb.append("\n## Notes about the installation from the artist\n").append(notes.trim()).append('\n');
    }
    sb.append(describeIncludes(shaderDir));
    sb.append(textureMode ? TEXTURE_SECTION : NO_TEXTURE_SECTION);
    sb.append("\n## Complete working example\n```glsl\n").append(EXAMPLE_SHADER).append("```\n");
    sb.append(OUTPUT_RULES);
    return sb.toString();
  }

  static private final Pattern FUNC_SIG = Pattern.compile(
    "^\\s*((?:vec[234]|float|int|bool|mat[234]|void))\\s+(\\w+)\\s*\\(([^)]*)\\)", Pattern.MULTILINE);
  static private final Pattern CONST_DECL = Pattern.compile(
    "^\\s*(?:const|#define)\\s+(?:\\w+\\s+)?(\\w+)\\s*=?", Pattern.MULTILINE);

  /** Scans the installed include files and lists the functions and constants they define. */
  static String describeIncludes(File dir) {
    File[] files = dir.listFiles((d, name) -> name.endsWith(".vti"));
    if (files == null || files.length == 0) {
      return "\n## Available includes\nNone. Define any helper functions yourself.\n";
    }
    Arrays.sort(files);
    StringBuilder sb = new StringBuilder("\n## Available includes\n");
    sb.append("Use with `#include <name.vti>`. They define the following:\n");
    for (File f : files) {
      String src;
      try {
        src = Files.readString(f.toPath());
      } catch (IOException ex) {
        continue;
      }
      List<String> items = new ArrayList<>();
      Matcher c = CONST_DECL.matcher(src);
      List<String> consts = new ArrayList<>();
      while (c.find()) {
        if (!consts.contains(c.group(1))) consts.add(c.group(1));
      }
      if (!consts.isEmpty()) items.add("constants " + String.join(", ", consts));
      Matcher m = FUNC_SIG.matcher(src);
      while (m.find()) {
        items.add(m.group(1) + " " + m.group(2) + "(" + m.group(3).trim().replaceAll("\\s+", " ") + ")");
      }
      sb.append("- ").append(f.getName()).append(": ");
      sb.append(items.isEmpty() ? "(no declarations found)" : String.join("; ", items)).append("\n");
    }
    sb.append("Notes: uvwrap(position) returns vec2(u, v) with u = angle around the vertical axis in [0,1) ")
      .append("and v = height in [0,1]; it requires consts.vti. paletteN(t, n) (palettes.vti) selects cosine palette ")
      .append("number n (0..20) and returns a color for t; palette(t,a,b,c,d) is the Inigo Quilez cosine palette.\n");
    return sb.toString();
  }

  static private final Pattern CODE_BLOCK = Pattern.compile("```[a-zA-Z]*[ \\t]*\\r?\\n(.*?)```", Pattern.DOTALL);

  /** Extracts the shader source from a model reply, or null if none is found. */
  static public String extractShader(String reply) {
    String best = null;
    Matcher m = CODE_BLOCK.matcher(reply);
    while (m.find()) {
      String block = m.group(1);
      if (best == null || (block.contains("#version") && !best.contains("#version"))
        || (block.contains("#version") == best.contains("#version") && block.length() > best.length())) {
        best = block;
      }
    }
    if (best == null && reply.contains("#version") && reply.trim().startsWith("/*")) {
      best = reply;
    }
    return best == null ? null : best.trim() + "\n";
  }

  /** Returns the reply with code blocks replaced by a placeholder, for compact history and display. */
  static public String withoutCode(String reply, String placeholder) {
    return CODE_BLOCK.matcher(reply).replaceAll(Matcher.quoteReplacement(placeholder)).trim();
  }
}
