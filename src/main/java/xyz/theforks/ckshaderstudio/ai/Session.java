package xyz.theforks.ckshaderstudio.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * One shader's working session: the chat transcript and every version of the shader produced by
 * the model or by hand.  Persisted as JSON so an iteration can be resumed later.
 */
public class Session {

  static public final String ROLE_USER = "user";
  static public final String ROLE_ASSISTANT = "assistant";
  /** Automatic "please fix these errors" requests; sent as user messages. */
  static public final String ROLE_FIX = "fix";
  /** Local notes shown in the transcript but never sent to the model. */
  static public final String ROLE_NOTE = "note";

  static public class Entry {
    public String role;
    public String text;
    /** Version produced by this assistant reply, or 0. */
    public int version;
    public boolean hadImage;
    public long time;

    public Entry() {
    }

    public Entry(String role, String text) {
      this.role = role;
      this.text = text;
      this.time = System.currentTimeMillis();
    }
  }

  static public class Version {
    public int number;
    public String source;
    /** "ai", "edit", "file" or "fix". */
    public String origin;
    public boolean ok;
    public String errors;
    public long time;

    public Version() {
    }

    public Version(int number, String source, String origin) {
      this.number = number;
      this.source = source;
      this.origin = origin;
      this.time = System.currentTimeMillis();
    }

    public String label() {
      String o = switch (origin == null ? "" : origin) {
        case "ai" -> "AI";
        case "fix" -> "AI fix";
        case "edit" -> "hand edit";
        case "file" -> "from file";
        default -> origin;
      };
      return "v" + number + " (" + o + (ok ? "" : ", errors") + ")";
    }
  }

  public String shaderName = "";
  public boolean textureMode = false;
  public String modelId;
  public List<Entry> entries = new ArrayList<>();
  public List<Version> versions = new ArrayList<>();
  /** Number of the version shown in the editor. */
  public int currentVersion = 0;
  public double totalCost = 0;
  public int totalPromptTokens = 0, totalCompletionTokens = 0;
  /** True once this session has saved to, or was opened from, shaders/&lt;shaderName&gt;.vtx. */
  public boolean ownsFile = false;
  /** Model the shader was designed on: an .lxm path or a built-in model name. */
  public String modelFile;
  /** LX view selector used for the preview, or null for the whole model. */
  public String viewSelector;
  public long created = System.currentTimeMillis();
  public long updated = System.currentTimeMillis();

  /** The first thing the user asked for, as a one-line title. */
  public String title() {
    for (Entry e : entries) {
      if (ROLE_USER.equals(e.role) && e.text != null && !e.text.isBlank()) {
        String t = e.text.trim().replaceAll("\\s+", " ");
        return t.length() > 90 ? t.substring(0, 90) + "…" : t;
      }
    }
    return "";
  }

  public int messageCount() {
    int n = 0;
    for (Entry e : entries) if (ROLE_USER.equals(e.role)) n++;
    return n;
  }

  public Version version(int number) {
    for (Version v : versions) if (v.number == number) return v;
    return null;
  }

  public Version current() {
    return version(currentVersion);
  }

  public Version addVersion(String source, String origin) {
    int n = versions.isEmpty() ? 1 : versions.get(versions.size() - 1).number + 1;
    Version v = new Version(n, source, origin);
    versions.add(v);
    currentVersion = n;
    return v;
  }

  public Entry add(String role, String text) {
    Entry e = new Entry(role, text);
    entries.add(e);
    return e;
  }

  /** The last version the model returned, to tell whether the user has edited it since. */
  public Version lastModelVersion() {
    for (int i = entries.size() - 1; i >= 0; i--) {
      Entry e = entries.get(i);
      if (ROLE_ASSISTANT.equals(e.role) && e.version > 0) return version(e.version);
    }
    return null;
  }

  static private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

  public void save(File f) throws IOException {
    updated = System.currentTimeMillis();
    f.getParentFile().mkdirs();
    File tmp = new File(f.getPath() + ".tmp");
    Files.writeString(tmp.toPath(), gson.toJson(this), StandardCharsets.UTF_8);
    Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  static public Session load(File f) throws IOException {
    Session s = gson.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), Session.class);
    if (s.entries == null) s.entries = new ArrayList<>();
    if (s.versions == null) s.versions = new ArrayList<>();
    if (s.updated == 0) s.updated = f.lastModified();
    if (s.created == 0) s.created = s.entries.isEmpty() ? s.updated : s.entries.get(0).time;
    return s;
  }

  /** Loads every session in a folder, newest first; unreadable files are skipped. */
  static public List<Session> loadAll(File dir) {
    List<Session> out = new ArrayList<>();
    File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
    if (files == null) return out;
    for (File f : files) {
      try {
        out.add(load(f));
      } catch (Exception ignored) {
      }
    }
    out.sort((a, b) -> Long.compare(b.updated, a.updated));
    return out;
  }
}
