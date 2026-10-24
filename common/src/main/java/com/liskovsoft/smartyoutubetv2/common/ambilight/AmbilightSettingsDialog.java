package com.liskovsoft.smartyoutubetv2.common.ambilight;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Екран налаштувань Ambilight: звичайний повноекранний Dialog зі списком рядків, керується пультом (D-pad + OK).
 * Створювати з контекстом Activity: {@code AmbilightSettingsDialog.show(activityContext)}.
 */
public final class AmbilightSettingsDialog {
    private interface ValueProvider {
        String get();
    }

    private interface IntSetter {
        void set(int value);
    }

    private interface TextSetter {
        void set(String value);
    }

    private static final class Row {
        final TextView valueView;
        final ValueProvider provider;

        Row(TextView valueView, ValueProvider provider) {
            this.valueView = valueView;
            this.provider = provider;
        }
    }

    private static final int COLOR_BG = Color.parseColor("#121417");
    private static final int COLOR_FOCUS = Color.parseColor("#33506E");
    private static final int COLOR_TEXT = Color.WHITE;
    private static final int COLOR_VALUE = Color.parseColor("#8AB4F8");
    private static final int COLOR_HINT = Color.parseColor("#9AA0A6");

    private final Context mContext;
    private final AmbilightPrefs mPrefs;
    private final List<Row> mRows = new ArrayList<>();
    private final float mDensity;
    private Dialog mDialog;
    private AmbilightTester mTester;
    private String mTestLabel = "";

    private AmbilightSettingsDialog(Context context) {
        mContext = context;
        mPrefs = new AmbilightPrefs(context);
        mDensity = context.getResources().getDisplayMetrics().density;
    }

    /** Приймає будь-який контекст, але для вікна потрібна Activity: шукаємо її серед обгорток контексту. */
    public static void show(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper && !(current instanceof Activity)) {
            current = ((ContextWrapper) current).getBaseContext();
        }

        if (!(current instanceof Activity) || ((Activity) current).isFinishing()) {
            Toast.makeText(context, "Не вдалося відкрити налаштування Ambilight", Toast.LENGTH_LONG).show();
            return;
        }

        new AmbilightSettingsDialog(current).build();
    }

    private void build() {
        mDialog = new Dialog(mContext, android.R.style.Theme_Holo_NoActionBar_Fullscreen);
        mDialog.setOnDismissListener(d -> {
            if (mTester != null) {
                mTester.stop();
            }
        });

        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(64), dp(32), dp(64), dp(24));

        TextView title = new TextView(mContext);
        title.setText("Ambilight");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setPadding(dp(16), 0, 0, dp(16));
        root.addView(title);

        ScrollView scroll = new ScrollView(mContext);
        LinearLayout list = new LinearLayout(mContext);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addRows(list);

        TextView hint = new TextView(mContext);
        hint.setText("Зміни діють з наступного запуску відео. Назад — закрити.");
        hint.setTextColor(COLOR_HINT);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hint.setPadding(dp(16), dp(12), 0, 0);
        root.addView(hint);

        mDialog.setContentView(root);
        mDialog.show();
        refresh();
        if (list.getChildCount() > 0) {
            list.getChildAt(0).requestFocus();
        }
    }

    private void addRows(LinearLayout list) {
        addRow(list, "Ambilight", () -> mPrefs.isEnabled() ? "Увімкнено" : "Вимкнено", () -> {
            mPrefs.setEnabled(!mPrefs.isEnabled());
            refresh();
        });

        addRow(list, "IP-адреса ESP", mPrefs::getHost,
                () -> promptText("IP-адреса ESP", mPrefs.getHost(), value -> {
                    if (!value.matches("[A-Za-z0-9.\\-]+")) {
                        toast("Некоректна адреса");
                        return;
                    }
                    mPrefs.setHost(value);
                    refresh();
                }));

        addRow(list, "UDP-порт", () -> String.valueOf(mPrefs.getPort()),
                () -> promptNumber("UDP-порт (1–65535)", mPrefs.getPort(), 1, 65535, value -> {
                    mPrefs.setPort(value);
                    refresh();
                }));

        addRow(list, "LED по горизонталі", () -> String.valueOf(mPrefs.getLedsH()),
                () -> promptNumber("LED на одній горизонтальній стороні", mPrefs.getLedsH(), 1, 239, value -> {
                    if (2 * (value + mPrefs.getLedsV()) > AmbilightPrefs.MAX_TOTAL_LEDS) {
                        toast("Усього LED (2 × (гориз. + верт.)) не більше " + AmbilightPrefs.MAX_TOTAL_LEDS);
                        return;
                    }
                    mPrefs.setLedsH(value);
                    refresh();
                }));

        addRow(list, "LED по вертикалі", () -> String.valueOf(mPrefs.getLedsV()),
                () -> promptNumber("LED на одній вертикальній стороні", mPrefs.getLedsV(), 1, 239, value -> {
                    if (2 * (mPrefs.getLedsH() + value) > AmbilightPrefs.MAX_TOTAL_LEDS) {
                        toast("Усього LED (2 × (гориз. + верт.)) не більше " + AmbilightPrefs.MAX_TOTAL_LEDS);
                        return;
                    }
                    mPrefs.setLedsV(value);
                    refresh();
                }));

        addRow(list, "Кадрів на секунду", () -> String.valueOf(mPrefs.getFps()),
                () -> promptChoice("Кадрів на секунду", range(5, 30, 1), "", mPrefs.getFps(), value -> {
                    mPrefs.setFps(value);
                    refresh();
                }));

        addRow(list, "Швидкість реакції", () -> mPrefs.getReaction() + " з 10",
                () -> promptChoice("Швидкість реакції (1 — плавно, 10 — миттєво)", range(1, 10, 1), "", mPrefs.getReaction(), value -> {
                    mPrefs.setReaction(value);
                    refresh();
                }));

        addRow(list, "Відступ зверху і знизу", () -> mPrefs.getMargin() + "%",
                () -> promptChoice("Відступ від краю (відсікання чорних смуг)", marginValues(), "%", mPrefs.getMargin(), value -> {
                    mPrefs.setMargin(value);
                    refresh();
                }));

        addRow(list, "Глибина зони захвату", () -> mPrefs.getDepth() + "%",
                () -> promptChoice("Глибина зони захвату", depthValues(), "%", mPrefs.getDepth(), value -> {
                    mPrefs.setDepth(value);
                    refresh();
                }));

        addRow(list, "Початок стрічки (LED №0)", () -> AmbilightLayout.START_POINT_NAMES[mPrefs.getStartPoint()],
                () -> promptStartPoint());

        addRow(list, "Напрямок стрічки", () -> mPrefs.isClockwise() ? "За годинниковою стрілкою" : "Проти годинникової",
                () -> promptDirection());

        addRow(list, "Яскравість", () -> mPrefs.getBrightness() + "%",
                () -> promptChoice("Яскравість", range(5, 100, 5), "%", mPrefs.getBrightness(), value -> {
                    mPrefs.setBrightness(value);
                    refresh();
                }));

        addRow(list, "Тест стрічки",
                () -> mTester != null && mTester.isRunning() ? mTestLabel + " (OK — зупинити)" : "OK — запустити",
                this::toggleTest);
    }

    private void addRow(LinearLayout list, String titleText, ValueProvider provider, Runnable onClick) {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(focusBackground());

        TextView title = new TextView(mContext);
        title.setText(titleText);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(mContext);
        value.setTextColor(COLOR_VALUE);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        row.setOnClickListener(v -> onClick.run());
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mRows.add(new Row(value, provider));
    }

    private void refresh() {
        for (Row row : mRows) {
            row.valueView.setText(row.provider.get());
        }
    }

    // ---- Тест стрічки ----

    private void toggleTest() {
        if (mTester != null && mTester.isRunning()) {
            mTester.stop();
            return;
        }

        mTestLabel = "";
        mTester = new AmbilightTester(mContext, new AmbilightTester.Listener() {
            @Override
            public void onStep(String label) {
                mTestLabel = label;
                refresh();
            }

            @Override
            public void onFinished() {
                mTestLabel = "";
                refresh();
            }
        });
        mTester.start();
        mTestLabel = "Старт…";
        refresh();
    }

    // ---- Діалоги вибору ----

    private void promptStartPoint() {
        String[] names = AmbilightLayout.START_POINT_NAMES;
        new AlertDialog.Builder(mContext, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Початок стрічки (LED №0)")
                .setSingleChoiceItems(names, mPrefs.getStartPoint(), (d, which) -> {
                    mPrefs.setStartPoint(which);
                    d.dismiss();
                    refresh();
                })
                .show();
    }

    private void promptDirection() {
        String[] names = {"За годинниковою стрілкою", "Проти годинникової"};
        new AlertDialog.Builder(mContext, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Напрямок стрічки")
                .setSingleChoiceItems(names, mPrefs.isClockwise() ? 0 : 1, (d, which) -> {
                    mPrefs.setClockwise(which == 0);
                    d.dismiss();
                    refresh();
                })
                .show();
    }

    private void promptChoice(String title, int[] values, String suffix, int current, IntSetter setter) {
        String[] labels = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + suffix;
            if (values[i] == current) {
                checked = i;
            }
        }

        new AlertDialog.Builder(mContext, AlertDialog.THEME_HOLO_DARK)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    setter.set(values[which]);
                    d.dismiss();
                })
                .show();
    }

    private void promptNumber(String title, int current, int min, int max, IntSetter setter) {
        promptInput(title, String.valueOf(current), InputType.TYPE_CLASS_NUMBER, text -> {
            int value;
            try {
                value = Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                toast("Введіть число");
                return;
            }
            if (value < min || value > max) {
                toast("Допустимо від " + min + " до " + max);
                return;
            }
            setter.set(value);
        });
    }

    private void promptText(String title, String current, TextSetter setter) {
        promptInput(title, current, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, text -> setter.set(text.trim()));
    }

    private void promptInput(String title, String current, int inputType, TextSetter onOk) {
        EditText edit = new EditText(mContext);
        edit.setInputType(inputType);
        edit.setSingleLine(true);
        edit.setText(current);
        edit.selectAll();

        AlertDialog dialog = new AlertDialog.Builder(mContext, AlertDialog.THEME_HOLO_DARK)
                .setTitle(title)
                .setView(edit)
                .setPositiveButton("OK", (d, w) -> onOk.set(edit.getText().toString()))
                .setNegativeButton("Скасувати", null)
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        edit.requestFocus();
    }

    // ---- Допоміжне ----

    private static int[] range(int from, int to, int step) {
        int count = (to - from) / step + 1;
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = from + i * step;
        }
        return result;
    }

    private static int[] marginValues() {
        // 0–20 з кроком 1 (чорні смуги 21:9 ≈ 12–13%), далі грубіше
        int[] fine = range(0, 20, 1);
        int[] coarse = {25, 30, 35, 40};
        int[] result = new int[fine.length + coarse.length];
        System.arraycopy(fine, 0, result, 0, fine.length);
        System.arraycopy(coarse, 0, result, fine.length, coarse.length);
        return result;
    }

    private static int[] depthValues() {
        return new int[]{2, 3, 4, 5, 6, 8, 10, 12, 14, 16, 18, 20, 25, 30};
    }

    private StateListDrawable focusBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(COLOR_FOCUS);
        focused.setCornerRadius(dp(8));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    private void toast(String message) {
        Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * mDensity);
    }
}
