package ec.net.blue.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/** Datos de sesion en memoria + persistencia en SharedPreferences. */
final class Session {
    static final String DEFAULT_SERVER = Config.SERVER;

    static final String server = Config.SERVER; // fijo: el cliente no lo ve ni lo cambia
    static String base;                    // servidor verificado (con protocolo y puerto)
    static String user;
    static String pass;
    static long expTs;
    static String liveExt = "m3u8";
    static int maxConnections;             // 0 = desconocido
    static boolean useExternal;            // (modo tecnico) abrir los canales en VLC
    static boolean devMode;                // modo tecnico oculto: diagnostico y detalles
    /** Formato que funciono para cada canal en esta sesion (stream_id -> m3u8/ts). */
    static final Map<String, String> extOk = new HashMap<String, String>();

    private static final String PREFS = "bluenet_tv";

    private Session() {
    }

    static boolean isLoggedIn() {
        return base != null && user != null && user.length() > 0 && pass != null && pass.length() > 0;
    }

    static void apply(XtreamClient.Auth a) {
        base = a.base;
        expTs = a.expTs;
        maxConnections = a.maxConnections;
        // Preferimos HLS; si el panel solo permite ts, usamos ts.
        if (a.formats.isEmpty() || a.formats.contains("m3u8")) {
            liveExt = "m3u8";
        } else {
            liveExt = a.formats.get(0);
        }
    }

    static void load(Context c) {
        if (user != null) {
            return; // ya cargada en este proceso
        }
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        user = p.getString("user", "");
        pass = p.getString("pass", "");
        base = p.getString("base", null);
        expTs = p.getLong("exp", 0);
        liveExt = p.getString("liveExt", "m3u8");
        maxConnections = p.getInt("maxConn", 0);
        useExternal = p.getBoolean("useExternal", false);
        devMode = p.getBoolean("devMode", false);
    }

    static void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("user", user)
                .putString("pass", pass)
                .putString("base", base)
                .putLong("exp", expTs)
                .putString("liveExt", liveExt)
                .putInt("maxConn", maxConnections)
                .putBoolean("useExternal", useExternal)
                .putBoolean("devMode", devMode)
                .apply();
    }

    /** Cerrar sesion: olvida la clave y el servidor verificado, conserva usuario y servidor escritos. */
    static void logout(Context c) {
        pass = "";
        base = null;
        expTs = 0;
        save(c);
    }
}
