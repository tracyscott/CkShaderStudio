package xyz.theforks.ckshaderstudio.ui;

import xyz.theforks.ckshaderstudio.ai.Session;
import xyz.theforks.ckshaderstudio.ai.ShaderPrompt;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The conversation with the model: transcript, prompt box and model picker.  Shader code is not
 * repeated in the transcript; replies show which version they produced instead.
 */
public class ChatPanel extends JPanel {

  public final JComboBox<String> provider = new JComboBox<>();
  public final JComboBox<String> model = new JComboBox<>();
  public final JButton refreshModels = new JButton("↻");
  public final JTextArea input = new JTextArea(4, 30);
  public final JButton send = new JButton("Send");
  public final JButton stop = new JButton("Stop");
  public final JButton newChat = new JButton("New chat");
  public final JButton history = new JButton("History…");
  public final JCheckBox attachPreview = new JCheckBox("Attach preview image");
  public final JLabel status = new JLabel(" ");
  public final JLabel usage = new JLabel(" ");
  private final JEditorPane transcript = new JEditorPane();
  private final JScrollPane transcriptScroll;

  public ChatPanel() {
    super(new BorderLayout(0, 4));
    setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));

    JPanel pick = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(1, 2, 1, 2);
    c.fill = GridBagConstraints.HORIZONTAL;
    c.gridx = 0; c.gridy = 0; pick.add(new JLabel("Provider"), c);
    c.gridx = 1; c.weightx = 1; pick.add(provider, c);
    c.gridx = 2; c.weightx = 0; pick.add(refreshModels, c);
    c.gridx = 0; c.gridy = 1; pick.add(new JLabel("Model"), c);
    c.gridx = 1; c.gridwidth = 2; c.weightx = 1; pick.add(model, c);
    refreshModels.setToolTipText("Reload the model list from OpenRouter");
    provider.setPrototypeDisplayValue("anthropic-xxxxxxxx");
    model.setPrototypeDisplayValue("claude-sonnet-4.5-xxxxxxxxxxxx");
    add(pick, BorderLayout.NORTH);

    transcript.setEditable(false);
    transcript.setContentType("text/html");
    transcript.setBackground(Theme.EDITOR_BG);
    transcript.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    transcriptScroll = new JScrollPane(transcript);
    transcriptScroll.setPreferredSize(new Dimension(380, 400));
    add(transcriptScroll, BorderLayout.CENTER);

    input.setLineWrap(true);
    input.setWrapStyleWord(true);
    input.setToolTipText("Describe the shader or the change you want. " + (CodePanel.isMac() ? "⌘" : "Ctrl") + "+Enter sends.");
    int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    CodePanel.bind(input, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, menu), () -> send.doClick());

    JPanel bottom = new JPanel(new BorderLayout(0, 2));
    bottom.add(new JScrollPane(input), BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(send);
    buttons.add(stop);
    buttons.add(newChat);
    buttons.add(history);
    history.setToolTipText("Browse earlier shader sessions and continue one where you left off");
    attachPreview.setToolTipText("Send a snapshot of the preview with the message (vision-capable models only)");
    stop.setEnabled(false);
    JPanel statusRow = new JPanel(new BorderLayout());
    statusRow.add(status, BorderLayout.CENTER);
    statusRow.add(usage, BorderLayout.EAST);
    usage.setEnabled(false);
    JPanel south = new JPanel(new BorderLayout());
    south.add(buttons, BorderLayout.NORTH);
    south.add(attachPreview, BorderLayout.CENTER);
    south.add(statusRow, BorderLayout.SOUTH);
    bottom.add(south, BorderLayout.SOUTH);
    add(bottom, BorderLayout.SOUTH);
    render(List.of(), null);
  }

  public void setStatus(String text) {
    status.setText(text);
    status.setToolTipText(text);
  }

  public void setBusy(boolean busy) {
    send.setEnabled(!busy);
    stop.setEnabled(busy);
    newChat.setEnabled(!busy);
    history.setEnabled(!busy);
  }

  /** Renders the transcript.  Pass a snapshot of the entries, not the live list. */
  public void render(List<Session.Entry> entries, Session session) {
    transcript.setText(toHtml(entries, session));
    SwingUtilities.invokeLater(() -> {
      var bar = transcriptScroll.getVerticalScrollBar();
      bar.setValue(bar.getMaximum());
    });
  }

  /** The transcript as HTML for a JEditorPane. */
  static String toHtml(List<Session.Entry> entries, Session session) {
    StringBuilder html = new StringBuilder();
    html.append("<html><body style='font-family:sans-serif;font-size:12pt;margin:6px;background:" + Theme.css(Theme.EDITOR_BG) + ";color:" + Theme.css(Theme.TEXT) + "'>");
    if (entries.isEmpty()) {
      html.append("<p style='color:#8b8e9a'>Describe the shader you want, e.g. <i>\"slow purple and teal waves rising up ")
        .append("the model, with a slider for wave width\"</i>. Each reply is compiled and previewed; keep chatting to refine it. ")
        .append("You can also edit the code by hand and the model will pick up your edits.</p>");
    }
    SimpleDateFormat time = new SimpleDateFormat("HH:mm");
    for (Session.Entry e : entries) {
      String when = time.format(new Date(e.time));
      switch (e.role) {
        case Session.ROLE_USER -> html.append("<div style='background:" + Theme.CSS_USER_BG + ";color:#e6ebf2;padding:6px;margin:6px 0 2px 30px'>")
          .append("<b>You</b> <span style='color:#8b8e9a'>").append(when).append(e.hadImage ? " · with preview image" : "")
          .append("</span><br>").append(escape(e.text)).append("</div>");
        case Session.ROLE_ASSISTANT -> {
          String prose = ShaderPrompt.withoutCode(e.text, "\u0000");
          String versionNote = "";
          if (e.version > 0) {
            Session.Version v = session == null ? null : session.version(e.version);
            boolean ok = v != null && v.ok;
            versionNote = "<div style='color:" + (ok ? Theme.css(Theme.OK) : Theme.css(Theme.ERROR)) + "'>▸ shader v" + e.version
              + (ok ? " compiled OK" : " has errors") + "</div>";
          }
          html.append("<div style='background:" + Theme.CSS_MODEL_BG + ";color:#dfe1e8;padding:6px;margin:6px 30px 2px 0'>")
            .append("<b>Model</b> <span style='color:#8b8e9a'>").append(when).append("</span><br>")
            .append(escape(prose).replace("\u0000", "").trim()).append(versionNote).append("</div>");
        }
        case Session.ROLE_FIX -> html.append("<div style='color:#d09a40;font-size:10pt;margin:2px 0 2px 12px'>↻ ")
          .append(escape(firstLine(e.text))).append("</div>");
        default -> html.append("<div style='color:#8b8e9a;font-size:10pt;margin:4px 0'>")
          .append(escape(e.text)).append("</div>");
      }
    }
    html.append("</body></html>");
    return html.toString();
  }

  static private String firstLine(String s) {
    String[] parts = s.split("\n");
    String first = parts[0];
    for (int i = 1; i < parts.length; i++) {
      if (!parts[i].isBlank()) {
        first += " " + parts[i].trim();
        break;
      }
    }
    return first.length() > 220 ? first.substring(0, 220) + "..." : first;
  }

  static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
  }
}
