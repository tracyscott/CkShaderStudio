package xyz.theforks.ckshaderstudio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Application settings, stored in ~/Chromatik/CkShaderStudio/settings.json (outside any project or
 * repository so the API key is not shared by accident).
 */
public class Settings {

  public String apiKey = "";
  public String provider = "anthropic";
  public String model = null;
  /** Chromatik media folder; Fixtures/ and Packages/ are read from here when loading models. */
  public String chromatikDir = new File(System.getProperty("user.home"), "Chromatik").getPath();
  /** Where shaders are saved and includes/textures are read.  Empty means CkVShader's default. */
  public String shaderDir = "";
  public String lastModelFile = "";
  public String lastView = "";
  public String lastShaderName = "";
  public int maxTokens = 16000;
  public int maxFixAttempts = 2;
  public boolean attachPreview = false;
  public float pointSize = 3f;
  /** Render the preview as an OpenGL point cloud (off: software renderer). */
  public boolean gpuPreview = true;
  public boolean glow = true;
  /** Per model file notes about the installation, included in the system prompt. */
  public Map<String, String> modelNotes = new HashMap<>();

  static public File appDir() {
    return new File(new File(System.getProperty("user.home"), "Chromatik"), "CkShaderStudio");
  }

  static private File file() {
    return new File(appDir(), "settings.json");
  }

  public File chromatikDir() {
    return new File(chromatikDir);
  }

  /** CkVShader loads shaders from ~/Chromatik/Data/CkVShader/shaders. */
  public File shaderDir() {
    if (shaderDir != null && !shaderDir.isBlank()) return new File(shaderDir);
    return new File(new File(new File(chromatikDir(), "Data"), "CkVShader"), "shaders");
  }

  public File texturesDir() {
    return new File(shaderDir(), "textures");
  }

  public File sessionsDir() {
    return new File(appDir(), "sessions");
  }

  public String effectiveApiKey() {
    if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();
    String env = System.getenv("OPENROUTER_API_KEY");
    return env == null ? "" : env.trim();
  }

  static public Settings load() {
    File f = file();
    if (f.exists()) {
      try {
        Settings s = new Gson().fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), Settings.class);
        if (s != null) {
          if (s.modelNotes == null) s.modelNotes = new HashMap<>();
          return s;
        }
      } catch (Exception ex) {
        System.err.println("Could not read " + f + ": " + ex);
      }
    }
    return new Settings();
  }

  public void save() {
    File f = file();
    try {
      f.getParentFile().mkdirs();
      Files.writeString(f.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(this), StandardCharsets.UTF_8);
    } catch (Exception ex) {
      System.err.println("Could not write " + f + ": " + ex);
    }
  }
}
