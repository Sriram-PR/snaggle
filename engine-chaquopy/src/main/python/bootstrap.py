"""Bridge between ChaquopyEngine (Kotlin) and yt-dlp.

yt-dlp itself is not bundled as a Python package: it is loaded from a zipapp on
disk so it can be updated at runtime. Events go back to Kotlin as JSON strings
through `sink.onEvent(str)`.
"""
import importlib
import json
import os
import sys
import tempfile
import threading
import traceback
import zipimport

_config = {}
_cancelled = set()
_job_threads = {}  # job_id -> thread ident running it
_procs = {}  # thread ident -> live ffmpeg/ffprobe Popen objects
_info_cache = {}
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
        'verbose': True,
    }
    if _config.get('quickjs'):
        params['js_runtimes'] = {'quickjs': {'path': _config['quickjs']}}
    if _config.get('ffmpeg'):
        params['ffmpeg_location'] = _config['ffmpeg']
    if _config.get('debug_params'):
        params.update(_config['debug_params'])
    if extra:
        params.update(extra)
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
        _info_cache[url] = info
    out = _summary(info)
    out['formats'] = [
        {k: f.get(k) for k in ('format_id', 'ext', 'vcodec', 'acodec', 'height', 'tbr', 'protocol')}
        for f in info.get('formats') or []
    ]
    return json.dumps(out)


def cancel(job_id):
    with _lock:
        _cancelled.add(job_id)
        procs = list(_procs.get(_job_threads.get(job_id), ()))
    for proc in procs:
        proc.kill()


def download(job_id, url, fmt, merge_format, out_dir, sink):
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
        if d['status'] == 'started':
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

    with _lock:
        _job_threads[job_id] = threading.get_ident()
    try:
        with yt_dlp.YoutubeDL(_params(sink, extra)) as ydl:
            with _lock:
                cached = _info_cache.pop(url, None)
            # Reuse the probe's extraction instead of hitting the site twice.
            info = ydl.process_ie_result(cached, download=True) if cached else ydl.extract_info(url, download=True)
        downloads = info.get('requested_downloads') or []
        path = downloads[-1].get('filepath') if downloads else None
        emit(type='done', path=path, info=_summary(info))
    except Exception as e:  # yt-dlp may wrap _Cancelled, so check the flag rather than the type
        if job_id in _cancelled:
            _delete_partials(touched)
            emit(type='cancelled')
        else:
            # Keep .part files: a retry resumes them.
            emit(type='error', message=str(e), trace=traceback.format_exc())
    finally:
        with _lock:
            _cancelled.discard(job_id)
            _job_threads.pop(job_id, None)
            _procs.pop(threading.get_ident(), None)


def _delete_partials(paths):
    for path in paths:
        root, ext = os.path.splitext(path)
        # .temp<ext> is where yt-dlp's ffmpeg postprocessors write before renaming.
        for candidate in (path, path + '.part', path + '.ytdl', root + '.temp' + ext):
            try:
                os.remove(candidate)
            except FileNotFoundError:
                pass
