package xyz.theforks.ckshaderstudio.ui;

import xyz.theforks.ckshaderstudio.Settings;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/** API key, limits and folder locations. */
public class SettingsDialog {

  private final Component parent;
  private final Settings settings;

  public SettingsDialog(Component parent, Settings settings) {
    this.parent = parent;
    this.settings = settings;
  }

  /** @return true if the user pressed OK (settings are updated but not saved) */
  public boolean showDialog() {
    JPasswordField key = new JPasswordField(settings.apiKey == null ? "" : settings.apiKey, 36);
    JSpinner maxTokens = new JSpinner(new SpinnerNumberModel(settings.maxTokens, 1000, 128000, 1000));
    JSpinner fixes = new JSpinner(new SpinnerNumberModel(settings.maxFixAttempts, 0, 6, 1));
    JTextField chromatik = new JTextField(settings.chromatikDir, 36);
    JTextField shaders = new JTextField(settings.shaderDir == null ? "" : settings.shaderDir, 36);
    shaders.setToolTipText("Leave empty for CkVShader's folder: <Chromatik>/Data/CkVShader/shaders");

    JPanel p = new JPanel(new GridBagLayout());
    int row = 0;
    row = add(p, row, "OpenRouter API key", key,
      "Stored in " + Settings.appDir() + "/settings.json. If empty, OPENROUTER_API_KEY is used.");
    row = add(p, row, "Max reply tokens", maxTokens, "Raise this for reasoning models that think before answering.");
    row = add(p, row, "Automatic fix attempts", fixes, "How many times to send compiler errors back to the model per request.");
    row = add(p, row, "Chromatik folder", chromatik, "Fixtures/ and Packages/ are read from here when loading .lxm models (restart to apply).");
    add(p, row, "Shader folder", shaders, "Where shaders are saved; includes (.vti) and textures/ are read from here.");

    int r = JOptionPane.showConfirmDialog(parent, p, "Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (r != JOptionPane.OK_OPTION) return false;
    settings.apiKey = new String(key.getPassword()).trim();
    settings.maxTokens = (Integer) maxTokens.getValue();
    settings.maxFixAttempts = (Integer) fixes.getValue();
    settings.chromatikDir = chromatik.getText().trim();
    settings.shaderDir = shaders.getText().trim();
    return true;
  }

  static private int add(JPanel p, int row, String label, JComponent field, String help) {
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(4, 4, 0, 4);
    c.anchor = GridBagConstraints.WEST;
    c.gridx = 0;
    c.gridy = row;
    p.add(new JLabel(label), c);
    c.gridx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;
    p.add(field, c);
    JLabel h = new JLabel("<html><span style='font-size:90%'>" + help + "</span></html>");
    h.setEnabled(false);
    c.gridy = row + 1;
    c.insets = new Insets(0, 4, 6, 4);
    p.add(h, c);
    return row + 2;
  }
}
