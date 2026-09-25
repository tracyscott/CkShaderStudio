package xyz.theforks.ckshaderstudio.shader;

/**
 * Stand-in for Chromatik's audio meter so audio-reactive shaders can be previewed without music.
 * Produces 16 band levels (bass first) with a kick on every beat, a snare on the off-beats and
 * noisy highs, roughly like a GraphicMeter fed by a four-on-the-floor track.
 */
public class AudioSim {

  public float bpm = 120f;
  public float level = 0.8f;
  public boolean enabled = true;

  private final float[] bands = new float[16];
  private final float[] noise = new float[16];
  private long seed = 1234567L;

  /** @param seconds elapsed wall time in seconds */
  public float[] bands(double seconds) {
    if (!enabled) {
      java.util.Arrays.fill(bands, 0f);
      return bands;
    }
    double beat = seconds * bpm / 60.0;
    double phase = beat - Math.floor(beat);
    double half = beat * 2 - Math.floor(beat * 2);
    float kick = (float) Math.exp(-phase * 7.0);
    float snare = ((long) Math.floor(beat) % 2 == 1) ? (float) Math.exp(-phase * 10.0) : 0f;
    float hat = (float) Math.exp(-half * 14.0);
    for (int i = 0; i < 16; i++) {
      noise[i] = 0.85f * noise[i] + 0.15f * rand();
      float t = i / 15f;
      float v = kick * Math.max(0f, 1f - t * 3.5f)
        + snare * (float) Math.exp(-Math.pow((t - 0.45) * 5.0, 2)) * 0.8f
        + hat * Math.max(0f, t - 0.6f) * 1.6f
        + noise[i] * (0.25f + 0.15f * (1f - t));
      bands[i] = Math.max(0f, Math.min(1f, v * level));
    }
    return bands;
  }

  private float rand() {
    seed = seed * 6364136223846793005L + 1442695040888963407L;
    return ((seed >>> 40) & 0xFFFF) / 65535f;
  }
}
