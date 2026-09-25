package xyz.theforks.ckshaderstudio.shader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands #include directives the same way CkVShader's GLUtil does (a line starting with
 * "#include", with &lt;name.vti&gt; resolved against the shader folder), so a shader that compiles
 * here also compiles in Chromatik.
 *
 * Unlike CkVShader, each file gets its own GLSL source-string number in the #line directives
 * (0 for the main file, 1.. for includes).  This does not change how the code compiles, but lets
 * compiler messages be mapped back to "file:line", which is much more useful to the model.
 */
public class ShaderSource {

  static private final int MAX_INCLUDE_DEPTH = 10;

  /** Result of expanding a shader, with the file for each GLSL source-string number. */
  static public class Expanded {
    public final String source;
    public final List<String> files;

    Expanded(String source, List<String> files) {
      this.source = source;
      this.files = files;
    }

    /** Rewrites "N:LINE" references in a compiler log to "file:LINE". */
    public String mapErrors(String log) {
      if (log == null) return null;
      Matcher m = ERROR_REF.matcher(log);
      StringBuilder sb = new StringBuilder();
      while (m.find()) {
        int index = Integer.parseInt(m.group(2));
        String file = index >= 0 && index < files.size() ? files.get(index) : m.group(2);
        m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + file + ":" + m.group(3)));
      }
      m.appendTail(sb);
      return sb.toString();
    }
  }

  // Matches "0:12" / "0(12)" style references found in Mesa, Apple and NVIDIA logs.
  static private final Pattern ERROR_REF = Pattern.compile("(^|[\\s:])(\\d+)[:(](\\d+)\\)?", Pattern.MULTILINE);

  /**
   * Expands includes in {@code body}, which is the text of {@code mainName}.
   * @throws IOException if an include cannot be found, naming the offending line.
   */
  static public Expanded expand(File shaderDir, String mainName, String body) throws IOException {
    List<String> files = new ArrayList<>();
    files.add(mainName);
    StringBuilder out = new StringBuilder();
    expandInto(shaderDir, body, 0, files, out, 0);
    return new Expanded(out.toString(), files);
  }

  static private void expandInto(File shaderDir, String body, int sourceIndex, List<String> files,
                                 StringBuilder out, int depth) throws IOException {
    if (depth >= MAX_INCLUDE_DEPTH) {
      throw new IOException("Exceeded maximum #include depth of " + MAX_INCLUDE_DEPTH);
    }
    String[] lines = body.split("\r?\n", -1);
    boolean seenVersion = false;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      int lineNo = i + 1;
      if (line.startsWith("#include")) {
        String name = line.substring("#include".length()).trim();
        File f = resolveInclude(shaderDir, name);
        if (f == null) {
          throw new IOException(files.get(sourceIndex) + ":" + lineNo + ": include file not found: " + name
            + " (includes are looked up in " + shaderDir + ")");
        }
        int includeIndex = files.size();
        files.add(f.getName());
        out.append("#line 1 ").append(includeIndex).append('\n');
        expandInto(shaderDir, Files.readString(f.toPath(), StandardCharsets.UTF_8), includeIndex, files, out, depth + 1);
        out.append("#line ").append(lineNo + 1).append(' ').append(sourceIndex).append('\n');
      } else {
        out.append(line).append('\n');
        // #line directives are only legal after #version, so set the source number right after it.
        if (!seenVersion && sourceIndex == 0 && line.trim().startsWith("#version")) {
          seenVersion = true;
          out.append("#line ").append(lineNo + 1).append(" 0\n");
        }
      }
    }
  }

  /** Mirrors CkVShader: &lt;name&gt; is relative to the shader folder; quoted names are tried as given, then there. */
  static public File resolveInclude(File shaderDir, String token) {
    String name = token.trim();
    if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) {
      name = name.substring(1, name.length() - 1).trim();
    }
    if (name.startsWith("<") && name.endsWith(">")) {
      File f = new File(shaderDir, name.substring(1, name.length() - 1).trim());
      return f.isFile() ? f : null;
    }
    File f = new File(name);
    if (f.isFile()) return f;
    f = new File(shaderDir, name);
    return f.isFile() ? f : null;
  }

  static public String readFile(File f) throws IOException {
    return Files.readString(f.toPath(), StandardCharsets.UTF_8);
  }
}
