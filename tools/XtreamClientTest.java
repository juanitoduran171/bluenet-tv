import ec.net.blue.tv.XtreamClient;
import ec.net.blue.tv.XtreamClient.*;
import java.util.List;

public class XtreamClientTest {
    static int fails = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "OK   " : "FALLA") + " " + name); if (!ok) fails++; }
    static int kindOf(String server, String u, String p) {
        try { XtreamClient.authenticate(server, u, p); return -1; } catch (ApiException e) { System.out.println("     (" + e.getMessage() + ")"); return e.kind; }
    }
    public static void main(String[] a) throws Exception {
        String host = "127.0.0.1:18080";
        String U = "juan", P = "cl ave+1&é";

        Auth au = XtreamClient.authenticate(host, U, P);
        check("auth ok base", au.base.equals("http://127.0.0.1:18080"));
        check("auth ok formatos", au.formats.size() == 2 && au.formats.get(0).equals("m3u8"));
        check("auth ok vencimiento", au.expTs == 1893456000L);
        check("auth ok max_connections (texto \"1\")", au.maxConnections == 1);
        check("clave incorrecta -> CREDENTIALS", kindOf(host, U, "mala") == ApiException.KIND_CREDENTIALS);
        check("cuenta vencida -> ACCOUNT", kindOf(host, "vencido", "x") == ApiException.KIND_ACCOUNT);
        check("no-Xtream -> RESPONSE", kindOf("http://127.0.0.1:18080/notxtream", U, P) == ApiException.KIND_RESPONSE);
        check("puerto cerrado -> NETWORK", kindOf("127.0.0.1:1", U, P) == ApiException.KIND_NETWORK);
        check("403 en cualquier ruta -> DENIED", kindOf("http://127.0.0.1:18080/forbidden", U, P) == ApiException.KIND_DENIED);
        check("sin servidor -> RESPONSE", kindOf("  ", U, P) == ApiException.KIND_RESPONSE);
        check("403 del panel -> DENIED (no 'sin internet')", kindOf(host, "bloq", "x") == ApiException.KIND_DENIED);
        check("503 del servicio -> DENIED", kindOf(host, "caido", "x") == ApiException.KIND_DENIED);
        long t0 = System.currentTimeMillis();
        try { XtreamClient.authenticate("127.0.0.1:18080", "bloq", "x", "http://127.0.0.1:18080"); check("servidor conocido 403", false); }
        catch (ApiException e) { check("servidor conocido + 403 -> DENIED inmediato (<2s, sin probar otros puertos)", e.kind == ApiException.KIND_DENIED && System.currentTimeMillis() - t0 < 2000); }
        check("candidatos con servidor conocido primero", XtreamClient.candidates("catv.blue.net.ec", "http://catv.blue.net.ec:8080").get(0).equals("http://catv.blue.net.ec:8080")
            && XtreamClient.candidates("catv.blue.net.ec", "http://catv.blue.net.ec:8080").size() == 5);
        check("login ok con servidor conocido", XtreamClient.authenticate(host, U, P, "http://127.0.0.1:18080").base.equals("http://127.0.0.1:18080"));
        Auth ar = XtreamClient.authenticate("http://127.0.0.1:18080/redir/", U, P);
        check("redireccion 302 seguida", ar.base.endsWith("/redir"));

        String b = au.base;
        List<Category> lc = XtreamClient.categories(b, U, P, true);
        check("cats live: 2", lc.size() == 2);
        check("cat con nombre null -> usa id", lc.get(1).name.equals("2"));
        List<Category> vc = XtreamClient.categories(b, U, P, false);
        check("cats vod acentos", vc.size() == 1 && vc.get(0).name.equals("Acción"));
        List<Stream> ls = XtreamClient.streams(b, U, P, true, "1");
        check("live streams 2", ls.size() == 2 && ls.get(0).id.equals("101") && ls.get(0).live);
        check("icon null -> vacio (no 'null')", ls.get(1).icon.equals(""));
        check("categoria vacia", XtreamClient.streams(b, U, P, true, "2").isEmpty());
        check("todos los live", XtreamClient.streams(b, U, P, true, null).size() == 2);
        List<Stream> vs = XtreamClient.streams(b, U, P, false, "10");
        check("vod ext mkv", vs.get(0).ext.equals("mkv"));
        check("vod cover como icono", vs.get(0).icon.equals("http://x/c.jpg"));
        check("vod ext por defecto mp4", vs.get(1).ext.equals("mp4"));
        check("vod rating null -> vacio", vs.get(1).rating.equals(""));
        check("respuesta 'false' -> lista vacia", XtreamClient.streams(b, U, P, false, "999").isEmpty());

        check("url live", XtreamClient.liveUrl(b, U, P, "101", "m3u8").equals("http://127.0.0.1:18080/live/juan/cl%20ave%2B1%26%C3%A9/101.m3u8"));
        check("url vod", XtreamClient.vodUrl(b, "u", "p", "201", "mkv").equals("http://127.0.0.1:18080/movie/u/p/201.mkv"));

        check("candidatos host", XtreamClient.candidates("catv.blue.net.ec").toString().equals(
            "[http://catv.blue.net.ec, https://catv.blue.net.ec, http://catv.blue.net.ec:8080, http://catv.blue.net.ec:25461, https://catv.blue.net.ec:8443]"));
        check("candidatos url completa", XtreamClient.candidates("https://x.com:8443/player_api.php?a=1").toString().equals("[https://x.com:8443]"));
        check("candidatos host:puerto", XtreamClient.candidates("x.com:8080/").toString().equals("[http://x.com:8080, https://x.com:8080]"));

        // ---- series ----
        List<Category> sc = XtreamClient.categories(b, U, P, XtreamClient.TYPE_SERIES);
        check("cats series", sc.size() == 1 && sc.get(0).name.equals("Dramas"));
        List<Stream> ss = XtreamClient.streams(b, U, P, XtreamClient.TYPE_SERIES, "50");
        check("series: 3, id=series_id, tipo SERIES", ss.size() == 3 && ss.get(0).id.equals("301") && ss.get(0).type == XtreamClient.TYPE_SERIES && !ss.get(0).live);
        check("series: cover como icono, null -> vacio", ss.get(0).icon.equals("http://x/sa.jpg") && ss.get(1).icon.equals(""));
        check("series: categoryId", ss.get(0).categoryId.equals("50"));
        check("live: categoryId", XtreamClient.streams(b, U, P, true, "1").get(0).categoryId.equals("1"));
        SeriesInfo a1 = XtreamClient.seriesInfo(b, U, P, "301");
        check("ficha: nombre/genero/anio", a1.name.equals("Serie A") && a1.genre.equals("Drama") && a1.year.equals("2019"));
        check("ficha: temporadas ordenadas 1,2", a1.seasons.size() == 2 && a1.seasons.get(0).number == 1 && a1.seasons.get(1).number == 2);
        check("ficha: nombre de temporada del panel", a1.seasons.get(0).name.equals("Primera temporada") && a1.seasons.get(1).name.equals("Temporada 2"));
        Season s1 = a1.seasons.get(0);
        check("episodios ordenados por numero", s1.episodes.size() == 3 && s1.episodes.get(0).number == 1 && s1.episodes.get(2).number == 3 && s1.episodes.get(0).id.equals("800"));
        check("extension vacia -> mp4", s1.episodes.get(1).ext.equals("mp4") && s1.episodes.get(0).ext.equals("mkv"));
        check("imagen null -> vacio; imagen presente", s1.episodes.get(0).image.equals("") && a1.seasons.get(1).episodes.get(0).image.equals("http://x/e.jpg"));
        SeriesInfo a2 = XtreamClient.seriesInfo(b, U, P, "302");
        check("formato lista de listas: 2 temporadas (1 y 2 episodios)", a2.seasons.size() == 2 && a2.seasons.get(0).episodes.size() == 1 && a2.seasons.get(1).episodes.size() == 2);
        check("release_date null -> anio vacio", a2.year.equals(""));
        check("serie sin episodios -> 0 temporadas", XtreamClient.seriesInfo(b, U, P, "303").seasons.isEmpty());
        check("serie inexistente -> vacia sin error", XtreamClient.seriesInfo(b, U, P, "999").seasons.isEmpty());
        check("url episodio", XtreamClient.seriesUrl(b, U, P, "800", "mkv").equals("http://127.0.0.1:18080/series/juan/cl%20ave%2B1%26%C3%A9/800.mkv"));

        System.out.println(fails == 0 ? "\nTODAS LAS PRUEBAS PASARON" : "\nFALLAS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
