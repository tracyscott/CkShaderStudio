package xyz.theforks.ckshaderstudio.ui;

import xyz.theforks.ckshaderstudio.shader.IsfHeader;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sliders for the built-in CkVShader controls (speed, alfTh) and every INPUT in the shader's ISF
 * header.  Values are kept across recompiles when an input keeps its name.
 */
public class ParamsPanel extends JPanel {

  static private final int STEPS = 1000;

  public interface Listener {
    void changed();
  }

  private final JPanel list = new JPanel();
  private final Map<String, Float> values = new LinkedHashMap<>();
  private final Map<String, Float> defaults = new LinkedHashMap<>();
  private final Map<String, float[]> ranges = new LinkedHashMap<>();
  private Listener listener = () -> { };
  public final JButton resetButton = new JButton("Reset");
  public final JButton defaultsButton = new JButton("Save as defaults");

  public float speed = 1f;
  public float alphaThreshold = 0.1f;

  public ParamsPanel() {
    super(new BorderLayout());
    list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
    list.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    add(new javax.swing.JScrollPane(list), BorderLayout.CENTER);
    JPanel buttons = new JPanel();
    buttons.add(resetButton);
    buttons.add(defaultsButton);
    defaultsButton.setToolTipText("Write the current slider values into the shader's ISF DEFAULTs (creates a new version)");
    add(buttons, BorderLayout.SOUTH);
    resetButton.addActionListener(e -> reset());
    setPreferredSize(new Dimension(230, 300));
    rebuild(List.of());
  }

  public void setListener(Listener l) {
    this.listener = l;
  }

  /** Rebuilds the sliders for a newly compiled shader, keeping values of inputs that still exist. */
  public void setInputs(List<IsfHeader.Input> inputs) {
    Map<String, Float> old = new LinkedHashMap<>(values);
    values.clear();
    defaults.clear();
    ranges.clear();
    for (IsfHeader.Input in : inputs) {
      defaults.put(in.name, in.def);
      ranges.put(in.name, new float[] { in.min, in.max });
      Float prev = old.get(in.name);
      values.put(in.name, prev != null && prev >= in.min && prev <= in.max ? prev : in.def);
    }
    rebuild(inputs);
  }

  /** Current values of the shader's inputs, in declaration order. */
  public Map<String, Float> values() {
    return new LinkedHashMap<>(values);
  }

  /** Inputs whose value differs from the header DEFAULT. */
  public Map<String, Float> changedValues() {
    Map<String, Float> out = new LinkedHashMap<>();
    values.forEach((k, v) -> {
      Float d = defaults.get(k);
      if (d == null || Math.abs(d - v) > 1e-4 * Math.max(1f, Math.abs(d))) out.put(k, v);
    });
    return out;
  }

  public void reset() {
    values.putAll(defaults);
    speed = 1f;
    alphaThreshold = 0.1f;
    rebuildCurrent();
    listener.changed();
  }

  private List<IsfHeader.Input> currentInputs() {
    return ranges.entrySet().stream()
      .map(e -> new IsfHeader.Input(e.getKey(), defaults.get(e.getKey()), e.getValue()[0], e.getValue()[1]))
      .toList();
  }

  private void rebuildCurrent() {
    rebuild(currentInputs());
  }

  private void rebuild(List<IsfHeader.Input> inputs) {
    list.removeAll();
    list.add(slider("speed", 0f, 20f, speed, v -> speed = v, "CkVShader speed: fTime = seconds x speed"));
    list.add(slider("alfTh", -0.1f, 1f, alphaThreshold, v -> alphaThreshold = v,
      "CkVShader alpha threshold: LEDs darker than this fade to transparent"));
    list.add(Box.createVerticalStrut(8));
    if (inputs.isEmpty()) {
      JLabel none = new JLabel("No shader inputs");
      none.setEnabled(false);
      list.add(none);
    }
    for (IsfHeader.Input in : inputs) {
      String name = in.name;
      list.add(slider(name, in.min, in.max, values.getOrDefault(name, in.def), v -> values.put(name, v),
        "Default " + fmt(in.def) + ", range " + fmt(in.min) + " .. " + fmt(in.max)));
    }
    list.add(Box.createVerticalGlue());
    list.revalidate();
    list.repaint();
  }

  private JPanel slider(String name, float min, float max, float value, java.util.function.Consumer<Float> set, String tip) {
    JPanel row = new JPanel(new BorderLayout(4, 0));
    row.setAlignmentX(LEFT_ALIGNMENT);
    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
    JLabel label = new JLabel(name);
    label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
    JLabel val = new JLabel(fmt(value));
    val.setFont(val.getFont().deriveFont(11f));
    JPanel top = new JPanel(new BorderLayout());
    top.add(label, BorderLayout.WEST);
    top.add(val, BorderLayout.EAST);
    JSlider s = new JSlider(0, STEPS, toSteps(value, min, max));
    s.setToolTipText(tip);
    label.setToolTipText(tip);
    s.addChangeListener(e -> {
      float v = min + (max - min) * s.getValue() / (float) STEPS;
      set.accept(v);
      val.setText(fmt(v));
      listener.changed();
    });
    row.add(top, BorderLayout.NORTH);
    row.add(s, BorderLayout.CENTER);
    return row;
  }

  static private int toSteps(float v, float min, float max) {
    if (max <= min) return 0;
    return Math.round(Math.max(0f, Math.min(1f, (v - min) / (max - min))) * STEPS);
  }

  static String fmt(float v) {
    return String.format(Locale.US, Math.abs(v) >= 100 ? "%.1f" : "%.3f", v);
  }
}
