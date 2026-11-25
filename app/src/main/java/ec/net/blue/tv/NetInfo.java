package ec.net.blue.tv;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/**
 * Describe la red del equipo para soporte: "Red: Wi-Fi «Casa» · señal 72%", "Red: cable", "Red: ninguna".
 * El nombre del Wi-Fi requiere el permiso de ubicacion (exigencia de Android desde la version 8.1).
 */
final class NetInfo {
    private static final int REQ = 7301;

    private NetInfo() {
    }

    /** Hay alguna red activa (aunque no tenga internet). */
    static boolean isConnected(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.getActiveNetwork() != null;
        } catch (RuntimeException e) {
            return true; // ante la duda no culpamos a la red
        }
    }

    static String describe(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) {
                return "Red: ninguna (sin conexión)";
            }
            String base;
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                base = "Red: cable (Ethernet)";
            } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                base = "Red: Wi-Fi " + wifiDetails(c);
            } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                base = "Red: datos móviles";
            } else {
                base = "Red: conectada";
            }
            if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                base += " · sin acceso a internet";
            }
            return base;
        } catch (RuntimeException e) {
            return "Red: desconocida";
        }
    }

    @SuppressWarnings("deprecation")
    private static String wifiDetails(Context c) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
        if (wi == null) {
            return "(nombre no disponible)";
        }
        String ssid = wi.getSSID();
        if (ssid != null && ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        if (ssid == null || ssid.length() == 0 || ssid.equalsIgnoreCase("<unknown ssid>")) {
            ssid = null;
        }
        StringBuilder sb = new StringBuilder(ssid != null ? "«" + ssid + "»" : "(nombre no disponible)");
        int rssi = wi.getRssi();
        if (rssi > -127 && rssi < 0) {
            sb.append(" · señal ").append(WifiManager.calculateSignalLevel(rssi, 101)).append("%");
        }
        return sb.toString();
    }

    /** Pide (una sola vez) el permiso que Android exige para leer el nombre del Wi-Fi. */
    static void askPermissionOnce(Activity a) {
        if (a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences p = a.getSharedPreferences("bluenet_tv", Context.MODE_PRIVATE);
        if (p.getBoolean("askedWifiPerm", false)) {
            return;
        }
        p.edit().putBoolean("askedWifiPerm", true).apply();
        a.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ);
    }
}
