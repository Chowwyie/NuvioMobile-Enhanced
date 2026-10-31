#!/usr/bin/env python3
"""Local redirect test server for the probe gate.

Serves one file with HTTP Range support and a /redirect endpoint that 307s
to it. Usage: redirect_server.py <directory> <port>. The directory must
contain bbb.mp4 (downloaded by the workflow). Binds 127.0.0.1 only.
"""
import http.server
import os
import sys


class Handler(http.server.BaseHTTPRequestHandler):
    directory = "."
    port = 8931

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/redirect":
            self.send_response(307)
            self.send_header("Location", "http://127.0.0.1:%d/bbb.mp4" % self.port)
            self.end_headers()
            return
        if self.path != "/bbb.mp4":
            self.send_error(404)
            return
        path = os.path.join(self.directory, "bbb.mp4")
        try:
            size = os.path.getsize(path)
        except OSError:
            self.send_error(404)
            return
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
            self.send_header("Content-Type", "video/mp4")
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
        self.send_header("Content-Type", "video/mp4")
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
