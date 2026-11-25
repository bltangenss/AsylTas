package kz.asyltas.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import kz.asyltas.model.Necklace;
import kz.asyltas.model.NecklaceItem;

/** Canvas пен Paint арқылы салынатын алқаның шағын көрінісі. */
public class NecklaceView extends View {
  private final List<Integer> colors = new ArrayList<>();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  public NecklaceView(Context context, AttributeSet attributes) {
    super(context, attributes);
    GemView.preload(context);
    setContentDescription("Алқаның көрінісі, алғашқы 24 тасқа дейін");
  }

  public void setNecklace(Necklace necklace) {
    colors.clear();
    for (NecklaceItem item : necklace.all()) {
      for (int i = 0; i < item.getQuantity() && colors.size() < 24; i++) {
        colors.add(Color.parseColor(item.getStone().getColor()));
      }
    }
    setContentDescription(
        "Алқаның көрінісі: "
            + necklace.count()
            + " тас. "
            + (necklace.count() > 24
                ? "Алғашқы 24 тас көрсетілген."
                : "Барлық таңдалған тас көрсетілген."));
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float width = getWidth(), height = getHeight();
    float density = getResources().getDisplayMetrics().density;
    float left = width * .16f, right = width * .84f;
    float centerY = height * .12f, radiusY = height * .64f;
    paint.setColor(0xFFBDA174);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.2f * density);
    canvas.drawArc(
        new RectF(left, centerY - radiusY, right, centerY + radiusY), 5, 170, false, paint);
    paint.setStyle(Paint.Style.FILL);
    int count = colors.size();
    for (int i = 0; i < count; i++) {
      double angle = Math.toRadians(20 + 140.0 * (i + .5) / count);
      float x = width * .5f + (float) Math.cos(angle) * width * .34f;
      float y = centerY + (float) Math.sin(angle) * radiusY;
      float radius = Math.min(height * .09f, width * .54f / (count + 4));
      GemView.drawGem(canvas, paint, x, y, radius, colors.get(i));
    }
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xFF65736B);
    paint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
    paint.setTextAlign(Paint.Align.CENTER);
    if (count == 0 && width / density > 150)
      canvas.drawText("Тас таңдаудан бастаңыз", width / 2, height - 8 * density, paint);
  }
}
