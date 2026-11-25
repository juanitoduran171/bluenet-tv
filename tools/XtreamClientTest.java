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
        check("403 -> NETWORK (bloqueo, no credenciales)", kindOf("http://127.0.0.1:18080/forbidden", U, P) == ApiException.KIND_NETWORK);
        check("sin servidor -> RESPONSE", kindOf("  ", U, P) == ApiException.KIND_RESPONSE);
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

        System.out.println(fails == 0 ? "\nTODAS LAS PRUEBAS PASARON" : "\nFALLAS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
