package kz.asyltas.android;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import kz.asyltas.model.Catalog;
import kz.asyltas.model.Format;
import kz.asyltas.model.Stone;

public class StoneActivity extends BaseActivity {
  private Stone stone;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    prepare(R.layout.activity_stone, "Тас туралы");
    findViewById(R.id.detail_favorite)
        .setOnClickListener(
            v -> {
              if (stone != null) favorite(stone.getId());
            });
    findViewById(R.id.detail_reviews)
        .setOnClickListener(
            v -> {
              if (stone != null) hub("reviews:" + stone.getId());
            });
    findViewById(R.id.detail_add)
        .setOnClickListener(
            v -> {
              if (stone != null) quantity(stone, null);
            });
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    refresh();
  }

  @Override
  protected void refresh() {
    try {
      stone = Catalog.get(getIntent().getIntExtra("stone_id", -1));
    } catch (IllegalArgumentException e) {
      message("Тас табылмады.");
      finish();
      return;
    }
    ((TextView) findViewById(R.id.detail_favorite))
        .setText(
            store.favorites().contains(stone.getId()) ? "♥ Таңдаулыдан алу" : "♡ Таңдаулыға қосу");
    ((GemView) findViewById(R.id.detail_gem)).setColor(stone.getColor());
    ((TextView) findViewById(R.id.detail_name)).setText(stone.getName());
    ((TextView) findViewById(R.id.detail_category)).setText(stone.getCategory());
    ((TextView) findViewById(R.id.detail_price)).setText(Format.money(stone.getCost()));
    ((TextView) findViewById(R.id.detail_properties))
        .setText(
            "Салмағы: "
                + Format.weight(stone.getCarats())
                + " карат\nМөлдірлігі: "
                + stone.getTransparency()
                + "%\nБір карат: "
                + Format.money(stone.getPricePerCarat()));
  }
}
