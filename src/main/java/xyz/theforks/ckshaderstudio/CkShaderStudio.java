package xyz.theforks.ckshaderstudio;

import com.jogamp.opengl.GLProfile;
import xyz.theforks.ckshaderstudio.ui.MainFrame;
import xyz.theforks.ckshaderstudio.ui.Theme;

import javax.swing.SwingUtilities;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.Window;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * CkShaderStudio: an AI-assisted editor for CkVShader vertex shaders with a live LED preview.
 * Shaders are saved to CkVShader's shader folder so they can be opened from the CkVShader and
 * CkVShaderTex patterns in Chromatik.
 */
public class CkShaderStudio {

  public static void main(String[] args) {
    System.setProperty("apple.laf.useScreenMenuBar", "true");
    System.setProperty("apple.awt.application.name", "CkShaderStudio");
    // Dark window title bar on macOS.
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
    // JOGL recommends initializing its profiles once, early, before any GL work.
    try {
      GLProfile.initSingleton();
    } catch (Throwable t) {
      System.err.println("JOGL initialization failed: " + t);
    }
    Settings settings = Settings.load();
    SwingUtilities.invokeLater(() -> {
      Theme.install();
      MainFrame frame = new MainFrame(settings);
      applyIcon(frame);
      frame.start();
    });
  }

  /** Sets the window icons and, where supported (the macOS dock), the application icon. */
  static void applyIcon(Window window) {
    List<Image> images = new ArrayList<>();
    for (int size : new int[] { 16, 32, 48, 64, 128, 256, 512, 1024 }) {
      URL url = CkShaderStudio.class.getResource("/icons/icon-" + size + ".png");
      if (url != null) images.add(Toolkit.getDefaultToolkit().getImage(url));
    }
    if (images.isEmpty()) return;
    window.setIconImages(images);
    try {
      if (Taskbar.isTaskbarSupported()) {
        Taskbar taskbar = Taskbar.getTaskbar();
        if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
          taskbar.setIconImage(images.get(images.size() - 1));
        }
      }
    } catch (Exception ignored) {
      // Not available on this platform or desktop environment.
    }
  }
}
