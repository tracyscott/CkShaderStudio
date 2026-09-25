package xyz.theforks.ckshaderstudio.shader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The ISF-style JSON header at the top of a CkVShader .vtx file, plus the checks CkVShader relies
 * on (every INPUT becomes a slider and a float uniform of the same name).
 */
public class IsfHeader {

  static public class Input {
    public final String name;
    public final float def, min, max;

    public Input(String name, float def, float min, float max) {
      this.name = name;
      this.def = def;
      this.min = min;
      this.max = max;
    }
  }

  static private final Gson gson = new Gson();
  static private final Pattern GLSL_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  public final String description;
  public final List<Input> inputs;
  /** Problems that CkVShader would choke on or silently ignore. */
  public final List<String> errors;
  /** Things that work but are worth knowing about. */
  public final List<String> warnings;

  private IsfHeader(String description, List<Input> inputs, List<String> errors, List<String> warnings) {
    this.description = description;
    this.inputs = inputs;
    this.errors = errors;
    this.warnings = warnings;
  }

  /** Parses and validates the header.  Pure Java, safe on any thread. */
  static public IsfHeader parse(String source) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    List<Input> inputs = new ArrayList<>();
    String description = "";

    int start = source.indexOf("/*");
    int end = source.indexOf("*/");
    JsonObject isf = null;
    if (start < 0 || end < start) {
      errors.add("Missing ISF JSON header comment /*{ ... }*/ at the top of the file.");
    } else {
      try {
        isf = gson.fromJson(source.substring(start + 2, end), JsonObject.class);
      } catch (Exception ex) {
        errors.add("ISF header is not valid JSON: " + ex.getMessage());
      }
    }
    if (isf != null) {
      if (isf.has("DESCRIPTION")) description = isf.get("DESCRIPTION").getAsString();
      if (!isf.has("INPUTS") || !isf.get("INPUTS").isJsonArray()) {
        errors.add("ISF header must contain an \"INPUTS\" array (it may be empty).");
      } else {
        for (JsonElement el : isf.getAsJsonArray("INPUTS")) {
          if (!el.isJsonObject()) {
            errors.add("Each INPUTS entry must be a JSON object.");
            continue;
          }
          JsonObject in = el.getAsJsonObject();
          String name = in.has("NAME") ? in.get("NAME").getAsString() : "?";
          boolean complete = true;
          for (String key : new String[] {"NAME", "TYPE", "DEFAULT", "MIN", "MAX"}) {
            if (!in.has(key)) {
              errors.add("Input " + name + " is missing \"" + key + "\" (CkVShader skips inputs without it).");
              complete = false;
            }
          }
          if (in.has("TYPE") && !"float".equals(in.get("TYPE").getAsString())) {
            errors.add("Input " + name + " has TYPE " + in.get("TYPE") + "; only \"float\" is supported.");
            complete = false;
          }
          if (!name.equals("?") && !GLSL_IDENT.matcher(name).matches()) {
            errors.add("Input NAME \"" + name + "\" is not a valid GLSL identifier.");
            complete = false;
          }
          if (!name.equals("?") && !Pattern.compile("\\buniform\\s+float\\s+" + Pattern.quote(name) + "\\s*;").matcher(source).find()) {
            warnings.add("Input " + name + " has no matching `uniform float " + name + ";` declaration, so its slider does nothing.");
          }
          if (complete) {
            try {
              float def = in.get("DEFAULT").getAsFloat();
              float min = in.get("MIN").getAsFloat();
              float max = in.get("MAX").getAsFloat();
              if (min >= max) {
                errors.add("Input " + name + " has MIN >= MAX.");
              } else {
                if (def < min || def > max) warnings.add("Input " + name + " DEFAULT is outside MIN..MAX.");
                inputs.add(new Input(name, def, min, max));
              }
            } catch (Exception ex) {
              errors.add("Input " + name + " DEFAULT/MIN/MAX must be numbers.");
            }
          }
          if (name.equals("speed") || name.equals("alfTh")) {
            warnings.add("Input name " + name + " collides with a built-in CkVShader slider.");
          }
        }
      }
    }
    if (!source.contains("#version")) {
      errors.add("Missing `#version 330` line after the header.");
    }
    if (!Pattern.compile("\\bout\\s+vec3\\s+outColor\\s*;").matcher(source).find()) {
      errors.add("The shader must declare `out vec3 outColor;` and assign it in main().");
    }
    if (!Pattern.compile("\\bin\\s+vec3\\s+position\\s*;").matcher(source).find()) {
      errors.add("The shader must declare `layout(location = 0) in vec3 position;`.");
    }
    if (Pattern.compile("(?m)^#include\\s*\"").matcher(source).find()) {
      warnings.add("Quoted #include \"file\" is resolved against Chromatik's working directory; use #include <file.vti>.");
    }
    return new IsfHeader(description, inputs, errors, warnings);
  }

  /**
   * Returns the source with each input's DEFAULT replaced by the given value.  The header JSON is
   * re-serialized; everything after the header is left untouched.
   */
  static public String withDefaults(String source, Map<String, Float> values) {
    int start = source.indexOf("/*");
    int end = source.indexOf("*/");
    if (start < 0 || end < start) return source;
    JsonObject isf;
    try {
      isf = gson.fromJson(source.substring(start + 2, end), JsonObject.class);
    } catch (Exception ex) {
      return source;
    }
    if (isf == null || !isf.has("INPUTS")) return source;
    JsonArray inputs = isf.getAsJsonArray("INPUTS");
    for (JsonElement el : inputs) {
      JsonObject in = el.getAsJsonObject();
      if (!in.has("NAME")) continue;
      Float v = values.get(in.get("NAME").getAsString());
      if (v != null) {
        in.addProperty("DEFAULT", Math.round(v * 10000f) / 10000f);
      }
    }
    String json = new GsonBuilder().setPrettyPrinting().create().toJson(isf);
    return source.substring(0, start) + "/*" + json + source.substring(end);
  }
}
