import os
from yt_dlp import YoutubeDL

def download(url, output_dir, cookie=""):
    url = (url or "").strip()
    if not url.startswith(("https://", "http://")):
        raise ValueError("请输入有效的视频网址")
    os.makedirs(output_dir, exist_ok=True)

    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36",
        "Referer": "https://www.bilibili.com/",
    }
    if cookie:
        headers["Cookie"] = cookie

    # Android APK currently ships without an FFmpeg executable. Select a
    # progressive format containing both audio and video to avoid silent files.
    options = {
        "outtmpl": os.path.join(output_dir, "%(title).140B [%(id)s].%(ext)s"),
        "format": "best[acodec!=none][vcodec!=none]/best",
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "ignoreerrors": False,
        "http_headers": headers,
        "retries": 5,
        "fragment_retries": 5,
        "continuedl": True,
        "overwrites": False,
    }
    with YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)
        if not info:
            raise RuntimeError("yt-dlp 没有返回视频信息")
        requested = info.get("requested_downloads") or []
        path = requested[0].get("filepath") if requested else None
        if not path:
            path = ydl.prepare_filename(info)
        if not os.path.isfile(path) or os.path.getsize(path) <= 0:
            raise RuntimeError("下载结束但没有生成有效视频文件")
        return path
