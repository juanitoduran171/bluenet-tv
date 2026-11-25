#!/usr/bin/env python3
"""Servidor Xtream simulado para probar XtreamClient (no es parte de la app)."""
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

GOOD = ("juan", "cl ave+1&é")
LIVE_CATS = [{"category_id": "1", "category_name": "Noticias", "parent_id": 0},
             {"category_id": "2", "category_name": None, "parent_id": 0}]
VOD_CATS = [{"category_id": "10", "category_name": "Acción", "parent_id": 0}]
LIVE = {"1": [{"num": 1, "name": "Canal Uno", "stream_type": "live", "stream_id": 101, "stream_icon": "http://x/u.png", "category_id": "1"},
              {"num": 2, "name": "Canal Dos", "stream_type": "live", "stream_id": 102, "stream_icon": None, "category_id": "1"}],
        "2": []}
SER_CATS = [{"category_id": "50", "category_name": "Dramas", "parent_id": 0}]
SERIES = {"50": [{"num": 1, "name": "Serie A", "series_id": 301, "cover": "http://x/sa.jpg", "rating": "8", "category_id": "50"},
                 {"num": 2, "name": "Serie B", "series_id": 302, "cover": None, "category_id": "50"},
                 {"num": 3, "name": "Serie vacía", "series_id": 303, "cover": "", "category_id": "50"}]}
EP = lambda i, n, s, ext="mkv", img=None: {"id": str(i), "episode_num": n, "title": "Ep %s" % n, "container_extension": ext, "season": s,
                                       "info": {"movie_image": img, "plot": "trama %s" % n, "duration": "00:42:00"}}
INFO = {
  "301": {"info": {"name": "Serie A", "plot": "Una serie", "cover": "http://x/sa.jpg", "genre": "Drama", "releaseDate": "2019-05-01", "rating": "8"},
          "seasons": [{"season_number": 1, "name": "Primera temporada"}],
          "episodes": {"2": [EP(902, 2, 2), EP(901, 1, 2, img="http://x/e.jpg")], "1": [EP(801, 3, 1), EP(800, 1, 1), EP(802, 2, 1, ext="")]}},
  "302": {"info": {"name": "Serie B", "release_date": None}, "seasons": [],
          "episodes": [[EP(700, 1, 1, "mp4")], [EP(710, 1, 2, "mp4"), EP(711, 2, 2, "mp4")]]},
  "303": {"info": {"name": "Serie vacía"}, "episodes": []},
}
VOD = {"10": [{"name": "Película A", "stream_id": 201, "stream_icon": "", "cover": "http://x/c.jpg", "rating": "7.5", "container_extension": "mkv", "category_id": "10"},
              {"name": "Película B", "stream_id": 202, "stream_icon": "http://x/b.jpg", "rating": None, "category_id": "10"}]}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, body, ctype="application/json", headers=None):
        b = body.encode() if isinstance(body, str) else body
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(b)))
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path == "/notxtream/player_api.php":
            return self.send(200, "<html>hola</html>", "text/html")
        if u.path == "/redir/player_api.php":
            return self.send(302, "", headers={"Location": "/player_api.php?" + u.query})
        if u.path == "/forbidden/player_api.php":
            return self.send(403, "no")
        if u.path != "/player_api.php":
            return self.send(404, "nf")
        user, pw = q.get("username"), q.get("password")
        act = q.get("action")
        if (user, pw) == ("bloq", "x"):          # panel que rechaza claves malas con 403
            return self.send(403, "Forbidden", "text/html")
        if (user, pw) == ("caido", "x"):         # servicio con falla interna
            return self.send(503, "Service Unavailable", "text/html")
        if (user, pw) == ("vencido", "x"):
            return self.send(200, json.dumps({"user_info": {"auth": 1, "status": "Expired"}}))
        if (user, pw) != GOOD:
            return self.send(200, json.dumps({"user_info": {"auth": 0}}))
        if act is None:
            return self.send(200, json.dumps({"user_info": {"auth": 1, "status": "Active", "exp_date": "1893456000",
                    "max_connections": "1", "active_cons": "0", "allowed_output_formats": ["m3u8", "ts"]}, "server_info": {"url": "x"}}))
        if act == "get_live_categories": return self.send(200, json.dumps(LIVE_CATS))
        if act == "get_vod_categories": return self.send(200, json.dumps(VOD_CATS))
        if act == "get_series_categories": return self.send(200, json.dumps(SER_CATS))
        if act == "get_series":
            c = q.get("category_id"); return self.send(200, json.dumps(SERIES.get(c, []) if c else sum(SERIES.values(), [])))
        if act == "get_series_info": return self.send(200, json.dumps(INFO.get(q.get("series_id"), {"info": [], "episodes": []})))
        if act == "get_live_streams":
            c = q.get("category_id"); return self.send(200, json.dumps(LIVE.get(c, []) if c else sum(LIVE.values(), [])))
        if act == "get_vod_streams":
            c = q.get("category_id")
            if c == "999": return self.send(200, "false")
            return self.send(200, json.dumps(VOD.get(c, []) if c else sum(VOD.values(), [])))
        return self.send(200, "{}")

if __name__ == "__main__":
    HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
