package kz.asyltas.android;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.View;
import java.util.HashMap;
import java.util.Map;
import kz.asyltas.model.Catalog;
import kz.asyltas.model.Stone;

/** Actual licensed gemstone photographs. Attribution is bundled in assets/PHOTO_CREDITS.txt. */
public class GemView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private int color = 0xFF26896D;
  private static final Map<Integer, Integer> RESOURCES = new HashMap<>();
  private static Context app;
  private static final LruCache<Integer, Bitmap> PHOTOS = new LruCache<Integer, Bitmap>(12 * 1024 * 1024) {
    @Override protected int sizeOf(Integer key, Bitmap value) { return value.getAllocationByteCount(); }
  };

  public GemView(Context c, AttributeSet a) {
    super(c, a);
    preload(c);
  }

  static synchronized void preload(Context c) {
    if (!RESOURCES.isEmpty()) return;
    app = c.getApplicationContext();
    for (Stone stone : Catalog.all()) {
      int resource = c.getResources().getIdentifier("stone_" + stone.getId(), "drawable", c.getPackageName());
      if (resource != 0) RESOURCES.put(Color.parseColor(stone.getColor()), resource);
    }
  }

  private static synchronized Bitmap photoFor(int color) {
    Bitmap bitmap = PHOTOS.get(color);
    if (bitmap != null) return bitmap;
    Integer resource = RESOURCES.get(color);
    if (resource == null || app == null) return null;
    BitmapFactory.Options options = new BitmapFactory.Options(); options.inScaled = false; options.inJustDecodeBounds = true;
    BitmapFactory.decodeResource(app.getResources(), resource, options);
    int largest = Math.max(options.outWidth, options.outHeight); options.inSampleSize = 1;
    while (largest / options.inSampleSize > 640) options.inSampleSize *= 2;
    options.inJustDecodeBounds = false;
    bitmap = BitmapFactory.decodeResource(app.getResources(), resource, options);
    if (bitmap != null) PHOTOS.put(color, bitmap);
    return bitmap;
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
    photo(canvas, paint, photoFor(color), new RectF(0, 0, getWidth(), getHeight()));
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
        photoFor(color),
        new RectF(x - radius, y - radius, x + radius, y + radius));
    canvas.restore();
  }
}
