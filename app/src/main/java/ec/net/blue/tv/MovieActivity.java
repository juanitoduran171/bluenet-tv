package ec.net.blue.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import ec.net.blue.tv.XtreamClient.MovieInfo;
import ec.net.blue.tv.XtreamClient.Stream;

import java.util.ArrayList;
import java.util.List;

/** Ficha de una pelicula: caratula, datos y boton Reproducir (igual que la ficha de series). */
public class MovieActivity extends Activity {

    /** Pelicula elegida en la pantalla principal. */
    static Stream movie;

    private ImageView backdrop;
    private ImageView poster;
    private TextView title;
    private TextView meta;
    private TextView plot;
    private TextView credits;
    private TextView playBtn;
    private ProgressBar progress;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Session.load(this);
        if (movie == null || !Session.isLoggedIn()) {
            finish();
            return;
        }
        buildUi();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
    }

    private void buildUi() {
        final Context c = this;
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(Ui.BG);

        // fondo: imagen de la pelicula oscurecida
        backdrop = new ImageView(c);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.25f);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(c, 48), Ui.dp(c, 32), Ui.dp(c, 48), Ui.dp(c, 24));
        root.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        poster = new ImageView(c);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(Ui.rect(c, Ui.PANEL, 10, 0, 0));
        row.addView(poster, new LinearLayout.LayoutParams(Ui.dp(c, 220), Ui.dp(c, 320)));
        ImageLoader.load(poster, movie.icon, 440);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        svLp.leftMargin = Ui.dp(c, 32);
        row.addView(sv, svLp);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);

        title = new TextView(c);
        title.setText(movie.name);
        title.setTextColor(Ui.TEXT);
        title.setTextSize(28);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);

        meta = new TextView(c);
        meta.setTextColor(Ui.ACCENT_LIGHT);
        meta.setTextSize(15);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = Ui.dp(c, 6);
        col.addView(meta, ml);

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(c, 18);
        bl.bottomMargin = Ui.dp(c, 18);
        col.addView(buttons, bl);

        playBtn = Ui.button(c, "▶  Reproducir", true);
        playBtn.setTextSize(19);
        buttons.addView(playBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 54)));
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play();
            }
        });

        progress = new ProgressBar(c);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32));
        pl.leftMargin = Ui.dp(c, 16);
        buttons.addView(progress, pl);

        plot = new TextView(c);
        plot.setTextColor(Ui.TEXT);
        plot.setTextSize(16);
        plot.setLineSpacing(0, 1.15f);
        col.addView(plot);

        credits = new TextView(c);
        credits.setTextColor(Ui.MUTED);
        credits.setTextSize(14);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = Ui.dp(c, 12);
        col.addView(credits, cl);

        setContentView(root);
        playBtn.requestFocus();
    }

    /** La informacion es opcional: si no llega, igual se puede reproducir. */
    private void load() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                MovieInfo r = null;
                try {
                    r = XtreamClient.vodInfo(Session.base, Session.user, Session.pass, movie.id);
                } catch (XtreamClient.ApiException e) {
                    android.util.Log.w(Config.TAG, "pelicula: " + e.getMessage());
                }
                final MovieInfo fr = r;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        progress.setVisibility(View.GONE);
                        show(fr);
                    }
                });
            }
        }).start();
    }

    private void show(MovieInfo m) {
        if (m == null) {
            plot.setText("");
            return;
        }
        if (m.name.length() > 0) {
            title.setText(m.name);
        }
        if (m.cover.length() > 0 && !m.cover.equals(movie.icon)) {
            ImageLoader.load(poster, m.cover, 440);
        }
        if (m.backdrop.length() > 0) {
            ImageLoader.load(backdrop, m.backdrop, 1280);
        }
        if (m.ext.length() > 0) {
            movie.ext = m.ext;
        }
        StringBuilder sb = new StringBuilder();
        add(sb, m.year);
        add(sb, m.genre);
        add(sb, m.duration);
        String rating = m.rating.length() > 0 ? m.rating : movie.rating;
        if (rating.length() > 0 && !rating.equals("0")) {
            add(sb, "★ " + rating);
        }
        meta.setText(sb.toString());
        plot.setText(m.plot);
        StringBuilder cr = new StringBuilder();
        if (m.director.length() > 0) {
            cr.append("Dirección: ").append(m.director);
        }
        if (m.cast.length() > 0) {
            cr.append(cr.length() > 0 ? "\n" : "").append("Reparto: ").append(m.cast);
        }
        credits.setText(cr.toString());
    }

    private static void add(StringBuilder sb, String v) {
        if (v.length() > 0) {
            sb.append(sb.length() > 0 ? "  ·  " : "").append(v);
        }
    }

    private void play() {
        List<Stream> q = new ArrayList<Stream>();
        q.add(movie);
        PlayerActivity.queue = q;
        PlayerActivity.index = 0;
        PlayerActivity.queueCategory = "";
        startActivity(new Intent(this, PlayerActivity.class));
    }
}
