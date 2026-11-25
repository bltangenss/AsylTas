package kz.asyltas.android;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.TextView;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kz.asyltas.model.Catalog;
import kz.asyltas.model.Format;
import kz.asyltas.model.Stone;

public class GuideActivity extends BaseActivity {
  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    prepare(R.layout.activity_guide, "Нұсқаулық");
    TextView guide = findViewById(R.id.guide_text);
    SpannableString text = new SpannableString(getString(R.string.guide_text));
    Matcher headings = Pattern.compile("(?m)^\\d\\. [^\\n]+").matcher(text);
    while (headings.find()) {
      text.setSpan(
          new StyleSpan(Typeface.BOLD),
          headings.start(),
          headings.end(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      text.setSpan(
          new ForegroundColorSpan(getColor(R.color.green)),
          headings.start(),
          headings.end(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    guide.setText(text);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    refresh();
  }

  @Override
  protected void refresh() {
    TextView info = findViewById(R.id.stone_info);
    int id = getIntent().getIntExtra("stone_id", -1);
    info.setVisibility(id == -1 ? View.GONE : View.VISIBLE);
    if (id != -1) {
      try {
        Stone stone = Catalog.get(id);
        info.setText(
            stone.getName()
                + "\n"
                + stone.getCategory()
                + "\n"
                + Format.weight(stone.getCarats())
                + " карат · "
                + stone.getTransparency()
                + "% мөлдірлік\nБір карат: "
                + Format.money(stone.getPricePerCarat())
                + "\nБір тас: "
                + Format.money(stone.getCost()));
      } catch (IllegalArgumentException error) {
        info.setVisibility(View.GONE);
      }
    }
  }
}
