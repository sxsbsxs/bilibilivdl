# 第三方项目与许可证说明

本项目参考并计划整合以下开源项目的功能与实现。凡实际复制、修改或移植的代码，均应在对应源文件保留原作者版权和许可证声明，并在此记录来源与修改情况。

## BiliMiao（bilimiao2）

- 上游仓库：https://github.com/10miaomiao/bilimiao2
- 许可证：GNU General Public License v3.0（GPL-3.0-only）
- 参考范围：视频播放、评论与楼中楼回复、点赞/互动、弹幕处理及相关 Bilibili 接口实现。
- 当前实际适配：`BiliVideoActionApi.kt`（点赞、投币、收藏、一键三连、发送弹幕）；播放页已接入按钮。并非 bilimiao2 的完整播放器 UI/播放服务移植。

## BiliDownload（BVD）

- 上游仓库：https://github.com/KafuuNeko/BiliDownload
- 许可证：GNU General Public License v3.0
- 参考范围：下载任务管理、DASH 音视频分轨、断点续传、并发下载、媒体合并与资源管理。
- 当前实际适配：`BiliDownloadEngine.kt`（DASH 音视频分轨并行下载、HTTP Range 续传、有限重试、FFmpeg stream-copy 合并、MediaStore 发布）；下载任务页显示任务状态。此为按 BVD 架构适配的实现，不是整套 BVD 源码原样迁入。

## 本项目修改说明

BiliVdl 是独立 Android 客户端工程。项目在整合上游代码时会标记修改内容和日期，不会将上游作者作品冒充为本项目原创。若发布包含 GPL 覆盖代码的组合版本，将按 GPL-3.0 的要求提供完整对应源代码、许可证文本及必要版权声明。

本项目已开始按 GPL-3.0 条款整合和适配上述项目。仍未完整移植 bilimiao2 的播放器服务/交互，也未移植 BVD 的 Room 任务数据库、后台下载服务、任务暂停恢复与批量下载工作流。
