# BiliVdl

一款独立开发的 Android 哔哩哔哩视频客户端与下载工具。

## 技术方案
- Kotlin：应用逻辑、网络与播放/下载业务
- Android XML：页面布局
- Material Components：原生控件与主题
- RecyclerView：视频与评论列表
- 分层组织：界面控制、数据模型和 API 请求分离

## 开发阶段
1. 项目基础与整体 UI
2. 视频浏览与搜索
3. 视频播放、弹幕与评论
4. 视频下载系统
5. 账号与完整体验

## 当前已实现
- Android 工程骨架、深色主题和底部导航入口
- 首页、发现搜索页面
- B站视频关键词搜索
- BV 号与 AV 号视频信息查询
- 视频播放页基础结构与 Media3 播放接入
- WBI 播放地址解析、DASH MPD 播放尝试及 durl 回退
- 评论分页、排序、楼中楼回复、点赞与发送
- 弹幕 XML 解析与播放时间轴叠加
- Cookie 导入、账号状态验证与退出
- GitHub Actions 自动构建 Debug APK

## 尚未完成
- bilimiao 的完整播放交互与账号操作移植（投币、收藏等）
- BVD 风格完整下载引擎（DASH 音视频分轨下载、合并、断点续传、任务恢复）
- 完整弹幕控制、发送与高级显示选项
- 登录流程与各类互动接口的实际设备验证

## 上游项目与许可证
本项目参考：
- [bilimiao2](https://github.com/10miaomiao/bilimiao2)：播放、评论、互动与弹幕功能，GPL-3.0-only
- [BiliDownload](https://github.com/KafuuNeko/BiliDownload)：下载系统，GPL-3.0

许可证文本见 [LICENSE](LICENSE)，第三方来源和移植状态见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 构建
GitHub Actions 会在 push 到 main 或提交 Pull Request 时构建 Debug APK。

## 说明
B站接口可能因地区、网络环境、风控策略或账号权限而返回限制结果。构建成功不等于已完成真实设备上的播放、互动和下载验证。
