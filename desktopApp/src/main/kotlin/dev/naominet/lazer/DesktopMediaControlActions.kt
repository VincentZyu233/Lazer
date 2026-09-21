package dev.naominet.lazer

/**
 * 单一的媒体控制动作蓝图,供任务栏缩略图工具栏(Windows Thumbar)与系统托盘右键菜单共用,
 * 保证不同平台上"上一首 / 播放·暂停 / 下一首"的顺序与文案一致。
 *
 * 播放/暂停项随播放状态切换标签与图标,由消费方读取 [isPlaying] 决定。
 */
internal enum class MediaControlAction {
    Previous,
    PlayPause,
    Next,
}

/**
 * 一个媒体控制动作在界面上的呈现:标签文案与本地图标资源名(不含扩展名)。
 * @param label 展示给用户的文案
 * @param iconResource 图标资源基名,对应 desktopApp 资源目录下的 media-control/<name>
 */
internal data class MediaControlItem(
    val action: MediaControlAction,
    val label: String,
    val iconResource: String,
)

/**
 * 依据当前是否播放,产出托盘/缩略图工具栏要显示的媒体控制项列表(顺序固定:上一首→播放·暂停→下一首)。
 * @param isPlaying 当前是否正在播放,决定中间项显示"暂停"还是"播放"
 * @param tr 文案翻译函数,传入 i18n key 返回本地化文本
 */
internal fun mediaControlItems(
    isPlaying: Boolean,
    tr: (String) -> String,
): List<MediaControlItem> = listOf(
    MediaControlItem(
        action = MediaControlAction.Previous,
        label = tr("media_control.previous"),
        iconResource = "previous",
    ),
    MediaControlItem(
        action = MediaControlAction.PlayPause,
        label = if (isPlaying) tr("media_control.pause") else tr("media_control.play"),
        iconResource = if (isPlaying) "pause" else "play",
    ),
    MediaControlItem(
        action = MediaControlAction.Next,
        label = tr("media_control.next"),
        iconResource = "next",
    ),
)

/**
 * 把媒体控制动作分发到播放器控制器的现有方法。缩略图工具栏与托盘菜单都经由此处触发,避免重复逻辑。
 */
internal fun DesktopPlayerController.dispatchMediaControlAction(action: MediaControlAction) {
    when (action) {
        MediaControlAction.Previous -> playPrevious()
        MediaControlAction.PlayPause -> togglePlayPause()
        MediaControlAction.Next -> playNext()
    }
}
