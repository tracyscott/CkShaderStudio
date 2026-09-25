package xyz.theforks.ckshaderstudio.ui;

import xyz.theforks.ckshaderstudio.ai.Session;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lists saved shader sessions (conversation plus versions) so one can be reopened and continued
 * with its full context.  Shows the selected session's transcript on the right.
 */
public class HistoryDialog extends JDialog {

  private final List<Session> all;
  private final List<Session> shown = new ArrayList<>();
  private final JTextField search = new JTextField(24);
  private final Model model = new Model();
  private final JTable table = new JTable(model);
  private final JEditorPane transcript = new JEditorPane();
  private final JLabel details = new JLabel(" ");
  private Session chosen;

  public HistoryDialog(JFrame owner, File sessionsDir, String currentName) {
    super(owner, "Shader history", true);
    all = Session.loadAll(sessionsDir);

    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setRowHeight(24);
    table.setFillsViewportHeight(true);
    table.getColumnModel().getColumn(0).setPreferredWidth(140);
    table.getColumnModel().getColumn(1).setPreferredWidth(120);
    table.getColumnModel().getColumn(2).setPreferredWidth(60);
    table.getColumnModel().getColumn(3).setPreferredWidth(60);
    table.getColumnModel().getColumn(4).setPreferredWidth(320);
    table.getSelectionModel().addListSelectionListener(e -> showSelected());
    table.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) choose();
      }
    });

    transcript.setEditable(false);
    transcript.setContentType("text/html");
    transcript.setBackground(Theme.EDITOR_BG);
    transcript.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    top.add(new JLabel("Search"));
    top.add(search);
    JLabel hint = new JLabel("Matches shader names and anything said in the conversation.");
    hint.setForeground(Theme.TEXT_DIM);
    top.add(hint);
    search.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { filter(); }
      public void removeUpdate(DocumentEvent e) { filter(); }
      public void changedUpdate(DocumentEvent e) { }
    });

    JPanel right = new JPanel(new BorderLayout());
    details.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
    details.setForeground(Theme.TEXT_DIM);
    right.add(details, BorderLayout.NORTH);
    right.add(new JScrollPane(transcript), BorderLayout.CENTER);

    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table), right);
    split.setResizeWeight(0.55);

    JButton open = new JButton("Continue this session");
    JButton cancel = new JButton("Cancel");
    open.addActionListener(e -> choose());
    cancel.addActionListener(e -> dispose());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    JLabel where = new JLabel("Saved in " + sessionsDir);
    where.setForeground(Theme.TEXT_FAINT);
    JPanel bottom = new JPanel(new BorderLayout());
    bottom.add(where, BorderLayout.WEST);
    buttons.add(cancel);
    buttons.add(open);
    bottom.add(buttons, BorderLayout.EAST);
    bottom.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

    getContentPane().add(top, BorderLayout.NORTH);
    getContentPane().add(split, BorderLayout.CENTER);
    getContentPane().add(bottom, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(open);
    setSize(new Dimension(1150, 640));
    setLocationRelativeTo(owner);

    filter();
    for (int i = 0; i < shown.size(); i++) {
      if (shown.get(i).shaderName != null && shown.get(i).shaderName.equals(currentName)) {
        table.setRowSelectionInterval(i, i);
      }
    }
    if (table.getSelectedRow() < 0 && !shown.isEmpty()) table.setRowSelectionInterval(0, 0);
  }

  /** Shows the dialog; returns the session to continue, or null. */
  public Session choose(boolean show) {
    setVisible(true);
    return chosen;
  }

  private void choose() {
    int row = table.getSelectedRow();
    if (row >= 0 && row < shown.size()) {
      chosen = shown.get(row);
      dispose();
    }
  }

  private void filter() {
    String q = search.getText().trim().toLowerCase(Locale.ROOT);
    shown.clear();
    for (Session s : all) {
      if (q.isEmpty() || matches(s, q)) shown.add(s);
    }
    model.fireTableDataChanged();
    if (!shown.isEmpty()) table.setRowSelectionInterval(0, 0); else showSelected();
  }

  static private boolean matches(Session s, String q) {
    if (s.shaderName != null && s.shaderName.toLowerCase(Locale.ROOT).contains(q)) return true;
    for (Session.Entry e : s.entries) {
      if (e.text != null && e.text.toLowerCase(Locale.ROOT).contains(q)) return true;
    }
    return false;
  }

  private void showSelected() {
    int row = table.getSelectedRow();
    if (row < 0 || row >= shown.size()) {
      transcript.setText("");
      details.setText(" ");
      return;
    }
    Session s = shown.get(row);
    Session.Version cur = s.current();
    String model = s.modelFile == null ? "" : new File(s.modelFile).getName()
      + (s.viewSelector != null && !s.viewSelector.isEmpty() ? " / " + s.viewSelector : "");
    details.setText("<html>" + (cur == null ? "no versions" : "current " + cur.label())
      + (model.isEmpty() ? "" : " · designed on " + model)
      + (s.modelId == null ? "" : " · " + s.modelId)
      + (s.totalCost > 0 ? String.format(Locale.US, " · $%.3f", s.totalCost) : "") + "</html>");
    transcript.setText(ChatPanel.toHtml(s.entries, s));
    transcript.setCaretPosition(transcript.getDocument().getLength());
  }

  private class Model extends AbstractTableModel {
    private final String[] cols = { "Shader", "Last activity", "Versions", "Requests", "First request" };
    private final SimpleDateFormat date = new SimpleDateFormat("MMM d, HH:mm");

    public int getRowCount() {
      return shown.size();
    }

    public int getColumnCount() {
      return cols.length;
    }

    @Override
    public String getColumnName(int c) {
      return cols[c];
    }

    public Object getValueAt(int r, int c) {
      Session s = shown.get(r);
      return switch (c) {
        case 0 -> s.shaderName;
        case 1 -> date.format(new Date(s.updated));
        case 2 -> s.versions.size();
        case 3 -> s.messageCount();
        default -> s.title();
      };
    }
  }
}
