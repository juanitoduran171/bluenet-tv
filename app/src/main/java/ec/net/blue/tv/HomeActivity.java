package ec.net.blue.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import ec.net.blue.tv.XtreamClient.Category;
import ec.net.blue.tv.XtreamClient.Stream;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeActivity extends Activity {

    private static final Category ALL = new Category("*", "Todos");

    private int kind = XtreamClient.TYPE_LIVE;
    private boolean live = true;    // kind == TYPE_LIVE
    private boolean lockSeen;       // estado del control parental la ultima vez que refrescamos

    private TextView tabLive;
    private TextView tabVod;
    private TextView tabSeries;
    private EditText search;
    private TextView diagBtn;
    private TextView modeBtn;
    private ListView catList;
    private ListView chanList;
    private GridView vodGrid;
    private TextView message;
    private ProgressBar progress;

    private final List<Category> cats = new ArrayList<Category>();
    private final List<Stream> shown = new ArrayList<Stream>();
    private List<Stream> loaded = new ArrayList<Stream>();
    private CatAdapter catAdapter;
    private ChanAdapter chanAdapter;
    private VodAdapter vodAdapter;

    private final HashMap<String, List<Category>> catCache = new HashMap<String, List<Category>>();
    private final HashMap<String, List<Stream>> streamCache = new HashMap<String, List<Stream>>();
    private final ExecutorService net = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int token = 0;          // invalida respuestas de red atrasadas
    private int loadedCat = -1;     // categoria cuyo contenido se muestra
    private boolean suppressFilter; // evita filtrar mientras cambiamos el texto por codigo
    private boolean firstFocus = true;
    private Runnable pendingSelect;
    private Runnable retryAction;
    private TextView netFooter;
    private final Runnable netTick = new Runnable() {
        @Override
        public void run() {
            refreshFooter();
            ui.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Session.load(this);
        if (!Session.isLoggedIn()) {
            goLogin(true);
            return;
        }
        buildUi();
        switchKind(XtreamClient.TYPE_LIVE);
        NetInfo.askPermissionOnce(this);
        if (b == null && getIntent().getBooleanExtra("resume", false) && Session.lastChanId.length() > 0) {
            resumeLastChannel();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(netTick);
        ui.post(netTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(netTick);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshFooter();
    }

    private void refreshFooter() {
        if (netFooter != null) {
            netFooter.setText(NetInfo.describe(this) + "   ·   Cuenta: " + Session.user
                    + "   ·   " + LoginActivity.appVersion(this));
        }
    }

    /** Al abrir la app: vuelve al ultimo canal en vivo (salvo que sea de una categoria protegida). */
    private void resumeLastChannel() {
        final String id = Session.lastChanId;
        final String cat = Session.lastChanCat;
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Category> lc = XtreamClient.categories(Session.base, Session.user, Session.pass,
                            XtreamClient.TYPE_LIVE);
                    HashSet<String> adult = new HashSet<String>();
                    for (Category c : lc) {
                        if (Parental.isAdult(c.name)) {
                            adult.add(c.id);
                        }
                    }
                    if (adult.contains(cat)) {
                        return;
                    }
                    List<Stream> all = XtreamClient.streams(Session.base, Session.user, Session.pass,
                            XtreamClient.TYPE_LIVE, "*".equals(cat) ? null : cat);
                    final List<Stream> q = new ArrayList<Stream>();
                    int found = -1;
                    for (Stream st : all) {
                        if (!Parental.unlocked && adult.contains(st.categoryId)) {
                            continue;
                        }
                        if (st.id.equals(id)) {
                            found = q.size();
                        }
                        q.add(st);
                    }
                    if (found < 0) {
                        return; // el canal ya no existe
                    }
                    final int idx = found;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }
                            PlayerActivity.queue = q;
                            PlayerActivity.index = idx;
                            PlayerActivity.queueCategory = cat;
                            startActivity(new Intent(HomeActivity.this, PlayerActivity.class));
                        }
                    });
                } catch (XtreamClient.ApiException e) {
                    android.util.Log.w(Config.TAG, "retomar canal: " + e.getMessage());
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Si la app paso a segundo plano, el control parental se volvio a bloquear.
        if (catAdapter != null && lockSeen != Parental.unlocked) {
            onParentalChanged();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
    }

    // ------------------------------------------------------------------ interfaz

    private void buildUi() {
        final Context c = this;

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        // margen de seguridad para el overscan de algunos televisores
        root.setPadding(Ui.dp(c, 28), Ui.dp(c, 16), Ui.dp(c, 28), Ui.dp(c, 12));

        // ---- barra superior
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 52)));

        ImageView logo = new ImageView(c);
        Bitmap bmp = Ui.asset(c, "logo.png");
        if (bmp != null) {
            logo.setImageBitmap(bmp);
        }
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setFocusable(true);
        logo.setClickable(true);
        logo.setPadding(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2));
        logo.setBackground(Ui.states(Ui.rect(c, 0x55FFFFFF, 24, 0, 0), null, Ui.transparent()));
        logo.setOnClickListener(new View.OnClickListener() {
            private int taps;
            private long first;

            @Override
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (now - first > 6000) {
                    first = now;
                    taps = 0;
                }
                if (++taps >= 7) {
                    taps = 0;
                    Session.devMode = !Session.devMode;
                    if (!Session.devMode) {
                        Session.useExternal = false;
                    }
                    Session.save(HomeActivity.this);
                    refreshDevButtons();
                    Toast.makeText(HomeActivity.this, Session.devMode
                            ? "Modo técnico activado" : "Modo técnico desactivado", Toast.LENGTH_SHORT).show();
                }
            }
        });
        top.addView(logo, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));

        tabLive = Ui.button(c, "TV en vivo", false);
        tabVod = Ui.button(c, "Películas", false);
        tabSeries = Ui.button(c, "Series", false);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 44));
        tabLp.leftMargin = Ui.dp(c, 20);
        top.addView(tabLive, tabLp);
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 44));
        tabLp2.leftMargin = Ui.dp(c, 8);
        top.addView(tabVod, tabLp2);
        LinearLayout.LayoutParams tabLp3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 44));
        tabLp3.leftMargin = Ui.dp(c, 8);
        top.addView(tabSeries, tabLp3);
        tabLive.setOnClickListener(tabClick(XtreamClient.TYPE_LIVE));
        tabVod.setOnClickListener(tabClick(XtreamClient.TYPE_VOD));
        tabSeries.setOnClickListener(tabClick(XtreamClient.TYPE_SERIES));

        View spacer = new View(c);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        search = Ui.field(c, "Buscar", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH);
        search.setTextSize(16);
        top.addView(search, new LinearLayout.LayoutParams(Ui.dp(c, 150), Ui.dp(c, 44)));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!suppressFilter) {
                    applyFilter();
                }
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                hideKeyboard();
                View target = live ? chanList : vodGrid;
                if (target.getVisibility() == View.VISIBLE) {
                    target.requestFocus();
                } else {
                    catList.requestFocus();
                }
                return true;
            }
        });

        diagBtn = Ui.button(c, "Diagnóstico", false);
        diagBtn.setTextSize(15);
        diagBtn.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        LinearLayout.LayoutParams dgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 44));
        dgLp.leftMargin = Ui.dp(c, 8);
        top.addView(diagBtn, dgLp);
        diagBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDiagnostic();
            }
        });

        modeBtn = Ui.button(c, "", false);
        modeBtn.setTextSize(15);
        modeBtn.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        LinearLayout.LayoutParams mdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 44));
        mdLp.leftMargin = Ui.dp(c, 8);
        top.addView(modeBtn, mdLp);
        refreshModeLabel();
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Session.useExternal = !Session.useExternal;
                Session.save(HomeActivity.this);
                refreshModeLabel();
            }
        });

        TextView lockBtn = Ui.button(c, "Control parental", false);
        lockBtn.setTextSize(15);
        lockBtn.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        LinearLayout.LayoutParams lkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 44));
        lkLp.leftMargin = Ui.dp(c, 8);
        top.addView(lockBtn, lkLp);
        lockBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Parental.showMenu(HomeActivity.this, new Runnable() {
                    @Override
                    public void run() {
                        onParentalChanged();
                    }
                });
            }
        });

        TextView logout = Ui.button(c, "Salir", false);
        LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 44));
        outLp.leftMargin = Ui.dp(c, 8);
        top.addView(logout, outLp);
        logout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Session.logout(HomeActivity.this);
                goLogin(true);
            }
        });

        // ---- cuerpo
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = Ui.dp(c, 12);
        root.addView(body, bodyLp);

        catAdapter = new CatAdapter();
        catList = new ListView(c);
        catList.setId(View.generateViewId());
        catList.setDivider(null);
        catList.setSelector(Ui.transparent());
        catList.setVerticalScrollBarEnabled(false);
        catList.setBackground(Ui.rect(c, Ui.PANEL, 14, 0, 0));
        catList.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        catList.setClipToPadding(false);
        catList.setAdapter(catAdapter);
        body.addView(catList, new LinearLayout.LayoutParams(Ui.dp(c, 240),
                ViewGroup.LayoutParams.MATCH_PARENT));
        catList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, final int position, long id) {
                // Al recorrer la lista con el control cargamos con un pequeno retraso.
                if (pendingSelect != null) {
                    ui.removeCallbacks(pendingSelect);
                }
                if (position == loadedCat) {
                    return;
                }
                pendingSelect = new Runnable() {
                    @Override
                    public void run() {
                        if (position != loadedCat) {
                            loadStreams(position);
                        }
                    }
                };
                ui.postDelayed(pendingSelect, 350);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        catList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (pendingSelect != null) {
                    ui.removeCallbacks(pendingSelect);
                }
                loadStreams(position);
                if (isLocked(position)) {
                    Parental.requestAccess(HomeActivity.this, afterUnlock(position));
                }
            }
        });

        FrameLayout content = new FrameLayout(c);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        contentLp.leftMargin = Ui.dp(c, 16);
        body.addView(content, contentLp);

        chanAdapter = new ChanAdapter();
        chanList = new ListView(c);
        chanList.setId(View.generateViewId());
        chanList.setDivider(null);
        chanList.setSelector(Ui.transparent());
        chanList.setVerticalScrollBarEnabled(false);
        chanList.setAdapter(chanAdapter);
        chanList.setVisibility(View.GONE);
        content.addView(chanList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        chanList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openPlayer(position);
            }
        });

        vodAdapter = new VodAdapter();
        vodGrid = new GridView(c);
        vodGrid.setId(View.generateViewId());
        vodGrid.setNumColumns(GridView.AUTO_FIT);
        vodGrid.setColumnWidth(Ui.dp(c, 150));
        vodGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        vodGrid.setHorizontalSpacing(Ui.dp(c, 8));
        vodGrid.setVerticalSpacing(Ui.dp(c, 8));
        vodGrid.setSelector(Ui.transparent());
        vodGrid.setVerticalScrollBarEnabled(false);
        vodGrid.setAdapter(vodAdapter);
        vodGrid.setVisibility(View.GONE);
        content.addView(vodGrid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        vodGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openPlayer(position);
            }
        });

        message = new TextView(c);
        message.setId(View.generateViewId());
        message.setTextColor(Ui.MUTED);
        message.setTextSize(20);
        message.setGravity(Gravity.CENTER);
        message.setPadding(Ui.dp(c, 24), Ui.dp(c, 16), Ui.dp(c, 24), Ui.dp(c, 16));
        message.setBackground(Ui.states(
                Ui.rect(c, Ui.ACCENT, 12, 2, Ui.TEXT), null, Ui.transparent()));
        message.setVisibility(View.GONE);
        message.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (retryAction != null) {
                    retryAction.run();
                }
            }
        });
        content.addView(message, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        progress = new ProgressBar(c);
        progress.setVisibility(View.GONE);
        content.addView(progress, new FrameLayout.LayoutParams(
                Ui.dp(c, 48), Ui.dp(c, 48), Gravity.CENTER));

        netFooter = new TextView(c);
        netFooter.setTextColor(Ui.MUTED);
        netFooter.setTextSize(12);
        netFooter.setSingleLine(true);
        netFooter.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nfLp.topMargin = Ui.dp(c, 6);
        root.addView(netFooter, nfLp);
        refreshFooter();

        // ---- navegacion con el control remoto
        tabLive.setNextFocusDownId(catList.getId());
        tabVod.setNextFocusDownId(catList.getId());
        tabSeries.setNextFocusDownId(catList.getId());
        lockBtn.setNextFocusDownId(catList.getId());
        search.setNextFocusDownId(catList.getId());
        diagBtn.setNextFocusDownId(catList.getId());
        modeBtn.setNextFocusDownId(catList.getId());
        catList.setNextFocusRightId(chanList.getId());
        chanList.setNextFocusLeftId(catList.getId());
        vodGrid.setNextFocusLeftId(catList.getId());

        setContentView(root);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) {
            imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
    }

    /** Diagnostico y VLC solo existen en modo tecnico (pestana TV en vivo). */
    private void refreshDevButtons() {
        int vis = (Session.devMode && live) ? View.VISIBLE : View.GONE;
        diagBtn.setVisibility(vis);
        modeBtn.setVisibility(vis);
        refreshModeLabel();
    }

    private void refreshModeLabel() {
        modeBtn.setText(Session.useExternal ? "Canales: VLC" : "Canales: Interno");
    }

    private void openDiagnostic() {
        if (shown.isEmpty() || loadedCat < 0) {
            Toast.makeText(this, "Abre primero una categoría con canales.", Toast.LENGTH_SHORT).show();
            return;
        }
        DiagActivity.streams = new ArrayList<Stream>(shown);
        DiagActivity.categoryName = cats.get(loadedCat).name;
        startActivity(new Intent(this, DiagActivity.class));
    }

    private void goLogin(boolean noAuto) {
        Intent i = new Intent(this, LoginActivity.class);
        i.putExtra("noauto", noAuto);
        startActivity(i);
        finish();
    }

    // ------------------------------------------------------------------ estados de la zona de contenido

    private void showBusy() {
        progress.setVisibility(View.VISIBLE);
        message.setVisibility(View.GONE);
        chanList.setVisibility(View.GONE);
        vodGrid.setVisibility(View.GONE);
    }

    private void showMessage(String text, Runnable retry) {
        showMessage(text, retry, retry == null ? null : "Presiona OK para reintentar");
    }

    private void showMessage(String text, Runnable retry, String okHint) {
        progress.setVisibility(View.GONE);
        chanList.setVisibility(View.GONE);
        vodGrid.setVisibility(View.GONE);
        retryAction = retry;
        message.setText(okHint == null ? text : text + "\n\n" + okHint);
        message.setFocusable(retry != null);
        message.setClickable(retry != null);
        message.setVisibility(View.VISIBLE);
        message.setNextFocusLeftId(catList.getId());
        // con error, "derecha" lleva al mensaje (para poder reintentar con OK)
        catList.setNextFocusRightId(retry != null ? message.getId() : contentId());
    }

    private int contentId() {
        return live ? chanList.getId() : vodGrid.getId();
    }

    // ------------------------------------------------------------------ carga de datos

    private View.OnClickListener tabClick(final int k) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (kind != k) {
                    switchKind(k);
                }
            }
        };
    }

    private String kindKey() {
        return kind == XtreamClient.TYPE_LIVE ? "L" : kind == XtreamClient.TYPE_SERIES ? "S" : "V";
    }

    private void switchKind(int k) {
        kind = k;
        live = k == XtreamClient.TYPE_LIVE;
        tabLive.setSelected(k == XtreamClient.TYPE_LIVE);
        tabVod.setSelected(k == XtreamClient.TYPE_VOD);
        tabSeries.setSelected(k == XtreamClient.TYPE_SERIES);
        refreshDevButtons();
        suppressFilter = true;
        search.setText("");
        suppressFilter = false;
        loaded = new ArrayList<Stream>();
        loadedCat = -1;
        catList.setNextFocusRightId(contentId());
        loadCategories();
    }

    private void loadCategories() {
        final int kindNow = kind;
        final String key = kindKey();
        List<Category> cached = catCache.get(key);
        final int my = ++token;
        if (cached != null) {
            applyCategories(cached);
            return;
        }
        showBusy();
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Category> res = XtreamClient.categories(
                            Session.base, Session.user, Session.pass, kindNow);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my != token || isFinishing()) {
                                return;
                            }
                            catCache.put(key, res);
                            applyCategories(res);
                        }
                    });
                } catch (final XtreamClient.ApiException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my != token || isFinishing()) {
                                return;
                            }
                            android.util.Log.w(Config.TAG, "catalogo: " + e.getMessage());
                            showMessage(Session.devMode ? e.getMessage()
                                    : "No se pudo cargar el contenido.", new Runnable() {
                                @Override
                                public void run() {
                                    loadCategories();
                                }
                            });
                        }
                    });
                }
            }
        });
    }

    private void applyCategories(List<Category> list) {
        cats.clear();
        cats.add(ALL);
        cats.addAll(list);
        loadedCat = -1;
        catAdapter.notifyDataSetChanged();
        catList.setSelection(0);
        if (firstFocus) {
            firstFocus = false;
            catList.requestFocus();
        }
        loadStreams(0);
    }

    private void loadStreams(final int idx) {
        if (idx < 0 || idx >= cats.size()) {
            return;
        }
        loadedCat = idx;
        lockSeen = Parental.unlocked;
        catAdapter.notifyDataSetChanged();
        Category cat = cats.get(idx);
        if (isLocked(idx)) {
            ++token; // descarta cargas en curso
            loaded = new ArrayList<Stream>();
            shown.clear();
            showMessage("Categoría protegida con control parental.", afterUnlockPrompt(idx),
                    "Presiona OK para ingresar la contraseña");
            return;
        }
        final int kindNow = kind;
        final String catId = ALL.id.equals(cat.id) ? null : cat.id;
        final String key = kindKey() + cat.id;
        List<Stream> cached = streamCache.get(key);
        final int my = ++token;
        if (cached != null) {
            showStreams(cached);
            return;
        }
        showBusy();
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Stream> res = XtreamClient.streams(
                            Session.base, Session.user, Session.pass, kindNow, catId);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my != token || isFinishing()) {
                                return;
                            }
                            streamCache.put(key, res);
                            showStreams(res);
                        }
                    });
                } catch (final XtreamClient.ApiException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my != token || isFinishing()) {
                                return;
                            }
                            android.util.Log.w(Config.TAG, "catalogo: " + e.getMessage());
                            showMessage(Session.devMode ? e.getMessage()
                                    : "No se pudo cargar el contenido.", new Runnable() {
                                @Override
                                public void run() {
                                    loadStreams(idx);
                                }
                            });
                        }
                    });
                }
            }
        });
    }

    private void showStreams(List<Stream> list) {
        loaded = list;
        applyFilter();
    }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase();
        HashSet<String> hidden = new HashSet<String>();
        if (!Parental.unlocked) {
            for (Category c : cats) {
                if (Parental.isAdult(c.name)) {
                    hidden.add(c.id);
                }
            }
        }
        shown.clear();
        for (Stream s : loaded) {
            if (hidden.contains(s.categoryId)) {
                continue; // protegido por control parental
            }
            if (q.length() == 0 || s.name.toLowerCase().contains(q)) {
                shown.add(s);
            }
        }
        progress.setVisibility(View.GONE);
        if (loadedCat < 0) {
            return; // todavia no hay categoria cargada
        }
        if (shown.isEmpty()) {
            showMessage(q.length() == 0 ? "No hay contenido en esta categoría."
                    : "Sin resultados para \"" + q + "\".", null);
            return;
        }
        message.setVisibility(View.GONE);
        catList.setNextFocusRightId(contentId());
        if (live) {
            vodGrid.setVisibility(View.GONE);
            chanList.setVisibility(View.VISIBLE);
            chanAdapter.notifyDataSetChanged();
            chanList.setSelection(0);
        } else {
            chanList.setVisibility(View.GONE);
            vodGrid.setVisibility(View.VISIBLE);
            vodAdapter.notifyDataSetChanged();
            vodGrid.setSelection(0);
        }
    }

    private void openPlayer(int position) {
        if (position < 0 || position >= shown.size()) {
            return;
        }
        Stream chosen = shown.get(position);
        if (chosen.type == XtreamClient.TYPE_SERIES) {
            SeriesActivity.series = chosen;
            startActivity(new Intent(this, SeriesActivity.class));
            return;
        }
        if (chosen.type == XtreamClient.TYPE_VOD) {
            MovieActivity.movie = chosen;
            startActivity(new Intent(this, MovieActivity.class));
            return;
        }
        if (chosen.live && Session.devMode && Session.useExternal) {
            Playback.launchExternal(this, Playback.urlFor(chosen, 0), chosen.name);
            return;
        }
        PlayerActivity.queue = new ArrayList<Stream>(shown);
        PlayerActivity.index = position;
        PlayerActivity.queueCategory = (loadedCat >= 0 && loadedCat < cats.size()) ? cats.get(loadedCat).id : "*";
        startActivity(new Intent(this, PlayerActivity.class));
    }

    // ------------------------------------------------------------------ control parental

    private boolean isLocked(int idx) {
        return idx >= 0 && idx < cats.size() && !Parental.unlocked && Parental.isAdult(cats.get(idx).name);
    }

    private Runnable afterUnlock(final int idx) {
        return new Runnable() {
            @Override
            public void run() {
                lockSeen = Parental.unlocked;
                catAdapter.notifyDataSetChanged();
                loadStreams(idx);
            }
        };
    }

    private Runnable afterUnlockPrompt(final int idx) {
        return new Runnable() {
            @Override
            public void run() {
                Parental.requestAccess(HomeActivity.this, afterUnlock(idx));
            }
        };
    }

    /** Se bloqueo/desbloqueo: refresca candados, "Todos" y la categoria abierta. */
    private void onParentalChanged() {
        lockSeen = Parental.unlocked;
        catAdapter.notifyDataSetChanged();
        if (loadedCat >= 0 && loadedCat < cats.size()) {
            loadStreams(loadedCat);
        }
    }

    @Override
    public void onBackPressed() {
        if (search != null && search.getText().length() > 0) {
            search.setText("");
            hideKeyboard();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ adaptadores

    private final class CatAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return cats.size();
        }

        @Override
        public Object getItem(int i) {
            return cats.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TextView t = (TextView) convert;
            if (t == null) {
                Context c = parent.getContext();
                t = new TextView(c);
                t.setTextSize(17);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 10), 0);
                t.setLayoutParams(new AbsListViewLp(Ui.dp(c, 46)));
                t.setBackground(Ui.states(
                        Ui.rect(c, Ui.ACCENT, 10, 0, 0),
                        Ui.rect(c, Ui.ACCENT, 10, 0, 0),
                        Ui.transparent()));
            }
            boolean current = i == loadedCat;
            t.setTextColor(Ui.textColors(Ui.TEXT, current ? Ui.ACCENT_LIGHT : Ui.TEXT));
            String nm = cats.get(i).name;
            if (Parental.isAdult(nm)) {
                nm = (Parental.unlocked ? "\uD83D\uDD13 " : "\uD83D\uDD12 ") + nm; // candado abierto/cerrado
            }
            t.setText(nm);
            return t;
        }
    }

    private static final class AbsListViewLp extends android.widget.AbsListView.LayoutParams {
        AbsListViewLp(int heightPx) {
            super(ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
        }
    }

    private final class ChanAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView name;
            if (convert == null) {
                Context c = parent.getContext();
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6));
                row.setLayoutParams(new AbsListViewLp(Ui.dp(c, 68)));
                row.setBackground(Ui.states(
                        Ui.rect(c, Ui.ACCENT, 12, 0, 0),
                        Ui.rect(c, Ui.ACCENT, 12, 0, 0),
                        Ui.transparent()));
                icon = new ImageView(c);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setBackground(Ui.rect(c, Ui.PANEL, 8, 0, 0));
                icon.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
                row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));
                name = new TextView(c);
                name.setTextSize(19);
                name.setTextColor(Ui.TEXT);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                nl.leftMargin = Ui.dp(c, 16);
                row.addView(name, nl);
                row.setTag(new View[]{icon, name});
            } else {
                row = (LinearLayout) convert;
            }
            View[] h = (View[]) row.getTag();
            icon = (ImageView) h[0];
            name = (TextView) h[1];
            Stream s = shown.get(i);
            name.setText(s.name);
            ImageLoader.load(icon, s.icon, 128);
            return row;
        }
    }

    private final class VodAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout card;
            if (convert == null) {
                Context c = parent.getContext();
                card = new LinearLayout(c);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
                card.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 262)));
                card.setBackground(Ui.states(
                        Ui.rect(c, Ui.ACCENT, 12, 0, 0),
                        Ui.rect(c, Ui.ACCENT, 12, 0, 0),
                        Ui.transparent()));
                ImageView poster = new ImageView(c);
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                poster.setBackground(Ui.rect(c, Ui.PANEL, 8, 0, 0));
                card.addView(poster, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 204)));
                TextView name = new TextView(c);
                name.setTextSize(14);
                name.setTextColor(Ui.TEXT);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nl.topMargin = Ui.dp(c, 4);
                card.addView(name, nl);
                card.setTag(new View[]{poster, name});
            } else {
                card = (LinearLayout) convert;
            }
            View[] h = (View[]) card.getTag();
            Stream s = shown.get(i);
            ((TextView) h[1]).setText(s.name);
            ImageLoader.load((ImageView) h[0], s.icon, 320);
            return card;
        }
    }
}
