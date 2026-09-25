package xyz.theforks.ckshaderstudio.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal blocking client for the OpenRouter HTTP API.  Calls block, so make them off the Swing
 * thread.  {@link #cancel()} aborts the request in flight from any thread.
 */
public class OpenRouterClient {
  /** Overridable with -Dckshaderstudio.openrouter.url=... for testing against a local stub. */
  static public final String BASE_URL = System.getProperty("ckshaderstudio.openrouter.url", "https://openrouter.ai/api/v1");
  static private final int CONNECT_TIMEOUT_MS = 15000;
  static private final int READ_TIMEOUT_MS = 600000;

  static private final Gson gson = new Gson();

  static public class Model {
    public final String id;
    public final String name;
    public final int contextLength;
    public final boolean acceptsImages;
    /** USD per million prompt / completion tokens, or -1 if unknown. */
    public final double promptPrice, completionPrice;

    public Model(String id, String name, int contextLength, boolean acceptsImages, double promptPrice, double completionPrice) {
      this.id = id;
      this.name = name;
      this.contextLength = contextLength;
      this.acceptsImages = acceptsImages;
      this.promptPrice = promptPrice;
      this.completionPrice = completionPrice;
    }

    /** OpenRouter model ids are of the form provider/model-name. */
    public String provider() {
      int slash = id.indexOf('/');
      return slash > 0 ? id.substring(0, slash) : id;
    }

    public String shortId() {
      int slash = id.indexOf('/');
      return slash > 0 ? id.substring(slash + 1) : id;
    }
  }

  /** A chat message; imageDataUrl (a data:image/png;base64,... URL) is optional. */
  static public class Message {
    public final String role;
    public final String content;
    public final String imageDataUrl;

    public Message(String role, String content) {
      this(role, content, null);
    }

    public Message(String role, String content, String imageDataUrl) {
      this.role = role;
      this.content = content;
      this.imageDataUrl = imageDataUrl;
    }
  }

  static public class Reply {
    public final String content;
    public final int promptTokens, completionTokens;
    public final double cost;

    Reply(String content, int promptTokens, int completionTokens, double cost) {
      this.content = content;
      this.promptTokens = promptTokens;
      this.completionTokens = completionTokens;
      this.cost = cost;
    }
  }

  private final String apiKey;
  private volatile HttpURLConnection current;
  private volatile boolean cancelled = false;

  public OpenRouterClient(String apiKey) {
    this.apiKey = apiKey;
  }

  /** Lists all models available through OpenRouter.  Does not require an API key. */
  public List<Model> listModels() throws IOException {
    JsonObject response = request("GET", BASE_URL + "/models", null);
    List<Model> models = new ArrayList<>();
    JsonArray data = response.getAsJsonArray("data");
    if (data == null) return models;
    for (JsonElement el : data) {
      JsonObject m = el.getAsJsonObject();
      String id = m.get("id").getAsString();
      String name = m.has("name") ? m.get("name").getAsString() : id;
      int ctx = m.has("context_length") && !m.get("context_length").isJsonNull() ? m.get("context_length").getAsInt() : 0;
      boolean images = false;
      if (m.has("architecture") && m.get("architecture").isJsonObject()) {
        JsonObject arch = m.getAsJsonObject("architecture");
        if (arch.has("input_modalities") && arch.get("input_modalities").isJsonArray()) {
          for (JsonElement mod : arch.getAsJsonArray("input_modalities")) {
            if ("image".equals(mod.getAsString())) images = true;
          }
        }
      }
      double pp = -1, cp = -1;
      if (m.has("pricing") && m.get("pricing").isJsonObject()) {
        JsonObject pr = m.getAsJsonObject("pricing");
        pp = price(pr, "prompt");
        cp = price(pr, "completion");
      }
      models.add(new Model(id, name, ctx, images, pp, cp));
    }
    return models;
  }

  static private double price(JsonObject pricing, String key) {
    try {
      return pricing.has(key) ? Double.parseDouble(pricing.get(key).getAsString()) * 1e6 : -1;
    } catch (Exception ex) {
      return -1;
    }
  }

  /** Sends a chat completion request and returns the assistant's reply. */
  public Reply chat(String model, List<Message> messages, int maxTokens) throws IOException {
    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("max_tokens", maxTokens);
    JsonObject usage = new JsonObject();
    usage.addProperty("include", true);
    body.add("usage", usage);
    JsonArray msgs = new JsonArray();
    for (Message m : messages) {
      JsonObject jm = new JsonObject();
      jm.addProperty("role", m.role);
      if (m.imageDataUrl == null) {
        jm.addProperty("content", m.content);
      } else {
        JsonArray parts = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", m.content);
        parts.add(text);
        JsonObject img = new JsonObject();
        img.addProperty("type", "image_url");
        JsonObject url = new JsonObject();
        url.addProperty("url", m.imageDataUrl);
        img.add("image_url", url);
        parts.add(img);
        jm.add("content", parts);
      }
      msgs.add(jm);
    }
    body.add("messages", msgs);

    JsonObject response = request("POST", BASE_URL + "/chat/completions", gson.toJson(body));
    if (response.has("error")) {
      throw new IOException("OpenRouter error: " + errorMessage(response));
    }
    JsonArray choices = response.getAsJsonArray("choices");
    if (choices == null || choices.size() == 0) {
      throw new IOException("OpenRouter returned no choices");
    }
    JsonObject choice = choices.get(0).getAsJsonObject();
    JsonObject message = choice.getAsJsonObject("message");
    if (message == null || !message.has("content") || message.get("content").isJsonNull()
      || message.get("content").getAsString().isBlank()) {
      String finish = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
        ? choice.get("finish_reason").getAsString() : "unknown";
      throw new IOException("The model returned an empty reply (finish reason: " + finish
        + "). Reasoning models may need a larger max token setting.");
    }
    int pt = 0, ct = 0;
    double cost = 0;
    if (response.has("usage") && response.get("usage").isJsonObject()) {
      JsonObject u = response.getAsJsonObject("usage");
      if (u.has("prompt_tokens")) pt = u.get("prompt_tokens").getAsInt();
      if (u.has("completion_tokens")) ct = u.get("completion_tokens").getAsInt();
      if (u.has("cost") && !u.get("cost").isJsonNull()) cost = u.get("cost").getAsDouble();
    }
    return new Reply(message.get("content").getAsString(), pt, ct, cost);
  }

  /** Aborts the request in flight, which then throws an IOException. */
  public void cancel() {
    cancelled = true;
    HttpURLConnection c = current;
    if (c != null) c.disconnect();
  }

  public boolean isCancelled() {
    return cancelled;
  }

  private JsonObject request(String method, String url, String jsonBody) throws IOException {
    if (cancelled) throw new IOException("Cancelled");
    HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
    current = conn;
    try {
      conn.setRequestMethod(method);
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setRequestProperty("Accept", "application/json");
      // Optional attribution headers recognized by OpenRouter.
      conn.setRequestProperty("HTTP-Referer", "https://github.com/tracyscott/CkShaderStudio");
      conn.setRequestProperty("X-Title", "CkShaderStudio");
      if (apiKey != null && !apiKey.isEmpty()) {
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
      }
      if (jsonBody != null) {
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
          os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
      }
      int status = conn.getResponseCode();
      InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
      String text = is == null ? "" : readAll(is);
      JsonObject obj;
      try {
        obj = gson.fromJson(text, JsonObject.class);
      } catch (Exception ex) {
        obj = null;
      }
      if (status >= 400) {
        String msg = obj != null ? errorMessage(obj) : text;
        throw new IOException("HTTP " + status + ": " + msg);
      }
      if (obj == null) {
        throw new IOException("Unexpected response from OpenRouter: " + text);
      }
      return obj;
    } catch (IOException ex) {
      if (cancelled) throw new IOException("Cancelled");
      throw ex;
    } finally {
      current = null;
      conn.disconnect();
    }
  }

  static private String errorMessage(JsonObject obj) {
    JsonElement err = obj.get("error");
    if (err != null && err.isJsonObject() && err.getAsJsonObject().has("message")) {
      return err.getAsJsonObject().get("message").getAsString();
    }
    return String.valueOf(err != null ? err : obj);
  }

  static private String readAll(InputStream is) throws IOException {
    try (InputStream in = is) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8);
    }
  }
}
