package ec.net.blue.tv;

import ec.net.blue.tv.XtreamClient.Stream;

/** Prueba la seleccion de URL/formato (necesita android.jar en el classpath solo para cargar las clases). */
public class PlaybackTest {
    static int fails = 0;
    static void check(String n, boolean ok) { System.out.println((ok ? "OK   " : "FALLA") + " " + n); if (!ok) fails++; }
    static Stream live(String id) { Stream s = new Stream(); s.id = id; s.live = true; s.name = "c" + id; return s; }
    public static void main(String[] a) {
        Session.base = "http://h:8080"; Session.user = "u"; Session.pass = "p"; Session.liveExt = "m3u8";
        Stream s = live("7");
        check("intento 0 -> formato de la cuenta (m3u8)", Playback.urlFor(s, 0).equals("http://h:8080/live/u/p/7.m3u8"));
        check("intento 1 -> formato alterno (ts)", Playback.urlFor(s, 1).equals("http://h:8080/live/u/p/7.ts"));
        check("intento 2 -> vuelve al preferido", Playback.urlFor(s, 2).equals("http://h:8080/live/u/p/7.m3u8"));
        Session.extOk.put("7", "ts");
        check("canal que funciono con ts: intento 0 -> ts", Playback.urlFor(s, 0).endsWith("/7.ts"));
        check("canal que funciono con ts: intento 1 -> m3u8", Playback.urlFor(s, 1).endsWith("/7.m3u8"));
        check("otro canal no se ve afectado", Playback.urlFor(live("8"), 0).endsWith("/8.m3u8"));
        Session.liveExt = "ts";
        check("cuenta solo-ts: intento 0 -> ts, 1 -> m3u8", Playback.urlFor(live("9"), 0).endsWith("/9.ts") && Playback.urlFor(live("9"), 1).endsWith("/9.m3u8"));
        Stream v = new Stream(); v.id = "201"; v.live = false; v.ext = "mkv";
        check("VOD: misma URL en todos los intentos", Playback.urlFor(v, 0).equals("http://h:8080/movie/u/p/201.mkv") && Playback.urlFor(v, 1).equals(Playback.urlFor(v, 0)));
        v.ext = "";
        check("VOD sin extension -> mp4", Playback.urlFor(v, 0).endsWith("/201.mp4"));
        check("extOfUrl", Playback.extOfUrl("http://h/live/u/p/7.m3u8").equals("m3u8"));
        System.out.println(fails == 0 ? "\nTODAS LAS PRUEBAS PASARON" : "\nFALLAS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
