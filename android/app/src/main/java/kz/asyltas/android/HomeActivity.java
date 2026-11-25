package kz.asyltas.android;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import java.util.List;
import kz.asyltas.model.*;

public class HomeActivity extends BaseActivity {
  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    prepare(R.layout.activity_home, "Асыл тас");
    findViewById(R.id.home_open).setOnClickListener(v -> open(NecklaceActivity.class, -1));
    findViewById(R.id.home_catalog).setOnClickListener(v -> open(MainActivity.class, -1));
    findViewById(R.id.home_save).setOnClickListener(v -> saveCollection());
    findViewById(R.id.home_saved).setOnClickListener(v -> open(SavedActivity.class, -1));
    findViewById(R.id.home_orders).setOnClickListener(v -> hub("orders"));
    findViewById(R.id.home_store).setOnClickListener(v -> open(CampusActivity.class, -1));
    for (int[] item : new int[][] {{R.id.home_gem1, 1}, {R.id.home_gem2, 2}, {R.id.home_gem3, 3}}) {
      ((GemView) findViewById(item[0])).setColor(Catalog.get(item[1]).getColor());
      findViewById(item[0]).setOnClickListener(v -> open(StoneActivity.class, item[1]));
    }
  }

  @Override
  protected void refresh() {
    AppStore.Row user = store.user();
    ((TextView) findViewById(R.id.home_greeting))
        .setText(user == null ? "ЖЕКЕ КОЛЛЕКЦИЯ" : user.text("name") + ", қош келдіңіз");
    ((TextView) findViewById(R.id.home_total)).setText(Format.money(necklace.totalCost()));
    ((TextView) findViewById(R.id.home_metrics))
        .setText(necklace.count() + " тас · " + Format.weight(necklace.totalWeight()) + " карат");
    NecklaceView preview = findViewById(R.id.home_preview);
    preview.setNecklace(necklace);
    preview.setVisibility(
        getSharedPreferences("appearance", 0).getBoolean("preview", true)
            ? View.VISIBLE
            : View.GONE);
    try {
      List<NecklaceDatabase.Saved> list = database.collections();
      ((TextView) findViewById(R.id.home_saved_info))
          .setText(
              list.isEmpty()
                  ? "Ұнаған алқаңызды атаумен сақтаңыз."
                  : list.size() + " жинақ · " + list.get(0).name);
    } catch (RuntimeException e) {
      ((TextView) findViewById(R.id.home_saved_info))
          .setText("Жинақтар оқылмады. Қайта кіріп көріңіз.");
    }
  }
}
