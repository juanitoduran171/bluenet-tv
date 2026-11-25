package ec.net.blue.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import ec.net.blue.tv.XtreamClient.Episode;
import ec.net.blue.tv.XtreamClient.Season;
import ec.net.blue.tv.XtreamClient.SeriesInfo;
import ec.net.blue.tv.XtreamClient.Stream;

import java.util.ArrayList;
import java.util.List;

/** Ficha de una serie: datos, temporadas y episodios. */
public class SeriesActivity extends Activity {

    /** Serie elegida en la pantalla principal. */
    static Stream series;

    private ImageView poster;
    private TextView title;
    private TextView meta;
    private TextView plot;
    private LinearLayout seasonBar;
    private ListView episodeList;
    private TextView message;
    private ProgressBar progress;

    private SeriesInfo info;
    private int currentSeason = -1;
    private final List<Episode> episodes = new ArrayList<Episode>();
    private final List<TextView> seasonButtons = new ArrayList<TextView>();
    private EpisodeAdapter adapter;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Session.load(this);
        if (series == null || !Session.isLoggedIn()) {
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
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(c, 36), Ui.dp(c, 24), Ui.dp(c, 36), Ui.dp(c, 16));

        // ---- columna izquierda: carátula y datos
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(Ui.dp(c, 240), ViewGroup.LayoutParams.MATCH_PARENT));

        poster = new ImageView(c);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(Ui.rect(c, Ui.PANEL, 10, 0, 0));
        left.addView(poster, new LinearLayout.LayoutParams(Ui.dp(c, 200), Ui.dp(c, 290)));
        ImageLoader.load(poster, series.icon, 400);

        title = new TextView(c);
        title.setText(series.name);
        title.setTextColor(Ui.TEXT);
        title.setTextSize(20);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, 10);
        left.addView(title, tl);

        meta = new TextView(c);
        meta.setTextColor(Ui.ACCENT_LIGHT);
        meta.setTextSize(13);
        left.addView(meta);

        plot = new TextView(c);
        plot.setTextColor(Ui.MUTED);
        plot.setTextSize(13);
        plot.setMaxLines(7);
        plot.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = Ui.dp(c, 8);
        left.addView(plot, pl);

        // ---- columna derecha: temporadas + episodios
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rl.leftMargin = Ui.dp(c, 24);
        root.addView(right, rl);

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        seasonBar = new LinearLayout(c);
        seasonBar.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(seasonBar);
        right.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 52)));

        FrameLayout content = new FrameLayout(c);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cl.topMargin = Ui.dp(c, 10);
        right.addView(content, cl);

        adapter = new EpisodeAdapter();
        episodeList = new ListView(c);
        episodeList.setId(View.generateViewId());
        episodeList.setDivider(null);
        episodeList.setSelector(Ui.transparent());
        episodeList.setVerticalScrollBarEnabled(false);
        episodeList.setAdapter(adapter);
        episodeList.setVisibility(View.GONE);
        content.addView(episodeList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        episodeList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                play(position);
            }
        });

        message = new TextView(c);
        message.setTextColor(Ui.MUTED);
        message.setTextSize(19);
        message.setGravity(Gravity.CENTER);
        message.setPadding(Ui.dp(c, 24), Ui.dp(c, 16), Ui.dp(c, 24), Ui.dp(c, 16));
        message.setBackground(Ui.states(Ui.rect(c, Ui.ACCENT, 12, 2, Ui.TEXT), null, Ui.transparent()));
        message.setVisibility(View.GONE);
        content.addView(message, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        progress = new ProgressBar(c);
        content.addView(progress, new FrameLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48), Gravity.CENTER));

        setContentView(root);
    }

    // ------------------------------------------------------------------ datos

    private void load() {
        progress.setVisibility(View.VISIBLE);
        message.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                SeriesInfo r = null;
                XtreamClient.ApiException err = null;
                try {
                    r = XtreamClient.seriesInfo(Session.base, Session.user, Session.pass, series.id);
                } catch (XtreamClient.ApiException e) {
                    err = e;
                }
                final SeriesInfo fr = r;
                final XtreamClient.ApiException fe = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        progress.setVisibility(View.GONE);
                        if (fr == null) {
                            android.util.Log.w(Config.TAG, "serie: " + fe.getMessage());
                            showMessage(Session.devMode ? fe.getMessage() : "No se pudo cargar la serie.", true);
                        } else {
                            show(fr);
                        }
                    }
                });
            }
        }).start();
    }

    private void showMessage(String text, boolean retry) {
        episodeList.setVisibility(View.GONE);
        message.setText(retry ? text + "\n\nPresiona OK para reintentar" : text);
        message.setFocusable(retry);
        message.setClickable(retry);
        message.setOnClickListener(retry ? new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        } : null);
        message.setVisibility(View.VISIBLE);
        if (retry) {
            message.requestFocus();
        }
    }

    private void show(SeriesInfo si) {
        info = si;
        if (si.name.length() > 0) {
            title.setText(si.name);
        }
        if (si.cover.length() > 0 && !si.cover.equals(series.icon)) {
            ImageLoader.load(poster, si.cover, 400);
        }
        StringBuilder m = new StringBuilder();
        if (si.year.length() > 0) {
            m.append(si.year);
        }
        if (si.genre.length() > 0) {
            m.append(m.length() > 0 ? "  ·  " : "").append(si.genre);
        }
        if (si.rating.length() > 0 && !si.rating.equals("0")) {
            m.append(m.length() > 0 ? "  ·  " : "").append("★ ").append(si.rating);
        }
        meta.setText(m.toString());
        plot.setText(si.plot);

        seasonBar.removeAllViews();
        seasonButtons.clear();
        if (si.seasons.isEmpty()) {
            showMessage("Esta serie todavía no tiene episodios disponibles.", false);
            return;
        }
        for (int i = 0; i < si.seasons.size(); i++) {
            final int idx = i;
            TextView bt = Ui.button(this, si.seasons.get(i).name, false);
            bt.setTextSize(15);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44));
            lp.rightMargin = Ui.dp(this, 8);
            seasonBar.addView(bt, lp);
            bt.setNextFocusDownId(episodeList.getId());
            // Al pasar por una temporada con el control se muestran sus episodios.
            bt.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        selectSeason(idx);
                    }
                }
            });
            bt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectSeason(idx);
                    episodeList.requestFocus();
                }
            });
            seasonButtons.add(bt);
        }
        selectSeason(0);
        episodeList.requestFocus();
    }

    private void selectSeason(int idx) {
        if (info == null || idx < 0 || idx >= info.seasons.size()) {
            return;
        }
        for (int i = 0; i < seasonButtons.size(); i++) {
            seasonButtons.get(i).setSelected(i == idx);
        }
        if (idx == currentSeason) {
            return;
        }
        currentSeason = idx;
        Season s = info.seasons.get(idx);
        episodes.clear();
        episodes.addAll(s.episodes);
        adapter.notifyDataSetChanged();
        episodeList.setSelection(0);
        message.setVisibility(View.GONE);
        episodeList.setVisibility(View.VISIBLE);
        if (idx < seasonButtons.size()) {
            episodeList.setNextFocusUpId(View.NO_ID);
        }
    }

    private static String episodeLabel(Episode e) {
        String t = e.title;
        return "E" + e.number + (t.length() > 0 ? "  ·  " + t : "");
    }

    private void play(int position) {
        if (position < 0 || position >= episodes.size()) {
            return;
        }
        List<Stream> queue = new ArrayList<Stream>();
        for (Episode e : episodes) {
            Stream st = new Stream();
            st.type = XtreamClient.TYPE_EPISODE;
            st.live = false;
            st.id = e.id;
            st.ext = e.ext;
            st.icon = e.image;
            st.name = "T" + e.season + " · " + episodeLabel(e);
            queue.add(st);
        }
        PlayerActivity.queue = queue;
        PlayerActivity.index = position;
        startActivity(new Intent(this, PlayerActivity.class));
    }

    // ------------------------------------------------------------------ lista de episodios

    private final class EpisodeAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return episodes.size();
        }

        @Override
        public Object getItem(int i) {
            return episodes.get(i);
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
                row.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6));
                row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 84)));
                row.setBackground(Ui.states(Ui.rect(c, Ui.ACCENT, 12, 0, 0),
                        Ui.rect(c, Ui.ACCENT, 12, 0, 0), Ui.transparent()));
                ImageView thumb = new ImageView(c);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(Ui.rect(c, Ui.PANEL, 6, 0, 0));
                row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(c, 128), Ui.dp(c, 72)));
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                cl.leftMargin = Ui.dp(c, 14);
                row.addView(col, cl);
                TextView name = new TextView(c);
                name.setTextColor(Ui.TEXT);
                name.setTextSize(17);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(name);
                TextView sub = new TextView(c);
                sub.setTextColor(Ui.MUTED);
                sub.setTextSize(13);
                sub.setMaxLines(2);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(sub);
                row.setTag(new View[]{thumb, name, sub});
            } else {
                row = (LinearLayout) convert;
            }
            View[] v = (View[]) row.getTag();
            Episode e = episodes.get(i);
            ((TextView) v[1]).setText(episodeLabel(e));
            String sub = e.duration.length() > 0 ? e.duration : "";
            if (e.plot.length() > 0) {
                sub += (sub.length() > 0 ? "  ·  " : "") + e.plot;
            }
            ((TextView) v[2]).setText(sub);
            ImageLoader.load((ImageView) v[0], e.image.length() > 0 ? e.image : series.icon, 256);
            return row;
        }
    }
}
