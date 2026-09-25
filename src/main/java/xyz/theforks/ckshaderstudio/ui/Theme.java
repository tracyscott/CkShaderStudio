package xyz.theforks.ckshaderstudio.ui;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;

/**
 * Dark theme built on Swing's Nimbus look and feel, so it needs no extra dependencies and looks
 * the same on every platform.  Colors used by custom-painted components live here too.
 */
public final class Theme {

  private Theme() {
  }

  // Surfaces, darkest to lightest.
  static public final Color BG = new Color(0x17181d);
  static public final Color PANEL = new Color(0x1f2027);
  static public final Color CONTROL = new Color(0x2a2c35);
  static public final Color BORDER = new Color(0x3a3d48);
  static public final Color EDITOR_BG = new Color(0x121318);
  static public final Color GUTTER_BG = new Color(0x0e0f13);

  // Text.
  static public final Color TEXT = new Color(0xdfe1e8);
  static public final Color TEXT_DIM = new Color(0x8b8e9a);
  static public final Color TEXT_FAINT = new Color(0x5f6270);

  // Accents.
  static public final Color ACCENT = new Color(0x3fc8c0);
  static public final Color ACCENT_2 = new Color(0xc05ad8);
  static public final Color SELECTION = new Color(0x2f4a6e);
  static public final Color OK = new Color(0x5cc27a);
  static public final Color ERROR = new Color(0xf06a6a);
  static public final Color WARN = new Color(0xe0a040);

  // Chat bubbles.
  static public final String CSS_USER_BG = "#243449";
  static public final String CSS_MODEL_BG = "#262730";

  static public String css(Color c) {
    return String.format("#%06x", c.getRGB() & 0xffffff);
  }

  /** Installs Nimbus with a dark palette.  Call on the Swing thread before creating components. */
  static public void install() {
    try {
      UIManager.put("control", PANEL);
      UIManager.put("info", CONTROL);
      UIManager.put("nimbusBase", new Color(0x23262f));
      UIManager.put("nimbusAlertYellow", WARN);
      UIManager.put("nimbusDisabledText", TEXT_FAINT);
      UIManager.put("nimbusFocus", new Color(0x2f8f8a));
      UIManager.put("nimbusGreen", OK);
      UIManager.put("nimbusInfoBlue", new Color(0x3b6ea5));
      UIManager.put("nimbusLightBackground", EDITOR_BG);
      UIManager.put("nimbusOrange", WARN);
      UIManager.put("nimbusRed", ERROR);
      UIManager.put("nimbusSelectedText", Color.WHITE);
      UIManager.put("nimbusSelectionBackground", SELECTION);
      UIManager.put("nimbusBorder", BORDER);
      UIManager.put("text", TEXT);
      UIManager.put("menu", PANEL);
      UIManager.put("menuText", TEXT);
      UIManager.put("textForeground", TEXT);
      UIManager.put("textBackground", EDITOR_BG);
      UIManager.put("textHighlight", SELECTION);
      UIManager.put("textHighlightText", Color.WHITE);
      UIManager.put("infoText", TEXT);
      UIManager.put("ToolTip.background", CONTROL);
      UIManager.put("ToolTip.foreground", TEXT);

      for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
        if ("Nimbus".equals(info.getName())) {
          UIManager.setLookAndFeel(info.getClassName());
          break;
        }
      }
      // Some keys must be set on the installed defaults to take effect.
      UIManager.getLookAndFeelDefaults().put("ScrollPane.background", PANEL);
      UIManager.getLookAndFeelDefaults().put("Viewport.background", PANEL);
      UIManager.getLookAndFeelDefaults().put("SplitPane.background", BG);
      UIManager.getLookAndFeelDefaults().put("TabbedPane.background", PANEL);
      UIManager.getLookAndFeelDefaults().put("ComboBox.background", new ColorUIResource(CONTROL));
      UIManager.getLookAndFeelDefaults().put("List.background", EDITOR_BG);
      UIManager.getLookAndFeelDefaults().put("Table.background", EDITOR_BG);
      UIManager.getLookAndFeelDefaults().put("Table.alternateRowColor", new Color(0x191a20));
      UIManager.getLookAndFeelDefaults().put("Table.gridColor", BORDER);
    } catch (Exception ex) {
      System.err.println("Could not install the dark theme: " + ex);
    }
  }
}
