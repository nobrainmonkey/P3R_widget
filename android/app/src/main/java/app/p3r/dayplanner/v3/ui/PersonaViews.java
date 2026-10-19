package app.p3r.dayplanner.v3.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.compendium.Compendium;

/**
 * Shared pieces of the 全书 screens: the glossary wording of fusion conditions and DLC packs, the
 * registration check and favorite star, the passive 「已登录」 plate, and the borderless Persona row
 * of the list and the pickers (recycled, with the game's sweeping cursor bar when pressed).
 */
final class PersonaViews {
    private PersonaViews() {}

    // ------------------------------------------------------------------ wording

    private static final String[] ROMAN = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
        "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX"};

    /** Teammates whose link episodes unlock a fusion, by their full names as in 羁绊剧情. */
    private static final Map<String, String> TEAMMATES = new HashMap<>();

    static {
        TEAMMATES.put("Junpei", "伊织顺平");
        TEAMMATES.put("Akihiko", "真田明彦");
        TEAMMATES.put("Koromaru", "虎狼丸");
        TEAMMATES.put("Ken", "天田乾");
        TEAMMATES.put("Shinjiro", "荒垣真次郎");
        TEAMMATES.put("Ryoji", "望月绫时");
    }

    /** The card number of an arcana ("0", "VI", "XX"); empty past 审判 (永劫 has no number of its own). */
    static String roman(Compendium.Arcana arcana) {
        return arcana != null && arcana.order >= 0 && arcana.order < ROMAN.length ? ROMAN[arcana.order] : "";
    }

    static String arcanaName(Compendium data, String id) {
        Compendium.Arcana value = data.arcana(id);
        return value == null ? id : value.nameZh;
    }

    /** The decorative Latin word of an arcana ("FOOL", "HANGED"); never the only label. */
    static String arcanaWord(Compendium data, String id) {
        Compendium.Arcana value = data.arcana(id);
        return (value == null ? id : value.nameEn).toUpperCase(Locale.ROOT);
    }

    /** 「愚者 Lv.1」. */
    static String arcanaLevel(Compendium data, Compendium.Persona persona) {
        return arcanaName(data, persona.arcana) + " Lv." + persona.level;
    }

    /** A DLC pack as the app writes it: 「Ｐ５Ｒ　人格面具组合１」 → 「P5R 人格面具组合 1」. */
    static String dlcName(Compendium data, String packId) {
        for (Compendium.DlcPack pack : data.dlcPacks()) {
            if (pack.id.equals(packId)) return halfWidth(pack.nameZh);
        }
        return "DLC";
    }

    /** Half-width letters and digits, one space between them and Chinese. */
    static String halfWidth(String text) {
        String normal = Normalizer.normalize(text, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
        return normal.replaceAll("(?<=[\\u4e00-\\u9fff])(?=[0-9A-Za-z])", " ")
            .replaceAll("(?<=[0-9A-Za-z])(?=[\\u4e00-\\u9fff])", " ");
    }

    /**
     * The fusion condition in the glossary's words, from the structured unlock: 「太阳社群升到 MAX」,
     * 「所有社群都升到 MAX」, 「完成伊莉莎白的第 77 号委托」, 「看完望月绫时的全部羁绊剧情」,
     * 「塔尔塔罗斯到达 143F」; the catalogue's own text otherwise; empty when there is none.
     */
    static String condition(Compendium data, Compendium.Persona persona) {
        switch (persona.unlockType) {
            case "social":
                if (!persona.unlockArcana.isEmpty()) {
                    String rank = persona.unlockRank <= 0 || persona.unlockRank >= 10 ? "MAX" : "RANK " + persona.unlockRank;
                    return arcanaName(data, persona.unlockArcana) + "社群升到 " + rank;
                }
                break;
            case "all_social":
                return "所有社群都升到 MAX";
            case "request":
                if (persona.unlockRequestId > 0) return "完成伊莉莎白的第 " + persona.unlockRequestId + " 号委托";
                break;
            case "linked": {
                String who = TEAMMATES.get(persona.unlockCharacter);
                if (who != null) return "看完" + who + "的全部羁绊剧情";
                break;
            }
            case "floor":
                if (persona.unlockFloor > 0) return "塔尔塔罗斯到达 " + persona.unlockFloor + "F";
                break;
            default:
                break;
        }
        return persona.unlockTextZh;
    }

    /** 「3 SP」, 「8% HP」 (a space before the unit); empty for a skill without a cost. */
    static String cost(Compendium.Skill skill) {
        if ("HP".equals(skill.costType)) return skill.cost + "% HP";
        if ("SP".equals(skill.costType)) return skill.cost + " SP";
        return "";
    }

    /** What a skill does besides its power (「威力50；HP恢复」 → 「HP恢复」); empty when nothing is left. */
    static String effect(Compendium.Skill skill) {
        StringBuilder out = new StringBuilder();
        for (String part : skill.effectZh.split("；")) {
            String piece = part.trim();
            if (piece.isEmpty() || piece.matches("威力\\d+")) continue;
            if (out.length() > 0) out.append("；");
            out.append(piece);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ small views

    /** The gold 「合体条件」 plate and the condition beside it. */
    static LinearLayout conditionLine(Context c, String condition) {
        LinearLayout row = Ui.row(c);
        TextView tag = Ui.tag(c, "合体条件", Ui.col(c, R.color.ui_gold), Ui.col(c, R.color.p3r_navy_950));
        row.addView(tag, Ui.wrap());
        TextView text = Ui.mixed(c, condition, 15, Ui.col(c, R.color.ui_gold), true);
        LinearLayout.LayoutParams p = Ui.weight(1);
        p.leftMargin = Ui.dp(c, 10);
        row.addView(text, p);
        row.setContentDescription("合体条件：" + condition);
        return row;
    }

    /** 「Lv.23」 in the numeral face with a smaller 「Lv.」. */
    static TextView level(Context c, int level, float sp, int color, float lvScale) {
        TextView view = Ui.numeral(c, "", sp, color);
        setLevel(view, level, lvScale);
        return view;
    }

    static void setLevel(TextView view, int level, float lvScale) {
        SpannableString text = new SpannableString("Lv." + level);
        text.setSpan(new RelativeSizeSpan(lvScale), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
        view.setContentDescription("等级 " + level);
    }

    /** A huge or small outlined Latin word, one line, cropped by its parent (decoration). */
    static TextView outlinedWord(Context c, String word, float sp, int color) {
        TextView view = new TextView(c);
        view.setText(word);
        view.setTypeface(Ui.numerals(c));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setIncludeFontPadding(false);
        view.setMaxLines(1);
        view.setHorizontallyScrolling(true);
        view.setTextColor(color);
        view.getPaint().setStyle(Paint.Style.STROKE);
        view.getPaint().setStrokeWidth(Ui.dp(c, 1.2f));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /** The arcana word as a giant faint outline behind a hero panel. */
    static Drawable withWatermark(Context c, Drawable panel, String word) {
        WatermarkDrawable mark = new WatermarkDrawable(word, Ui.numerals(c),
            Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 44), Ui.dp(c, word.length() > 6 ? 76 : 96),
            Ui.dp(c, 1.6f), -8f, 0.30f);
        return new LayerDrawable(new Drawable[]{panel, mark});
    }

    /** The registration check: a slanted square, outlined when off, cyan with a navy tick when on. */
    static Drawable checkDrawable(Context c, boolean checked, int sizePx) {
        SlantedPanelDrawable box = new SlantedPanelDrawable().skew(Math.round(sizePx * 0.16f));
        if (checked) {
            box.fill(Ui.col(c, R.color.p3r_cyan_400));
        } else {
            box.fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 130))
                .stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 220), Ui.dp(c, 1.6f));
        }
        if (!checked) {
            box.setBounds(0, 0, sizePx, sizePx);
            return box;
        }
        Drawable tick = Ui.tinted(c, R.drawable.ui_ic_check, Ui.col(c, R.color.p3r_navy_950));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{box, tick});
        int inset = Math.round(sizePx * 0.13f);
        layers.setLayerInset(1, inset, inset, inset, inset);
        layers.setBounds(0, 0, sizePx, sizePx);
        return layers;
    }

    /** The favorite star: cyan when on, a dim star when off. */
    static Drawable starDrawable(Context c, boolean on, int sizePx) {
        Drawable star = Ui.tinted(c, R.drawable.ui_ic_star, on ? Ui.col(c, R.color.p3r_cyan_400)
            : Ui.alpha(Ui.col(c, R.color.p3r_ink_faint), 170));
        star.setBounds(0, 0, sizePx, sizePx);
        return star;
    }

    /** Announces a view as a check box whose checked state is its selected state. */
    static final View.AccessibilityDelegate CHECKBOX = new View.AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName("android.widget.CheckBox");
            info.setCheckable(true);
            info.setChecked(host.isSelected());
        }
    };

    /** A 48 dp icon toggle: the registration check or the favorite star. */
    static final class Toggle extends View {
        private final boolean star;
        private boolean on;
        private Drawable icon;

        Toggle(Context c, boolean star) {
            super(c);
            this.star = star;
            setClickable(true);
            setFocusable(true);
            setMinimumWidth(Ui.dp(c, Ui.TOUCH));
            setMinimumHeight(Ui.dp(c, Ui.TOUCH));
            setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_soft))
                .skew(Ui.dp(c, 6)));
            setAccessibilityDelegate(CHECKBOX);
            setChecked(false);
        }

        void setChecked(boolean value) {
            on = value;
            setSelected(value);
            icon = star ? starDrawable(getContext(), value, Ui.dp(getContext(), 22))
                : checkDrawable(getContext(), value, Ui.dp(getContext(), 24));
            invalidate();
        }

        boolean isChecked() {
            return on;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthSpec),
                resolveSize(getSuggestedMinimumHeight(), heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Rect b = icon.getBounds();
            int save = canvas.save();
            canvas.translate((getWidth() - b.width()) / 2f, (getHeight() - b.height()) / 2f);
            icon.draw(canvas);
            canvas.restoreToCount(save);
        }
    }

    /** A labelled slanted toggle (「★ 已收藏」, 「✓ 已登录」) announced as a check box. */
    static TextView labelledToggle(Context c) {
        TextView view = Ui.text(c, "", 15, Ui.col(c, R.color.p3r_white), true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(Ui.dp(c, Ui.TOUCH));
        view.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
        view.setMaxLines(1);
        view.setCompoundDrawablePadding(Ui.dp(c, 8));
        Ui.pad(view, 14, 6, 18, 6);
        view.setClickable(true);
        view.setFocusable(true);
        view.setAccessibilityDelegate(CHECKBOX);
        Motion.pressable(view);
        return view;
    }

    static void paintLabelled(TextView view, boolean on, String label, Drawable icon) {
        Context c = view.getContext();
        view.setText(label);
        view.setSelected(on);
        view.setCompoundDrawables(icon, null, null, null);
        view.setTextColor(Ui.col(c, on ? R.color.p3r_white : R.color.p3r_ice_100));
        SlantedPanelDrawable plate = new SlantedPanelDrawable().skew(Ui.dp(c, 8))
            .pressedFill(Ui.col(c, R.color.p3r_navy_700));
        if (on) {
            plate.fill(Ui.col(c, R.color.ui_cyan_soft)).stroke(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 1.5f));
        } else {
            plate.fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 120)).stroke(Ui.col(c, R.color.p3r_line), Ui.dp(c, 1));
        }
        view.setBackground(plate);
    }

    /** The passive 「已登录」 plate; the page repaints every one after a registration changes. */
    static final class OwnedMark extends TextView {
        String personaId;

        OwnedMark(Context c) {
            super(c);
            setText("已登录");
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            setTypeface(Typeface.DEFAULT_BOLD);
            setTextColor(Ui.col(c, R.color.p3r_cyan_300));
            setMaxLines(1);
            setGravity(Gravity.CENTER);
            Drawable tick = Ui.tinted(c, R.drawable.ui_ic_check, Ui.col(c, R.color.p3r_cyan_300));
            tick.setBounds(0, 0, Ui.dp(c, 12), Ui.dp(c, 12));
            setCompoundDrawables(tick, null, null, null);
            setCompoundDrawablePadding(Ui.dp(c, 3));
            setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.ui_cyan_soft)).skew(Ui.dp(c, 4)));
            Ui.pad(this, 7, 2, 9, 3);
            setVisibility(GONE);
        }

        void show(String id, boolean registered) {
            personaId = id;
            setSelected(registered);
            setVisibility(registered ? VISIBLE : GONE);
        }
    }

    // ------------------------------------------------------------------ count plate

    /** 「合体篮 2」: the count on a small slanted plate in the label's own colour. */
    static CharSequence withCount(Context c, String label, int count) {
        SpannableStringBuilder text = new SpannableStringBuilder(label).append(' ');
        int start = text.length();
        text.append(String.valueOf(count));
        text.setSpan(new CountPlate(Ui.numerals(c), Ui.dp(c, 5), Ui.dp(c, 4)), start, text.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private static final class CountPlate extends ReplacementSpan {
        private final Typeface face;
        private final int pad;
        private final int skew;
        private final Paint numeral = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CountPlate(Typeface face, int pad, int skew) {
            this.face = face;
            this.pad = pad;
            this.skew = skew;
        }

        private String digits(CharSequence text, int start, int end) {
            return Zh.plain(text.subSequence(start, end));
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            numeral.set(paint);
            numeral.setTypeface(face);
            numeral.setTextSize(paint.getTextSize() * 1.05f);
            if (fm != null) {
                Paint.FontMetricsInt own = paint.getFontMetricsInt();
                fm.ascent = own.ascent;
                fm.descent = own.descent;
                fm.top = own.top;
                fm.bottom = own.bottom;
            }
            return Math.round(numeral.measureText(digits(text, start, end)) + 2 * pad + skew);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                         int bottom, Paint paint) {
            String value = digits(text, start, end);
            numeral.set(paint);
            numeral.setTypeface(face);
            numeral.setTextSize(paint.getTextSize() * 1.05f);
            float width = numeral.measureText(value) + 2 * pad + skew;
            Paint.FontMetrics m = paint.getFontMetrics();
            float plateTop = y + m.ascent * 0.92f;
            float plateBottom = y + m.descent * 0.7f;
            path.reset();
            path.moveTo(x + skew, plateTop);
            path.lineTo(x + width, plateTop);
            path.lineTo(x + width - skew, plateBottom);
            path.lineTo(x, plateBottom);
            path.close();
            int ink = paint.getColor();
            plate.setColor(ink);
            canvas.drawPath(path, plate);
            boolean light = (Color.red(ink) * 299 + Color.green(ink) * 587 + Color.blue(ink) * 114) / 1000 > 128;
            numeral.setColor(light ? 0xFF030A24 : 0xFF3CE4FF);
            float baseline = (plateTop + plateBottom) / 2f - (numeral.ascent() + numeral.descent()) / 2f;
            canvas.drawText(value, x + pad + skew / 2f, baseline, numeral);
        }
    }

    // ------------------------------------------------------------------ message bar

    /** A message with one action (「撤销」, 「去看看」) in the app's message bar; elsewhere the plain message. */
    static void announce(HomeHost host, String text, String action, Runnable onAction) {
        Activity activity = host.activity();
        if (action != null && activity instanceof HomeActivity) {
            HomeActivity home = (HomeActivity) activity;
            home.undoBar().show(text, action, onAction);
            home.placeUndo();
        } else {
            host.showMessage(text);
        }
    }

    // ------------------------------------------------------------------ the cursor

    /**
     * The game's menu cursor: pressed, a cyan slanted bar sweeps in from the left with a bright
     * blade at its start; released, it pulls back. Without animations it simply appears.
     */
    static final class CursorDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float skew;
        private final float bladeWidth;
        private float progress;
        private boolean pressed;
        private ValueAnimator animator;

        CursorDrawable(Context c) {
            skew = Ui.dp(c, 12);
            bladeWidth = Ui.dp(c, 4);
            fill.setColor(Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 52));
            blade.setColor(Ui.col(c, R.color.p3r_cyan_400));
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean now = false;
            for (int s : state) {
                if (s == android.R.attr.state_pressed) now = true;
            }
            if (now == pressed) return false;
            pressed = now;
            animateTo(now ? 1f : 0f);
            return true;
        }

        private void animateTo(float target) {
            if (animator != null) animator.cancel();
            if (!Ui.animationsEnabled()) {
                progress = target;
                invalidateSelf();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(target > 0 ? 170 : 140);
            animator.setInterpolator(Motion.SETTLE);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            animator.start();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (progress <= 0.01f || b.isEmpty()) return;
            float right = b.left + (b.width() + skew) * progress;
            path.reset();
            path.moveTo(b.left + skew, b.top);
            path.lineTo(Math.min(right, b.right), b.top);
            path.lineTo(Math.min(right - skew, b.right - skew), b.bottom);
            path.lineTo(b.left, b.bottom);
            path.close();
            canvas.drawPath(path, fill);
            path.reset();
            path.moveTo(b.left + skew, b.top);
            path.lineTo(b.left + skew + bladeWidth, b.top);
            path.lineTo(b.left + bladeWidth, b.bottom);
            path.lineTo(b.left, b.bottom);
            path.close();
            canvas.drawPath(path, blade);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha * 52 / 255);
            blade.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
            blade.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ the Persona row

    /**
     * A borderless row of the 一览 list and the pickers: the level as a big cyan numeral, the
     * Chinese name over 「Orpheus · 愚者」, the passive 「已登录」 plate and (in the list) the star.
     * Sorted by arcana, the first row of each arcana carries its header (「VI ◇LOVERS◇ 恋爱」).
     * Rebound for list recycling.
     */
    static final class PersonaRow extends LinearLayout {
        interface Callbacks {
            void open(Compendium.Persona persona);

            void favorite(Compendium.Persona persona);
        }

        private final LinearLayout header;
        private final TextView headerNumber;
        private final TextView headerWord;
        private final TextView headerName;
        private final TextView levelNumber;
        private final TextView name;
        private final TextView sub;
        private final TextView dlc;
        private final OwnedMark owned;
        private final Toggle star;
        private final ImageView favoriteMark;
        private Compendium.Persona persona;

        PersonaRow(Context c, boolean catalogue, Callbacks callbacks) {
            super(c);
            setOrientation(VERTICAL);
            setClickable(true);
            setFocusable(true);
            header = Ui.row(c);
            header.setGravity(Gravity.BOTTOM);
            Ui.pad(header, 4, 18, 0, 6);
            headerNumber = Ui.numeral(c, "", 22, Ui.col(c, R.color.p3r_cyan_400));
            header.addView(headerNumber, Ui.wrap());
            headerWord = outlinedWord(c, "", 26, Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 170));
            LinearLayout.LayoutParams wordParams = Ui.wrap();
            wordParams.leftMargin = Ui.dp(c, 10);
            header.addView(headerWord, wordParams);
            headerName = Ui.text(c, "", 15, Ui.col(c, R.color.p3r_white), true);
            LinearLayout.LayoutParams nameParams = Ui.wrap();
            nameParams.leftMargin = Ui.dp(c, 10);
            nameParams.bottomMargin = Ui.dp(c, 1);
            header.addView(headerName, nameParams);
            header.setVisibility(GONE);
            addView(header, Ui.matchWrap());

            LinearLayout body = Ui.row(c);
            body.setDuplicateParentStateEnabled(true);
            body.setBackground(new CursorDrawable(c));
            body.setMinimumHeight(Ui.dp(c, catalogue ? 64 : 56));
            Ui.pad(body, 2, 4, 0, 4);
            LinearLayout levelColumn = Ui.column(c);
            levelColumn.setGravity(Gravity.CENTER);
            TextView lv = Ui.numeral(c, "Lv", 12, Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 200));
            lv.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            levelColumn.addView(lv, Ui.wrap());
            levelNumber = Ui.numeral(c, "", catalogue ? 26 : 22, Ui.col(c, R.color.p3r_cyan_400));
            levelColumn.addView(levelNumber, Ui.wrap());
            body.addView(levelColumn, new LinearLayout.LayoutParams(Ui.dp(c, 44), LayoutParams.WRAP_CONTENT));
            LinearLayout texts = Ui.column(c);
            name = Ui.text(c, "", catalogue ? 17 : 16, Ui.col(c, R.color.p3r_white), true);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name);
            LinearLayout subRow = Ui.row(c);
            sub = Ui.text(c, "", 12, Ui.col(c, R.color.p3r_ink_muted), false);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            subRow.addView(sub, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            dlc = Ui.tag(c, "DLC", Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 170), Ui.col(c, R.color.p3r_cyan_200));
            dlc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams dlcParams = Ui.wrap();
            dlcParams.leftMargin = Ui.dp(c, 6);
            subRow.addView(dlc, dlcParams);
            texts.addView(subRow, Ui.matchWrap());
            LinearLayout.LayoutParams textParams = Ui.weight(1);
            textParams.leftMargin = Ui.dp(c, 8);
            body.addView(texts, textParams);
            owned = new OwnedMark(c);
            LinearLayout.LayoutParams ownedParams = Ui.wrap();
            ownedParams.leftMargin = Ui.dp(c, 6);
            body.addView(owned, ownedParams);
            if (catalogue) {
                star = new Toggle(c, true);
                star.setOnClickListener(v -> {
                    if (persona != null) callbacks.favorite(persona);
                });
                body.addView(star, new LinearLayout.LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
                favoriteMark = null;
            } else {
                star = null;
                favoriteMark = Ui.icon(c, R.drawable.ui_ic_star, Ui.col(c, R.color.p3r_cyan_400), 16);
                LinearLayout.LayoutParams markParams = (LinearLayout.LayoutParams) favoriteMark.getLayoutParams();
                markParams.leftMargin = Ui.dp(c, 8);
                markParams.rightMargin = Ui.dp(c, 8);
                body.addView(favoriteMark, markParams);
            }
            addView(body, Ui.matchWrap());
            setOnClickListener(v -> {
                if (persona != null) callbacks.open(persona);
            });
        }

        void bind(Compendium data, Compendium.Persona value, boolean available, boolean favorite,
                  boolean registered, boolean groupHeader, boolean grouped) {
            persona = value;
            setTag("persona_" + value.id);
            header.setVisibility(groupHeader ? VISIBLE : GONE);
            if (groupHeader) {
                String number = roman(data.arcana(value.arcana));
                headerNumber.setText(number);
                headerNumber.setVisibility(number.isEmpty() ? GONE : VISIBLE);
                headerWord.setText(arcanaWord(data, value.arcana));
                headerName.setText(arcanaName(data, value.arcana));
            }
            levelNumber.setText(String.valueOf(value.level));
            name.setText(value.nameZh);
            // Under an arcana header the arcana is already said.
            sub.setText(Ui.latin(getContext(), grouped ? value.nameEn : value.nameEn + " · " + arcanaName(data, value.arcana)));
            dlc.setVisibility(value.isDlc() ? VISIBLE : GONE);
            if (value.isDlc()) dlc.setText(Ui.latin(getContext(), available ? "DLC" : "DLC · 没买"));
            owned.show(value.id, registered);
            owned.setTag("persona_owned_" + value.id);
            if (star != null) {
                star.setChecked(favorite);
                star.setTag("favorite_" + value.id);
                star.setContentDescription((favorite ? "取消收藏 " : "收藏 ") + value.nameZh);
            }
            if (favoriteMark != null) favoriteMark.setVisibility(favorite ? VISIBLE : GONE);
            setContentDescription(value.nameZh + "，" + value.nameEn + "，" + arcanaLevel(data, value)
                + (registered ? "，已登录" : "") + (favorite && star == null ? "，已收藏" : ""));
        }
    }

    /** A Chinese name with its English name after it, smaller and muted, for guide lookup. */
    static CharSequence bilingual(Context c, String zh, String en, float enScale) {
        SpannableStringBuilder text = new SpannableStringBuilder(zh).append("  ");
        int start = text.length();
        text.append(Ui.latin(c, en));
        text.setSpan(new RelativeSizeSpan(enScale), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Ui.col(c, R.color.p3r_ink_muted)), start, text.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }
}
