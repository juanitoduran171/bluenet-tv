package ec.net.blue.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import ec.net.blue.tv.XtreamClient.Stream;

import java.util.List;
import java.util.Locale;

/** Reproductor interno basado en ExoPlayer (Media3) + decodificador FFmpeg para audio. */
public class PlayerActivity extends Activity {

    /** Lista de reproduccion actual (la categoria que se estaba viendo) y posicion. */
    static List<Stream> queue;
    static int index;

    private static final int LIVE_TIMEOUT_MS = 15000;   // espera maxima a que un canal empiece
    private static final int VOD_TIMEOUT_MS = 30000;
    private static final int FIRST_FRAME_MS = 8000;     // listo pero sin imagen tras este tiempo = sin video
    private static final int MAX_FAILS_LIVE = 3;
    private static final int MAX_FAILS_VOD = 2;

    private ExoPlayer player;
    private FrameLayout root;
    private SurfaceView surface;
    private View overlay;
    private TextView title;
    private TextView info;
    private ProgressBar bar;
    private ProgressBar spinner;
    private TextView status;       // "Cargando..." bajo el indicador
    private TextView errorView;    // mensaje central (fallo total)
    private TextView banner;       // aviso no bloqueante (canal sin imagen)

    private boolean live;
    private int attempt;           // fallos acumulados del elemento actual (decide el formato)
    private int noVideoTried;      // veces que probamos otro formato por falta de imagen
    private long resumeMs;         // para reanudar una pelicula tras un error
    private boolean failed;        // fallo total mostrado
    private boolean gotFirstFrame;
    private long lastSeekAt;
    private String currentUrl = "";
    private String lastError = "";
    private float videoAspect;     // 0 = desconocido
    private int generation;        // invalida callbacks de un play() anterior

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            overlay.setVisibility(View.GONE);
        }
    };
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateInfo();
            h.postDelayed(this, 500);
        }
    };
    private final Runnable replay = new Runnable() {
        @Override
        public void run() {
            play();
        }
    };
    private final Runnable loadTimeout = new Runnable() {
        @Override
        public void run() {
            onPlaybackError("tiempo de espera agotado");
        }
    };
    private final Runnable firstFrameCheck = new Runnable() {
        @Override
        public void run() {
            if (!gotFirstFrame && !failed) {
                handleNoVideo("listo pero sin imagen");
            }
        }
    };

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_BUFFERING) {
                spinner.setVisibility(View.VISIBLE);
            } else if (state == Player.STATE_READY) {
                h.removeCallbacks(loadTimeout);
                spinner.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                errorView.setVisibility(View.GONE);
                failed = false;
                if (!live) {
                    if (resumeMs > 0) {
                        player.seekTo(resumeMs);
                        resumeMs = 0;
                    }
                    attempt = 0;
                } else {
                    checkTracks(player.getCurrentTracks());
                    if (!gotFirstFrame) {
                        h.removeCallbacks(firstFrameCheck);
                        h.postDelayed(firstFrameCheck, FIRST_FRAME_MS);
                    }
                }
            } else if (state == Player.STATE_ENDED) {
                if (live) {
                    h.postDelayed(replay, 1000); // un canal en vivo no deberia terminar: reconecta
                } else if (queue.get(index).type == XtreamClient.TYPE_EPISODE && index + 1 < queue.size()) {
                    // serie: sigue con el proximo episodio
                    index++;
                    attempt = 0;
                    noVideoTried = 0;
                    resumeMs = 0;
                    play();
                } else {
                    finish();
                }
            }
        }

        @Override
        public void onPlayerError(PlaybackException e) {
            Log.w(Config.TAG, "player: " + e.getErrorCodeName() + " url=" + currentUrl, e);
            if (live && e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // nos quedamos atras de la ventana en vivo: volvemos al punto en vivo
                player.seekToDefaultPosition();
                player.prepare();
                return;
            }
            onPlaybackError(e.getErrorCodeName());
        }

        @Override
        public void onTracksChanged(Tracks tracks) {
            if (live && player.getPlaybackState() == Player.STATE_READY) {
                checkTracks(tracks);
            }
        }

        @Override
        public void onRenderedFirstFrame() {
            gotFirstFrame = true;
            h.removeCallbacks(firstFrameCheck);
            banner.setVisibility(View.GONE);
            if (live) {
                // formato confirmado para este canal
                Session.extOk.put(queue.get(index).id, Playback.extOfUrl(currentUrl));
                attempt = 0;
                noVideoTried = 0;
            }
        }

        @Override
        public void onVideoSizeChanged(VideoSize vs) {
            if (vs.width > 0 && vs.height > 0) {
                videoAspect = vs.width * vs.pixelWidthHeightRatio / vs.height;
                fitSurface();
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Session.load(this);
        if (queue == null || queue.isEmpty() || index < 0 || index >= queue.size() || !Session.isLoggedIn()) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        buildPlayer();
        play();
    }

    private void buildPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(XtreamClient.USER_AGENT)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true);
        // Los streams IPTV suelen traer marcas de keyframe incompletas: estas banderas lo toleran.
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        // ON: usa los decodificadores del equipo y, si no tiene uno (AC3, MP2, DTS...), el de FFmpeg.
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http, extractors))
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(listener);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int bt, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || bt - t != ob - ot) {
                    fitSurface();
                }
            }
        });

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.CENTER));

        status = new TextView(this);
        status.setTextColor(Ui.TEXT);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        sl.topMargin = Ui.dp(this, 80);
        root.addView(status, sl);

        errorView = new TextView(this);
        errorView.setTextColor(Ui.TEXT);
        errorView.setTextSize(19);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(Ui.dp(this, 28), Ui.dp(this, 20), Ui.dp(this, 28), Ui.dp(this, 20));
        errorView.setBackground(Ui.rect(this, 0xE6000000, 14, 0, 0));
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                Ui.dp(this, 640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        banner = new TextView(this);
        banner.setTextColor(Ui.TEXT);
        banner.setTextSize(16);
        banner.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        banner.setBackground(Ui.rect(this, 0xE6B26A00, 10, 0, 0));
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams bnl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bnl.topMargin = Ui.dp(this, 28);
        root.addView(banner, bnl);

        // panel inferior con titulo / estado / progreso
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 48), Ui.dp(this, 24), Ui.dp(this, 48), Ui.dp(this, 28));
        panel.setBackgroundColor(0xB3000000);
        overlay = panel;
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        title = new TextView(this);
        title.setTextColor(Ui.TEXT);
        title.setTextSize(24);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(title);

        info = new TextView(this);
        info.setTextColor(Ui.MUTED);
        info.setTextSize(16);
        panel.addView(info);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        bl.topMargin = Ui.dp(this, 10);
        panel.addView(bar, bl);

        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (overlay.getVisibility() == View.VISIBLE) {
                        overlay.setVisibility(View.GONE);
                    } else {
                        showOverlay();
                    }
                }
                return true;
            }
        });

        setContentView(root);
    }

    /** Ajusta la superficie al tamano del video sin deformarlo (bandas negras si hace falta). */
    private void fitSurface() {
        int w = root.getWidth();
        int hh = root.getHeight();
        if (w == 0 || hh == 0 || videoAspect <= 0) {
            return;
        }
        int sw;
        int sh;
        if ((float) w / hh > videoAspect) {
            sh = hh;
            sw = Math.round(hh * videoAspect);
        } else {
            sw = w;
            sh = Math.round(w / videoAspect);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != sw || lp.height != sh) {
            lp.width = sw;
            lp.height = sh;
            lp.gravity = Gravity.CENTER;
            surface.setLayoutParams(lp);
        }
    }

    // ------------------------------------------------------------------ reproduccion

    private void cancelTimers() {
        h.removeCallbacks(replay);
        h.removeCallbacks(loadTimeout);
        h.removeCallbacks(firstFrameCheck);
    }

    private void play() {
        cancelTimers();
        generation++;
        Stream s = queue.get(index);
        live = s.live;
        currentUrl = Playback.urlFor(s, attempt);
        gotFirstFrame = false;
        failed = false;
        title.setText(s.name);
        bar.setVisibility(live ? View.GONE : View.VISIBLE);
        errorView.setVisibility(View.GONE);
        banner.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        if (Session.devMode && live) {
            status.setText("Conectando (" + Playback.extOfUrl(currentUrl) + ")"
                    + (attempt > 0 ? " · intento " + (attempt + 1) : "") + "…");
        } else {
            status.setText(attempt > 0 ? "Reconectando…" : "Cargando…");
        }
        player.setMediaItem(MediaItem.fromUri(currentUrl));
        player.prepare();
        player.setPlayWhenReady(true);
        h.postDelayed(loadTimeout, live ? LIVE_TIMEOUT_MS : VOD_TIMEOUT_MS);
        showOverlay();
    }

    /** No arranco (error o tiempo agotado): reintenta con el otro formato o se rinde. */
    private void onPlaybackError(String why) {
        cancelTimers();
        lastError = why;
        if (!live) {
            long p = player.getCurrentPosition();
            if (p > 0) {
                resumeMs = p;
            }
        }
        attempt++;
        if (attempt < (live ? MAX_FAILS_LIVE : MAX_FAILS_VOD)) {
            player.stop();
            spinner.setVisibility(View.VISIBLE);
            status.setVisibility(View.VISIBLE);
            status.setText(Session.devMode ? "No cargó (" + why + "). Probando otro formato…" : "Reconectando…");
            h.postDelayed(replay, 800);
            return;
        }
        finalFailure(why);
    }

    /** Revisa si el canal trae video que este equipo puede mostrar. */
    private void checkTracks(Tracks tracks) {
        if (tracks == null || tracks.isEmpty() || failed) {
            return;
        }
        if (tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) {
            handleNoVideo("video no compatible con este equipo");
        }
        // Sin pista de video (posible radio) lo decide firstFrameCheck, para no cortar radios.
    }

    private void handleNoVideo(String why) {
        if (gotFirstFrame || failed) {
            return;
        }
        lastError = why;
        h.removeCallbacks(firstFrameCheck);
        if (noVideoTried < 1) {
            noVideoTried++;
            attempt++; // el siguiente intento usa el otro formato
            player.stop();
            spinner.setVisibility(View.VISIBLE);
            status.setVisibility(View.VISIBLE);
            status.setText(Session.devMode ? "Sin imagen (" + why + "). Probando otro formato…" : "Reconectando…");
            h.removeCallbacks(replay);
            h.postDelayed(replay, 500);
            return;
        }
        // Ya probamos ambos formatos: dejamos el audio (puede ser una radio) y avisamos.
        banner.setText(Session.devMode
                ? "Sin imagen: " + why + ".   OK: abrir en VLC"
                : "Este canal no muestra imagen en este equipo.");
        banner.setVisibility(View.VISIBLE);
        failed = Session.devMode; // en modo tecnico, OK abre VLC
    }

    private void finalFailure(final String why) {
        failed = true;
        player.stop(); // liberamos la conexion (cuentas de 1 conexion)
        spinner.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        final String head = live ? "No pudimos reproducir este canal en este momento."
                : "No pudimos reproducir esta película en este momento.";
        if (!Session.devMode) {
            showFailure(head, "");
            return;
        }
        final Stream s = queue.get(index);
        final int gen = generation;
        showFailure(head, "Detalle: " + why + "\nAnalizando el stream…");
        if (!live) {
            showFailure(head, "Detalle: " + why);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder sb = new StringBuilder("Detalle: " + why + "\n");
                String hint = "";
                for (int i = 0; i < 2; i++) {
                    String url = Playback.urlFor(s, i);
                    StreamProbe.Result r = StreamProbe.probe(url, 10000);
                    sb.append(Playback.extOfUrl(url)).append(": ").append(r.summary()).append('\n');
                    if (hint.length() == 0) {
                        hint = r.problemHint();
                    }
                }
                final String details = sb.toString().trim() + (hint.length() > 0 ? "\n\n" + hint : "");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && gen == generation) {
                            showFailure(head, details);
                        }
                    }
                });
            }
        }).start();
    }

    private void showFailure(String head, String details) {
        String keys = Session.devMode
                ? "OK: abrir en VLC     ↑ ↓: otro canal     ATRÁS: volver"
                : (live ? "OK: reintentar     ↑ ↓: otro canal     ATRÁS: volver"
                        : "OK: reintentar     ATRÁS: volver");
        errorView.setText(head + (details.length() > 0 ? "\n\n" + details : "") + "\n\n" + keys);
        errorView.setVisibility(View.VISIBLE);
    }

    private void retry() {
        attempt = 0;
        noVideoTried = 0;
        play();
    }

    private void zap(int delta) {
        int n = queue.size();
        if (n < 2) {
            return;
        }
        index = (index + delta + n) % n;
        attempt = 0;
        noVideoTried = 0;
        resumeMs = 0;
        // Cortamos ya y abrimos el canal nuevo con una pequena espera: da tiempo al panel de liberar
        // la conexion anterior y evita abrir uno por cada canal si se mantiene pulsado.
        cancelTimers();
        generation++;
        player.stop();
        failed = false;
        errorView.setVisibility(View.GONE);
        banner.setVisibility(View.GONE);
        title.setText(queue.get(index).name);
        spinner.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText("Cambiando de canal…");
        showOverlay();
        h.postDelayed(replay, 450);
    }

    private void seekBy(long deltaMs) {
        long dur = player.getDuration();
        if (dur <= 0) {
            return;
        }
        long target = Math.max(0, Math.min(player.getCurrentPosition() + deltaMs, dur - 1000));
        player.seekTo(target);
        showOverlay();
    }

    // ------------------------------------------------------------------ overlay

    private void showOverlay() {
        overlay.setVisibility(View.VISIBLE);
        updateInfo();
        h.removeCallbacks(hideOverlay);
        h.removeCallbacks(ticker);
        h.postDelayed(ticker, 500);
        h.postDelayed(hideOverlay, 4500);
    }

    private void updateInfo() {
        if (overlay.getVisibility() != View.VISIBLE) {
            h.removeCallbacks(ticker);
            return;
        }
        if (live) {
            StringBuilder sb = new StringBuilder("EN VIVO");
            if (Session.devMode) {
                sb.append("   •   ").append(Playback.extOfUrl(currentUrl));
                VideoSize vs = player.getVideoSize();
                if (vs.width > 0) {
                    sb.append("   •   ").append(vs.width).append("x").append(vs.height);
                }
            }
            sb.append("   •   ↑ ↓ cambiar canal");
            info.setText(sb.toString());
            return;
        }
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        info.setText((player.isPlaying() ? "Reproduciendo" : "Pausado") + "   " + fmt(pos) + " / " + fmt(dur)
                + "   •   ← → adelantar / retroceder");
        bar.setProgress(dur > 0 ? (int) (pos * 1000L / dur) : 0);
    }

    private static String fmt(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long s = ms / 1000;
        long hh = s / 3600;
        long mm = (s % 3600) / 60;
        long ss = s % 60;
        return hh > 0 ? String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss)
                : String.format(Locale.US, "%02d:%02d", mm, ss);
    }

    // ------------------------------------------------------------------ control remoto

    private void togglePause() {
        if (live) {
            showOverlay();
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
        showOverlay();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        if (ok && failed && ev.getRepeatCount() == 0) {
            if (Session.devMode) {
                Stream s = queue.get(index);
                Playback.launchExternal(this, Playback.urlFor(s, 0), s.name);
            } else {
                retry();
            }
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (ev.getRepeatCount() == 0) {
                    togglePause();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (!live) {
                    player.play();
                }
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (!live) {
                    player.pause();
                }
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (live) {
                    zap(-1);
                } else {
                    showOverlay();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (live) {
                    zap(1);
                } else {
                    showOverlay();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (live) {
                    showOverlay();
                    return true;
                }
                long now = System.currentTimeMillis();
                if (ev.getRepeatCount() > 0 && now - lastSeekAt < 300) {
                    return true; // al mantener pulsado, limitamos la frecuencia
                }
                lastSeekAt = now;
                boolean back = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND;
                long step = ev.getRepeatCount() > 2 ? 30000 : 10000;
                seekBy(back ? -step : step);
                return true;
            default:
                return super.onKeyDown(keyCode, ev);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            player.removeListener(listener);
            player.release();
            player = null;
        }
        finish(); // al salir de la app el streaming no sigue en segundo plano
    }
}
