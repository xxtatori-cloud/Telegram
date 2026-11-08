package org.telegram.messenger.monogram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;

/**
 * Custom MONOGRAM Owner/Administrator verification badge.
 * Renders a distinct gold-accented badge with the 'M' insignia,
 * distinguishing MONOGRAM owner verification from standard Telegram badges.
 */
public class MonogramBadgeDrawable extends Drawable {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;

    public MonogramBadgeDrawable(Context context) {
        size = AndroidUtilities.dp(16);
        bgPaint.setColor(0xFFFFB300); // MONOGRAM gold accent
        bgPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextSize(AndroidUtilities.dp(10));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        float radius = Math.min(bounds.width(), bounds.height()) / 2.0f;

        canvas.drawCircle(cx, cy, radius, bgPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float y = cy - (fm.ascent + fm.descent) / 2.0f;
        canvas.drawText("M", cx, y, textPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        bgPaint.setAlpha(alpha);
        textPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        bgPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
