# Changelog / 更新日志

## v1.1 — 2026-09-13

### English

**Highlights**

- **Liquid Glass & Acrylic** — unified Material / Miuix / Liquid Glass into one `LazerStyle` setting with localized options. Android gains a liquid-glass bottom dock with a long-press lens switcher; Windows gains a DWM acrylic backdrop for the window, title bar, sidebar, and player.
- **Independent Playback (Android)** — a new playback-interface setting bypasses system media controls and audio focus, letting Lazer play alongside other apps. The media session, audio focus, and media-style notification are now created/released lazily and react to runtime changes.
- **Localization** — added bundled `zh-Hans`, `zh-Hant`, `ja`, and `en` catalogs and migrated hardcoded strings (theme, speed, quality, playback, notifications).

**Appearance**

- Custom wallpaper with an opacity slider that fades the UI, not the background; on/off switch; stores the file path instead of copying it.
- Seed-based palettes (Default / System / Custom) with a shared seed color picker.
- Page + global scrims so returning to a page never exposes the wallpaper; manual status/nav bar insets.
- Adjustable Liquid Glass blur intensity, persisted.
- Debug watermark on Android (debuggable package) and desktop (Gradle run).

**Lyrics**

- Word-by-word (timed) lyrics with improved display.
- Lyric glow toggle; row spacing now derives from the lyric font size.
- Dynamic lyric layout engine (row centers, translation gaps, font size).
- Android publishes real-time lyrics to the system **SuperLyric** service; no title-as-lyric fallback.
- Desktop supports animated lyric text.

**Playback & Caching**

- Improved playback settings and session restore.
- Cache controls; Android playlist cache rewritten with async writes and in-memory caching.
- Stream URLs cached and adjacent tracks prefetched.
- Windows WASAPI and PCM audio output on desktop.

**Build & CI**

- GitHub Actions builds an arm64-v8a APK, x64 MSI, and runnable JAR (separate artifacts; released uncompressed on tags).
- Release signing made optional at configuration time; a dedicated `verifyReleaseSigning` task fails only when a release artifact is requested.
- Added JitPack repository for `SuperLyricApi`; ProGuard keeps its models.

**Fixes**

- Android startup crash: escaped the closing brace in the translation-placeholder regex.
- Whole-line lyric scan, theme glow, and row alignment corrected.

---

### 中文

**重点更新**

- **液态玻璃与亚克力** — 将 Material / Miuix / 液态玻璃合并为统一的 `LazerStyle` 设置，并支持多语言选项。Android 新增液态玻璃底部 Dock，长按可切换凸透镜效果；Windows 新增 DWM 亚克力背景，覆盖窗口、标题栏、侧边栏与播放器。
- **独立播放（Android）** — 新增播放接口设置，可绕过系统媒体控制与音频焦点，让 Lazer 与其他应用同时发声。媒体会话、音频焦点与媒体式通知改为按需创建/释放，并响应运行时切换。
- **多语言** — 内置 `zh-Hans`、`zh-Hant`、`ja`、`en` 语言表，并将硬编码文案（主题、速度、音质、播放、通知）全部迁移。

**外观**

- 自定义壁纸，附带透明度滑块（淡化 UI 而非背景）；支持开关；保存文件路径而非复制文件。
- 基于种子的调色板（默认 / 系统 / 自定义），共用取色器。
- 页面与全局双层遮罩，返回时不再露出完整壁纸；手动处理状态栏/导航栏内边距。
- 液态玻璃模糊强度可调并持久化。
- Android（可调试包）与桌面端（Gradle 运行）显示 DEBUG 水印。

**歌词**

- 逐字（逐时）歌词，显示效果优化。
- 歌词辉光开关；行间距改为根据歌词字号推导。
- 动态歌词布局引擎（行中心、翻译间距、字号）。
- Android 将实时歌词发布至系统 **SuperLyric** 服务；不再用歌名充当歌词。
- 桌面端支持动画歌词文本。

**播放与缓存**

- 播放设置优化，支持会话恢复。
- 新增缓存控制；Android 歌单缓存重写，改为异步写入 + 内存缓存。
- 缓存并预取相邻曲目的流地址。
- 桌面端新增 Windows WASAPI 与 PCM 音频输出。

**构建与 CI**

- GitHub Actions 构建 arm64-v8a APK、x64 MSI 与可运行 JAR（独立产物；仅在打标签时以未压缩形式发布）。
- 发布签名在配置阶段改为可选；仅当请求发布产物时，专用 `verifyReleaseSigning` 任务才报错。
- 新增 `SuperLyricApi` 的 JitPack 仓库；ProGuard 保留其数据模型。

**修复**

- Android 启动崩溃：修复翻译占位符正则中右花括号未转义的问题。
- 修正整行歌词扫描、主题辉光与行对齐。
