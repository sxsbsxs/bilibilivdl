# BiliVdl 1.0.0

BiliVdl 是一款独立设计界面的 Android 哔哩哔哩视频播放与下载应用，使用 Kotlin、Android XML 和 Media3。

## 1.0.0 功能

### 视频
- 关键词搜索视频，支持直接输入 BV 号或 AV 号
- 获取视频播放信息和可用清晰度
- Media3 播放器，支持 DASH 与 MP4/durl 播放
- 播放清晰度切换、倍速控制
- 弹幕加载、时间轴叠加与发送
- 评论分页、排序、回复、点赞与发表评论
- 登录后支持视频点赞、投币、一键三连和收藏夹收藏

### 下载
- 从播放页选择下载清晰度
- DASH 视频轨与音频轨并行下载
- HTTP Range 断点续传与失败重试
- Android MediaExtractor / MediaMuxer 合并音视频
- 通过前台服务在离开播放页后继续下载
- 下载任务状态保存在本地，完成后写入系统媒体库的 `Movies/BiliVdl`

### 账号
- 支持使用 B站 Cookie 登录
- 未登录时可尝试搜索、播放和下载公开可用的视频
- 需要账号权限的互动操作会提示登录或接口限制

## 技术与上游来源

- Kotlin + Android XML + Material Components
- AndroidX Media3 ExoPlayer
- [bilimiao2](https://github.com/10miaomiao/bilimiao2)：已接入其网络核心和视频/评论 API 客户端，并集成其播放/弹幕相关库源码
- [BiliDownload](https://github.com/KafuuNeko/BiliDownload)：上游下载项目源码保存在 `upstream/BiliDownload/`；本应用下载入口采用独立适配实现，尚未把其 Room 任务数据库与全部下载业务类完整替换进应用

两个上游项目均为 GPL-3.0，原始许可证和移植说明保留在仓库。详情见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 [upstream/UPSTREAM_SOURCES.md](upstream/UPSTREAM_SOURCES.md)。

## 构建

GitHub Actions 自动构建 Debug APK：

[查看构建记录](https://github.com/sxsbsxs/bilibilivdl/actions)

## 已知限制

- B站接口可能因地区、网络、账号权限或风控策略限制播放、清晰度和互动。
- 公开视频是否能下载取决于 B站实际返回的播放流权限。
- DASH 轨道合并受 Android 系统解码器和具体编码格式影响；需在真机上覆盖不同视频编码/CDN 验证。
- 当前下载队列已具备前台服务执行与本地任务状态，但尚未完整迁移 BVD 的 Room 数据库、批量任务、任务暂停/取消界面和全部恢复策略。
- GitHub Actions 构建成功只代表 APK 可编译，不等于已完成真机播放、下载和互动验证。

## 许可证

GPL-3.0。详见 [LICENSE](LICENSE)。
