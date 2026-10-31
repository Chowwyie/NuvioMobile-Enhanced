#!/usr/bin/env python3
"""Local test server for the probe gate.

Serves files under a directory with HTTP Range support, plus 307
redirect routes for the redirect tests. Usage:
redirect_server.py <directory> <port>. Binds 127.0.0.1 only.
"""
import http.server
import os
import sys


REDIRECTS = {
    "/redirect": "/bbb.mp4",
    "/redirect-mkv": "/bbb-aac.mkv",
}

CONTENT_TYPES = {
    ".mp4": "video/mp4",
    ".mkv": "video/x-matroska",
    ".webm": "video/webm",
    ".m3u8": "application/vnd.apple.mpegurl",
    ".ts": "video/mp2t",
    ".m4s": "video/iso.segment",
    ".mp3": "audio/mpeg",
}


class Handler(http.server.BaseHTTPRequestHandler):
    directory = "."
    port = 8931

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path in REDIRECTS:
            self.send_response(307)
            self.send_header("Location", "http://127.0.0.1:%d%s" % (self.port, REDIRECTS[self.path]))
            self.end_headers()
            return
        rel = self.path.lstrip("/").split("?")[0]
        base = os.path.abspath(self.directory)
        path = os.path.abspath(os.path.join(base, rel))
        if not path.startswith(base + os.sep):
            self.send_error(403)
            return
        try:
            size = os.path.getsize(path)
        except OSError:
            self.send_error(404)
            return
        if os.path.isdir(path):
            self.send_error(404)
            return
        ext = os.path.splitext(path)[1].lower()
        ctype = CONTENT_TYPES.get(ext, "application/octet-stream")
        range_header = self.headers.get("Range")
        if range_header and range_header.startswith("bytes="):
            try:
                spec = range_header[len("bytes="):].split("-")
                start = int(spec[0]) if spec[0] else 0
                end = int(spec[1]) if spec[1] else size - 1
            except ValueError:
                self.send_error(400)
                return
            if start >= size or end < start:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.end_headers()
                return
            end = min(end, size - 1)
            length = end - start + 1
            self.send_response(206)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            self.send_header("Content-Length", str(length))
            self.end_headers()
            with open(path, "rb") as f:
                f.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = f.read(min(65536, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
            return
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with open(path, "rb") as f:
            while True:
                chunk = f.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk)


if __name__ == "__main__":
    Handler.directory = sys.argv[1]
    Handler.port = int(sys.argv[2]) if len(sys.argv) > 2 else 8931
    http.server.ThreadingHTTPServer(("127.0.0.1", Handler.port), Handler).serve_forever()
