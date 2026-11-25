package ec.net.blue.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente minimo de la API clasica de Xtream Codes (player_api.php).
 * Sin dependencias de Android salvo org.json, para poder probarlo en una JVM normal.
 */
public final class XtreamClient {

    public static final String USER_AGENT = "BLUEnetTV/1.0 (Android TV)";

    public static final class Category {
        public final String id;
        public final String name;

        public Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class Stream {
        public String id = "";
        public String name = "";
        public String icon = "";
        public String ext = "";     // solo VOD: container_extension
        public String rating = "";
        public boolean live;
    }

    public static final class Auth {
        public String base;         // servidor que respondio, ej. http://host:8080
        public long expTs;          // epoch en segundos, 0 = sin dato / ilimitado
        public int maxConnections;  // 0 = el panel no lo informa
        public List<String> formats = new ArrayList<String>();
    }

    /** kind: KIND_NETWORK, KIND_CREDENTIALS, KIND_ACCOUNT, KIND_RESPONSE */
    public static final class ApiException extends Exception {
        public static final int KIND_NETWORK = 0;
        public static final int KIND_CREDENTIALS = 1;
        public static final int KIND_ACCOUNT = 2;
        public static final int KIND_RESPONSE = 3;

        public final int kind;

        public ApiException(int kind, String message) {
            super(message);
            this.kind = kind;
        }
    }

    private XtreamClient() {
    }

    // ------------------------------------------------------------------ servidor

    /** Normaliza lo que el usuario escribio y devuelve las bases a probar, en orden. */
    public static List<String> candidates(String input) {
        List<String> out = new ArrayList<String>();
        String s = input == null ? "" : input.trim();
        if (s.length() == 0) {
            return out;
        }
        int q = s.indexOf("/player_api.php");
        if (q >= 0) {
            s = s.substring(0, q);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        String lower = s.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            out.add(s);
            return out;
        }
        boolean hasPort = s.indexOf(':') > 0;
        out.add("http://" + s);
        out.add("https://" + s);
        if (!hasPort) {
            out.add("http://" + s + ":8080");
            out.add("http://" + s + ":25461");
            out.add("https://" + s + ":8443");
        }
        return out;
    }

    /**
     * Valida usuario y clave. Prueba cada base candidata hasta que una responda como Xtream.
     * Si el servidor responde pero rechaza las credenciales, falla de inmediato.
     */
    public static Auth authenticate(String serverInput, String user, String pass) throws ApiException {
        List<String> bases = candidates(serverInput);
        if (bases.isEmpty()) {
            throw new ApiException(ApiException.KIND_RESPONSE, "Escribe el servidor.");
        }
        IOException lastIo = null;
        boolean sawNonXtream = false;
        for (String base : bases) {
            String body;
            try {
                body = httpGet(base + "/player_api.php?username=" + enc(user) + "&password=" + enc(pass), 6000, 20000);
            } catch (HttpStatusException e) {
                if (e.code == 401) {
                    throw new ApiException(ApiException.KIND_CREDENTIALS, "Usuario o contraseña incorrectos.");
                }
                lastIo = e;
                continue;
            } catch (IOException e) {
                lastIo = e;
                continue;
            }
            JSONObject root;
            try {
                root = new JSONObject(body.trim());
            } catch (JSONException e) {
                sawNonXtream = true;
                continue;
            }
            JSONObject ui = root.optJSONObject("user_info");
            if (ui == null) {
                sawNonXtream = true;
                continue;
            }
            if (ui.optInt("auth", 0) != 1) {
                throw new ApiException(ApiException.KIND_CREDENTIALS, "Usuario o contraseña incorrectos.");
            }
            String status = str(ui, "status");
            if (status.length() > 0 && !status.equalsIgnoreCase("Active")) {
                throw new ApiException(ApiException.KIND_ACCOUNT, "La cuenta no está activa (" + status + ").");
            }
            Auth a = new Auth();
            a.base = base;
            String exp = str(ui, "exp_date");
            if (exp.length() > 0) {
                try {
                    a.expTs = Long.parseLong(exp);
                } catch (NumberFormatException ignored) {
                    a.expTs = 0;
                }
            }
            String mc = str(ui, "max_connections");
            if (mc.length() > 0) {
                try {
                    a.maxConnections = Integer.parseInt(mc);
                } catch (NumberFormatException ignored) {
                    a.maxConnections = 0;
                }
            }
            JSONArray f = ui.optJSONArray("allowed_output_formats");
            if (f != null) {
                for (int i = 0; i < f.length(); i++) {
                    String v = f.optString(i, "").trim().toLowerCase();
                    if (v.length() > 0) {
                        a.formats.add(v);
                    }
                }
            }
            return a;
        }
        if (sawNonXtream) {
            throw new ApiException(ApiException.KIND_RESPONSE,
                    "El servidor respondió, pero no parece una API Xtream. Revisa la dirección.");
        }
        String why = lastIo == null ? "" : " (" + lastIo.getMessage() + ")";
        throw new ApiException(ApiException.KIND_NETWORK, "No se pudo conectar con el servidor" + why + ".");
    }

    // ------------------------------------------------------------------ catalogo

    public static List<Category> categories(String base, String user, String pass, boolean live) throws ApiException {
        String action = live ? "get_live_categories" : "get_vod_categories";
        JSONArray arr = getArray(api(base, user, pass, action, null));
        List<Category> out = new ArrayList<Category>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = str(o, "category_id");
            String name = str(o, "category_name");
            if (id.length() == 0) {
                continue;
            }
            out.add(new Category(id, name.length() == 0 ? id : name));
        }
        return out;
    }

    /** categoryId == null devuelve todo el catalogo. */
    public static List<Stream> streams(String base, String user, String pass, boolean live, String categoryId)
            throws ApiException {
        String action = live ? "get_live_streams" : "get_vod_streams";
        JSONArray arr = getArray(api(base, user, pass, action, categoryId));
        List<Stream> out = new ArrayList<Stream>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Stream s = new Stream();
            s.live = live;
            s.id = str(o, "stream_id");
            s.name = str(o, "name");
            s.icon = str(o, "stream_icon");
            if (s.icon.length() == 0) {
                s.icon = str(o, "cover");
            }
            s.rating = str(o, "rating");
            s.ext = live ? "" : str(o, "container_extension");
            if (!live && s.ext.length() == 0) {
                s.ext = "mp4";
            }
            if (s.id.length() == 0) {
                continue;
            }
            if (s.name.length() == 0) {
                s.name = s.id;
            }
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ URLs de reproduccion

    public static String liveUrl(String base, String user, String pass, String streamId, String ext) {
        return base + "/live/" + seg(user) + "/" + seg(pass) + "/" + seg(streamId) + "." + ext;
    }

    public static String vodUrl(String base, String user, String pass, String streamId, String ext) {
        return base + "/movie/" + seg(user) + "/" + seg(pass) + "/" + seg(streamId) + "." + ext;
    }

    // ------------------------------------------------------------------ internos

    private static String api(String base, String user, String pass, String action, String categoryId) {
        StringBuilder sb = new StringBuilder(base);
        sb.append("/player_api.php?username=").append(enc(user));
        sb.append("&password=").append(enc(pass));
        sb.append("&action=").append(action);
        if (categoryId != null) {
            sb.append("&category_id=").append(enc(categoryId));
        }
        return sb.toString();
    }

    private static JSONArray getArray(String url) throws ApiException {
        String body;
        try {
            body = httpGet(url, 10000, 60000).trim();
        } catch (IOException e) {
            throw new ApiException(ApiException.KIND_NETWORK, "Error de red: " + e.getMessage());
        }
        if (body.length() == 0 || body.charAt(0) != '[') {
            // Algunos paneles devuelven {} o false cuando no hay datos.
            return new JSONArray();
        }
        try {
            return new JSONArray(body);
        } catch (JSONException e) {
            throw new ApiException(ApiException.KIND_RESPONSE, "Respuesta inválida del servidor.");
        }
    }

    /** org.json devuelve la cadena "null" para valores JSON null; aqui los tratamos como vacio. */
    static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) {
            return "";
        }
        return o.optString(key, "").trim();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Para segmentos de ruta: igual que enc pero con %20 en vez de +. */
    static String seg(String s) {
        return enc(s).replace("+", "%20");
    }

    static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    static String httpGet(String url, int connectMs, int readMs) throws IOException {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            try {
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(readMs);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept", "application/json, */*");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) {
                        throw new HttpStatusException(code);
                    }
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code >= 400) {
                    throw new HttpStatusException(code);
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                in.close();
                return new String(out.toByteArray(), "UTF-8");
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Demasiadas redirecciones");
    }
}
