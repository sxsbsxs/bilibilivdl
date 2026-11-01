# 第三方项目与许可证说明

本项目整合并适配以下 GPL-3.0 开源项目。上游源码保存在 `upstream/`，其原始许可证、版权声明和来源信息随仓库保留。BiliVdl 的应用界面为独立设计，不复制上游界面。

## bilimiao2

- 上游仓库：https://github.com/10miaomiao/bilimiao2
- 许可证：GNU General Public License v3.0（GPL-3.0-only）
- 源码位置：`upstream/bilimiao2/`
- 已接入 Gradle 的模块：`bilimiao-comm`、`bilimiao-download`、`danmaku-engine`，以及其所需 gRPC/proto 依赖。
- 应用桥接：`BiliVdlApplication.kt` 初始化平台 Provider；`BilimiaoApiBridge.kt` 复用上游 `CommentApi` 与 `VideoAPI`，提供免登录评论读取、评论分页、评论回复、评论点赞/发送及视频互动接口。
- 播放地址适配：`BiliPlaybackApi.kt` 参考并适配上游 `PlayerAPI.getVideoPalyUrl` 的免登录公开播放流程，优先请求 `/x/player/playurl`（avid/cid/qn/fnval=4048/force_host=2），获取 DASH 或 durl 流；公开接口受限时再回退 WBI playurl。播放器仍使用本项目的 Media3 ExoPlayer、DASH 解析和独立 UI，并非完整移植 bilimiao 原版播放服务、GRPC 协议栈或手势 UI。

## BiliDownload（BVD）

- 上游仓库：https://github.com/KafuuNeko/BiliDownload
- 许可证：GNU General Public License v3.0
- 源码位置：`upstream/BiliDownload/`
- 应用当前下载实现：`BiliDownloadEngine.kt` 负责 DASH 音视频轨道并行下载、HTTP Range 续传、失败重试、MediaExtractor/MediaMuxer 合并和 MediaStore 发布；`BiliDownloadService.kt` 负责前台服务下载、任务状态持久化和通知；播放页将下载任务交给该服务。
- 登录适配：`BiliQrLoginApi.kt` 与账号页参考上游二维码登录事务，包含二维码申请、状态轮询、扫码确认、Cookie 接收与账号验证；应用 UI 为独立设计。\n- 说明：上游 BVD 完整 Room 数据库、原版 DownloadService 与批量任务 UI 仍保留在上游源码目录中，当前应用没有将其全部替换为原版实现。因此应用下载模块应称为 BVD 架构适配，不应称为完整原样移植。

## 本项目修改说明

BiliVdl 采用独立 Android UI 与应用入口。所有上游适配均应保留来源标记；发布包含 GPL 覆盖代码的组合版本时，按 GPL-3.0 提供对应源代码、许可证文本和版权声明。

## 验证边界

GitHub Actions 构建成功只表示代码可编译。B站接口权限、地区/网络限制、不同 CDN 的 DASH 编码兼容性、MediaMuxer 合并和真机后台下载仍需实际设备验证。未通过真机验证的功能不会标注为已验证。
