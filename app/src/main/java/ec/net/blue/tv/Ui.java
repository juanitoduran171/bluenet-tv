package ec.net.blue.tv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;

/** Colores y piezas de interfaz compartidas (todo se construye en codigo, sin XML de layouts). */
final class Ui {
    static final int BG = 0xFF0A1722;
    static final int PANEL = 0xFF132635;
    static final int ACCENT = 0xFF0080B3;
    static final int ACCENT_DARK = 0xFF005F85;
    static final int ACCENT_LIGHT = 0xFF29B6F0;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFF8FA6B8;
    static final int ERROR = 0xFFFF7B7B;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rect(Context c, int fill, float radiusDp, float strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) {
            g.setStroke(dp(c, strokeDp), strokeColor);
        }
        return g;
    }

    /** focused / pressed -> focused; selected -> selected; resto -> normal. */
    static StateListDrawable states(Drawable focused, Drawable selected, Drawable normal) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        if (selected != null) {
            s.addState(new int[]{android.R.attr.state_selected}, selected);
        }
        s.addState(new int[]{}, normal);
        return s;
    }

    static Drawable transparent() {
        return new ColorDrawable(Color.TRANSPARENT);
    }

    static ColorStateList textColors(int whenSelected, int normal) {
        return new ColorStateList(
                new int[][]{{android.R.attr.state_selected}, {android.R.attr.state_focused}, {}},
                new int[]{whenSelected, whenSelected, normal});
    }

    /** Boton de texto enfocable con control remoto. */
    static TextView button(Context c, String text, boolean primary) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        t.setFocusable(true);
        t.setClickable(true);
        int normal = primary ? ACCENT : PANEL;
        t.setBackground(states(
                rect(c, ACCENT_LIGHT, 10, 2, TEXT),
                rect(c, ACCENT, 10, 0, 0),
                rect(c, normal, 10, 0, 0)));
        return t;
    }

    static EditText field(Context c, String hint, int inputType, int imeAction) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(18);
        e.setSingleLine(true);
        e.setEllipsize(TextUtils.TruncateAt.END);
        e.setInputType(inputType);
        e.setImeOptions(imeAction);
        e.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        e.setBackground(states(
                rect(c, PANEL, 10, 2, ACCENT_LIGHT),
                null,
                rect(c, PANEL, 10, 1, 0xFF2A4258)));
        return e;
    }

    static Bitmap asset(Context c, String name) {
        InputStream in = null;
        try {
            in = c.getAssets().open(name);
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
