import os
import shutil
from urllib.parse import urlparse
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
    host = (urlparse(url).hostname or "").lower()
    if cookie and (host == "bilibili.com" or host.endswith(".bilibili.com")):
        headers["Cookie"] = cookie

    # Prefer separate best video/audio only when a real FFmpeg binary is available.
    # The current APK does not bundle FFmpeg, so Android safely falls back to a
    # progressive stream that already contains both tracks.
    ffmpeg_path = shutil.which("ffmpeg")
    options = {
        "outtmpl": os.path.join(output_dir, "%(title).140B [%(id)s].%(ext)s"),
        "format": "bestvideo+bestaudio/best" if ffmpeg_path else "best[acodec!=none][vcodec!=none]/best",
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "ignoreerrors": False,
        "http_headers": headers,
        "retries": 8,
        "extractor_retries": 3,
        "file_access_retries": 3,
        "fragment_retries": 8,
        "socket_timeout": 30,
        "concurrent_fragment_downloads": 4,
        "continuedl": True,
        "overwrites": False,
    }
    if ffmpeg_path:
        options["ffmpeg_location"] = ffmpeg_path
    with YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)
        if not info:
            raise RuntimeError("yt-dlp 没有返回视频信息")
        requested = info.get("requested_downloads") or []
        path = requested[0].get("filepath") if requested else None
        if not path:
            path = info.get("_filename") or ydl.prepare_filename(info)
        path = os.path.realpath(path)
        root = os.path.realpath(output_dir)
        if os.path.commonpath([root, path]) != root:
            raise RuntimeError("yt-dlp 返回的文件路径超出了应用下载目录")
        if not os.path.isfile(path) or os.path.getsize(path) <= 0:
            raise RuntimeError("下载结束但没有生成有效视频文件")
        return path
