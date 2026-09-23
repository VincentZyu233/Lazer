# Changelog / 更新日志

## v1.3 — 2026-09-24

### English

**Highlights**

- **Lyric passage selection** — long press a line to start marking, keep holding to drag down the sheet, tap to add or drop a line, select all, then copy the passage with its translations. Marked lines borrow the singing line's own focus spring, so the highlight moves the way a lyric change does and no row changes size; the sheet holds still and gives the control bar a temporary safe area while a passage is picked.
- **Long press to copy** — the song title, the artist names and a comment body offer the same confirmation sheet that saving a cover does.
- **About page** — version, source repository, acknowledgements, license and the third-party notice. The version is defined once, in `shared/.../LazerRelease.kt`, which both Gradle scripts and the iOS build configuration read, so the number on screen is the number the artefacts carry.
- **Playback stops hijacking navigation** — tapping a song switches what is playing instead of forcing the full player open, and a song with nothing to sing rests with the large cover instead of an empty lyric pane.
- **Tap feedback you can set** — off, light, standard or strong, applied to every control that answers a landed tap.
- **Predictive back keeps the screen's own corners**, so a page scaled mid-gesture is no longer a square cut-out.

**Interface and fixes**

- The line being sung is capped at 0.85 alpha, and its not-yet-sung part now stays brighter than its neighbours instead of dimmer.
- Desktop lyric selection uses the same highlight, and an interlude row entering or leaving no longer clears the lines already marked.
- Album-flow backgrounds moved into the shared module, and Windows acrylic handling was updated.

---

### 中文

**重点更新**

- **歌词划段复制** —— 长按一行开始标记,按住不放可以继续往下拖选,点按加选或减选,全选后连同译文一起复制。被标记的行借用“正在唱的那行”自己的弹簧,所以高亮和切歌词是同一种运动,行高不变;选择期间歌词面板保持静止,并为控制条让出临时安全区。
- **长按复制** —— 歌名、歌手名、评论正文都会弹出和长按封面一样的确认层,确认后复制到剪贴板。
- **关于页** —— 版本、开源仓库、致谢、许可证与第三方声明。版本号只写在 `shared/.../LazerRelease.kt`,两个 Gradle 脚本和 iOS 构建配置都从这里读,页面上的数字和包里的数字不会不一致。
- **播放不再抢导航** —— 点歌曲只切换正在播放,不再自动打开正在播放页;没有歌词的歌直接以大封面呈现,不留一块空歌词区。
- **触感强度可调** —— 关闭 / 轻 / 标准 / 强,对所有“落下的点击”生效。
- **预测式返回按设备屏幕圆角裁切页面**,手势缩放中的页面不再是直角切片。

**界面与修复**

- 当前歌词亮度上限改为 0.85,并抬高未唱到部分的地平线,不再出现“正在唱的行比旁边更暗”。
- 桌面端歌词选择使用同一套高亮;间奏行进出不再清空已经选好的行。
- 流动封面背景重构进 shared 模块,Windows 亚克力处理更新。

---

## v1.2 — 2026-09-20

### English

**Highlights**

- **Android now playing** — rebuilt the cover expansion, compact/large artwork transition, translated song titles, and landscape layout so controls and lyrics remain usable on wide screens.
- **AMLL-style lyrics** — added hot-inserted interlude dots with integrated entrance/exit motion, improved word timing and row springs, and fixed glow clipping during lyric changes. Interlude dots now share the lyric glow treatment.
- **Artwork-driven appearance** — backgrounds can use an image, a solid artwork color, or a low-frame-rate animated artwork color. App colors can follow the current cover, and image backgrounds support adjustable blur.

**Interface**

- The Android root page now remains mounted behind detail pages, removing the flash when returning from a playlist.
- Material navigation now uses the standard navigation bar; Liquid Glass uses calmer refraction and clearer surfaces. The Discover tab and its Android page were removed.
- Bottom navigation labels stay on one line and use fixed heights in every language, including Japanese.
- The library rotates through ten localized usage tips on entry. Large artwork gains an animated shadow.

**Performance and fixes**

- Cached artwork palette extraction, reduced continuous background work, and capped optional color motion at 12 fps.
- Fixed interlude dots disappearing before the lyric layout closed, full-screen artwork animation stopping, and glow being clipped to a small rectangle during focus transitions.
- Added persistent background modes, image blur settings, translated track-title rendering, and broader lyric/layout tests.

---

### 中文

**重点更新**

- **Android 正在播放页** — 重做封面展开、大小封面过渡、歌曲译名显示和横屏布局，宽屏下控制区与歌词区均可正常使用。
- **AMLL 风格歌词** — 新增可热插入的间奏圆点及融合式进出动画，优化逐字时间、歌词行弹簧，并修复切换歌词时辉光被裁剪的问题；间奏圆点也会跟随歌词发光设置。
- **封面驱动外观** — 背景可选择图片、封面纯色或低帧率动态封面色；应用配色可跟随当前歌曲封面，图片背景支持可调模糊。

**界面**

- Android 主页常驻在详情页下方，修复从歌单返回时的底部闪烁。
- Material 底栏改用标准导航组件，液态玻璃降低折射并提高可读性；移除 Android 的“发现”入口与页面。
- 所有语言的底栏标签固定为单行和固定高度，修复日语界面底栏异常增高。
- 音乐库每次进入轮换十条本地化提示；大封面模式新增渐进阴影。

**性能与修复**

- 缓存封面取色、减少持续背景绘制，并将可选动态取色限制为 12 帧每秒。
- 修复间奏圆点提前消失、全屏后封面色动画停止，以及歌词聚焦过程中辉光被限制在小矩形内的问题。
- 新增可持久化的背景模式、图片模糊设置、歌曲译名渲染，并补充歌词与布局测试。

---

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
