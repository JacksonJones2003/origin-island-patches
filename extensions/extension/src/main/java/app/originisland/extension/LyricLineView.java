package app.originisland.extension;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a line of lyrics, and brings out each word as it is sung: words that are still to
 * come are dimmed, and the word that is sung brightens and swells a little.
 * A line without times for its words is drawn as a whole.
 * A new line swoops in from below, growing to its size as it settles.
 */
final class LyricLineView extends View {

    interface Position {
        /**
         * @return The position the song is at in milliseconds, or -1 if it is not playing.
         */
        long get();
    }

    private static final class Token {
        final MorpheLyrics.Word word;
        final String text;
        float x;
        float width;
        int row;

        Token(MorpheLyrics.Word word, String text) {
            this.word = word;
            this.text = text;
        }
    }

    private static final float TEXT_SIZE_SP = 15;
    private static final int MAX_ROWS = 2;
    private static final float PADDING_DP = 24;

    private static final int ALPHA_SUNG = 0xFF;
    private static final int ALPHA_TO_COME = 0x73;
    /**
     * How much the word that is sung is larger at most.
     */
    private static final float SWELL = 0.13f;
    /**
     * Time a word takes to brighten at least, and to settle after it was sung.
     */
    private static final long MIN_WORD_TIME_MS = 180;
    private static final long SETTLE_TIME_MS = 220;

    /**
     * Room added between words, as a part of the text size, for the word that is sung
     * to swell into without touching the words next to it.
     */
    private static final float WORD_GAP = 0.14f;

    /**
     * The word that is sung is lit from its start to how far it is sung, with a soft edge
     * of this part of its width, and glows by up to this part of the text size.
     */
    private static final float SHIMMER_EDGE = 0.35f;
    private static final float SHIMMER_GLOW = 0.45f;
    /**
     * A glint runs over the word that is sung this often.
     */
    private static final long SHIMMER_GLINT_TIME_MS = 900;

    private static final long SWOOP_TIME_MS = 520;
    /**
     * How far below its place a new line starts, in rows, and how much smaller.
     */
    private static final float SWOOP_DISTANCE = 1.8f;
    private static final float SWOOP_SHRINK = 0.12f;
    /**
     * How far a new line goes past its place before it settles.
     */
    private static final float SWOOP_OVERSHOOT = 1.4f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final List<Token> tokens = new ArrayList<>();
    private final float padding;
    private final Position position;

    private MorpheLyrics.Line line;
    private int rows;
    private float[] rowWidths = new float[MAX_ROWS];
    private int laidOutWidth = -1;
    private long lineChangedTime;

    LyricLineView(Context context, Position position) {
        super(context);
        this.position = position;

        padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, PADDING_DP,
                getResources().getDisplayMetrics());
        paint.setColor(0xFFFFFFFF);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP,
                getResources().getDisplayMetrics()));
    }

    void setLine(MorpheLyrics.Line line) {
        if (this.line == line) {
            return;
        }
        this.line = line;
        lineChangedTime = SystemClock.uptimeMillis();

        tokens.clear();
        if (line != null) {
            if (line.words.isEmpty()) {
                // A line break starts a new row.
                for (String row : line.text.split("\n")) {
                    if (!tokens.isEmpty()) {
                        tokens.add(new Token(null, "\n"));
                    }
                    for (String text : row.split(" ")) {
                        if (!text.isEmpty()) {
                            tokens.add(new Token(null, text + " "));
                        }
                    }
                }
            } else {
                for (MorpheLyrics.Word word : line.words) {
                    String text = word.text.trim();
                    if (!text.isEmpty()) {
                        tokens.add(new Token(word, word.endsWithSpace ? text + " " : text));
                    }
                }
            }
        }

        // The view keeps its size, so that the player is not laid out again with every line,
        // which would start the title of the queue over if it scrolls.
        laidOutWidth = -1;
        if (getWidth() > 0) {
            layOut(getWidth());
        }
        invalidate();
    }

    /**
     * @return The height of the rows of the line, without the room around them.
     */
    float getTextHeight() {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        return Math.max(rows, 1) * (metrics.descent - metrics.ascent) * 1.15f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        layOut(width);

        Paint.FontMetrics metrics = paint.getFontMetrics();
        float rowHeight = (metrics.descent - metrics.ascent) * 1.15f;
        // Room for the word that is sung to swell into, and below for a new line to come from.
        setMeasuredDimension(width, (int) Math.ceil(
                MAX_ROWS * rowHeight + rowHeight * (0.3f + SWOOP_DISTANCE)));
    }

    /**
     * Breaks the line into rows at its spaces, and drops what does not fit.
     */
    private void layOut(int width) {
        if (width == laidOutWidth) {
            return;
        }
        laidOutWidth = width;

        float available = width - 2 * padding;
        rows = 0;
        rowWidths = new float[MAX_ROWS];
        float x = 0;
        int rowStart = 0;

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.text.equals("\n")) {
                if (rows == MAX_ROWS - 1) {
                    while (tokens.size() > i) {
                        tokens.remove(tokens.size() - 1);
                    }
                    break;
                }
                rowWidths[rows] = x;
                rows++;
                x = 0;
                token.row = rows;
                token.x = 0;
                token.width = 0;
                continue;
            }
            token.width = paint.measureText(token.text)
                    + (token.text.endsWith(" ") ? paint.getTextSize() * WORD_GAP : 0);

            // Parts of a word stay together.
            boolean startsWord = i == 0 || tokens.get(i - 1).text.endsWith(" ")
                    || tokens.get(i - 1).text.equals("\n");
            if (startsWord) {
                float wordWidth = 0;
                for (int j = i; j < tokens.size(); j++) {
                    wordWidth += paint.measureText(tokens.get(j).text.trim());
                    if (tokens.get(j).text.endsWith(" ")) {
                        break;
                    }
                }
                if (x > 0 && x + wordWidth > available) {
                    if (rows == MAX_ROWS - 1) {
                        while (tokens.size() > i) {
                            tokens.remove(tokens.size() - 1);
                        }
                        break;
                    }
                    rowWidths[rows] = x;
                    rows++;
                    x = 0;
                }
            }

            token.row = rows;
            token.x = x;
            x += token.width;
        }

        if (!tokens.isEmpty()) {
            rowWidths[rows] = x;
            rows++;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (tokens.isEmpty()) {
            return;
        }

        long now = position.get();
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float rowHeight = (metrics.descent - metrics.ascent) * 1.15f;
        boolean animated = false;

        // A new line comes up from below and slightly past its place, then settles.
        float swoop = Math.min(1, (SystemClock.uptimeMillis() - lineChangedTime) / (float) SWOOP_TIME_MS);
        float lineAlpha = Math.min(1, swoop * 2.5f);
        if (swoop < 1) {
            float t = swoop - 1;
            float settled = t * t * ((SWOOP_OVERSHOOT + 1) * t + SWOOP_OVERSHOOT) + 1;
            float scale = 1 - SWOOP_SHRINK * (1 - settled);
            canvas.translate(0, rowHeight * SWOOP_DISTANCE * (1 - settled));
            canvas.scale(scale, scale, getWidth() / 2f, rowHeight * Math.max(rows, 1) / 2f);
            animated = true;
        }

        for (Token token : tokens) {
            if (token.width == 0) {
                continue;
            }
            // The space after the last word of a row is not part of what is centered.
            float rowWidth = rowWidths[token.row] - paint.measureText(" ");
            float x = (getWidth() - rowWidth) / 2 + token.x;
            float baseline = rowHeight * 0.15f + token.row * rowHeight - metrics.ascent;

            float brightness = 1;
            float swell = 0;
            float sung = -1;
            if (token.word != null && now >= 0) {
                long start = token.word.startTimeMs;
                long end = Math.max(token.word.endTimeMs, start + MIN_WORD_TIME_MS);
                if (now < start) {
                    brightness = 0;
                    animated = true;
                } else if (now < end) {
                    float progress = (now - start) / (float) (end - start);
                    brightness = Math.min(1, progress * 3);
                    sung = progress;
                    swell = (float) Math.sin(Math.PI * Math.min(1, progress * 1.5f) / 2);
                    animated = true;
                } else if (now < end + SETTLE_TIME_MS) {
                    swell = 1 - (now - end) / (float) SETTLE_TIME_MS;
                    animated = true;
                }
            }

            paint.setAlpha((int) ((ALPHA_TO_COME + (ALPHA_SUNG - ALPHA_TO_COME) * brightness) * lineAlpha));
            if (swell > 0) {
                float scale = 1 + SWELL * swell;
                float textWidth = paint.measureText(token.text.trim());

                if (sung >= 0) {
                    // Lit up to how far the word is sung, with a glint that runs over it.
                    int lit = ((int) (ALPHA_SUNG * lineAlpha) << 24) | 0xFFFFFF;
                    int unlit = ((int) (ALPHA_TO_COME * lineAlpha) << 24) | 0xFFFFFF;
                    float edge = sung * (1 + SHIMMER_EDGE);
                    float glint = (now % SHIMMER_GLINT_TIME_MS) / (float) SHIMMER_GLINT_TIME_MS * edge;
                    float from = Math.max(0, edge - SHIMMER_EDGE);
                    paint.setShader(new LinearGradient(x, 0, x + textWidth, 0,
                            new int[]{lit, lit, unlit},
                            new float[]{0, Math.min(from, 1), Math.min(Math.max(edge, from + 0.01f), 1)},
                            Shader.TileMode.CLAMP));
                    paint.setShadowLayer(paint.getTextSize() * SHIMMER_GLOW * swell
                                    * (0.75f + 0.25f * (float) Math.sin(2 * Math.PI * glint / Math.max(edge, 0.01f))),
                            0, 0, ((int) (0xB0 * swell * lineAlpha) << 24) | 0xFFFFFF);
                } else {
                    // The glow fades as the word settles.
                    paint.setShadowLayer(paint.getTextSize() * SHIMMER_GLOW * swell, 0, 0,
                            ((int) (0x80 * swell * lineAlpha) << 24) | 0xFFFFFF);
                }

                canvas.save();
                canvas.scale(scale, scale, x + textWidth / 2, baseline);
                canvas.drawText(token.text, x, baseline, paint);
                canvas.restore();

                paint.setShader(null);
                paint.clearShadowLayer();
            } else {
                canvas.drawText(token.text, x, baseline, paint);
            }
        }

        if (animated && isShown()) {
            postInvalidateOnAnimation();
        }
    }
}
