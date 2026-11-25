package ec.net.blue.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import ec.net.blue.tv.XtreamClient.Stream;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Analiza los primeros canales de la categoria actual y muestra que codec de video/audio trae
 * cada uno en cada formato (m3u8 / ts). Sirve para saber POR QUE un canal no se ve.
 */
public class DiagActivity extends Activity {

    static List<Stream> streams;
    static String categoryName = "";

    private static final int SAMPLE = 30;

    private static final class Row {
        String detail = "";
        int level = -1; // -1 = pendiente
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private volatile boolean stopped;

    private List<Stream> items = new ArrayList<Stream>();
    private Row[] rows;
    private TextView header;
    private RowAdapter adapter;

    // estadisticas (solo se tocan en el hilo principal)
    private int done;
    private final int[] okByFormat = new int[2];      // [m3u8, ts] con respuesta valida
    private final int[] triedByFormat = new int[2];
    private final int[] levelCount = new int[3];
    private final Map<String, Integer> videoCount = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> audioCount = new LinkedHashMap<String, Integer>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Session.load(this);
        if (streams == null || streams.isEmpty() || !Session.isLoggedIn()) {
            finish();
            return;
        }
        int n = Math.min(SAMPLE, streams.size());
        items = new ArrayList<Stream>(streams.subList(0, n));
        rows = new Row[n];
        for (int i = 0; i < n; i++) {
            rows[i] = new Row();
        }
        buildUi();
        refreshHeader();
        startScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopped = true;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void buildUi() {
        final Context c = this;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(c, 40), Ui.dp(c, 24), Ui.dp(c, 40), Ui.dp(c, 16));

        header = new TextView(c);
        header.setTextColor(Ui.TEXT);
        header.setTextSize(15);
        header.setPadding(Ui.dp(c, 18), Ui.dp(c, 14), Ui.dp(c, 18), Ui.dp(c, 14));
        header.setBackground(Ui.rect(c, Ui.PANEL, 12, 0, 0));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        adapter = new RowAdapter();
        ListView list = new ListView(c);
        list.setDivider(null);
        list.setSelector(Ui.transparent());
        list.setAdapter(adapter);
        list.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = Ui.dp(c, 12);
        root.addView(list, lp);

        TextView foot = new TextView(c);
        foot.setText("↑ ↓ para recorrer   •   ATRÁS para salir");
        foot.setTextColor(Ui.MUTED);
        foot.setTextSize(13);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot);

        setContentView(root);
        list.requestFocus();
    }

    // ------------------------------------------------------------------ analisis

    private void startScan() {
        // Cuentas con 1 conexion (o desconocido): un canal a la vez. Con mas, dos en paralelo.
        int threads = Session.maxConnections >= 3 ? 2 : 1;
        pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (stopped) {
                        return;
                    }
                    Stream s = items.get(idx);
                    String pref = Playback.preferredExt(s);
                    String[] exts = {pref, Playback.otherExt(pref)};
                    final StringBuilder detail = new StringBuilder();
                    final StreamProbe.Result[] res = new StreamProbe.Result[2];
                    final String[] names = new String[2];
                    for (int k = 0; k < 2 && !stopped; k++) {
                        String url = Playback.urlFor(s, k);
                        if (k > 0 || idx > 0) {
                            pause(700); // deja que el panel libere la conexion anterior
                        }
                        res[k] = StreamProbe.probe(url, 12000);
                        names[k] = exts[k];
                        detail.append(k > 0 ? "\n" : "").append(exts[k]).append(": ").append(res[k].summary());
                    }
                    if (stopped) {
                        return;
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!stopped) {
                                record(idx, detail.toString(), names, res);
                            }
                        }
                    });
                }
            });
        }
    }

    private static void pause(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void record(int idx, String detail, String[] names, StreamProbe.Result[] res) {
        int best = 2;
        for (int k = 0; k < 2; k++) {
            if (res[k] == null) {
                continue;
            }
            int col = "m3u8".equals(names[k]) ? 0 : 1;
            triedByFormat[col]++;
            if (res[k].level() < 2) {
                okByFormat[col]++;
            }
            best = Math.min(best, res[k].level());
        }
        // codecs: tomamos el primer analisis que haya podido leer el video/audio
        for (int k = 0; k < 2; k++) {
            if (res[k] != null && !res[k].video.isEmpty()) {
                for (String v : res[k].video) {
                    bump(videoCount, v);
                }
                for (String a : res[k].audio) {
                    bump(audioCount, a);
                }
                break;
            }
        }
        rows[idx].detail = detail;
        rows[idx].level = best;
        levelCount[best]++;
        done++;
        adapter.notifyDataSetChanged();
        refreshHeader();
    }

    private static void bump(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }

    private static String counts(Map<String, Integer> m) {
        if (m.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(e.getKey()).append(" ×").append(e.getValue());
        }
        return sb.toString();
    }

    private void refreshHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("Diagnóstico — ").append(categoryName).append("   (").append(done).append("/")
                .append(items.size()).append(done < items.size() ? " analizando…" : " listo").append(")\n");
        sb.append("Cuenta: ").append(Session.user).append("  ·  conexiones máx.: ")
                .append(Session.maxConnections > 0 ? String.valueOf(Session.maxConnections) : "?");
        if (Session.expTs > 0) {
            sb.append("  ·  vence ").append(new SimpleDateFormat("dd/MM/yyyy").format(new Date(Session.expTs * 1000L)));
        }
        sb.append("\nFormato que responde:  m3u8 ").append(okByFormat[0]).append("/").append(triedByFormat[0])
                .append("   ts ").append(okByFormat[1]).append("/").append(triedByFormat[1]);
        sb.append("\nVideo: ").append(counts(videoCount));
        sb.append("\nAudio: ").append(counts(audioCount));
        sb.append("\nVerde (H.264+AAC) ").append(levelCount[0]).append("   Ámbar (depende del equipo) ")
                .append(levelCount[1]).append("   Rojo (error) ").append(levelCount[2]);
        header.setText(sb.toString());
    }

    // ------------------------------------------------------------------ lista

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row;
            if (convert == null) {
                Context c = parent.getContext();
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
                row.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                View dot = new View(c);
                row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14)));
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                cl.leftMargin = Ui.dp(c, 14);
                row.addView(col, cl);
                TextView name = new TextView(c);
                name.setTextColor(Ui.TEXT);
                name.setTextSize(17);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(name);
                TextView detail = new TextView(c);
                detail.setTextColor(Ui.MUTED);
                detail.setTextSize(13);
                col.addView(detail);
                row.setTag(new View[]{dot, name, detail});
            } else {
                row = (LinearLayout) convert;
            }
            View[] v = (View[]) row.getTag();
            Row r = rows[i];
            int color = r.level < 0 ? 0xFF4A5C6B : r.level == 0 ? 0xFF3DDC84 : r.level == 1 ? 0xFFFFB300 : 0xFFFF5252;
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            v[0].setBackground(g);
            ((TextView) v[1]).setText((i + 1) + ".  " + items.get(i).name);
            ((TextView) v[2]).setText(r.level < 0 ? "analizando…" : r.detail);
            return row;
        }
    }
}
