# CLAUDE.md

Guidance for working on CkShaderStudio.

## What this is

A standalone Swing application (not a Chromatik plugin) for writing CkVShader vertex shaders with an LLM
via OpenRouter, previewed on a real Chromatik model. It only writes `.vtx` files into CkVShader's shader
folder (`~/Chromatik/Data/CkVShader/shaders`); Chromatik loads them from there.

## Build

- `mvn package` → `target/ckshaderstudio-<version>-jar-with-dependencies.jar` (main class in manifest)
- `mvn compile exec:exec` runs from `target/classes`
- Java 21. LX 1.1.0 and JOGL 2.4.0-rc-20230123 must match CkVShader's versions.

## Parity with CkVShader (keep these in sync)

The preview must behave like `xyz.theforks.ckvshader.patterns.CkVShader` / `CkVShaderTex`:

- `shader/GLRunner`: offscreen GL3 context, `position` attribute from normalized `xn,yn,zn`, rasterizer
  discard, transform feedback of `outColor`, `fTime = speed * seconds`, float uniforms per ISF input,
  `textureSampler` on unit 0 (AWTTextureIO, nearest, mirrored repeat), `audioTexture` on unit 1
  (512x2 GL_R8, even texel i = band i % 16).
- `shader/ShaderSource`: `#include` handling mirrors CkVShader's `GLUtil.expandIncludes` (lines starting
  with `#include`, `<name>` relative to the shader folder, max depth 10). It adds a source-string number to
  `#line` so errors map to `file:line`; this does not change compilation.
- `shader/IsfHeader`: first `/* ... */` is the ISF JSON; inputs need NAME, TYPE float, DEFAULT, MIN, MAX.
- `ui/PreviewPanel.ledColor`: alpha threshold uses `LXColor.luminosity`, as `CkVShader.run()` does.
- `model/ModelLoader`: headless `LX` with `mediaPath` = Chromatik folder, `structure.importModel(.lxm)`,
  views via `LXView.create(..., RELATIVE, GLOBAL)`.

## UI theme

`ui/Theme` installs a dark Nimbus palette and holds the colors used by custom-painted components and the
chat HTML. Use its constants rather than hard-coded colors.

## Threads

- GL: all GL calls run on `GLRunner`'s single thread, which keeps the context current. Public methods block.
- Background executor: model loading, compiles triggered from the UI.
- Assistant executor: OpenRouter calls and the auto-fix loop (`ai/Assistant.turn`).
- Frames: one frame in flight at a time, driven by a Swing timer.
- Swing state is only touched on the EDT; worker results come back through `SwingUtilities.invokeLater`.

## Prompt/conversation design

- `ai/ShaderPrompt` holds the rules for CkVShader shaders; `model/ModelSummary` adds geometry facts.
- `ai/Assistant.buildMessages` strips code from all but the most recent assistant reply and appends the
  current shader + changed slider values to the latest user message, so the model always edits what the
  user is looking at.

## Testing without the network

`-Dckshaderstudio.openrouter.url=http://127.0.0.1:PORT` points the client at a local stub that implements
`GET /models` and `POST /chat/completions`.
