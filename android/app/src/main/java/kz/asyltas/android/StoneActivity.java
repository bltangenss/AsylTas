package kz.asyltas.android;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;
import android.text.InputFilter;
import kz.asyltas.model.Catalog;
import kz.asyltas.model.Format;
import kz.asyltas.model.Stone;

public class StoneActivity extends BaseActivity {
  private Stone stone;
  private EditText note;
  private int loadedStone = -1, restoredStone = -1;
  private long loadedOwner = -1, restoredOwner = -1;
  private String restoredNote;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    prepare(R.layout.activity_stone, "Тас туралы");
    note = findViewById(R.id.detail_note);
    note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
    if (state != null) { restoredStone = state.getInt("draft_stone", -1); restoredOwner = state.getLong("draft_owner", -1); restoredNote = state.getString("draft_note"); }
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
    findViewById(R.id.detail_compare).setOnClickListener(v -> { if (stone != null) compare(stone.getId()); });
    findViewById(R.id.detail_compare_open).setOnClickListener(v -> library("compare"));
    findViewById(R.id.detail_note_save).setOnClickListener(v -> {
      if (stone == null || !requireAccount()) return;
      try { catalogData.saveNote(stone.getId(), note.getText().toString()); Toast.makeText(this,"Жеке жазба сақталды",Toast.LENGTH_SHORT).show(); }
      catch (IllegalArgumentException error) { message(error.getMessage()); }
    });
    findViewById(R.id.detail_share).setOnClickListener(v -> {
      if (stone == null) return;
      String value = "Асыл тас · " + stone.getName() + "\n" + Format.weight(stone.getCarats()) + " карат · мөлдірлігі " + stone.getTransparency() + "%\nБір тас: " + Format.money(stone.getCost()) + "\nБаға мен салмақ — оқу деректері.";
      startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,value),"Тас туралы бөлісу"));
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
    ((TextView) findViewById(R.id.detail_compare)).setText(catalogData.comparison().contains(stone.getId()) ? "Салыстырудан алу" : "Салыстыруға қосу");
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
    CatalogDatabase.Detail detail = catalogData.detail(stone.getId());
    ((TextView) findViewById(R.id.detail_description)).setText(detail.description);
    ((TextView) findViewById(R.id.detail_care)).setText(detail.care);
    long owner = Session.user(this);
    if (loadedStone != stone.getId() || loadedOwner != owner) {
      catalogData.viewed(stone.getId());
      note.setText(restoredNote != null && restoredStone == stone.getId() && restoredOwner == owner ? restoredNote : catalogData.note(stone.getId()));
      restoredNote = null; loadedStone = stone.getId(); loadedOwner = owner;
    }
    note.setEnabled(owner > 0);
    note.setHint(owner > 0 ? "Мысалы: осы тасты сыйлық алқасына қосқым келеді" : "Жеке жазба үшін аккаунтқа кіріңіз");
    ((TextView) findViewById(R.id.detail_note_save)).setText(owner > 0 ? "Жазбаны сақтау" : "Жазба үшін кіру");
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    if (note != null && stone != null && Session.user(this) > 0) { state.putInt("draft_stone",stone.getId()); state.putLong("draft_owner",Session.user(this)); state.putString("draft_note",note.getText().toString()); }
    super.onSaveInstanceState(state);
  }
}
