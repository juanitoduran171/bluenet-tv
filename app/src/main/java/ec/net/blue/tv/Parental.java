package ec.net.blue.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;

/**
 * Control parental de categorias para adultos.
 * - La primera vez que se entra a una categoria protegida se crea la contrasena.
 * - Desbloqueada, queda abierta mientras la app esta en pantalla; al pasar a segundo plano se bloquea.
 * - Solo se guarda el hash (SHA-256 con sal), nunca la contrasena.
 */
final class Parental {
    private static final String PREFS = "bluenet_parental";
    private static final int MIN_LEN = 4;

    /** true mientras la sesion esta desbloqueada (se reinicia al salir de la app). */
    static boolean unlocked;

    private Parental() {
    }

    /** Categoria para adultos segun su nombre (ADULTOS, XXX, +18, ...). */
    static boolean isAdult(String categoryName) {
        if (categoryName == null) {
            return false;
        }
        String n = Normalizer.normalize(categoryName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase();
        return n.contains("adult") || n.contains("xxx") || n.contains("+18") || n.contains("18+")
                || n.contains("porn") || n.contains("erotic");
    }

    static boolean hasPin(Context c) {
        return prefs(c).contains("hash");
    }

    static void lock() {
        unlocked = false;
    }

    // ------------------------------------------------------------------ acceso

    /** Ejecuta onGranted si la sesion ya esta desbloqueada; si no, pide (o crea) la contrasena. */
    static void requestAccess(final Activity a, final Runnable onGranted) {
        if (unlocked) {
            onGranted.run();
        } else if (!hasPin(a)) {
            showCreate(a, "Esta categoría es solo para adultos.\nCrea una contraseña de control parental "
                    + "(mínimo " + MIN_LEN + " números). Se pedirá cada vez que se quiera entrar.", onGranted);
        } else {
            showEnter(a, onGranted);
        }
    }

    /** Menu del boton "Control parental": bloquear ya / cambiar contrasena. */
    static void showMenu(final Activity a, final Runnable onChanged) {
        if (!hasPin(a)) {
            showCreate(a, "Crea la contraseña de control parental (mínimo " + MIN_LEN
                    + " números). Protege las categorías para adultos.", onChanged);
            return;
        }
        final String[] items = unlocked
                ? new String[]{"Bloquear ahora", "Cambiar contraseña"}
                : new String[]{"Cambiar contraseña"};
        new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Control parental — " + (unlocked ? "desbloqueado" : "bloqueado"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (unlocked && which == 0) {
                            lock();
                            Toast.makeText(a, "Categorías para adultos bloqueadas.", Toast.LENGTH_SHORT).show();
                            onChanged.run();
                        } else {
                            // cambiar: primero la contrasena actual
                            showEnter(a, new Runnable() {
                                @Override
                                public void run() {
                                    showCreate(a, "Escribe la nueva contraseña de control parental.", onChanged);
                                }
                            });
                        }
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    // ------------------------------------------------------------------ dialogos

    private static void showCreate(final Activity a, String message, final Runnable onDone) {
        LinearLayout box = box(a, message);
        final EditText p1 = pinField(a, "Nueva contraseña");
        final EditText p2 = pinField(a, "Repite la contraseña");
        box.addView(p1);
        box.addView(p2);
        final AlertDialog d = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Control parental")
                .setView(box)
                .setPositiveButton("Guardar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        showWithKeyboard(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String a1 = p1.getText().toString();
                String a2 = p2.getText().toString();
                if (a1.length() < MIN_LEN) {
                    p1.setError("Mínimo " + MIN_LEN + " números");
                    p1.requestFocus();
                    return;
                }
                if (!a1.equals(a2)) {
                    p2.setText("");
                    p2.setError("No coincide");
                    p2.requestFocus();
                    return;
                }
                savePin(a, a1);
                unlocked = true;
                d.dismiss();
                Toast.makeText(a, "Contraseña guardada.", Toast.LENGTH_SHORT).show();
                onDone.run();
            }
        });
    }

    private static void showEnter(final Activity a, final Runnable onGranted) {
        LinearLayout box = box(a, "Contenido protegido. Escribe la contraseña de control parental.");
        final EditText p = pinField(a, "Contraseña");
        box.addView(p);
        final AlertDialog d = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Control parental")
                .setView(box)
                .setPositiveButton("Desbloquear", null)
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("La olvidé", null)
                .create();
        showWithKeyboard(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkPin(a, p.getText().toString())) {
                    unlocked = true;
                    d.dismiss();
                    onGranted.run();
                } else {
                    p.setText("");
                    p.setError("Contraseña incorrecta");
                    p.requestFocus();
                }
            }
        });
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                showForgot(a, onGranted);
            }
        });
    }

    /** Recuperacion: con la contrasena de la cuenta BLUEnet se puede crear una nueva. */
    private static void showForgot(final Activity a, final Runnable onGranted) {
        LinearLayout box = box(a, "Para crear una nueva contraseña de control parental, escribe la "
                + "contraseña de tu cuenta BLUEnet TV (la que usas para iniciar sesión).");
        final EditText acc = new EditText(a);
        acc.setHint("Contraseña de la cuenta");
        acc.setSingleLine(true);
        acc.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(acc);
        final AlertDialog d = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Restablecer control parental")
                .setView(box)
                .setPositiveButton("Continuar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        showWithKeyboard(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Session.pass != null && Session.pass.length() > 0
                        && Session.pass.equals(acc.getText().toString())) {
                    d.dismiss();
                    showCreate(a, "Escribe la nueva contraseña de control parental.", onGranted);
                } else {
                    acc.setText("");
                    acc.setError("No coincide con la contraseña de la cuenta");
                    acc.requestFocus();
                }
            }
        });
    }

    private static LinearLayout box(Context c, String message) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 20);
        box.setPadding(pad, Ui.dp(c, 8), pad, 0);
        TextView t = new TextView(c);
        t.setText(message);
        t.setTextSize(16);
        t.setPadding(0, 0, 0, Ui.dp(c, 10));
        box.addView(t);
        return box;
    }

    private static EditText pinField(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        return e;
    }

    private static void showWithKeyboard(AlertDialog d) {
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
    }

    // ------------------------------------------------------------------ almacenamiento

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void savePin(Context c, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltHex = hex(salt);
        prefs(c).edit().putString("salt", saltHex).putString("hash", hash(saltHex, pin)).apply();
    }

    static boolean checkPin(Context c, String pin) {
        SharedPreferences p = prefs(c);
        String salt = p.getString("salt", "");
        String h = p.getString("hash", null);
        return h != null && pin != null && pin.length() > 0 && MessageDigest.isEqual(
                h.getBytes(), hash(salt, pin).getBytes());
    }

    static String hash(String saltHex, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(saltHex.getBytes("UTF-8"));
            md.update(pin.getBytes("UTF-8"));
            return hex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }
}
