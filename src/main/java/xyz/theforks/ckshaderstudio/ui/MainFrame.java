package xyz.theforks.ckshaderstudio.ui;

import xyz.theforks.ckshaderstudio.Settings;
import xyz.theforks.ckshaderstudio.ai.Assistant;
import xyz.theforks.ckshaderstudio.ai.OpenRouterClient;
import xyz.theforks.ckshaderstudio.ai.Session;
import xyz.theforks.ckshaderstudio.ai.ShaderPrompt;
import xyz.theforks.ckshaderstudio.model.ModelLoader;
import xyz.theforks.ckshaderstudio.model.ModelSummary;
import xyz.theforks.ckshaderstudio.shader.AudioSim;
import xyz.theforks.ckshaderstudio.shader.GLRunner;
import xyz.theforks.ckshaderstudio.shader.IsfHeader;
import xyz.theforks.ckshaderstudio.shader.ShaderCheck;
import xyz.theforks.ckshaderstudio.shader.ShaderSource;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Main window: chat on the left, live preview and sliders on the right, code editor and notes
 * below.  Owns the GL runner, the model loader and the current session.
 */
public class MainFrame extends JFrame {

  static private final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_\\-]+");
  static private final String OPEN_MODEL = "Open model file (.lxm)…";
  static private final String NO_MODELS = "(none)";

  private final Settings settings;
  private final GLRunner runner = new GLRunner();
  private boolean glReady = false;
  private final ModelLoader loader;
  private final AudioSim audio = new AudioSim();

  private final ExecutorService background = Executors.newSingleThreadExecutor(daemon("Background"));
  private final ExecutorService aiThread = Executors.newSingleThreadExecutor(daemon("Assistant"));
  private final ExecutorService frameThread = Executors.newSingleThreadExecutor(daemon("Frames"));
  private final AtomicBoolean framePending = new AtomicBoolean(false);

  // UI
  private final ChatPanel chat = new ChatPanel();
  private final PreviewPanel preview = new PreviewPanel();
  private final ParamsPanel params = new ParamsPanel();
  private final CodePanel code = new CodePanel();
  private final JTextArea notes = new JTextArea();
  private final JTextArea info = new JTextArea();
  private final JComboBox<String> modelBox = new JComboBox<>();
  private final JComboBox<String> viewBox = new JComboBox<>();
  private final JTextField nameField = new JTextField(14);
  private final JCheckBox texMode = new JCheckBox("Tex");
  private final JComboBox<String> textureBox = new JComboBox<>();
  private final JCheckBox audioBox = new JCheckBox("Audio sim", true);
  private final JButton playButton = new JButton("Pause");
  private final JCheckBox autoSave = new JCheckBox("Auto-save");
  private final JLabel savedLabel = new JLabel(" ");

  // State (touched on the Swing thread unless noted)
  private Session session = new Session();
  private ModelLoader.Loaded loadedModel;
  private ModelLoader.ViewPoints viewPoints;
  private String geometry = "";
  private volatile GLRunner.Program program;
  private IsfHeader header;
  private boolean updatingCombos = false;
  private OpenRouterClient activeClient;
  private final Map<String, List<OpenRouterClient.Model>> modelsByProvider = new TreeMap<>();

  // Time
  private boolean playing = true;
  private double elapsed = 0;
  private long lastTick = System.nanoTime();
  private final long startNanos = System.nanoTime();
  private int frames = 0;
  private long fpsStart = System.nanoTime();
  private String fpsText = "";

  public MainFrame(Settings settings) {
    super("CkShaderStudio");
    this.settings = settings;
    this.loader = new ModelLoader(settings.chromatikDir());
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        quit();
      }
    });
    buildUI();
    wire();
    setSize(1500, 950);
    setLocationRelativeTo(null);
  }

  static private java.util.concurrent.ThreadFactory daemon(String name) {
    return r -> {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    };
  }

  // ------------------------------------------------------------------ layout

  private void buildUI() {
    ToolRow bar = new ToolRow();
    bar.add(new JLabel(" Model "));
    modelBox.setPrototypeDisplayValue("InterlaceV1.lxm-xxxxxxxxx");
    bar.add(modelBox);
    bar.add(new JLabel("  View "));
    viewBox.setPrototypeDisplayValue("View \"Hyperboloid\" (17280 points)");
    bar.add(viewBox);
    bar.addSeparator();
    texMode.setToolTipText("Target CkVShaderTex: the shader may sample textureSampler and audioTexture");
    bar.add(texMode);
    textureBox.setPrototypeDisplayValue("ballredarms-xxxx");
    textureBox.setToolTipText("Texture bound to textureSampler (from " + settings.texturesDir() + ")");
    bar.add(textureBox);
    bar.add(audioBox);
    audioBox.setToolTipText("Feed a simulated 120 BPM track into audioTexture");
    bar.addSeparator();
    bar.add(new JLabel("Time "));
    bar.add(playButton);
    JButton restartTime = new JButton("Restart");
    restartTime.setToolTipText("Restart time at 0");
    bar.add(restartTime);
    JSlider pointSize = new JSlider(1, 9, Math.round(settings.pointSize));
    pointSize.setMaximumSize(new Dimension(90, 28));
    pointSize.setToolTipText("LED dot size");
    bar.add(new JLabel("  Dot size "));
    bar.add(pointSize);
    ToolRow fileBar = new ToolRow();
    fileBar.add(new JLabel(" Shader name "));
    nameField.setMinimumSize(new Dimension(160, 26));
    nameField.setPreferredSize(new Dimension(200, 26));
    nameField.setMaximumSize(new Dimension(220, 28));
    nameField.setToolTipText("Saved as <name>.vtx in " + settings.shaderDir());
    fileBar.add(nameField);
    JButton newButton = new JButton("New");
    JButton openButton = new JButton("Open…");
    JButton saveButton = new JButton("Save");
    newButton.setToolTipText("Start a new shader and conversation");
    openButton.setToolTipText("Open an existing .vtx shader to iterate on");
    saveButton.setToolTipText("Save to " + settings.shaderDir() + " (" + (CodePanel.isMac() ? "⌘" : "Ctrl") + "+S)");
    fileBar.add(newButton);
    fileBar.add(openButton);
    fileBar.add(saveButton);
    fileBar.add(autoSave);
    autoSave.setToolTipText("Save automatically whenever the model returns a shader that compiles");
    fileBar.addSeparator();
    fileBar.add(savedLabel);
    fileBar.add(javax.swing.Box.createHorizontalGlue());
    JButton settingsButton = new JButton("Settings…");
    bar.add(javax.swing.Box.createHorizontalGlue());
    fileBar.add(settingsButton);

    newButton.addActionListener(e -> newShader());
    openButton.addActionListener(e -> openShader());
    saveButton.addActionListener(e -> save(true));
    restartTime.addActionListener(e -> elapsed = 0);
    playButton.addActionListener(e -> {
      playing = !playing;
      playButton.setText(playing ? "Pause" : "Play");
    });
    pointSize.addChangeListener(e -> {
      settings.pointSize = pointSize.getValue();
      preview.setPointSize(settings.pointSize);
    });
    settingsButton.addActionListener(e -> showSettings());
    preview.setPointSize(settings.pointSize);

    notes.setLineWrap(true);
    notes.setWrapStyleWord(true);
    notes.setFont(notes.getFont().deriveFont(13f));
    JPanel notesPanel = new JPanel(new BorderLayout());
    JLabel notesHelp = new JLabel("<html>Notes about this installation for the model (shape, how it is seen, what looks good). "
      + "Saved per model file and included in every request.</html>");
    notesHelp.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
    notesPanel.add(notesHelp, BorderLayout.NORTH);
    notesPanel.add(new JScrollPane(notes), BorderLayout.CENTER);

    info.setEditable(false);
    info.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab("Code", code);
    tabs.addTab("Installation notes", notesPanel);
    tabs.addTab("Model & paths", new JScrollPane(info));

    JSplitPane previewSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, preview, params);
    previewSplit.setResizeWeight(1.0);
    JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, previewSplit, tabs);
    right.setResizeWeight(0.55);
    JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chat, right);
    main.setDividerLocation(430);

    JPanel bars = new JPanel();
    bars.setLayout(new javax.swing.BoxLayout(bars, javax.swing.BoxLayout.Y_AXIS));
    bar.setAlignmentX(LEFT_ALIGNMENT);
    fileBar.setAlignmentX(LEFT_ALIGNMENT);
    bars.add(bar);
    bars.add(fileBar);
    getContentPane().add(bars, BorderLayout.NORTH);
    getContentPane().add(main, BorderLayout.CENTER);

    int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    getRootPane().registerKeyboardAction(e -> save(true), KeyStroke.getKeyStroke(KeyEvent.VK_S, menu),
      javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
    CodePanel.bind(code.editor, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menu), this::compileEditor);
  }

  private void wire() {
    chat.send.addActionListener(e -> sendPrompt());
    chat.stop.addActionListener(e -> {
      OpenRouterClient c = activeClient;
      if (c != null) c.cancel();
    });
    chat.newChat.addActionListener(e -> newConversation());
    chat.history.addActionListener(e -> showHistory());
    chat.refreshModels.addActionListener(e -> refreshModels());
    chat.provider.addActionListener(e -> onProviderChanged());
    chat.model.addActionListener(e -> {
      if (!updatingCombos && chat.model.getSelectedItem() != null && !NO_MODELS.equals(chat.model.getSelectedItem())) {
        settings.model = selectedModelId();
        settings.save();
        updateAttachAvailability();
      }
    });
    chat.attachPreview.setSelected(settings.attachPreview);
    chat.attachPreview.addActionListener(e -> settings.attachPreview = chat.attachPreview.isSelected());

    code.compileButton.addActionListener(e -> compileEditor());
    code.onDirtyChange(() -> code.dirtyLabel.setText(code.isDirty() ? "edited — Compile to preview" : " "));
    code.versions.addActionListener(e -> {
      if (updatingCombos) return;
      int idx = code.versions.getSelectedIndex();
      if (idx >= 0 && idx < session.versions.size()) {
        Session.Version v = session.versions.get(idx);
        if (v.number != session.currentVersion || code.isDirty()) {
          session.currentVersion = v.number;
          showVersion(v);
          saveSession();
        }
      }
    });

    params.setListener(() -> preview.setAlphaThreshold(params.alphaThreshold));
    params.defaultsButton.addActionListener(e -> saveSliderDefaults());

    modelBox.addActionListener(e -> {
      if (updatingCombos) return;
      Object sel = modelBox.getSelectedItem();
      if (OPEN_MODEL.equals(sel)) {
        chooseModelFile();
      } else if (sel != null) {
        String s = sel.toString();
        for (String syn : ModelLoader.SYNTHETIC) {
          if (syn.equals(s)) {
            useSynthetic(s);
            return;
          }
        }
        if (loadedModel == null || !loadedModel.file.getName().equals(s)) {
          loadModel(new File(settings.lastModelFile));
        }
      }
    });
    viewBox.addActionListener(e -> {
      if (updatingCombos || loadedModel == null) return;
      String label = (String) viewBox.getSelectedItem();
      if (label != null) selectView(label);
    });

    texMode.addActionListener(e -> {
      session.textureMode = texMode.isSelected();
      textureBox.setEnabled(texMode.isSelected());
      audioBox.setEnabled(texMode.isSelected());
    });
    textureBox.addActionListener(e -> {
      if (updatingCombos) return;
      loadTexture((String) textureBox.getSelectedItem());
    });
    audioBox.addActionListener(e -> audio.enabled = audioBox.isSelected());

    notes.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
      public void insertUpdate(javax.swing.event.DocumentEvent e) { storeNotes(); }
      public void removeUpdate(javax.swing.event.DocumentEvent e) { storeNotes(); }
      public void changedUpdate(javax.swing.event.DocumentEvent e) { }
    });
  }

  // ------------------------------------------------------------------ startup

  /** Initializes GL, restores the last model and shader, and starts the preview loop. */
  public void start() {
    setVisible(true);
    status("Starting OpenGL...");
    background.submit(() -> {
      try {
        runner.init();
        glReady = true;
        ui(() -> status("OpenGL ready: " + runner.glInfo()));
      } catch (Throwable t) {
        ui(() -> {
          status("OpenGL 3 is not available; previews and compile checks are disabled.");
          JOptionPane.showMessageDialog(this, "Could not create an OpenGL 3 context:\n" + t,
            "CkShaderStudio", JOptionPane.ERROR_MESSAGE);
        });
      }
    });
    refreshTextures();
    textureBox.setEnabled(false);
    audioBox.setEnabled(false);
    rebuildModelBox(null);
    File last = settings.lastModelFile == null || settings.lastModelFile.isEmpty() ? null : new File(settings.lastModelFile);
    if (last != null && last.isFile()) {
      loadModel(last);
    } else {
      useSynthetic(ModelLoader.SYNTHETIC[1]);
    }
    restoreLastSession();
    refreshModels();
    new Timer(33, e -> tick()).start();
  }

  private void restoreLastSession() {
    String name = settings.lastShaderName;
    if (name != null && VALID_NAME.matcher(name).matches()) {
      File f = sessionFile(name);
      if (f.isFile()) {
        try {
          Session resumed = Session.load(f);
          setSession(resumed);
          status("Resumed " + name + ". Use History… to continue a different shader.");
          return;
        } catch (Exception ex) {
          status("Could not resume " + f + ": " + ex.getMessage());
        }
      }
    }
    setSession(new Session());
  }

  // ------------------------------------------------------------------ models

  private void rebuildModelBox(String loadedName) {
    updatingCombos = true;
    DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
    if (loadedName != null) m.addElement(loadedName);
    for (String s : ModelLoader.SYNTHETIC) m.addElement(s);
    m.addElement(OPEN_MODEL);
    modelBox.setModel(m);
    updatingCombos = false;
  }

  private void chooseModelFile() {
    JFileChooser fc = new JFileChooser(new File(settings.chromatikDir(), "Models"));
    fc.setFileFilter(new FileNameExtensionFilter("Chromatik model (*.lxm)", "lxm"));
    if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      loadModel(fc.getSelectedFile());
    } else {
      // Put the combo back on whatever is showing.
      updatingCombos = true;
      modelBox.setSelectedIndex(0);
      updatingCombos = false;
    }
  }

  private void loadModel(File f) {
    status("Loading " + f.getName() + "...");
    background.submit(() -> {
      try {
        ModelLoader.Loaded loaded = loader.load(f);
        ui(() -> {
          loadedModel = loaded;
          settings.lastModelFile = f.getAbsolutePath();
          settings.save();
          rebuildModelBox(f.getName());
          updatingCombos = true;
          viewBox.setModel(new DefaultComboBoxModel<>(loaded.views.keySet().toArray(new String[0])));
          String view = loaded.views.keySet().iterator().next();
          for (String label : loaded.views.keySet()) {
            String sel = loaded.views.get(label);
            if (sel != null && sel.equals(settings.lastView)) view = label;
          }
          viewBox.setSelectedItem(view);
          viewBox.setEnabled(true);
          updatingCombos = false;
          notes.setText(settings.modelNotes.getOrDefault(f.getAbsolutePath(), ""));
          selectView(view);
        });
      } catch (Throwable t) {
        ui(() -> {
          status("Could not load " + f.getName() + ": " + t.getMessage());
          if (viewPoints == null) useSynthetic(ModelLoader.SYNTHETIC[1]);
          JOptionPane.showMessageDialog(this, "Could not load " + f + ":\n" + t.getMessage(), "Model", JOptionPane.ERROR_MESSAGE);
        });
      }
    });
  }

  private void useSynthetic(String name) {
    loadedModel = null;
    updatingCombos = true;
    modelBox.setSelectedItem(name);
    viewBox.setModel(new DefaultComboBoxModel<>(new String[] { "Whole model" }));
    viewBox.setEnabled(false);
    updatingCombos = false;
    notes.setText(settings.modelNotes.getOrDefault(name, ""));
    applyView(ModelLoader.synthetic(name), name);
  }

  private void selectView(String label) {
    ModelLoader.Loaded loaded = loadedModel;
    String selector = loaded.views.get(label);
    background.submit(() -> {
      try {
        ModelLoader.ViewPoints vp = loader.view(loaded, label, selector);
        ui(() -> {
          settings.lastView = selector == null ? "" : selector;
          settings.save();
          applyView(vp, loaded.file.getName());
        });
      } catch (Throwable t) {
        ui(() -> status("Could not build view " + label + ": " + t.getMessage()));
      }
    });
  }

  private void applyView(ModelLoader.ViewPoints vp, String modelName) {
    viewPoints = vp;
    preview.setPoints(vp);
    geometry = ModelSummary.describe(modelName, vp);
    updateInfo();
    background.submit(() -> {
      try {
        if (!glReady) return;
        runner.setPoints(vp.xyzn);
        ui(() -> status(vp.size() + " LEDs in view. " + (program == null ? "Describe a shader to get started." : "")));
      } catch (Exception ex) {
        ui(() -> status("GL error: " + ex.getMessage()));
      }
    });
  }

  private void storeNotes() {
    String key = loadedModel != null ? loadedModel.file.getAbsolutePath() : (String) modelBox.getSelectedItem();
    if (key == null) return;
    String text = notes.getText();
    if (text.isBlank()) settings.modelNotes.remove(key); else settings.modelNotes.put(key, text);
  }

  private void updateInfo() {
    StringBuilder sb = new StringBuilder();
    sb.append("Shader folder:   ").append(settings.shaderDir()).append('\n');
    sb.append("Textures:        ").append(settings.texturesDir()).append('\n');
    sb.append("Chromatik media: ").append(settings.chromatikDir()).append('\n');
    sb.append("Sessions:        ").append(settings.sessionsDir()).append('\n');
    sb.append("OpenGL:          ").append(glReady ? runner.glInfo() : "not ready").append("\n\n");
    sb.append("What the model is told about the geometry:\n").append(geometry);
    info.setText(sb.toString());
    info.setCaretPosition(0);
  }

  // ------------------------------------------------------------------ textures

  private void refreshTextures() {
    File[] pngs = settings.texturesDir().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
    List<String> names = new ArrayList<>();
    names.add("(no texture)");
    if (pngs != null) {
      java.util.Arrays.sort(pngs);
      for (File f : pngs) names.add(f.getName().substring(0, f.getName().length() - 4));
    }
    updatingCombos = true;
    textureBox.setModel(new DefaultComboBoxModel<>(names.toArray(new String[0])));
    updatingCombos = false;
  }

  private void loadTexture(String name) {
    File f = name == null || name.startsWith("(") ? null : new File(settings.texturesDir(), name + ".png");
    background.submit(() -> {
      try {
        if (glReady) runner.setTexture(f);
      } catch (Exception ex) {
        ui(() -> status("Could not load texture: " + ex.getMessage()));
      }
    });
  }

  // ------------------------------------------------------------------ preview loop

  private void tick() {
    long now = System.nanoTime();
    double dt = (now - lastTick) / 1e9;
    lastTick = now;
    if (playing) elapsed += dt;
    GLRunner.Program p = program;
    if (p == null || !glReady || viewPoints == null) return;
    if (!framePending.compareAndSet(false, true)) return;
    float time = (float) (params.speed * elapsed);
    Map<String, Float> values = params.values();
    float[] bands = audio.bands((now - startNanos) / 1e9);
    runner.setAudioBands(bands);
    frameThread.submit(() -> {
      try {
        float[] rgb = runner.run(p, time, values);
        ui(() -> {
          preview.setAlphaThreshold(params.alphaThreshold);
          preview.setColors(rgb);
          countFrame(time);
        });
      } catch (Exception ex) {
        ui(() -> status("Preview error: " + ex.getMessage()));
      } finally {
        framePending.set(false);
      }
    });
  }

  private void countFrame(float time) {
    frames++;
    long now = System.nanoTime();
    if (now - fpsStart > 1_000_000_000L) {
      fpsText = String.format(Locale.US, "%.0f fps", frames / ((now - fpsStart) / 1e9));
      frames = 0;
      fpsStart = now;
    }
    Session.Version v = session.current();
    preview.setOverlay(String.format(Locale.US, "%s   %d LEDs   fTime %.1f   %s",
      v == null ? "" : "v" + v.number, viewPoints == null ? 0 : viewPoints.size(), time, fpsText));
  }

  // ------------------------------------------------------------------ compiling

  /** Validates a source against the current settings.  Blocking; call off the Swing thread. */
  private ShaderCheck check(String name, String source, boolean tex) {
    if (!glReady) {
      IsfHeader h = IsfHeader.parse(source);
      return ShaderCheck.headerOnly(h);
    }
    ShaderCheck c = ShaderCheck.run(runner, settings.shaderDir(), name.isEmpty() ? "shader" : name, source);
    if (c.ok() && !tex && source.contains("sampler2D")) {
      runner.delete(c.program);
      return ShaderCheck.error(c.header,
        "This shader declares a sampler2D, but the plain CkVShader pattern provides no textures. "
          + "Remove the sampler (or enable Tex mode to target CkVShaderTex).");
    }
    return c;
  }

  /** Makes a checked shader the one being previewed. */
  private void install(ShaderCheck c) {
    if (c.program != null) {
      GLRunner.Program old = program;
      program = c.program;
      runner.delete(old);
    }
    header = c.header;
    if (c.ok()) params.setInputs(c.header.inputs);
    code.setMessages(c.errors, c.warnings);
  }

  private void compileEditor() {
    String source = code.getSource();
    if (source.isBlank()) return;
    String name = nameField.getText().trim();
    boolean tex = texMode.isSelected();
    Session.Version cur = session.current();
    boolean isNew = cur == null || !cur.source.equals(source);
    status("Compiling...");
    background.submit(() -> {
      ShaderCheck c = check(name, source, tex);
      ui(() -> {
        Session.Version v = isNew ? session.addVersion(source, "edit") : session.current();
        v.ok = c.ok();
        v.errors = c.errors;
        install(c);
        refreshVersions();
        code.setSource(source);
        status(c.ok() ? "Compiled v" + v.number + "." : "v" + v.number + " has errors (see Code tab).");
        saveSession();
      });
    });
  }

  private void showVersion(Session.Version v) {
    code.setSource(v.source);
    String name = nameField.getText().trim();
    boolean tex = texMode.isSelected();
    background.submit(() -> {
      ShaderCheck c = check(name, v.source, tex);
      ui(() -> {
        v.ok = c.ok();
        v.errors = c.errors;
        install(c);
        refreshVersions();
        status("Showing " + v.label() + ".");
      });
    });
  }

  private void refreshVersions() {
    updatingCombos = true;
    DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
    int sel = -1;
    for (int i = 0; i < session.versions.size(); i++) {
      Session.Version v = session.versions.get(i);
      m.addElement(v.label());
      if (v.number == session.currentVersion) sel = i;
    }
    code.versions.setModel(m);
    if (sel >= 0) code.versions.setSelectedIndex(sel);
    updatingCombos = false;
  }

  private void saveSliderDefaults() {
    Session.Version cur = session.current();
    if (cur == null) return;
    String updated = IsfHeader.withDefaults(code.getSource(), params.values());
    code.setSource(updated);
    compileEditor();
  }

  // ------------------------------------------------------------------ sessions & files

  private File sessionFile(String name) {
    return new File(settings.sessionsDir(), name + ".json");
  }

  private File shaderFile(String name) {
    return new File(settings.shaderDir(), name + ".vtx");
  }

  /** Records which model and view the session is being designed on. */
  private void rememberModel(Session s) {
    s.modelFile = loadedModel != null ? loadedModel.file.getAbsolutePath() : (String) modelBox.getSelectedItem();
    s.viewSelector = viewPoints == null ? null : viewPoints.selector;
  }

  /** Switches the preview to the model and view a session was designed on, if it is available. */
  private void restoreModel(Session s) {
    if (s.modelFile == null || s.modelFile.isEmpty()) return;
    for (String syn : ModelLoader.SYNTHETIC) {
      if (syn.equals(s.modelFile)) {
        if (loadedModel != null || !syn.equals(modelBox.getSelectedItem())) useSynthetic(syn);
        return;
      }
    }
    File f = new File(s.modelFile);
    if (!f.isFile()) {
      status("This session was designed on " + f.getName() + ", which no longer exists; keeping the current model.");
      return;
    }
    String selector = s.viewSelector == null ? "" : s.viewSelector;
    if (loadedModel == null || !loadedModel.file.equals(f)) {
      settings.lastView = selector;
      loadModel(f);
      return;
    }
    String current = viewPoints == null || viewPoints.selector == null ? "" : viewPoints.selector;
    if (!current.equals(selector)) {
      for (Map.Entry<String, String> e : loadedModel.views.entrySet()) {
        String sel = e.getValue() == null ? "" : e.getValue();
        if (sel.equals(selector)) {
          updatingCombos = true;
          viewBox.setSelectedItem(e.getKey());
          updatingCombos = false;
          selectView(e.getKey());
          return;
        }
      }
    }
  }

  private void showHistory() {
    if (!confirmDiscard()) return;
    saveSession();
    Session chosen = new HistoryDialog(this, settings.sessionsDir(), session.shaderName).choose(true);
    if (chosen == null) return;
    setSession(chosen);
    restoreModel(chosen);
    settings.lastShaderName = chosen.shaderName;
    settings.save();
    Session.Version v = chosen.current();
    status("Continuing " + chosen.shaderName + (v == null ? "" : " at v" + v.number)
      + ". The model sees the whole conversation and the current code.");
  }

  private void setSession(Session s) {
    session = s;
    nameField.setText(s.shaderName == null ? "" : s.shaderName);
    texMode.setSelected(s.textureMode);
    textureBox.setEnabled(s.textureMode);
    audioBox.setEnabled(s.textureMode);
    renderChat();
    refreshVersions();
    Session.Version v = s.current();
    if (v != null) {
      showVersion(v);
    } else {
      code.setSource("");
      code.setMessages(null, null);
      GLRunner.Program old = program;
      program = null;
      runner.delete(old);
      preview.setColors(null);
      params.setInputs(List.of());
    }
    savedLabel.setText(" ");
  }

  private boolean confirmDiscard() {
    if (!code.isDirty()) return true;
    return JOptionPane.showConfirmDialog(this, "Discard the uncompiled edits in the code editor?", "CkShaderStudio",
      JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
  }

  private void newShader() {
    if (!confirmDiscard()) return;
    saveSession();
    Session s = new Session();
    s.textureMode = texMode.isSelected();
    setSession(s);
    nameField.requestFocusInWindow();
    status("New shader. Enter a name and describe what you want.");
  }

  /** Keeps the current shader but forgets the conversation, for a fresh start on the same code. */
  private void newConversation() {
    Session.Version cur = session.current();
    session.entries.clear();
    if (cur != null) session.add(Session.ROLE_NOTE, "New conversation, continuing from v" + cur.number + ".");
    renderChat();
    saveSession();
  }

  private void openShader() {
    if (!confirmDiscard()) return;
    JFileChooser fc = new JFileChooser(settings.shaderDir());
    fc.setFileFilter(new FileNameExtensionFilter("CkVShader vertex shader (*.vtx)", "vtx"));
    if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    File f = fc.getSelectedFile();
    String name = f.getName().replaceFirst("\\.vtx$", "");
    try {
      String source = Files.readString(f.toPath(), StandardCharsets.UTF_8);
      saveSession();
      Session s = null;
      File sf = sessionFile(name);
      if (sf.isFile() && f.getParentFile().getCanonicalFile().equals(settings.shaderDir().getCanonicalFile())) {
        s = Session.load(sf);
      }
      if (s == null) {
        s = new Session();
        s.shaderName = name;
        s.textureMode = source.contains("sampler2D");
      }
      Session.Version cur = s.current();
      if (cur == null || !cur.source.equals(source)) {
        s.addVersion(source, "file");
        s.add(Session.ROLE_NOTE, "Loaded " + f.getName() + " as v" + s.currentVersion + ".");
      }
      s.ownsFile = f.getParentFile().getCanonicalFile().equals(settings.shaderDir().getCanonicalFile());
      if (!s.ownsFile) s.shaderName = name;
      setSession(s);
      settings.lastShaderName = name;
      settings.save();
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(this, "Could not open " + f + ":\n" + ex.getMessage());
    }
  }

  private boolean validName() {
    String name = nameField.getText().trim();
    if (!VALID_NAME.matcher(name).matches()) {
      JOptionPane.showMessageDialog(this, "Enter a shader name using only letters, digits, - and _.", "Shader name",
        JOptionPane.WARNING_MESSAGE);
      nameField.requestFocusInWindow();
      return false;
    }
    return true;
  }

  /** Writes the current version to the CkVShader shader folder. */
  private void save(boolean interactive) {
    if (!validName()) return;
    if (code.isDirty()) {
      if (code.getSource().isBlank()) {
        if (interactive) JOptionPane.showMessageDialog(this, "The code editor is empty.");
        return;
      }
      if (interactive) {
        compileEditor();
        background.submit(() -> ui(() -> save(true)));
      }
      return;
    }
    Session.Version v = session.current();
    if (v == null) {
      if (interactive) JOptionPane.showMessageDialog(this, "There is no shader to save yet.");
      return;
    }
    String name = nameField.getText().trim();
    File out = shaderFile(name);
    boolean nameChanged = !name.equals(session.shaderName);
    if (out.exists() && (nameChanged || !session.ownsFile)) {
      if (!interactive) {
        status("Not auto-saving: " + out.getName() + " exists and was not created in this session.");
        return;
      }
      if (JOptionPane.showConfirmDialog(this, out.getName() + " already exists in\n" + out.getParent() + "\nOverwrite it?",
        "Save", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
        return;
      }
    }
    if (!v.ok && interactive && JOptionPane.showConfirmDialog(this, "v" + v.number + " has errors. Save it anyway?",
      "Save", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
      return;
    }
    try {
      out.getParentFile().mkdirs();
      Files.writeString(out.toPath(), v.source, StandardCharsets.UTF_8);
      session.shaderName = name;
      session.ownsFile = true;
      rememberModel(session);
      settings.lastShaderName = name;
      settings.save();
      saveSession();
      savedLabel.setText("saved v" + v.number);
      status("Saved " + out.getName() + " (v" + v.number + "). In Chromatik, open it from " + (session.textureMode ? "CkVShaderTex" : "CkVShader")
        + ", or press its reload button if it is already loaded.");
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(this, "Could not save " + out + ":\n" + ex.getMessage());
    }
  }

  private void saveSession() {
    String name = nameField.getText().trim();
    if (!VALID_NAME.matcher(name).matches() || (session.versions.isEmpty() && session.entries.isEmpty())) return;
    if (session.shaderName == null || session.shaderName.isEmpty()) session.shaderName = name;
    try {
      session.save(sessionFile(session.shaderName));
    } catch (Exception ex) {
      status("Could not save session: " + ex.getMessage());
    }
  }

  // ------------------------------------------------------------------ AI

  public void refreshModels() {
    chat.setStatus("Fetching model list from OpenRouter...");
    String key = settings.effectiveApiKey();
    aiThread.submit(() -> {
      try {
        List<OpenRouterClient.Model> models = new OpenRouterClient(key).listModels();
        Map<String, List<OpenRouterClient.Model>> byProvider = new TreeMap<>();
        for (OpenRouterClient.Model m : models) {
          if (m.id.endsWith(":batch")) continue;
          byProvider.computeIfAbsent(m.provider(), k -> new ArrayList<>()).add(m);
        }
        byProvider.values().forEach(list -> list.sort((a, b) -> a.id.compareTo(b.id)));
        ui(() -> {
          modelsByProvider.clear();
          modelsByProvider.putAll(byProvider);
          updatingCombos = true;
          chat.provider.setModel(new DefaultComboBoxModel<>(modelsByProvider.keySet().toArray(new String[0])));
          chat.provider.setSelectedItem(modelsByProvider.containsKey(settings.provider) ? settings.provider
            : modelsByProvider.keySet().stream().findFirst().orElse(null));
          updatingCombos = false;
          onProviderChanged();
          chat.setStatus(models.size() + " models available."
            + (key.isEmpty() ? " Add your OpenRouter API key in Settings to chat." : ""));
        });
      } catch (Exception ex) {
        ui(() -> chat.setStatus("Could not fetch models: " + ex.getMessage()));
      }
    });
  }

  private void onProviderChanged() {
    if (updatingCombos) return;
    String prov = (String) chat.provider.getSelectedItem();
    List<OpenRouterClient.Model> list = prov == null ? null : modelsByProvider.get(prov);
    updatingCombos = true;
    DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
    int sel = 0;
    if (list == null || list.isEmpty()) {
      m.addElement(NO_MODELS);
    } else {
      for (int i = 0; i < list.size(); i++) {
        m.addElement(list.get(i).shortId());
        if (list.get(i).id.equals(settings.model)) sel = i;
      }
    }
    chat.model.setModel(m);
    chat.model.setSelectedIndex(sel);
    updatingCombos = false;
    if (list != null && !list.isEmpty()) {
      settings.provider = prov;
      settings.model = selectedModelId();
      settings.save();
    }
    updateAttachAvailability();
  }

  private OpenRouterClient.Model selectedModel() {
    List<OpenRouterClient.Model> list = modelsByProvider.get((String) chat.provider.getSelectedItem());
    int idx = chat.model.getSelectedIndex();
    if (list == null || idx < 0 || idx >= list.size()) return null;
    return list.get(idx);
  }

  private String selectedModelId() {
    OpenRouterClient.Model m = selectedModel();
    return m == null ? null : m.id;
  }

  private void updateAttachAvailability() {
    OpenRouterClient.Model m = selectedModel();
    boolean images = m != null && m.acceptsImages;
    chat.attachPreview.setEnabled(images);
    chat.attachPreview.setToolTipText(images ? "Send a snapshot of the preview with the message"
      : "The selected model does not accept images");
    if (m != null) {
      chat.model.setToolTipText(m.name + (m.contextLength > 0 ? " — " + m.contextLength / 1000 + "k context" : "")
        + (m.promptPrice >= 0 ? String.format(Locale.US, " — $%.2f / $%.2f per M tokens in/out", m.promptPrice, m.completionPrice) : ""));
    }
  }

  private void sendPrompt() {
    String text = chat.input.getText().trim();
    if (text.isEmpty()) return;
    String key = settings.effectiveApiKey();
    if (key.isEmpty()) {
      JOptionPane.showMessageDialog(this, "Enter your OpenRouter API key in Settings first (or set OPENROUTER_API_KEY).");
      showSettings();
      return;
    }
    String modelId = selectedModelId();
    if (modelId == null) {
      chat.setStatus("No model selected. Press ↻ to load the model list.");
      return;
    }
    if (!validName()) return;
    if (code.isDirty() && !code.getSource().isBlank()) {
      // Make the hand edit a version so the model sees it; then send.
      compileEditor();
      background.submit(() -> ui(this::sendPrompt));
      return;
    }
    String name = nameField.getText().trim();
    if (!name.equals(session.shaderName)) {
      if (shaderFile(name).exists() && !session.ownsFile && session.versions.isEmpty()) {
        chat.setStatus(name + ".vtx already exists. Use Open… to iterate on it, or pick another name.");
        return;
      }
      session.shaderName = name;
      session.ownsFile = false;
    }
    boolean tex = texMode.isSelected();
    session.textureMode = tex;
    session.modelId = modelId;
    rememberModel(session);
    String system = ShaderPrompt.systemPrompt(settings.shaderDir(), tex, geometry, notes.getText());
    Map<String, Float> changed = params.changedValues();
    OpenRouterClient.Model model = selectedModel();
    String image = chat.attachPreview.isSelected() && model != null && model.acceptsImages ? preview.snapshotDataUrl(768) : null;
    chat.input.setText("");
    chat.setBusy(true);
    settings.save();

    OpenRouterClient client = new OpenRouterClient(key);
    activeClient = client;
    Assistant assistant = new Assistant(client, modelId, settings.maxTokens, settings.maxFixAttempts);
    Session s = session;
    aiThread.submit(() -> {
      try {
        Assistant.Result r = assistant.turn(system, s, text, changed, image,
          src -> check(name, src, tex),
          c -> runner.delete(c.program),
          new Assistant.Listener() {
            public void status(String message) {
              ui(() -> chat.setStatus(message));
            }

            public void entryAdded(Session.Entry entry) {
              List<Session.Entry> snapshot = new ArrayList<>(s.entries);
              ui(() -> chat.render(snapshot, s));
            }
          });
        ui(() -> {
          if (session != s) {
            // The user switched shaders while waiting; keep the result in the old session only.
            if (r.check != null) runner.delete(r.check.program);
            return;
          }
          if (r.check != null) {
            install(r.check);
            refreshVersions();
            code.setSource(r.version.source);
            chat.setStatus(r.check.ok() ? "v" + r.version.number + " compiled and is previewing."
              : "v" + r.version.number + " still has errors after " + settings.maxFixAttempts + " fix attempts.");
            if (r.check.ok() && autoSave.isSelected()) save(false);
          } else {
            chat.setStatus("Answered without changing the shader.");
          }
          updateUsage();
          saveSession();
        });
      } catch (Exception ex) {
        ui(() -> {
          chat.setStatus(client.isCancelled() ? "Stopped." : "Error: " + ex.getMessage());
          chatNote(client.isCancelled() ? "Request stopped." : "Request failed: " + ex.getMessage());
          saveSession();
        });
      } finally {
        ui(() -> {
          chat.setBusy(false);
          renderChat();
          activeClient = null;
        });
      }
    });
  }

  private void updateUsage() {
    chat.usage.setText(session.totalCost > 0
      ? String.format(Locale.US, "$%.3f · %,d tok", session.totalCost, session.totalPromptTokens + session.totalCompletionTokens)
      : (session.totalPromptTokens > 0 ? String.format(Locale.US, "%,d tok", session.totalPromptTokens + session.totalCompletionTokens) : " "));
  }

  private void chatNote(String text) {
    session.add(Session.ROLE_NOTE, text);
    renderChat();
  }

  private void renderChat() {
    chat.render(new ArrayList<>(session.entries), session);
    updateUsage();
  }

  // ------------------------------------------------------------------ settings & misc

  private void showSettings() {
    SettingsDialog d = new SettingsDialog(this, settings);
    if (d.showDialog()) {
      settings.save();
      updateInfo();
      refreshTextures();
      refreshModels();
    }
  }

  private void status(String s) {
    chat.setStatus(s);
  }

  static private void ui(Runnable r) {
    SwingUtilities.invokeLater(r);
  }

  private void quit() {
    storeNotes();
    saveSession();
    settings.save();
    runner.dispose();
    loader.dispose();
    dispose();
    System.exit(0);
  }

  /** A row of controls with normal (bordered) buttons; Nimbus draws toolbar buttons flat. */
  static private class ToolRow extends JPanel {
    ToolRow() {
      setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS));
      setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
    }

    @Override
    public java.awt.Component add(java.awt.Component c) {
      if (getComponentCount() > 0 && !(c instanceof javax.swing.Box.Filler)) {
        super.add(javax.swing.Box.createHorizontalStrut(4));
      }
      if (c instanceof JComboBox || c instanceof JTextField || c instanceof JSlider) {
        c.setMaximumSize(new Dimension(c.getMaximumSize().width, c.getPreferredSize().height));
      }
      return super.add(c);
    }

    void addSeparator() {
      super.add(javax.swing.Box.createHorizontalStrut(6));
      javax.swing.JSeparator sep = new javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL);
      sep.setMaximumSize(new Dimension(2, 22));
      super.add(sep);
      super.add(javax.swing.Box.createHorizontalStrut(2));
    }
  }
}
