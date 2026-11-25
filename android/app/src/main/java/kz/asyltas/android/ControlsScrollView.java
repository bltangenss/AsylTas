package kz.asyltas.android;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** Шағын экранда немесе ірі қаріпте тізімге орын қалдырады. */
public class ControlsScrollView extends ScrollView {
  public ControlsScrollView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int maximum = (int) (getResources().getDisplayMetrics().heightPixels * .40f);
    int available = MeasureSpec.getSize(heightMeasureSpec);
    if (available > 0) maximum = Math.min(maximum, available);
    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST));
  }
}
