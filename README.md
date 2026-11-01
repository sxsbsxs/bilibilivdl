# BiliVdl

一款独立开发的 Android 哔哩哔哩视频客户端与下载工具。

## 技术方案
- Kotlin：应用逻辑、网络与播放/下载业务
- Android XML：全部页面布局
- Material Components：原生控件与主题
- RecyclerView：视频结果列表
- Glide：封面加载
- 分层组织：界面控制、数据模型和 API 请求分离

## 开发阶段
1. 项目基础与整体 UI
2. 视频浏览与搜索
3. 视频播放、弹幕与评论
4. 视频下载系统
5. 账号与完整体验

## 当前已实现
- Android 工程骨架、深色主题和四个底部导航入口
- 首页基础布局、独立发现搜索页面
- B站视频关键词搜索接口
- BV 号与 AV 号视频信息查询
- 搜索结果列表、封面、标题、UP 主、播放量和时长
- GitHub Actions 自动构建 Debug APK

## 构建
GitHub Actions 会在 push 到 main 或提交 Pull Request 时构建 Debug APK。

## 说明
B站接口可能因地区、网络环境、风控策略或登录权限而返回限制结果。当前搜索功能不代表已实现视频播放或下载；这两部分在后续阶段单独开发。
