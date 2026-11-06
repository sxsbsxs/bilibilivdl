# BiliVdl 2.0.18

## 2.0.18 更新

- 移除第三方通用解析器及其内嵌 Python 运行时，下载统一使用 B 站原生播放地址与现有下载队列。
- 播放器进度条改为轻量细轨道，区分已播放与已缓冲进度，控制区调整至画面底部。


BiliVdl 是一款独立设计界面的 Android 哔哩哔哩视频播放与下载应用，使用 Kotlin、Android XML 和 Media3。

## 2.0.3 历史更新

- 播放页互动区采用图标 + 文字的轻量布局，恢复分享、分P、弹幕和字幕的直接入口，不再把常用操作藏进“更多”。
- 新增 BVD 风格的哔哩哔哩官方网页登录页；Cookie 必须通过导航接口验证后才保存，并显示账号昵称与 UID。
- 下载统一使用 B 站原生 DASH/DURL 播放地址与本地下载队列。

## 1.1.0 更新

- 热门推荐列表每次刷新重新混排，减少首页总是先看到相同视频的情况。
- 修复首页 NestedScrollView + RecyclerView 组合在首屏内容不足时不触发翻页的问题，并在首屏未填满时自动补充下一页。
- 修复播放器评论列表位于外层 NestedScrollView、内部 RecyclerView 无法收到滚动事件导致的“滑到底不加载”问题。
- 账号页迁移 BVD 风格的扫码登录流程：申请二维码、轮询扫码/确认状态、接收登录 Cookie，并通过 B 站账号接口确认登录状态。
- 评论读取继续使用 bilimiao2 的 CommentApi，读取评论不主动要求登录；评论分页使用返回的 cursor/page 信息判断是否还有下一页。

## 视频

- 关键词搜索视频，支持直接输入 BV 号或 AV 号
- 参考 bilimiao2 的 gRPC PlayURL 优先 + 免登录公开 HTTP `/x/player/playurl` 备用双线路；gRPC 不可用时自动回退 HTTP，登录 Cookie 仅作为可选项；HTTP 公开接口受限时再回退 WBI 播放接口
- 获取视频播放信息和可用清晰度
- Media3 播放器，支持 DASH 音视频轨合并、MP4/durl 播放及多段 DURL 连续播放
- 播放清晰度切换、倍速控制
- 番剧关键词搜索、SS/EP 信息读取、剧集列表切换及番剧 DASH/DURL 播放
- 字幕列表获取、字幕选择与 Media3 WebVTT 渲染
- 弹幕加载、时间轴叠加与发送
- 评论分页、排序、回复、点赞与发表评论
- 登录后支持视频点赞、投币、一键三连和收藏夹收藏

## 下载

- 从播放页选择下载清晰度
- DASH 视频轨与音频轨并行下载；多段 DURL 按顺序完整下载并合并
- HTTP Range 断点续传与失败重试
- Android MediaExtractor / MediaMuxer 合并音视频
- 通过前台服务在离开播放页后继续下载
- 下载任务状态保存在本地，完成后写入系统媒体库的 `Movies/BiliVdl`
- 下载管理使用 Room 持久化任务，支持暂停、继续、取消、失败重试及历史清理
- 番剧下载任务保存 EP 编号，后台按番剧播放接口获取对应流

## 账号

- 使用 B 站 APP 扫码登录，并验证会话有效性
- 未登录时可尝试搜索、查看评论、播放和下载公开可用的视频；更高画质仍受 B 站账号权限限制
- 点赞、投币、收藏、发表评论等需要账号权限的操作会提示登录或接口限制

## 技术与上游来源

- Kotlin + Jetpack Compose / Android XML + Material Components
- AndroidX Media3 ExoPlayer
- [bilimiao2](https://github.com/10miaomiao/bilimiao2)：接入网络核心、评论 API 客户端及相关上游模块
- [BiliDownload](https://github.com/KafuuNeko/BiliDownload)：参考并适配其二维码生成、轮询确认和 Cookie 交接流程；上游完整源码保存在 `upstream/BiliDownload/`

两个上游项目均为 GPL-3.0，原始许可证和移植说明保留在仓库。详情见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 [upstream/UPSTREAM_SOURCES.md](upstream/UPSTREAM_SOURCES.md)。

## 构建

GitHub Actions 自动构建 Debug APK：

[查看构建记录](https://github.com/sxsbsxs/bilibilivdl/actions)

## 已知限制

- 热门视频的实际内容与分页由 B 站接口控制；混排只改变当前获取批次的展示顺序，不代表 B 站每次都返回全新数据。
- B站接口可能因地区、网络、账号权限或风控策略限制播放、清晰度和互动。
- 公开视频是否能下载取决于 B 站实际返回的播放流权限。
- DASH 与 DURL 分段合并使用 Android MediaExtractor / MediaMuxer；临时文件按任务 ID 隔离，暂停、进程恢复和网络重试保留可续传数据。兼容性仍需在真机上覆盖不同视频编码/CDN 验证。
- 下载队列已迁移到 Room；旧 SharedPreferences 任务会在启动时迁移。进程被系统强杀后的自动恢复、并发队列调度和批量任务仍需继续完善。
- GitHub Actions 构建成功只代表 APK 可编译，不等于已完成真机播放、下载和互动验证。

## 许可证

GPL-3.0。详见 [LICENSE](LICENSE)。
