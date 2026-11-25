package kz.asyltas.android;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import java.util.*;
import kz.asyltas.model.*;

public class MainActivity extends BaseActivity {
  private StoneAdapter adapter;
  private Spinner categorySpinner;
  private EditText search;
  private CheckBox favorites;
  private int categoryPosition, sort;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    prepare(R.layout.activity_main, "Тастар каталогы");
    search = findViewById(R.id.catalog_search);
    favorites = findViewById(R.id.favorite_only);
    favorites.setChecked(
        state != null
            ? state.getBoolean("favorites")
            : getIntent().getBooleanExtra("favorites", false));
    favorites.setOnCheckedChangeListener((b, on) -> refresh());
    if (state != null) {
      categoryPosition = state.getInt("category");
      sort = state.getInt("sort");
      search.setText(state.getString("search", ""));
    }
    ListView list = findViewById(R.id.stone_list);
    adapter =
        new StoneAdapter(
            this,
            new StoneAdapter.Actions() {
              public void favorite(Stone stone) {
                MainActivity.this.favorite(stone.getId());
              }

              public void add(Stone stone) {
                quantity(stone, null);
              }

              public void details(Stone stone) {
                open(StoneActivity.class, stone.getId());
              }
            });
    list.setAdapter(adapter);
    list.setEmptyView(findViewById(R.id.catalog_empty_state));
    list.setOnItemClickListener(
        (parent, row, position, id) ->
            open(StoneActivity.class, adapter.getItem(position).getId()));
    list.setOnItemLongClickListener(
        (parent, row, position, id) -> {
          Stone stone = adapter.getItem(position);
          new AlertDialog.Builder(this)
              .setTitle(stone.getName())
              .setItems(
                  new String[] {"Алқаға қосу", "Тас туралы"},
                  (d, which) -> {
                    if (which == 0) quantity(stone, null);
                    else open(StoneActivity.class, stone.getId());
                  })
              .show();
          return true;
        });
    categorySpinner = findViewById(R.id.category_spinner);
    ArrayAdapter<CharSequence> categories =
        ArrayAdapter.createFromResource(this, R.array.stone_categories, R.layout.spinner_item);
    categories.setDropDownViewResource(R.layout.spinner_dropdown);
    categorySpinner.setAdapter(categories);
    categorySpinner.setSelection(categoryPosition);
    categorySpinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
            categoryPosition = position;
            refresh();
          }

          public void onNothingSelected(AdapterView<?> p) {}
        });
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            refresh();
          }

          public void afterTextChanged(Editable e) {}
        });
    findViewById(R.id.catalog_sort)
        .setOnClickListener(
            v ->
                new AlertDialog.Builder(this)
                    .setTitle("Бір карат бағасы бойынша")
                    .setSingleChoiceItems(
                        new String[] {"Каталог реті", "Арзанынан бастап", "Қымбатынан бастап"},
                        sort,
                        (d, which) -> {
                          sort = which;
                          refresh();
                          d.dismiss();
                        })
                    .setNegativeButton("Бас тарту", null)
                    .show());
    findViewById(R.id.catalog_reset)
        .setOnClickListener(
            v -> {
              search.setText("");
              categorySpinner.setSelection(0);
              favorites.setChecked(false);
            });
  }

  @Override
  protected void refresh() {
    if (adapter == null) return;
    List<Stone> visible = new ArrayList<>();
    String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
    java.util.Set<Integer> liked = store.favorites();
    adapter.setFavorites(liked);
    for (Stone stone : Catalog.all()) {
      if (favorites.isChecked() && !liked.contains(stone.getId())) continue;
      if ((categoryPosition == 0
              || categoryPosition == 1 && stone instanceof PreciousStone
              || categoryPosition == 2 && stone instanceof SemiPreciousStone)
          && stone.getName().toLowerCase(Locale.ROOT).contains(query)) visible.add(stone);
    }
    if (sort != 0) {
      Comparator<Stone> order = Comparator.comparing(Stone::getPricePerCarat);
      if (sort == 2) order = order.reversed();
      visible.sort(order.thenComparingInt(Stone::getId));
    }
    adapter.clear();
    adapter.addAll(visible);
    adapter.notifyDataSetChanged();
    ((TextView) findViewById(R.id.catalog_summary))
        .setText(visible.size() + " тас түрі · Алқада " + necklace.count() + " тас");
    ((TextView) findViewById(R.id.catalog_sort))
        .setText(sort == 0 ? "Баға ↕" : sort == 1 ? "Баға ↑" : "Баға ↓");
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putBoolean("favorites", favorites.isChecked());
    state.putInt("category", categoryPosition);
    state.putInt("sort", sort);
    state.putString("search", search.getText().toString());
    super.onSaveInstanceState(state);
  }
}
