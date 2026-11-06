import json
import os
import shutil
from urllib.parse import urlparse
from yt_dlp import YoutubeDL


def _download_path(info, ydl):
    requested = info.get("requested_downloads") or []
    path = requested[0].get("filepath") if requested else None
    if not path:
        path = info.get("_filename") or ydl.prepare_filename(info)
    path = os.path.realpath(path)
    if not os.path.isfile(path) or os.path.getsize(path) <= 0:
        raise RuntimeError("下载结束但没有生成有效视频文件")
    return path


def _download_single_format(url, options, output_dir, suffix, format_selector):
    current = dict(options)
    current["format"] = format_selector
    current["outtmpl"] = os.path.join(
        output_dir, "%(title).140B [%(id)s]." + suffix + ".%(ext)s"
    )
    with YoutubeDL(current) as ydl:
        info = ydl.extract_info(url, download=True)
        if not info:
            raise RuntimeError("yt-dlp 没有返回媒体信息")
        return _download_path(info, ydl)


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
            return json.dumps({"kind": "file", "path": _download_path(info, ydl)})

    # Android ships FFmpegKit as a native library rather than a shell binary.
    # Download the best independent video/audio formats and let Kotlin invoke
    # the bundled FFmpegKit API to mux them without requiring executable files.
    video_path = None
    audio_path = None
    try:
        video_path = _download_single_format(url, options, output_dir, "video", "bestvideo")
        audio_path = _download_single_format(url, options, output_dir, "audio", "bestaudio")
        return json.dumps({
            "kind": "separate",
            "video": video_path,
            "audio": audio_path,
        })
    except Exception:
        for partial in (video_path, audio_path):
            if partial and os.path.isfile(partial):
                try:
                    os.remove(partial)
                except OSError:
                    pass

    # Some sites only expose a progressive file or unsupported separate
    # formats. Fall back to the best stream that already contains audio+video.
    fallback = dict(options)
    fallback["format"] = "best[acodec!=none][vcodec!=none]/best"
    with YoutubeDL(fallback) as ydl:
        info = ydl.extract_info(url, download=True)
        if not info:
            raise RuntimeError("yt-dlp 没有返回视频信息")
        return json.dumps({"kind": "file", "path": _download_path(info, ydl)})
