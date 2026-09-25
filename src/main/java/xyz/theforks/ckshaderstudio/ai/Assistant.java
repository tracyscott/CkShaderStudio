package xyz.theforks.ckshaderstudio.ai;

import xyz.theforks.ckshaderstudio.shader.ShaderCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Runs one conversational turn: sends the user's request with the current shader, extracts the
 * returned shader, validates it, and asks the model to repair errors a few times if needed.
 * Blocking; run it on a worker thread.
 */
public class Assistant {

  public interface Listener {
    void status(String message);

    /** Called for every transcript entry added during the turn. */
    void entryAdded(Session.Entry entry);
  }

  /** What the turn produced. */
  static public class Result {
    /** The final check of the newest version, or null if the model returned no shader. */
    public final ShaderCheck check;
    public final Session.Version version;

    Result(ShaderCheck check, Session.Version version) {
      this.check = check;
      this.version = version;
    }
  }

  private final OpenRouterClient client;
  private final String modelId;
  private final int maxTokens;
  private final int maxFixAttempts;

  public Assistant(OpenRouterClient client, String modelId, int maxTokens, int maxFixAttempts) {
    this.client = client;
    this.modelId = modelId;
    this.maxTokens = maxTokens;
    this.maxFixAttempts = maxFixAttempts;
  }

  /**
   * @param system system prompt
   * @param session the session; entries and versions are appended
   * @param userText what the user typed
   * @param sliderValues current slider values that differ from the shader's defaults, may be empty
   * @param imageDataUrl optional PNG snapshot of the preview
   * @param validator compiles a source; any program in an intermediate result is released by the caller
   * @param discard called with every check that is superseded, so its GL program can be freed
   */
  public Result turn(String system, Session session, String userText, Map<String, Float> sliderValues,
                     String imageDataUrl, Function<String, ShaderCheck> validator,
                     java.util.function.Consumer<ShaderCheck> discard, Listener listener) throws Exception {
    Session.Entry userEntry = session.add(Session.ROLE_USER, userText);
    userEntry.hadImage = imageDataUrl != null;
    listener.entryAdded(userEntry);

    String context = currentContext(session, sliderValues, imageDataUrl != null);
    ShaderCheck check = null;
    Session.Version version = null;
    for (int attempt = 0; attempt <= maxFixAttempts; attempt++) {
      if (client.isCancelled()) throw new java.io.IOException("Cancelled");
      listener.status(attempt == 0 ? "Asking " + modelId + "..."
        : "Asking the model to fix errors (attempt " + attempt + " of " + maxFixAttempts + ")...");
      List<OpenRouterClient.Message> messages = buildMessages(system, session, context, attempt == 0 ? imageDataUrl : null);
      OpenRouterClient.Reply reply = client.chat(modelId, messages, maxTokens);
      session.totalCost += reply.cost;
      session.totalPromptTokens += reply.promptTokens;
      session.totalCompletionTokens += reply.completionTokens;

      String source = ShaderPrompt.extractShader(reply.content);
      Session.Entry answer = session.add(Session.ROLE_ASSISTANT, reply.content);
      if (source == null) {
        listener.entryAdded(answer);
        if (attempt == 0 && !looksLikeItMeantToWriteCode(reply.content)) {
          // A plain answer to a question; nothing to validate.
          return new Result(null, null);
        }
        if (attempt == maxFixAttempts) break;
        Session.Entry fix = session.add(Session.ROLE_FIX,
          "Your reply did not contain a ```glsl code block with the complete shader file. Return the complete file.");
        listener.entryAdded(fix);
        continue;
      }
      listener.status("Compiling version...");
      if (check != null) discard.accept(check);
      check = validator.apply(source);
      version = session.addVersion(source, attempt == 0 ? "ai" : "fix");
      version.ok = check.ok();
      version.errors = check.errors;
      answer.version = version.number;
      listener.entryAdded(answer);
      if (check.ok()) break;
      if (attempt == maxFixAttempts) break;
      Session.Entry fix = session.add(Session.ROLE_FIX,
        "The shader failed validation with these errors. Return the complete corrected file.\n\n" + check.errors);
      listener.entryAdded(fix);
    }
    return new Result(check, version);
  }

  static private boolean looksLikeItMeantToWriteCode(String reply) {
    return reply.contains("#version") || reply.contains("outColor") || reply.contains("```");
  }

  /** Text appended to the user's message: the shader being revised and the slider settings. */
  static private String currentContext(Session session, Map<String, Float> sliders, boolean image) {
    StringBuilder sb = new StringBuilder();
    Session.Version cur = session.current();
    if (cur != null) {
      Session.Version lastAi = session.lastModelVersion();
      boolean edited = lastAi == null || !lastAi.source.equals(cur.source);
      sb.append("\n\n---\nCurrent shader (version ").append(cur.number).append(")");
      if (edited && lastAi != null) sb.append(", which differs from your last reply (edited by hand or an earlier version was restored)");
      if (!cur.ok && cur.errors != null) sb.append(". It currently has these errors:\n").append(cur.errors);
      sb.append(":\n```glsl\n").append(cur.source.trim()).append("\n```\n");
    }
    if (sliders != null && !sliders.isEmpty()) {
      sb.append("\nThe user has moved these sliders away from their defaults: ");
      List<String> parts = new ArrayList<>();
      sliders.forEach((k, v) -> parts.add(k + "=" + String.format(java.util.Locale.US, "%.3f", v)));
      sb.append(String.join(", ", parts)).append(". Consider adopting them as the new DEFAULTs if they relate to the request.\n");
    }
    if (image) {
      sb.append("\nAttached is a snapshot of the current preview: each dot is one LED, drawn at its real position ")
        .append("in 3D and seen by the preview camera over a near-black background; unlit LEDs are drawn dark grey.\n");
    }
    return sb.toString();
  }

  /**
   * History with code removed from all but the most recent assistant reply, to keep requests small.
   * The context (current shader, sliders) is attached to the most recent user message only.
   */
  static List<OpenRouterClient.Message> buildMessages(String system, Session session, String context, String image) {
    List<OpenRouterClient.Message> out = new ArrayList<>();
    out.add(new OpenRouterClient.Message("system", system));
    int lastAssistant = -1, lastUser = -1;
    for (int i = 0; i < session.entries.size(); i++) {
      String role = session.entries.get(i).role;
      if (Session.ROLE_ASSISTANT.equals(role)) lastAssistant = i;
      if (Session.ROLE_USER.equals(role)) lastUser = i;
    }
    for (int i = 0; i < session.entries.size(); i++) {
      Session.Entry e = session.entries.get(i);
      switch (e.role) {
        case Session.ROLE_USER -> {
          if (i == lastUser) {
            out.add(new OpenRouterClient.Message("user", e.text + context, image));
          } else {
            out.add(new OpenRouterClient.Message("user", e.text));
          }
        }
        case Session.ROLE_FIX -> out.add(new OpenRouterClient.Message("user", e.text));
        case Session.ROLE_ASSISTANT -> {
          // Only replies after the latest user message keep their code (the model is fixing them).
          if (i == lastAssistant && i > lastUser) {
            out.add(new OpenRouterClient.Message("assistant", e.text));
          } else {
            String placeholder = e.version > 0 ? "[shader version " + e.version + " omitted]" : "[code omitted]";
            out.add(new OpenRouterClient.Message("assistant", ShaderPrompt.withoutCode(e.text, placeholder)));
          }
        }
        default -> {
        }
      }
    }
    return out;
  }
}
