import ec.net.blue.tv.StreamProbe;
import ec.net.blue.tv.StreamProbe.Result;

public class StreamProbeTest {
    static int fails = 0;
    static final String B = "http://127.0.0.1:18081";
    static void check(String name, boolean ok, Result r) {
        System.out.println((ok ? "OK   " : "FALLA") + " " + name + "   -> " + r.summary() + "  [nivel " + r.level() + "]"
            + (r.problemHint().length() > 0 ? "  hint: " + r.problemHint() : ""));
        if (!ok) fails++;
    }
    static Result p(String path) { return StreamProbe.probe(B + path, 12000); }
    public static void main(String[] a) {
        Result r;
        r = p("/h264_aac.ts");   check("TS H.264+AAC", r.container.equals("TS") && r.video.toString().equals("[H.264]") && r.audio.toString().equals("[AAC]") && r.level() == 0, r);
        r = p("/mpeg2_mp2.ts");  check("TS MPEG-2+MP2", r.video.toString().equals("[MPEG-2]") && r.audio.toString().equals("[MP2]") && r.level() == 1, r);
        r = p("/hevc_ac3.ts");   check("TS HEVC+AC3", r.video.toString().equals("[HEVC]") && r.audio.toString().equals("[AC3]") && r.level() == 1, r);
        r = p("/h264_mp3.ts");   check("TS H.264+MP3 (MP3 si es compatible)", r.audio.toString().equals("[MP3]") && r.level() == 0, r);
        r = p("/h264_only.ts");  check("TS solo video", r.video.toString().equals("[H.264]") && r.audio.isEmpty(), r);
        r = p("/redir.ts");      check("redireccion 302 seguida", r.container.equals("TS") && r.video.toString().equals("[H.264]"), r);
        r = p("/hls/media.m3u8");check("HLS lista de medios -> lee segmento TS", r.container.equals("HLS") && r.video.toString().equals("[H.264]") && r.audio.toString().equals("[AAC]") && r.level() == 0, r);
        r = p("/hls/master.m3u8");check("HLS maestra (2 calidades, CODECS)", r.container.equals("HLS") && r.hlsCodecs.startsWith("avc1") && r.note.contains("2 calidades") && r.video.toString().equals("[H.264]"), r);
        r = p("/hlsf/media.m3u8");check("HLS con segmentos fMP4: aviso, no error", r.container.equals("HLS") && r.note.contains("fMP4") && r.level() == 1 && r.problemHint().contains("fMP4") && !r.problemHint().contains("no trae video"), r);
        r = p("/junk.bin");      check("formato no reconocido", r.note.contains("no reconocido") && r.level() == 2 && !r.problemHint().contains("no trae video"), r);
        r = p("/nada.ts");       check("404 -> canal no encontrado (sin hablar de conexiones)", r.http == 404 && r.level() == 2 && r.problemHint().contains("no encontró") && !r.problemHint().contains("conexiones"), r);
        r = p("/forbidden.ts");  check("403 -> pista de limite de conexiones", r.http == 403 && r.problemHint().contains("límite de conexiones"), r);
        long t0 = System.currentTimeMillis();
        r = p("/endless.ts");    check("stream en vivo infinito: corta a los ~400KB", r.video.toString().equals("[H.264]") && System.currentTimeMillis() - t0 < 8000, r);
        t0 = System.currentTimeMillis();
        r = StreamProbe.probe(B + "/stall.ts", 4000);
        check("stream que se queda callado: analiza lo recibido y respeta el limite de tiempo", r.video.toString().equals("[H.264]") && System.currentTimeMillis() - t0 < 6000, r);
        r = StreamProbe.probe("http://127.0.0.1:1/x.ts", 4000);
        check("sin conexion -> ERROR nivel 2", r.note.startsWith("ERROR") && r.level() == 2, r);
        System.out.println(fails == 0 ? "\nTODAS LAS PRUEBAS PASARON" : "\nFALLAS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
