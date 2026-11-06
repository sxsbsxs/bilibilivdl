# 上游项目完整功能审查与 bilibilivdl 差距对照

审查基线：`bilibilivdl` 分支 `v2.0-integration`，提交 `c8b308c8efaaf9e64021562d5df4ff338e089418`。

参考项目：
- `10miaomiao/bilimiao2`：播放器交互、播放会话、弹幕和字幕渲染。
- `KafuuNeko/BiliDownload`：下载任务、资源登记、恢复、历史列表和本地资源播放。

两个参考项目的源码镜像已存在本仓库 `upstream/bilimiao2/` 与 `upstream/BiliDownload/`。本次审查直接基于仓库内镜像与各模块源码，不需要再复制一份仓库。本文记录的是架构与功能差距，不代表已把上游功能全部移植。

## 1. bilimiao2 播放器审查

### 1.1 播放器整体结构

播放器不是单个视频控件，而是分层播放器框架。核心文件包括：

- `bilimiao-compose/.../components/player/VideoPlayer.kt`：只负责视频画面承载，平台播放器实现与 UI 控制层分离。
- `.../videoplayer/VideoScaffold.kt`：播放器画面框架，按层组合顶部栏、视频画面、弹幕、字幕、手势层、左右操作区、底部控制栏、进度条、状态覆盖层和侧边栏；竖屏非全屏保持 16:9，展开时占满可用区域。
- `.../videoplayer/PlayerControllerState.kt`：控制器显隐、锁定状态、焦点与键盘控制状态。
- `.../videoplayer/PlayerGestureHost.kt`：统一接收播放器触摸/鼠标手势。
- `.../videoplayer/progress/*`：播放进度、拖动预览、时间显示、独立进度条、全屏菜单与控制栏。
- `.../videoplayer/top/*`：顶部操作栏、更多操作、屏幕模式切换。
- `.../videoplayer/PlaybackSpeedControls.kt`：独立倍速状态和范围管理。
- `.../components/player/PlayerStatusOverlay.kt`：缓冲、异常、播放完成等状态覆盖。
- `.../components/player/VideoPlayer.android.kt` 与 `bilimiao-comm/.../BiliExoPlayerMediampPlayer.kt`：Android 播放器实现与抽象接口的连接。

### 1.2 手势与控制器

播放器手势宿主覆盖以下行为：

- 单击切换控制器显隐。
- 双击暂停/恢复。
- 横向滑动预览并跳转进度，取消手势可撤销预览。
- 左侧纵向滑动调整屏幕亮度，右侧纵向滑动调整系统音量。
- 中部纵向滑动切换全屏/退出全屏。
- 长按临时加速播放。
- 锁定手势、控制器自动隐藏、滑动时临时显示进度条。
- 鼠标/键盘输入也有独立处理，包括快捷键、焦点恢复、滚轮音量等。

相关实现分布在 `GestureFamily.kt`、`GestureLock.kt`、`SwipeSeekerState.kt`、`SwipeVolumeControl.kt`、`BrightnessLevelController.kt`、`FastSkipState.kt`、`GestureIndicator.kt`、`PlayerKeyboardShortcuts.kt`、`KeyboardSeek.kt` 等文件中。它不是简单给 PlayerView 加一个 OnTouchListener，而是将手势识别、预览状态、控制器状态和播放器动作拆开管理。

### 1.3 弹幕与字幕

弹幕相关核心不只是覆盖层：

- `danmaku-engine/` 提供解析器、弹幕模型、过滤器、布局保留器、绘制任务、渲染器、缓存和引擎。
- `BiliDanmakuParser.kt` 负责 B 站弹幕数据解析；Android 平台有 XML 解析、Canvas、Paint、字体和 Displayer 实现。
- `DanmakuOverlay.kt` 将引擎渲染接到播放器画布；播放未真正开始时不提前推进弹幕时钟，暂停/缓冲时冻结，seek 后清屏并重新投喂，倍速影响弹幕出场密度。
- 弹幕设置有字号、透明度、速度、类型、行数等配置入口，并支持本地发送弹幕回显。
- `SubtitleOverlay.kt` 根据当前播放毫秒位置绘制 B 站 JSON 字幕；用推进式索引匹配字幕时间段，字幕独立于弹幕绘制，并根据底部控制栏高度动态避让。

### 1.4 播放状态、播放源与播放列表

- `PlayerDelegateImpl.kt` 将播放操作与界面分离，使用共享 `PlayerSession` 保持播放器、播放位置、播放状态和协程在页面重建后继续存在。
- `PlayerAPI.kt` 负责播放信息接口层。
- `VideoPlayerSource.kt`、`BangumiPlayerSource.kt`、`SmartSourceSelector.kt` 负责视频/番剧播放源和源选择。
- `PlayerSourceState`、`PlaybackState`、`DashSource`、`SubtitleSourceInfo` 等模型分离播放源、音视频轨道、字幕和播放状态。
- `PlayListStore`、`PlayListItemInfo`、`PlayListFrom` 等提供播放列表和来源管理。
- Android 播放服务、MediaSession bridge、播放器平台层处理后台播放和系统媒体控制。
- 分P/番剧播放、自动下一P/下一集、全屏方向与小窗/窗口状态由播放器周边模块共同支撑。

## 2. bilimiao2 与当前播放器对照

当前实现位于 `VideoPlayerActivity.kt`（约 1110 行）及 `activity_video_player.xml`（约 505 行），采用传统 Android View + Media3 ExoPlayer。

| 能力 | 当前 bilibilivdl | bilimiao2 参考实现 | 差距判断 |
|---|---|---|---|
| 播放画面 | Media3 PlayerView，固定约 220dp 高度 | 16:9 自适应与展开全屏框架 | 需要重做尺寸与全屏框架 |
| 播放控制 | 使用 PlayerView 内置控制器 | 自定义顶部/底部/侧边控制器 | 基础可用，交互层较弱 |
| 单击/双击 | 未见独立手势宿主 | 单击显隐、双击暂停/恢复 | 缺失 |
| 滑动调进度 | 未见手势 seek 预览 | 横向滑动、预览、提交/撤销 | 缺失 |
| 亮度/音量 | 未见播放器区域手势调节 | 左亮度、右音量 | 缺失 |
| 长按倍速 | 只有按钮循环切换倍速 | 长按临时倍速与独立速度状态 | 部分缺失 |
| 全屏/锁定 | 没有完整的方向/锁定控制层 | 全屏、方向、手势锁定 | 缺失或不完整 |
| 播放状态层 | 有状态文字 | 缓冲、错误、完成等独立覆盖层 | 部分实现 |
| 弹幕 | 自定义 View 绘制；当前过滤主要覆盖模式 1/4/5/6 | 独立引擎，完整解析/过滤/布局/渲染管线 | 重大差距；需要重点修复 |
| 字幕 | 已支持字幕列表、加载、导出和外挂轨道重载 | 自绘 JSON 字幕层并按时间轴同步 | 有基础功能，渲染方式需完善 |
| 播放会话 | Activity 保存播放位置 | 共享会话跨界面重建存活 | 部分实现 |
| 播放列表 | 有分P/番剧选集入口 | 播放列表状态、来源、拖动和队列管理 | 部分缺失 |
| 后台媒体控制 | 需进一步核对 Service/MediaSession | PlaybackService + MediaSession | 需要专项核对 |

当前页面已有视频详情、UP 主信息、点赞/投币/收藏/分享、相关视频、评论分页/回复、字幕、弹幕开关/导出、分P/番剧选集、画质、倍速等功能。它们不应在播放器重构时被删掉；应该保留为视频画面下方的信息区，并将播放器本身升级为独立、可全屏的交互层。

## 3. KafuuNeko/BiliDownload 下载功能审查

### 3.1 下载任务执行链

关键模块：

- `common/download/ResourceDownloader.kt`：下载单个不可变资源，负责有限次数重试、候选源轮换、Range 续传、ETag/资源身份检查、响应状态/范围校验、取消传播和完成后发布。
- `DownloadCheckpoint.kt`：保存缓存文件的来源身份、ETag 与总长度，避免把不同 CDN 或不同版本的内容错误拼接。
- `DownloadRetryPolicy.kt`、`DownloadFailure.kt`：统一错误分类和重试预算。
- `DownloadSourceSelector.kt`：选择/轮换候选源。
- `DownloadManager.kt`：下载组调度、等待队列、并发限制、暂停/取消、实时快照与进度聚合。
- `DownloadService.kt`：前台服务执行任务并管理通知。
- `DownloadTaskRecoveryUseCase.kt`：按任务数据库状态恢复下载、合成或发布阶段，不是所有失败都从头重新下载。
- `DownloadTaskPostProcessor.kt`：串行执行资源登记、合成和发布等后处理，避免多个任务竞争同一输出。
- `DownloadTaskRequestRegistry.kt`：避免同一任务重复启动。
- `SynthesisRecoveryUseCase.kt`、`ResourcePublishUseCase` 相关模块：将下载、音视频合成与文件发布拆分为可恢复阶段。

### 3.2 数据与资源组织

- Room 分别保存下载任务、DASH 轨道和下载资源；不是只有一个任务表。
- 一个视频/分P对应一个任务组，组内可以有视频轨、音频轨等多个资源。
- 下载资源有独立登记、MIME 类型、文件路径、大小和所属任务信息。
- 下载完成后将资源登记、合成音视频、发布到用户可访问的存储位置；失败可以按所处阶段恢复。
- 文件名、路径、MIME、导出和存储权限有独立工具模块。
- 支持视频、番剧等资源类型，并有格式/编码转换、音频提取、弹幕/字幕导出等周边功能。

### 3.3 下载列表与本地资源

- `DownloadHistoryFragment.kt` 按任务状态显示历史，列表更新有防抖。
- 支持多选、批量删除、批量重试、批量导出，以及选择目标目录。
- `HistoryViewModel.kt`、`HistoryMultiSelectUiState.kt`、`HistoryExportUiState.kt` 将列表、选择和导出状态分离。
- `LocalResourceActivity.kt` 通过数据库资源 ID 观察最新资源、任务和媒体详情，而不是只依赖进入页面时传入的旧路径。
- 本地资源可以进入应用内媒体播放器或外部应用，也可导出、分享、删除。
- `MediaPlayerActivity.kt` / `MediaPlayerLayout.kt` 提供应用内本地视频播放；音频另有播放器。
- 资源列表和详情按任务组/视频分P组织，而不是把每条音轨、视频轨都作为互不相关的任务卡片。

## 4. BiliDownload 与当前下载实现对照

当前实现位于 `BiliDownloadEngine.kt`、`BiliDownloadService.kt`、`DownloadTaskEntity.kt`、`DownloadTaskDao.kt`、`DownloadsFragment.kt` 和 `DownloadedResourceActivity.kt`。

| 能力 | 当前 bilibilivdl | BiliDownload 参考实现 | 差距判断 |
|---|---|---|---|
| 后台下载 | 前台 Service + Room 任务队列 | 前台 Service + Manager 调度 | 已有基础 |
| 并发 | 当前服务用 Semaphore 限制并发 | Manager 统一调度下载组与等待队列 | 需要完善队列调度 |
| DASH | 同时下载视频/音频并用 MediaMuxer 合并 | 视频/音频资源分开登记、后处理合成 | 已有合并，缺资源模型 |
| DURL 多段 | 顺序下载分段后合并 | 资源级管理 | 已有基础 |
| 断点续传 | 临时文件 + Range | 检查点 + 来源身份/ETag/Range 校验 | 需要加强跨源续传校验 |
| CDN 回退 | 候选 URL 轮换和重试 | 来源选择器 + 有界统一重试预算 | 已有基础，策略需收敛 |
| 任务恢复 | 中断任务重置为 queued 再执行 | 按下载/合成/发布阶段恢复 | 缺阶段恢复 |
| 任务持久化 | 单个 Room 任务表 | 任务、DASH、资源多表关联 | 重大差距 |
| 失败重试 | 可重试失败任务 | 按阶段和失败类型恢复 | 部分实现 |
| 批量操作 | 全部暂停/继续/取消、重试、清理 | 多选、批量重试/删除/导出 | 部分实现 |
| 已完成列表 | 完成任务卡片显示文件详情入口 | 历史任务与本地资源列表分离 | 部分实现 |
| 本地播放 | 文件详情调用系统 ACTION_VIEW | 应用内播放器 + 外部打开 | 缺应用内本地播放 |
| 文件导出 | 当前支持分享/音频提取/删除 | SAF 目录导出、批量导出、MIME 管理 | 部分缺失 |
| 本地资源管理 | 依赖任务记录的 outputPath | 独立资源数据库与本地资源详情 | 重大差距 |

特别注意：当前 `DownloadsFragment` 是“任务队列/下载历史”，不是完整的本地下载文件库。清理已完成记录不会删除文件，但清理后列表也不再有这些任务记录可供重新打开；因此必须增加独立的“已下载文件”浏览入口，直接从应用拥有的 MediaStore/文件索引发现文件。已完成任务不应是本地文件唯一索引。

## 5. 下一轮改造优先级

### P0：播放器核心体验
1. 将 220dp 固定视频区域升级为自适应 16:9 + 横屏全屏播放器框架。
2. 加入单击显隐、双击暂停、横向滑动 seek、左右亮度/音量、长按临时倍速、手势锁定。
3. 保留现有视频详情、UP主、评论、分P、画质、字幕和互动功能，不因播放器 UI 重构丢失。
4. 修复弹幕数据链：完整解析弹幕模式、校验 CID/分段响应、增加模式 2/3，并以可验证的渲染状态判断“显示成功”，而不是只看接口返回非空。
5. 将弹幕绘制升级为稳定的时间轴驱动渲染；seek/暂停/缓冲时同步状态。评估迁移 `danmaku-engine` 时需同时处理其平台抽象和依赖，不应只复制单个 Overlay 文件。

### P0：下载完成后的文件体验
1. 增加独立“已下载”文件列表，不依赖下载任务表；清理任务记录后文件仍可浏览。
2. 完成文件支持应用内播放，不只跳转系统播放器。
3. 显示封面、标题、UP主、清晰度、文件大小、保存时间；支持播放、分享、导出、删除。
4. 下载任务与本地文件分区显示，任务完成后可从任务卡片跳转对应本地文件。

### P1：下载执行可靠性
1. 将任务状态拆分为下载、合成、发布阶段，进程重启后恢复未完成阶段。
2. 引入资源级记录：视频轨、音频轨、字幕、弹幕和最终合成文件分别登记。
3. 加强 Range/ETag/来源身份校验，CDN 换源时不盲目追加旧文件。
4. 统一重试预算、失败分类和批量重试。
5. 增加多选、批量导出、批量删除和下载组/分P详情。

### P1：播放列表与系统集成
1. 增加播放列表、自动下一P/下一集、播放历史恢复。
2. 核对后台播放、通知栏、耳机按键和 MediaSession。
3. 增加字幕时间轴同步、弹幕设置和播放器状态层。

## 6. 迁移与许可注意事项

两个参考仓库均采用 GPL-3.0。当前项目已有上游源码镜像与许可证文件。后续若直接复制或改编上游实现，应保留原作者版权/许可证声明，并确保本项目分发方式与 GPL-3.0 义务一致。优先移植可独立适配的算法与架构，不要把 Compose Multiplatform / KMP 模块整包塞进当前传统 Android View 工程，避免引入大量不兼容依赖和构建风险。

## 7. 当前审查结论

- 当前播放器不是完全没有功能：视频详情、画质、倍速、分P/番剧、评论、互动、字幕和弹幕入口已经存在。
- 最大差距是播放器核心交互层和弹幕引擎，不是简单换一套按钮颜色。
- 当前下载已有后台队列、暂停/继续、取消、失败重试、DASH 合并和文件详情；最大差距是资源级数据模型、阶段恢复、独立本地文件库和应用内本地播放器。
- 后续应按第 5 节分阶段落地，并在每批提交后验证 Android Debug APK。没有真机验证的功能不得标记为完全完成。
