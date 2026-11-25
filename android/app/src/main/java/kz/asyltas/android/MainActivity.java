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
  private CatalogFilters limits = new CatalogFilters();

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
      limits = new CatalogFilters(state.getString("min_price", ""), state.getString("max_price", ""), state.getString("min_carats", ""), state.getString("max_carats", ""), state.getInt("min_transparency", 0), state.getInt("max_transparency", 100));
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

              public void compare(Stone stone) { MainActivity.this.compare(stone.getId()); }
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
                  new String[] {"Алқаға қосу", "Тас туралы", "Салыстыруға қосу / алу"},
                  (d, which) -> {
                    if (which == 0) quantity(stone, null);
                    else if (which == 1) open(StoneActivity.class, stone.getId());
                    else compare(stone.getId());
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
                    .setTitle("Каталогты реттеу")
                    .setSingleChoiceItems(
                        new String[] {"Каталог реті", "Бір тас: арзанынан", "Бір тас: қымбатынан", "Бір карат: арзанынан", "Салмағы: жеңілінен", "Салмағы: ауырынан", "Атауы бойынша"},
                        sort,
                        (d, which) -> {
                          sort = which;
                          refresh();
                          d.dismiss();
                        })
                    .setNegativeButton("Бас тарту", null)
                    .show());
    findViewById(R.id.catalog_reset).setOnClickListener(v -> resetFilters());
    findViewById(R.id.catalog_filter).setOnClickListener(v -> filtersDialog());
    findViewById(R.id.catalog_compare).setOnClickListener(v -> library("compare"));
    findViewById(R.id.catalog_recent).setOnClickListener(v -> library("recent"));
  }

  @Override
  protected void refresh() {
    if (adapter == null) return;
    List<Stone> visible = new ArrayList<>();
    String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
    java.util.Set<Integer> liked = store.favorites();
    adapter.setFavorites(liked);
    adapter.setComparison(new java.util.HashSet<>(catalogData.comparison()));
    for (Stone stone : catalogData.search(query, limits)) {
      if (favorites.isChecked() && !liked.contains(stone.getId())) continue;
      if ((categoryPosition == 0
              || categoryPosition == 1 && stone instanceof PreciousStone
              || categoryPosition == 2 && stone instanceof SemiPreciousStone)) visible.add(stone);
    }
    if (sort != 0) {
      Comparator<Stone> order = sort == 3 ? Comparator.comparing(Stone::getPricePerCarat)
          : sort == 4 || sort == 5 ? Comparator.comparing(Stone::getCarats)
          : sort == 6 ? Comparator.comparing(Stone::getName) : Comparator.comparing(Stone::getCost);
      if (sort == 2 || sort == 5) order = order.reversed();
      visible.sort(order.thenComparingInt(Stone::getId));
    }
    adapter.clear();
    adapter.addAll(visible);
    adapter.notifyDataSetChanged();
    ((TextView) findViewById(R.id.catalog_summary))
        .setText(visible.size() + " / " + Catalog.all().size() + " тас түрі · Алқада " + necklace.count() + " тас");
    ((TextView) findViewById(R.id.catalog_sort))
        .setText(sort == 0 ? "Реттеу ↕" : sort == 1 || sort == 3 ? "Баға ↑" : sort == 2 ? "Баға ↓" : sort == 4 ? "Карат ↑" : sort == 5 ? "Карат ↓" : "Атауы");
    ((TextView) findViewById(R.id.catalog_filter)).setText(limits.active() ? "Сүзгі · бар" : "Сүзгі");
    ((TextView) findViewById(R.id.catalog_compare)).setText("Салыстыру " + catalogData.comparison().size());
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putBoolean("favorites", favorites.isChecked());
    state.putInt("category", categoryPosition);
    state.putInt("sort", sort);
    state.putString("search", search.getText().toString());
    state.putString("min_price", limits.minPrice); state.putString("max_price", limits.maxPrice);
    state.putString("min_carats", limits.minCarats); state.putString("max_carats", limits.maxCarats);
    state.putInt("min_transparency", limits.minTransparency); state.putInt("max_transparency", limits.maxTransparency);
    super.onSaveInstanceState(state);
  }

  private void resetFilters() {
    limits = new CatalogFilters(); sort = 0;
    search.setText(""); categorySpinner.setSelection(0); favorites.setChecked(false); refresh();
  }

  private void filtersDialog() {
    ScrollView scroll = new ScrollView(this);
    LinearLayout fields = Ui.column(this); int pad = Ui.dp(this, 20); fields.setPadding(pad, pad, pad, pad); scroll.addView(fields);
    Ui.text(fields, "Баға бір тас үшін есептеледі. Бос шек — шектеусіз.", 13, false);
    int decimal = android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL;
    EditText minPrice = Ui.field(fields, "Төменгі баға, ₸", limits.minPrice, decimal);
    EditText maxPrice = Ui.field(fields, "Жоғарғы баға, ₸", limits.maxPrice, decimal);
    EditText minCarats = Ui.field(fields, "Төменгі салмақ, карат", limits.minCarats, decimal);
    EditText maxCarats = Ui.field(fields, "Жоғарғы салмақ, карат", limits.maxCarats, decimal);
    EditText minTransparency = Ui.field(fields, "Мөлдірлік: бастап, %", String.valueOf(limits.minTransparency), android.text.InputType.TYPE_CLASS_NUMBER);
    EditText maxTransparency = Ui.field(fields, "Мөлдірлік: дейін, %", String.valueOf(limits.maxTransparency), android.text.InputType.TYPE_CLASS_NUMBER);
    TextView error = Ui.text(fields, "", 13, false); error.setTextColor(0xFF9D352D);
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Бюджет және қасиеттер").setView(scroll).setNegativeButton("Бас тарту", null).setNeutralButton("Тазарту", (d,w) -> {limits = new CatalogFilters(); refresh();}).setPositiveButton("Қолдану", null).create();
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      try {
        limits = new CatalogFilters(Ui.value(minPrice), Ui.value(maxPrice), Ui.value(minCarats), Ui.value(maxCarats), Integer.parseInt(Ui.value(minTransparency)), Integer.parseInt(Ui.value(maxTransparency)));
        refresh(); ((ListView) findViewById(R.id.stone_list)).setSelection(0); dialog.dismiss();
      } catch (IllegalArgumentException invalid) { error.setText(invalid instanceof NumberFormatException ? "Мөлдірлікке 0–100 аралығындағы бүтін сан енгізіңіз." : invalid.getMessage()); error.requestFocus(); }
    }));
    dialog.show();
  }
}
