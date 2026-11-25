package ec.net.blue.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

public class LoginActivity extends Activity {

    private EditText userField;
    private EditText passField;
    private TextView loginButton;
    private TextView status;
    private ProgressBar spinner;
    private boolean busy;
    private TextView netLabel;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable netTick = new Runnable() {
        @Override
        public void run() {
            refreshNet();
            h.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Session.load(this);
        buildUi();

        userField.setText(Session.user == null ? "" : Session.user);
        passField.setText(Session.pass == null ? "" : Session.pass);

        NetInfo.askPermissionOnce(this);
        boolean skipAuto = getIntent().getBooleanExtra("noauto", false);
        if (!skipAuto && Session.user.length() > 0 && Session.pass.length() > 0) {
            attemptLogin(); // sesion guardada: entra directo
        } else {
            userField.requestFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(netTick);
        h.post(netTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(netTick);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshNet();
    }

    private void refreshNet() {
        if (netLabel != null) {
            netLabel.setText(NetInfo.describe(this) + "   ·   BLUEnet TV " + appVersion(this));
        }
    }

    static String appVersion(Context c) {
        try {
            return "v" + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void buildUi() {
        final Context c = this;
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(Ui.BG);

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 24));
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                Ui.dp(c, 440), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scroll.addView(col, colLp);

        ImageView logo = new ImageView(c);
        Bitmap bmp = Ui.asset(c, "logo.png");
        if (bmp != null) {
            logo.setImageBitmap(bmp);
        }
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        col.addView(logo, new LinearLayout.LayoutParams(Ui.dp(c, 150), Ui.dp(c, 150)));

        TextView title = new TextView(c);
        title.setText("Iniciar sesión");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, 12);
        tl.bottomMargin = Ui.dp(c, 18);
        col.addView(title, tl);

        userField = Ui.field(c, "Usuario",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                EditorInfo.IME_ACTION_NEXT);
        col.addView(userField, fieldLp(c, 56, 12));

        passField = Ui.field(c, "Contraseña",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_DONE);
        col.addView(passField, fieldLp(c, 56, 8));

        // "Mostrar contraseña" (reemplaza al ojito del teclado, que no se entiende en TV)
        final TextView showPass = Ui.button(c, "Mostrar contraseña", false);
        showPass.setTextSize(15);
        showPass.setOnClickListener(new View.OnClickListener() {
            private boolean visible;

            @Override
            public void onClick(View v) {
                visible = !visible;
                int sel = passField.getSelectionEnd();
                passField.setInputType(InputType.TYPE_CLASS_TEXT | (visible
                        ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                passField.setSelection(Math.max(0, Math.min(sel, passField.length())));
                showPass.setText(visible ? "Ocultar contraseña" : "Mostrar contraseña");
            }
        });
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 42));
        spLp.gravity = Gravity.END;
        spLp.bottomMargin = Ui.dp(c, 14);
        col.addView(showPass, spLp);
        passField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard();
                    attemptLogin();
                    return true;
                }
                return false;
            }
        });

        loginButton = Ui.button(c, "Ingresar", true);
        loginButton.setTextSize(19);
        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                attemptLogin();
            }
        });
        col.addView(loginButton, fieldLp(c, 56, 12));

        spinner = new ProgressBar(c);
        spinner.setVisibility(View.GONE);
        col.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32)));

        status = new TextView(c);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Ui.MUTED);
        col.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // red del equipo, al pie (para soporte: el cliente manda foto)
        netLabel = new TextView(c);
        netLabel.setTextColor(Ui.MUTED);
        netLabel.setTextSize(13);
        netLabel.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        nl.bottomMargin = Ui.dp(c, 14);
        root.addView(netLabel, nl);
        refreshNet();

        setContentView(root);
    }

    /** Mensaje para el cliente: sin nombres de servidor ni detalles tecnicos. */
    static String friendly(Context c, XtreamClient.ApiException e) {
        switch (e.kind) {
            case XtreamClient.ApiException.KIND_CREDENTIALS:
                return "Usuario o contraseña incorrectos.";
            case XtreamClient.ApiException.KIND_ACCOUNT:
                return "Tu suscripción no está activa. Comunícate con BLUEnet.";
            case XtreamClient.ApiException.KIND_NETWORK:
                if (!NetInfo.isConnected(c)) {
                    return "Tu equipo no está conectado a ninguna red. Revisa el Wi-Fi o el cable de internet.";
                }
                return "Contraseña incorrecta o error del servicio. Revisa tus datos e inténtalo de nuevo.";
            default:
                return "Contraseña incorrecta o error del servicio. Revisa tus datos e inténtalo de nuevo.";
        }
    }

    private LinearLayout.LayoutParams fieldLp(Context c, int heightDp, int bottomMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, heightDp));
        lp.bottomMargin = Ui.dp(c, bottomMarginDp);
        return lp;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) {
            imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
    }

    private void setBusy(boolean on, String message, boolean error) {
        busy = on;
        spinner.setVisibility(on ? View.VISIBLE : View.GONE);
        loginButton.setEnabled(!on);
        loginButton.setAlpha(on ? 0.5f : 1f);
        status.setTextColor(error ? Ui.ERROR : Ui.MUTED);
        status.setText(message == null ? "" : message);
    }

    private void attemptLogin() {
        if (busy) {
            return;
        }
        final String u = userField.getText().toString().trim();
        final String p = passField.getText().toString();
        final String s = Config.SERVER;
        if (u.length() == 0 || p.length() == 0) {
            setBusy(false, "Escribe usuario y contraseña.", true);
            return;
        }
        setBusy(true, "Conectando…", false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                XtreamClient.Auth auth = null;
                XtreamClient.ApiException err = null;
                try {
                    auth = XtreamClient.authenticate(s, u, p, Session.base);
                } catch (XtreamClient.ApiException e) {
                    err = e;
                }
                final XtreamClient.Auth fa = auth;
                final XtreamClient.ApiException fe = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (fa != null) {
                            Session.user = u;
                            Session.pass = p;
                            Session.apply(fa);
                            Session.save(LoginActivity.this);
                            Intent home = new Intent(LoginActivity.this, HomeActivity.class);
                            home.putExtra("resume", true); // al abrir la app, retomar el ultimo canal
                            startActivity(home);
                            finish();
                        } else {
                            if (fe.kind == XtreamClient.ApiException.KIND_CREDENTIALS) {
                                passField.setText("");
                                passField.requestFocus();
                            } else {
                                userField.requestFocus();
                            }
                            android.util.Log.w(Config.TAG, "login: " + fe.getMessage());
                            setBusy(false, friendly(LoginActivity.this, fe)
                                    + (Session.devMode ? "\n(" + fe.getMessage() + ")" : ""), true);
                        }
                    }
                });
            }
        }).start();
    }
}
