package kz.asyltas.android;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import java.util.HashMap;
import java.util.Map;
import kz.asyltas.model.Catalog;
import kz.asyltas.model.Stone;

/** Actual licensed gemstone photographs. Attribution is bundled in assets/PHOTO_CREDITS.txt. */
public class GemView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private int color = 0xFF26896D;
  private static final Map<Integer, Bitmap> PHOTOS = new HashMap<>();
  private static final int[] RES = {
    0,
    R.drawable.stone_1,
    R.drawable.stone_2,
    R.drawable.stone_3,
    R.drawable.stone_4,
    R.drawable.stone_5,
    R.drawable.stone_6,
    R.drawable.stone_7,
    R.drawable.stone_8,
    R.drawable.stone_9,
    R.drawable.stone_10,
    R.drawable.stone_11,
    R.drawable.stone_12
  };

  public GemView(Context c, AttributeSet a) {
    super(c, a);
    preload(c);
  }

  static synchronized void preload(Context c) {
    if (!PHOTOS.isEmpty()) return;
    for (Stone stone : Catalog.all()) {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inScaled = false;
      o.inJustDecodeBounds = true;
      BitmapFactory.decodeResource(c.getResources(), RES[stone.getId()], o);
      o.inSampleSize = Math.max(o.outWidth, o.outHeight) > 800 ? 2 : 1;
      o.inJustDecodeBounds = false;
      PHOTOS.put(
          Color.parseColor(stone.getColor()),
          BitmapFactory.decodeResource(c.getResources(), RES[stone.getId()], o));
    }
  }

  public void setColor(String value) {
    color = Color.parseColor(value);
    invalidate();
  }

  private static void photo(Canvas canvas, Paint paint, Bitmap bitmap, RectF box) {
    if (bitmap == null) return;
    float scale = Math.min(box.width() / bitmap.getWidth(), box.height() / bitmap.getHeight());
    float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
    canvas.drawBitmap(
        bitmap,
        null,
        new RectF(
            box.centerX() - w / 2,
            box.centerY() - h / 2,
            box.centerX() + w / 2,
            box.centerY() + h / 2),
        paint);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float radius = Math.min(getWidth(), getHeight()) * .12f;
    Path path = new Path();
    path.addRoundRect(new RectF(0, 0, getWidth(), getHeight()), radius, radius, Path.Direction.CW);
    canvas.save();
    canvas.clipPath(path);
    canvas.drawColor(Color.WHITE);
    photo(canvas, paint, PHOTOS.get(color), new RectF(0, 0, getWidth(), getHeight()));
    canvas.restore();
  }

  static void drawGem(Canvas canvas, Paint paint, float x, float y, float radius, int color) {
    paint.setShader(null);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xFFBA9C68);
    canvas.drawCircle(x, y, radius + 1, paint);
    Path circle = new Path();
    circle.addCircle(x, y, radius, Path.Direction.CW);
    canvas.save();
    canvas.clipPath(circle);
    canvas.drawColor(Color.WHITE);
    photo(
        canvas,
        paint,
        PHOTOS.get(color),
        new RectF(x - radius, y - radius, x + radius, y + radius));
    canvas.restore();
  }
}
