package xyz.theforks.ckshaderstudio.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.undo.UndoManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shader source editor with line numbers, undo, a version picker and a compiler message area.
 * Clicking a "file.vtx:LINE" reference in the messages jumps to that line.
 */
public class CodePanel extends JPanel {

  public final JTextArea editor = new JTextArea();
  public final JTextArea messages = new JTextArea(5, 40);
  public final JComboBox<String> versions = new JComboBox<>();
  public final JButton compileButton = new JButton("Compile ▶");
  public final JLabel dirtyLabel = new JLabel(" ");
  private final UndoManager undo = new UndoManager();
  private String baseline = "";
  private Runnable onDirtyChange = () -> { };

  public CodePanel() {
    super(new BorderLayout());
    Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    editor.setFont(mono);
    editor.setTabSize(2);
    editor.setBackground(Theme.EDITOR_BG);
    editor.setForeground(Theme.TEXT);
    editor.setCaretColor(Color.WHITE);
    editor.setSelectionColor(Theme.SELECTION);
    editor.setSelectedTextColor(Color.WHITE);
    editor.getDocument().addUndoableEditListener(e -> undo.addEdit(e.getEdit()));
    int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    bind(editor, KeyStroke.getKeyStroke('Z', menu), () -> { if (undo.canUndo()) undo.undo(); });
    bind(editor, KeyStroke.getKeyStroke('Z', menu | java.awt.event.InputEvent.SHIFT_DOWN_MASK), () -> { if (undo.canRedo()) undo.redo(); });
    editor.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { onDirtyChange.run(); }
      public void removeUpdate(DocumentEvent e) { onDirtyChange.run(); }
      public void changedUpdate(DocumentEvent e) { }
    });

    JScrollPane scroll = new JScrollPane(editor);
    scroll.setRowHeaderView(new LineNumbers(editor));

    messages.setEditable(false);
    messages.setLineWrap(true);
    messages.setWrapStyleWord(true);
    messages.setFont(mono.deriveFont(12f));
    messages.addMouseListener(new java.awt.event.MouseAdapter() {
      @Override
      public void mouseClicked(java.awt.event.MouseEvent e) {
        jumpToReference(messages.viewToModel2D(e.getPoint()));
      }
    });

    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    top.add(new JLabel("Version"));
    versions.setPrototypeDisplayValue("v999 (hand edit, errors)");
    top.add(versions);
    top.add(compileButton);
    compileButton.setToolTipText("Compile the editor contents and preview them (" + (isMac() ? "⌘" : "Ctrl") + "+Enter)");
    top.add(dirtyLabel);
    dirtyLabel.setForeground(Theme.WARN);

    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, new JScrollPane(messages));
    split.setResizeWeight(0.8);
    split.setBorder(BorderFactory.createEmptyBorder());
    add(top, BorderLayout.NORTH);
    add(split, BorderLayout.CENTER);
  }

  static boolean isMac() {
    return System.getProperty("os.name", "").toLowerCase().contains("mac");
  }

  static void bind(JComponent c, KeyStroke ks, Runnable r) {
    String key = "action-" + ks;
    c.getInputMap(JComponent.WHEN_FOCUSED).put(ks, key);
    c.getActionMap().put(key, new AbstractAction() {
      public void actionPerformed(ActionEvent e) {
        r.run();
      }
    });
  }

  public void onDirtyChange(Runnable r) {
    this.onDirtyChange = r;
  }

  /** Replaces the editor contents; the text becomes the clean baseline. */
  public void setSource(String source) {
    baseline = source == null ? "" : source;
    editor.setText(baseline);
    editor.setCaretPosition(0);
    undo.discardAllEdits();
    onDirtyChange.run();
  }

  public String getSource() {
    return editor.getText();
  }

  public boolean isDirty() {
    return !editor.getText().equals(baseline);
  }

  public void setMessages(String errors, String warnings) {
    StringBuilder sb = new StringBuilder();
    if (errors != null && !errors.isBlank()) sb.append("ERRORS\n").append(errors.trim()).append("\n");
    if (warnings != null && !warnings.isBlank()) {
      if (sb.length() > 0) sb.append('\n');
      sb.append("Warnings\n").append(warnings.trim()).append("\n");
    }
    messages.setText(sb.length() == 0 ? "Compiled OK." : sb.toString());
    messages.setForeground(errors != null && !errors.isBlank() ? Theme.ERROR : Theme.TEXT);
    messages.setCaretPosition(0);
  }

  static private final Pattern REF = Pattern.compile("([A-Za-z0-9_\\-]+)\\.vtx:(\\d+)");

  private void jumpToReference(int offset) {
    try {
      String text = messages.getText();
      int lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
      int lineEnd = text.indexOf('\n', offset);
      if (lineEnd < 0) lineEnd = text.length();
      Matcher m = REF.matcher(text.substring(lineStart, lineEnd));
      if (m.find()) {
        int line = Integer.parseInt(m.group(2)) - 1;
        Element root = editor.getDocument().getDefaultRootElement();
        if (line >= 0 && line < root.getElementCount()) {
          Element el = root.getElement(line);
          editor.requestFocusInWindow();
          editor.select(el.getStartOffset(), Math.max(el.getStartOffset(), el.getEndOffset() - 1));
        }
      }
    } catch (Exception ignored) {
    }
  }

  /** Simple line number gutter. */
  static class LineNumbers extends JComponent {
    private final JTextArea text;

    LineNumbers(JTextArea text) {
      this.text = text;
      setFont(text.getFont());
      setForeground(Theme.TEXT_FAINT);
      setBackground(Theme.GUTTER_BG);
      setOpaque(true);
      text.getDocument().addDocumentListener(new DocumentListener() {
        public void insertUpdate(DocumentEvent e) { update(); }
        public void removeUpdate(DocumentEvent e) { update(); }
        public void changedUpdate(DocumentEvent e) { }
      });
    }

    private void update() {
      revalidate();
      repaint();
    }

    @Override
    public Dimension getPreferredSize() {
      int lines = Math.max(1, text.getLineCount());
      int digits = Math.max(3, String.valueOf(lines).length());
      FontMetrics fm = getFontMetrics(getFont());
      return new Dimension(fm.charWidth('0') * digits + 12, text.getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
      g.setColor(getBackground());
      Rectangle clip = g.getClipBounds();
      g.fillRect(clip.x, clip.y, clip.width, clip.height);
      g.setColor(getForeground());
      g.setFont(getFont());
      FontMetrics fm = g.getFontMetrics();
      try {
        int start = text.viewToModel2D(new java.awt.Point(0, clip.y));
        int end = text.viewToModel2D(new java.awt.Point(0, clip.y + clip.height));
        Element root = text.getDocument().getDefaultRootElement();
        int first = root.getElementIndex(start), last = root.getElementIndex(end);
        for (int i = first; i <= last; i++) {
          Rectangle r = text.modelToView2D(root.getElement(i).getStartOffset()).getBounds();
          String s = String.valueOf(i + 1);
          g.drawString(s, getWidth() - fm.stringWidth(s) - 6, r.y + fm.getAscent());
        }
      } catch (BadLocationException ignored) {
      }
    }
  }
}
