#!/usr/bin/env python3
"""Servidor local para probar StreamProbe: archivos de un directorio + rutas especiales."""
import os, sys, time
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler

ROOT = sys.argv[2]

class H(SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=ROOT, **k)
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path == "/redir.ts":
            self.send_response(302); self.send_header("Location", "/h264_aac.ts"); self.end_headers(); return
        if self.path == "/forbidden.ts":
            self.send_response(403); self.end_headers(); return
        if self.path == "/endless.ts":   # "en vivo": repite el TS sin parar, con ritmo
            data = open(os.path.join(ROOT, "h264_aac.ts"), "rb").read()
            self.send_response(200); self.send_header("Content-Type", "video/mp2t"); self.end_headers()
            try:
                while True:
                    for i in range(0, len(data), 32768):
                        self.wfile.write(data[i:i+32768]); self.wfile.flush(); time.sleep(0.02)
            except Exception:
                pass
            return
        if self.path == "/stall.ts":     # manda un poco y se queda callado
            data = open(os.path.join(ROOT, "h264_aac.ts"), "rb").read()[:150000]
            self.send_response(200); self.end_headers()
            try:
                self.wfile.write(data); self.wfile.flush(); time.sleep(30)
            except Exception:
                pass
            return
        super().do_GET()

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
