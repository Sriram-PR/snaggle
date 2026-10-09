"""Bridge between ChaquopyEngine (Kotlin) and yt-dlp.

yt-dlp itself is not bundled as a Python package: it is loaded from a zipapp on
disk so it can be updated at runtime. Events go back to Kotlin as JSON strings
through `sink.onEvent(str)`.
"""
import importlib
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import traceback
import zipimport

_config = {}
_cancelled = set()
_discard = set()  # cancelled jobs whose partial files are deleted; any discard request wins over a keep
_job_threads = {}  # job_id -> thread ident running it
_procs = {}  # thread ident -> live ffmpeg/ffprobe Popen objects
_info_cache = {}  # url -> (time.monotonic(), info) from probe, reused by the next download
# Stream URLs expire (YouTube's after hours), so only a recent probe is worth reusing.
_INFO_CACHE_SECONDS = 300
_lock = threading.Lock()


class _Cancelled(Exception):
    pass


def init(zip_path, cache_dir, quickjs_path=None, ffmpeg_dir=None):
    # yt-dlp's QuickJS provider writes the solver script to a temp file.
    tempfile.tempdir = cache_dir
    _config.update(cache=cache_dir, quickjs=quickjs_path, ffmpeg=ffmpeg_dir)
    return load(zip_path)


_PACKAGES = ('yt_dlp', 'yt_dlp_ejs')


def load(zip_path):
    """(Re)loads yt-dlp from zip_path in-process, unloading any previously imported version.

    Callers must ensure no download is running: live YoutubeDL objects keep using old modules.
    """
    old = _config.get('zip')
    for name in list(sys.modules):
        if name.split('.')[0] in _PACKAGES:
            del sys.modules[name]
    if old:
        while old in sys.path:
            sys.path.remove(old)
        sys.path_importer_cache.pop(old, None)
        zipimport._zip_directory_cache.pop(old, None)
    with _lock:
        _info_cache.clear()
    sys.path.insert(0, zip_path)
    importlib.invalidate_caches()
    import yt_dlp
    if not yt_dlp.__file__.startswith(zip_path):
        raise ImportError(f'yt_dlp imported from {yt_dlp.__file__}, expected {zip_path}')
    _config['zip'] = zip_path
    _track_ffmpeg_processes()
    return json.dumps(versions())


def _track_ffmpeg_processes():
    """Wraps the Popen used by yt-dlp's ffmpeg postprocessors so cancel() can kill a running merge."""
    import yt_dlp.postprocessor.ffmpeg as ffmpeg_pp
    base = getattr(ffmpeg_pp, 'Popen', None)
    if base is None:  # yt-dlp refactored; cancel then only takes effect after ffmpeg exits
        return

    class TrackedPopen(base):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            with _lock:
                _procs.setdefault(threading.get_ident(), set()).add(self)

        def wait(self, *args, **kwargs):
            try:
                return super().wait(*args, **kwargs)
            finally:
                with _lock:
                    _procs.get(threading.get_ident(), set()).discard(self)

    ffmpeg_pp.Popen = TrackedPopen


def versions():
    import yt_dlp
    from yt_dlp.version import __version__ as ytdlp_version
    try:
        import yt_dlp_ejs
        ejs = getattr(yt_dlp_ejs, 'version', None)
    except ImportError:
        ejs = None
    return {
        'yt_dlp': ytdlp_version,
        'yt_dlp_path': os.path.dirname(os.path.dirname(yt_dlp.__file__)),
        'ejs': ejs,
        'python': sys.version.split()[0],
    }


class _Logger:
    def __init__(self, sink):
        self._sink = sink

    def _emit(self, level, msg):
        if self._sink is not None:
            self._sink.onEvent(json.dumps({'type': 'log', 'level': level, 'message': msg}))

    def debug(self, msg):
        # yt-dlp sends regular screen output through debug() too; only real debug lines carry the prefix.
        self._emit('debug' if msg.startswith('[debug] ') else 'info', msg)

    def info(self, msg):
        self._emit('info', msg)

    def warning(self, msg):
        self._emit('warning', msg)

    def error(self, msg):
        self._emit('error', msg)


def _params(sink, extra=None):
    params = {
        'logger': _Logger(sink),
        'noprogress': True,
        'cachedir': os.path.join(_config['cache'], 'yt-dlp'),
        'js_runtimes': {},
        # A video shared from a playlist (watch?v=…&list=…) means just that video.
        'noplaylist': True,
        # Links that are always several videos (playlist pages, multi-video posts): first one only.
        'playlist_items': '1',
        'verbose': True,
    }
    if _config.get('quickjs'):
        params['js_runtimes'] = {'quickjs': {'path': _config['quickjs']}}
    if _config.get('ffmpeg'):
        params['ffmpeg_location'] = _config['ffmpeg']
    if extra:
        params.update(extra)
    # Testing only, so it wins over everything (e.g. a forced 'format').
    if _config.get('debug_params'):
        params.update(_config['debug_params'])
    return params


def set_debug_params(params_json):
    """Spike/testing only: extra yt-dlp params merged into every call (JSON object or empty)."""
    _config['debug_params'] = json.loads(params_json) if params_json else None


def _summary(info):
    return {
        'id': info.get('id') or '',
        'title': info.get('title') or '',
        'extractor': info.get('extractor_key') or info.get('extractor') or '',
        'duration': info.get('duration'),
        'thumbnail': info.get('thumbnail'),
    }


def probe(url, sink=None):
    import yt_dlp
    # 'all' so probing never fails on format choice; the download picks its own format.
    with yt_dlp.YoutubeDL(_params(sink, {'format': 'all'})) as ydl:
        info = ydl.extract_info(url, download=False)
    with _lock:
        _info_cache[url] = (time.monotonic(), info)
    out = _summary(_playable(info))
    out['formats'] = [
        {**{k: f.get(k) for k in ('format_id', 'ext', 'vcodec', 'acodec', 'height', 'fps', 'tbr', 'abr', 'protocol',
                                   'language', 'language_preference', 'dynamic_range')},
         'size': f.get('filesize') or f.get('filesize_approx')}
        for f in _playable(info).get('formats') or []
    ]
    return json.dumps(out)


def _playable(info):
    """The video itself; for a playlist page or multi-video post, its first entry (see playlist_items)."""
    while info.get('_type') == 'playlist' and info.get('entries'):
        info = info['entries'][0]
    return info


def cancel(job_id, keep_files=False):
    with _lock:
        if keep_files and job_id not in _job_threads:
            return  # already finished: a flag now would cancel the next run of this job
        if not keep_files:
            _discard.add(job_id)
        _cancelled.add(job_id)
        procs = list(_procs.get(_job_threads.get(job_id), ()))
    for proc in procs:
        proc.kill()


def download(job_id, url, fmt, merge_format, out_dir, sink, audio_only=False):
    """Blocking; reports through sink. Returns nothing: the final event is done/cancelled/error."""
    import yt_dlp

    def emit(**event):
        sink.onEvent(json.dumps(event))

    # Every file this job may leave behind; deleted if the job is cancelled.
    touched = set()

    def check_cancel():
        if job_id in _cancelled:
            raise _Cancelled()

    announced = []
    no_space = []

    def check_space(info):
        formats = info.get('requested_formats') or [info]
        sizes = [f.get('filesize') or f.get('filesize_approx') for f in formats]
        if not all(sizes):
            return  # unknown size: let the download find out
        # The finished file is copied into the gallery, so it briefly exists twice.
        have = sum(os.path.getsize(os.path.join(out_dir, n)) for n in os.listdir(out_dir))
        needed = 2 * sum(sizes) - have + _SPACE_MARGIN
        free = shutil.disk_usage(out_dir).free
        if free < needed:
            no_space.append((needed, free))
            raise _NoSpace()

    def progress_hook(d):
        touched.update(filter(None, (d.get('filename'), d.get('tmpfilename'))))
        check_cancel()
        if not announced and d.get('info_dict'):
            announced.append(True)
            emit(type='started', info=_summary(d['info_dict']))
        if d['status'] == 'downloading':
            emit(type='progress',
                 bytes=d.get('downloaded_bytes') or 0,
                 total=d.get('total_bytes') or d.get('total_bytes_estimate'),
                 speed=d.get('speed'),
                 eta=d.get('eta'))

    def pp_hook(d):
        info = d.get('info_dict') or {}
        touched.update(filter(None, (info.get('filepath'), *(info.get('__files_to_merge') or ()))))
        check_cancel()
        if d['status'] == 'started' and d.get('postprocessor') != 'SnaggleHook':
            emit(type='postprocessing', name=d.get('postprocessor'))

    extra = {
        'format': fmt,
        'outtmpl': os.path.join(out_dir, '%(title).80B [%(id)s].%(ext)s'),
        'progress_hooks': [progress_hook],
        'postprocessor_hooks': [pp_hook],
        'hls_prefer_native': True,
    }
    if merge_format:
        extra['merge_output_format'] = merge_format
        # A single non-MP4 stream (e.g. video-only VP9 WebM) isn't merged, so remux it too.
        extra['postprocessors'] = [{'key': 'FFmpegVideoRemuxer', 'preferedformat': merge_format}]
    if audio_only:
        # Audio taken from an MP4 (sites that don't report AAC) ends up as .m4a. AAC is copied, never
        # re-encoded; a .m4a download is left alone; other codecs fail (this ffmpeg has no encoders).
        extra['postprocessors'] = [{'key': 'FFmpegExtractAudio', 'preferredcodec': 'm4a'}]
        extra['writethumbnail'] = True

    with _lock:
        _job_threads[job_id] = threading.get_ident()
    try:
        with yt_dlp.YoutubeDL(_params(sink, extra)) as ydl:
            ydl.add_post_processor(_hook(check_space), when='before_dl')
            # After the merge/remux/audio postprocessors from params.
            ydl.add_post_processor(_tags_pp(ydl), when='post_process')
            if audio_only:
                ydl.add_post_processor(_hook(_keep_embeddable_thumbnails), when='pre_process')
                # After the tags: their ffmpeg pass drops the cover from .m4a.
                ydl.add_post_processor(_cover_pp(ydl), when='post_process')
            with _lock:
                probed_at, cached = _info_cache.pop(url, (0, None))
            if time.monotonic() - probed_at > _INFO_CACHE_SECONDS:
                cached = None
            # Reuse the probe's extraction instead of hitting the site twice.
            info = ydl.process_ie_result(cached, download=True) if cached else ydl.extract_info(url, download=True)
        info = _playable(info)
        downloads = info.get('requested_downloads') or []
        path = downloads[-1].get('filepath') if downloads else None
        emit(type='done', path=path, info=_summary(info))
    except Exception as e:  # yt-dlp may wrap _Cancelled, so check the flag rather than the type
        if job_id in _cancelled:
            if job_id in _discard:
                _delete_partials(touched)
            emit(type='cancelled')
        elif no_space:
            emit(type='nospace', needed=no_space[0][0], free=no_space[0][1])
        else:
            # Keep .part files: a retry resumes them.
            emit(type='error', message=str(e), trace=traceback.format_exc())
    finally:
        with _lock:
            _cancelled.discard(job_id)
            _discard.discard(job_id)
            _job_threads.pop(job_id, None)
            _procs.pop(threading.get_ident(), None)


_SPACE_MARGIN = 50 * 1024 * 1024


class _NoSpace(Exception):
    pass


def _hook(fn):
    """A yt-dlp postprocessor that only calls fn(info)."""
    from yt_dlp.postprocessor.common import PostProcessor

    class SnaggleHookPP(PostProcessor):
        def run(self, info):
            fn(info)
            return [], info

    return SnaggleHookPP()


def _tags_pp(ydl):
    """yt-dlp's FFmpegMetadata (tags and chapters; stream copy), but a failure only skips them."""
    from yt_dlp.postprocessor import FFmpegMetadataPP
    from yt_dlp.utils import prepend_extension

    class SnaggleTagsPP(FFmpegMetadataPP):
        def run(self, info):
            try:
                return super().run(info)
            except Exception as e:
                try:
                    os.remove(prepend_extension(info['filepath'], 'temp'))
                except OSError:
                    pass
                self.report_warning(f'Could not add tags: {e}')
                return [], info

    return SnaggleTagsPP(ydl, add_infojson=False)


# Extensions don't always say (TikTok's ".image" URLs serve JPEG), so only known misfits are dropped.
_NOT_EMBEDDABLE = ('webp', 'avif', 'gif', 'heic', 'heif')


def _keep_embeddable_thumbnails(info):
    """mutagen embeds JPEG/PNG as is; anything else needs an ffmpeg image encoder, which our build lacks."""
    from yt_dlp.utils import determine_ext
    kept = []
    for t in info.get('thumbnails') or []:
        ext = t.get('ext') or determine_ext(t['url'], 'jpg')
        if ext in _NOT_EMBEDDABLE:
            continue
        # Otherwise yt-dlp converts by extension; it sniffs the bytes (and renames real WebP) before embedding.
        if ext not in ('jpg', 'jpeg', 'png'):
            t['ext'] = 'jpg'
        kept.append(t)
    info['thumbnails'] = kept


def _cover_pp(ydl):
    """yt-dlp's EmbedThumbnail (mutagen for .m4a), but a failure only skips the cover."""
    from yt_dlp.postprocessor import EmbedThumbnailPP

    class SnaggleCoverPP(EmbedThumbnailPP):
        def run(self, info):
            try:
                return super().run(info)
            except Exception as e:
                self.report_warning(f'Could not add cover art: {e}')
                thumbs = [t['filepath'] for t in info.get('thumbnails') or () if t.get('filepath')]
                return thumbs, info

    return SnaggleCoverPP(ydl)


def _delete_partials(paths):
    for path in paths:
        root, ext = os.path.splitext(path)
        # .temp<ext> is where yt-dlp's ffmpeg postprocessors write before renaming.
        for candidate in (path, path + '.part', path + '.ytdl', root + '.temp' + ext):
            try:
                os.remove(candidate)
            except FileNotFoundError:
                pass
