package kz.asyltas.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class Ui {
  static final int INK = Color.rgb(52, 42, 37), MUTED = Color.rgb(120, 109, 100);

  static int dp(Context c, float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  static GradientDrawable surface(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(radius);
    return d;
  }

  static LinearLayout column(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  static LinearLayout card(LinearLayout parent) {
    LinearLayout l = column(parent.getContext());
    int p = dp(l.getContext(), 18);
    l.setPadding(p, p, p, p);
    GradientDrawable bg = surface(Color.WHITE, dp(l.getContext(), 18));
    bg.setStroke(dp(l.getContext(), 1), 0xFFE7DFD6);
    l.setBackground(bg);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(l.getContext(), 16);
    parent.addView(l, lp);
    return l;
  }

  static TextView text(LinearLayout p, String value, int size, boolean bold) {
    TextView t = new TextView(p.getContext());
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(bold ? INK : MUTED);
    if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    t.setLineSpacing(dp(p.getContext(), 3), 1);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(p.getContext(), 8);
    p.addView(t, lp);
    return t;
  }

  static Button button(LinearLayout p, String label, boolean primary, Runnable action) {
    Button b =
        new Button(
            p.getContext(), null, 0, primary ? R.style.ActionButton : R.style.SecondaryButton);
    b.setText(label);
    b.setContentDescription(label);
    b.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(p.getContext(), 8);
    b.setMinHeight(dp(p.getContext(), 48));
    p.addView(b, lp);
    return b;
  }

  static EditText field(LinearLayout p, String label, String value, int type) {
    text(p, label, 12, false);
    EditText e = new EditText(p.getContext());
    e.setText(value);
    e.setContentDescription(label);
    e.setHint(label);
    e.setTextSize(15);
    e.setTextColor(INK);
    e.setInputType(type);
    e.setSingleLine(
        type
            != android.text.InputType.TYPE_CLASS_TEXT
                + android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    e.setBackgroundResource(R.drawable.bg_field);
    int pad = dp(p.getContext(), 12);
    e.setPadding(pad, pad, pad, pad);
    e.setMinHeight(dp(p.getContext(), 50));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(p.getContext(), 14);
    p.addView(e, lp);
    return e;
  }

  static String value(EditText e) {
    return e.getText().toString().trim();
  }

  static void link(LinearLayout p, String title, String subtitle, Runnable action) {
    LinearLayout row = new LinearLayout(p.getContext());
    row.setGravity(Gravity.CENTER_VERTICAL);
    int pad = dp(p.getContext(), 12);
    row.setPadding(0, pad, 0, pad);
    LinearLayout words = column(p.getContext());
    text(words, title, 15, true);
    if (!subtitle.isEmpty()) text(words, subtitle, 12, false);
    row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    TextView arrow = new TextView(p.getContext());
    arrow.setText("›");
    arrow.setTextSize(24);
    arrow.setTextColor(MUTED);
    row.addView(arrow, new LinearLayout.LayoutParams(dp(p.getContext(), 24), -2));
    row.setContentDescription(title);
    row.setClickable(true);
    row.setFocusable(true);
    row.setBackgroundResource(android.R.drawable.list_selector_background);
    row.setOnClickListener(v -> action.run());
    p.addView(row, new LinearLayout.LayoutParams(-1, -2));
    View line = new View(p.getContext());
    line.setBackgroundColor(0xFFEDE7E0);
    p.addView(line, new LinearLayout.LayoutParams(-1, dp(p.getContext(), 1)));
  }

  static void insets(View root) {
    root.setOnApplyWindowInsetsListener(
        (v, i) -> {
          v.setPadding(
              i.getSystemWindowInsetLeft(),
              i.getSystemWindowInsetTop(),
              i.getSystemWindowInsetRight(),
              i.getSystemWindowInsetBottom());
          return i.consumeSystemWindowInsets();
        });
    root.requestApplyInsets();
  }
}
