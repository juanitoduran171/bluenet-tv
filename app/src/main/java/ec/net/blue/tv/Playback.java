package ec.net.blue.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import ec.net.blue.tv.XtreamClient.Stream;

/** URLs de reproduccion y apertura en reproductores externos (VLC). */
final class Playback {
    private static final String VLC = "org.videolan.vlc";

    private Playback() {
    }

    /** Formato preferido para un canal: el que ya funciono, o el de la cuenta. */
    static String preferredExt(Stream s) {
        String ok = Session.extOk.get(s.id);
        return ok != null ? ok : Session.liveExt;
    }

    static String otherExt(String ext) {
        return "m3u8".equals(ext) ? "ts" : "m3u8";
    }

    /** attempt par: formato preferido; impar: el alterno. En VOD siempre la misma URL. */
    static String urlFor(Stream s, int attempt) {
        if (!s.live) {
            return XtreamClient.vodUrl(Session.base, Session.user, Session.pass, s.id,
                    s.ext.length() == 0 ? "mp4" : s.ext);
        }
        String pref = preferredExt(s);
        String ext = (attempt % 2 == 0) ? pref : otherExt(pref);
        return XtreamClient.liveUrl(Session.base, Session.user, Session.pass, s.id, ext);
    }

    static String extOfUrl(String url) {
        int i = url.lastIndexOf('.');
        return i < 0 ? "" : url.substring(i + 1);
    }

    /** Abre la URL en VLC; si no esta instalado, en cualquier reproductor que acepte video. */
    static boolean launchExternal(Activity a, String url, String title) {
        Uri uri = Uri.parse(url);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setPackage(VLC);
            i.setDataAndType(uri, "video/*");
            i.putExtra("title", title);
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            // VLC no esta instalado
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "video/*");
            i.putExtra("title", title);
            a.startActivity(Intent.createChooser(i, "Abrir con"));
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, "No hay un reproductor externo instalado. Instala VLC.", Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
