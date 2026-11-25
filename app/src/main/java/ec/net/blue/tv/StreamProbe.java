package ec.net.blue.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Analiza un stream en vivo (HLS o MPEG-TS directo) y dice que codecs de video y audio trae.
 * Sin dependencias de Android, para poder probarlo en una JVM normal.
 */
public final class StreamProbe {

    public static final class Result {
        public int http;                                   // ultimo codigo HTTP (0 = sin respuesta)
        public String container = "?";                     // HLS, TS, MP4, ?
        public final List<String> video = new ArrayList<String>();
        public final List<String> audio = new ArrayList<String>();
        public String hlsCodecs = "";                      // atributo CODECS de la lista maestra
        public String note = "";                           // errores y observaciones
        public int videoPackets;
        public int audioPackets;

        /** 0 = deberia funcionar con el reproductor de Android, 1 = depende del equipo, 2 = problema. */
        public int level() {
            if (note.startsWith("ERROR") || http >= 400 || http == 0) {
                return 2;
            }
            if (video.isEmpty()) {
                if (note.contains("fMP4")) {
                    return 1;
                }
                return hlsCodecs.length() > 0 ? codecStringLevel() : 2;
            }
            int lvl = 0;
            for (String v : video) {
                if (!v.equals("H.264")) {
                    lvl = 1;
                }
            }
            for (String a : audio) {
                if (!(a.equals("AAC") || a.equals("MP3"))) {
                    lvl = 1;
                }
            }
            if (videoPackets == 0 && container.equals("TS")) {
                lvl = Math.max(lvl, 1);
            }
            return lvl;
        }

        private int codecStringLevel() {
            String c = hlsCodecs.toLowerCase();
            if (c.contains("hvc1") || c.contains("hev1") || c.contains("ac-3") || c.contains("ec-3")
                    || c.contains("mp4v")) {
                return 1;
            }
            return 0;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder(container);
            if (http >= 400 || http == 0) {
                return sb.append(" · HTTP ").append(http).append(note.length() > 0 ? " · " + note : "").toString();
            }
            if (!video.isEmpty() || !audio.isEmpty()) {
                sb.append(" · ").append(video.isEmpty() ? "sin video" : join(video));
                sb.append(" + ").append(audio.isEmpty() ? "sin audio" : join(audio));
            } else if (hlsCodecs.length() > 0) {
                sb.append(" · ").append(hlsCodecs);
            }
            if (note.length() > 0) {
                sb.append(" · ").append(note);
            }
            return sb.toString();
        }

        /** Explicacion en espanol de por que un canal podria no verse. Vacio si no hay motivo claro. */
        public String problemHint() {
            StringBuilder sb = new StringBuilder();
            for (String v : video) {
                if (v.equals("HEVC")) {
                    sb.append("Video HEVC (H.265): muchos equipos no lo decodifican con el reproductor nativo. ");
                } else if (v.equals("MPEG-2")) {
                    sb.append("Video MPEG-2: no todos los equipos lo soportan. ");
                } else if (!v.equals("H.264")) {
                    sb.append("Video ").append(v).append(" poco compatible. ");
                }
            }
            for (String a : audio) {
                if (a.equals("AC3") || a.equals("E-AC3") || a.equals("DTS") || a.equals("MP2") || a.equals("MP1")) {
                    sb.append("Audio ").append(a).append(": puede dar video sin sonido o solo audio. ");
                }
            }
            if (note.contains("fMP4")) {
                sb.append("HLS con segmentos fMP4: solo lo reproducen bien equipos con Android reciente. ");
            } else if (note.contains("no reconocido")) {
                sb.append("El servidor devolvió datos que no son un stream de video. ");
            } else if (video.isEmpty() && hlsCodecs.length() == 0 && http > 0 && http < 400
                    && container.length() > 0 && !container.equals("?") && !note.startsWith("ERROR")
                    && note.indexOf("no se pudo") < 0) {
                sb.append("El stream no trae video. ");
            }
            if (http == 404) {
                sb.append("El servidor no encontró este canal (HTTP 404). ");
            } else if (http >= 400) {
                sb.append("El servidor respondió HTTP ").append(http)
                        .append(" (¿límite de conexiones de la cuenta?). ");
            }
            return sb.toString().trim();
        }

        private static String join(List<String> l) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append("/");
                }
                sb.append(l.get(i));
            }
            return sb.toString();
        }
    }

    private static final class Fetched {
        int status;
        String finalUrl;
        byte[] data = new byte[0];
    }

    private static final int TS_SAMPLE = 400 * 1024;
    private static final int TEXT_SAMPLE = 96 * 1024;

    private StreamProbe() {
    }

    /** deadlineMs: tiempo maximo total para todo el analisis. */
    public static Result probe(String url, int deadlineMs) {
        Result r = new Result();
        long deadline = System.currentTimeMillis() + deadlineMs;
        try {
            Fetched f = fetch(url, TS_SAMPLE, deadline, true);
            r.http = f.status;
            if (f.status >= 400 || f.status == 0) {
                return r;
            }
            if (f.data.length == 0) {
                r.note = "ERROR: respuesta vacía";
                return r;
            }
            if (looksLikeText(f.data)) {
                r.container = "HLS";
                probeHls(f, r, deadline);
            } else if (findTsSync(f.data) >= 0) {
                r.container = "TS";
                parseTs(f.data, r);
            } else if (f.data.length > 12 && f.data[4] == 'f' && f.data[5] == 't' && f.data[6] == 'y'
                    && f.data[7] == 'p') {
                r.container = "MP4";
                r.note = "contenedor MP4";
            } else {
                r.note = "formato no reconocido";
            }
        } catch (IOException e) {
            r.note = "ERROR: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (RuntimeException e) {
            r.note = "ERROR: análisis (" + e.getClass().getSimpleName() + ")";
        }
        return r;
    }

    // ------------------------------------------------------------------ HLS

    private static void probeHls(Fetched first, Result r, long deadline) throws IOException {
        String text = new String(first.data, "UTF-8");
        String base = first.finalUrl;
        if (text.contains("#EXT-X-STREAM-INF")) {
            String[] lines = text.split("\r?\n");
            int variants = 0;
            String firstUri = null;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                    variants++;
                    if (firstUri == null) {
                        String cod = attr(lines[i], "CODECS");
                        if (cod != null) {
                            r.hlsCodecs = cod;
                        }
                        for (int j = i + 1; j < lines.length; j++) {
                            String l = lines[j].trim();
                            if (l.length() > 0 && !l.startsWith("#")) {
                                firstUri = l;
                                break;
                            }
                        }
                    }
                }
            }
            if (firstUri == null) {
                r.note = "lista HLS sin variantes";
                return;
            }
            if (variants > 1) {
                r.note = variants + " calidades";
            }
            Fetched media = fetch(resolve(base, firstUri), TEXT_SAMPLE, deadline, false);
            if (media.status >= 400 || media.status == 0) {
                r.http = media.status;
                r.note = "no se pudo leer la lista de la variante";
                return;
            }
            text = new String(media.data, "UTF-8");
            base = media.finalUrl;
        }
        // lista de medios: buscamos el primer segmento
        boolean fmp4 = text.contains("#EXT-X-MAP");
        String segUri = null;
        for (String l : text.split("\r?\n")) {
            String t = l.trim();
            if (t.length() > 0 && !t.startsWith("#")) {
                segUri = t;
                break;
            }
        }
        if (segUri == null) {
            r.note = append(r.note, "lista HLS sin segmentos todavía");
            return;
        }
        if (fmp4) {
            r.note = append(r.note, "segmentos fMP4");
            return;
        }
        Fetched seg = fetch(resolve(base, segUri), 300 * 1024, deadline, false);
        if (seg.status >= 400 || seg.status == 0) {
            r.http = seg.status;
            r.note = append(r.note, "no se pudo leer el segmento");
            return;
        }
        if (findTsSync(seg.data) < 0) {
            r.note = append(r.note, "segmento no es MPEG-TS");
            return;
        }
        parseTs(seg.data, r);
    }

    private static String attr(String line, String name) {
        int i = line.indexOf(name + "=");
        if (i < 0) {
            return null;
        }
        i += name.length() + 1;
        if (i < line.length() && line.charAt(i) == '"') {
            int e = line.indexOf('"', i + 1);
            return e < 0 ? line.substring(i + 1) : line.substring(i + 1, e);
        }
        int e = line.indexOf(',', i);
        return e < 0 ? line.substring(i) : line.substring(i, e);
    }

    private static String append(String a, String b) {
        return a.length() == 0 ? b : a + " · " + b;
    }

    // ------------------------------------------------------------------ MPEG-TS

    static int findTsSync(byte[] d) {
        int limit = Math.min(188, d.length - 376);
        for (int i = 0; i < limit; i++) {
            if (d[i] == 0x47 && d[i + 188] == 0x47 && d[i + 376] == 0x47) {
                return i;
            }
        }
        return -1;
    }

    private static final class Section {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean started;
        int need = -1;
    }

    static void parseTs(byte[] d, Result r) {
        int off = findTsSync(d);
        if (off < 0) {
            r.note = append(r.note, "sin sincronía MPEG-TS");
            return;
        }
        Map<Integer, Integer> pidCount = new HashMap<Integer, Integer>();
        Section pat = new Section();
        Section pmt = new Section();
        int pmtPid = -1;
        byte[] patSec = null;
        byte[] pmtSec = null;
        Map<Integer, String> videoPids = new HashMap<Integer, String>();
        Map<Integer, String> audioPids = new HashMap<Integer, String>();

        for (int p = off; p + 188 <= d.length; p += 188) {
            if (d[p] != 0x47) {
                continue;
            }
            int pusi = (d[p + 1] >> 6) & 1;
            int pid = ((d[p + 1] & 0x1F) << 8) | (d[p + 2] & 0xFF);
            int afc = (d[p + 3] >> 4) & 3;
            Integer c = pidCount.get(pid);
            pidCount.put(pid, c == null ? 1 : c + 1);
            int pos = p + 4;
            if ((afc & 2) != 0) {
                pos += 1 + (d[p + 4] & 0xFF);
            }
            if ((afc & 1) == 0 || pos >= p + 188) {
                continue;
            }
            if (pid == 0 && patSec == null) {
                patSec = feedSection(pat, d, pos, p + 188, pusi == 1);
                if (patSec != null) {
                    pmtPid = firstProgramPid(patSec);
                }
            } else if (pid == pmtPid && pmtSec == null) {
                pmtSec = feedSection(pmt, d, pos, p + 188, pusi == 1);
                if (pmtSec != null) {
                    parsePmt(pmtSec, r, videoPids, audioPids);
                }
            }
        }
        if (patSec == null) {
            r.note = append(r.note, "sin tabla PAT");
            return;
        }
        if (pmtSec == null) {
            r.note = append(r.note, "sin tabla PMT en la muestra");
            return;
        }
        for (int pid : videoPids.keySet()) {
            Integer n = pidCount.get(pid);
            r.videoPackets += n == null ? 0 : n;
        }
        for (int pid : audioPids.keySet()) {
            Integer n = pidCount.get(pid);
            r.audioPackets += n == null ? 0 : n;
        }
        refineMpegAudio(d, off, audioPids, r);
        if (!videoPids.isEmpty() && r.videoPackets == 0) {
            r.note = append(r.note, "sin paquetes de video en la muestra");
        }
    }

    /** Lee la capa (I/II/III) del primer frame de audio MPEG para distinguir MP2 de MP3. */
    private static void refineMpegAudio(byte[] d, int off, Map<Integer, String> audioPids, Result r) {
        int idx = r.audio.indexOf("MPEG-A");
        if (idx < 0) {
            return;
        }
        String found = null;
        for (int p = off; p + 188 <= d.length && found == null; p += 188) {
            if (d[p] != 0x47 || ((d[p + 1] >> 6) & 1) == 0) {
                continue;
            }
            int pid = ((d[p + 1] & 0x1F) << 8) | (d[p + 2] & 0xFF);
            if (!"MPEG-A".equals(audioPids.get(pid))) {
                continue;
            }
            int afc = (d[p + 3] >> 4) & 3;
            int pos = p + 4;
            if ((afc & 2) != 0) {
                pos += 1 + (d[p + 4] & 0xFF);
            }
            if ((afc & 1) == 0 || pos + 9 >= p + 188 || d[pos] != 0 || d[pos + 1] != 0 || d[pos + 2] != 1) {
                continue;
            }
            int hdr = pos + 9 + (d[pos + 8] & 0xFF);
            for (int q = hdr; q + 2 <= p + 188; q++) {
                if ((d[q] & 0xFF) == 0xFF && (d[q + 1] & 0xE0) == 0xE0) {
                    int layer = (d[q + 1] >> 1) & 3;
                    found = layer == 3 ? "MP1" : layer == 2 ? "MP2" : layer == 1 ? "MP3" : null;
                    break;
                }
            }
        }
        r.audio.set(idx, found == null ? "MP2" : found);
    }

    /** Reensambla una seccion PSI; devuelve la seccion completa o null si aun falta. */
    private static byte[] feedSection(Section s, byte[] d, int pos, int end, boolean pusi) {
        if (pusi) {
            int pointer = d[pos] & 0xFF;
            pos += 1 + pointer;
            s.buf.reset();
            s.started = true;
            s.need = -1;
        } else if (!s.started) {
            return null;
        }
        if (pos >= end) {
            return null;
        }
        s.buf.write(d, pos, end - pos);
        byte[] cur = s.buf.toByteArray();
        if (s.need < 0 && cur.length >= 3) {
            s.need = 3 + (((cur[1] & 0x0F) << 8) | (cur[2] & 0xFF));
        }
        if (s.need > 0 && cur.length >= s.need) {
            byte[] out = new byte[s.need];
            System.arraycopy(cur, 0, out, 0, s.need);
            return out;
        }
        return null;
    }

    private static int firstProgramPid(byte[] sec) {
        // table_id(1) len(2) tsid(2) ver(1) sec#(1) last(1) | programas de 4 bytes | CRC(4)
        for (int i = 8; i + 4 <= sec.length - 4; i += 4) {
            int program = ((sec[i] & 0xFF) << 8) | (sec[i + 1] & 0xFF);
            int pid = ((sec[i + 2] & 0x1F) << 8) | (sec[i + 3] & 0xFF);
            if (program != 0) {
                return pid;
            }
        }
        return -1;
    }

    private static void parsePmt(byte[] sec, Result r, Map<Integer, String> videoPids,
                                 Map<Integer, String> audioPids) {
        if (sec.length < 16) {
            return;
        }
        int progInfoLen = ((sec[10] & 0x0F) << 8) | (sec[11] & 0xFF);
        int i = 12 + progInfoLen;
        int end = sec.length - 4; // sin CRC
        while (i + 5 <= end) {
            int type = sec[i] & 0xFF;
            int pid = ((sec[i + 1] & 0x1F) << 8) | (sec[i + 2] & 0xFF);
            int infoLen = ((sec[i + 3] & 0x0F) << 8) | (sec[i + 4] & 0xFF);
            int dStart = i + 5;
            int dEnd = Math.min(dStart + infoLen, end);
            String v = videoName(type);
            String a = audioName(type, sec, dStart, dEnd);
            if (v != null) {
                videoPids.put(pid, v);
                if (!r.video.contains(v)) {
                    r.video.add(v);
                }
            } else if (a != null) {
                audioPids.put(pid, a);
                if (!r.audio.contains(a)) {
                    r.audio.add(a);
                }
            }
            i = dStart + infoLen;
        }
    }

    private static String videoName(int type) {
        switch (type) {
            case 0x01:
            case 0x02:
                return "MPEG-2";
            case 0x10:
                return "MPEG-4";
            case 0x1B:
                return "H.264";
            case 0x24:
                return "HEVC";
            default:
                return null;
        }
    }

    private static String audioName(int type, byte[] sec, int dStart, int dEnd) {
        switch (type) {
            case 0x03:
            case 0x04:
                return "MPEG-A"; // se refina a MP1/MP2/MP3 leyendo la cabecera
            case 0x0F:
            case 0x11:
                return "AAC";
            case 0x81:
                return "AC3";
            case 0x87:
                return "E-AC3";
            case 0x82:
            case 0x8A:
                return "DTS";
            case 0x06:
                // datos privados: el descriptor dice si es audio
                for (int p = dStart; p + 2 <= dEnd; ) {
                    int tag = sec[p] & 0xFF;
                    int len = sec[p + 1] & 0xFF;
                    if (tag == 0x6A) {
                        return "AC3";
                    }
                    if (tag == 0x7A) {
                        return "E-AC3";
                    }
                    if (tag == 0x7B) {
                        return "DTS";
                    }
                    p += 2 + len;
                }
                return null;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ red

    private static boolean looksLikeText(byte[] d) {
        int i = 0;
        if (d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        return d.length >= i + 7 && d[i] == '#' && d[i + 1] == 'E' && d[i + 2] == 'X' && d[i + 3] == 'T'
                && d[i + 4] == 'M' && d[i + 5] == '3' && d[i + 6] == 'U';
    }

    private static String resolve(String base, String ref) throws IOException {
        return new URL(new URL(base), ref).toString();
    }

    private static Fetched fetch(String url, int maxBytes, long deadline, boolean firstRequest) throws IOException {
        Fetched out = new Fetched();
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                throw new IOException("tiempo agotado");
            }
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            try {
                c.setConnectTimeout((int) Math.min(left, 6000));
                c.setReadTimeout((int) Math.min(left, 8000));
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", XtreamClient.USER_AGENT);
                c.setRequestProperty("Accept", "*/*");
                out.status = c.getResponseCode();
                out.finalUrl = current;
                if (out.status == 301 || out.status == 302 || out.status == 303 || out.status == 307
                        || out.status == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) {
                        return out;
                    }
                    current = resolve(current, loc);
                    continue;
                }
                if (out.status >= 400) {
                    return out;
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[16384];
                int n;
                try {
                    while (buf.size() < maxBytes && System.currentTimeMillis() < deadline
                            && (n = in.read(tmp, 0, Math.min(tmp.length, maxBytes - buf.size()))) > 0) {
                        buf.write(tmp, 0, n);
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // el stream se detuvo: analizamos lo que llego
                    if (buf.size() == 0) {
                        throw e;
                    }
                }
                out.data = buf.toByteArray();
                return out;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("demasiadas redirecciones");
    }
}
