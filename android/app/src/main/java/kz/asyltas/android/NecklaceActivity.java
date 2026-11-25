package kz.asyltas.android;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.List;
import kz.asyltas.model.NecklaceItem;

public class NecklaceActivity extends BaseActivity {
  private NecklaceAdapter adapter;
  private int min = 0, max = 100;
  private boolean descending = true;
  private AlertDialog filterDialog;
  private EditText minInput, maxInput;
  private CheckBox sortInput;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    prepare(R.layout.activity_necklace, "Менің алқам");
    if (state != null) {
      min = state.getInt("min");
      max = state.getInt("max", 100);
      descending = state.getBoolean("descending", true);
    }
    ListView list = findViewById(R.id.necklace_list);
    adapter =
        new NecklaceAdapter(
            this,
            new NecklaceAdapter.Actions() {
              public void adjust(NecklaceItem item, int delta) {
                changeQuantity(item, delta);
              }

              public void more(NecklaceItem item) {
                itemActions(item);
              }
            });
    list.setAdapter(adapter);
    list.setEmptyView(findViewById(R.id.empty_message_state));
    list.setOnItemClickListener(
        (parent, row, position, id) -> {
          NecklaceItem item = adapter.getItem(position);
          quantity(item.getStone(), item);
        });
    registerForContextMenu(list);
    findViewById(R.id.filter_button).setOnClickListener(v -> showFilter(null));
    findViewById(R.id.checkout_button)
        .setOnClickListener(
            v -> {
              if (requireAccount()) hub("checkout");
            });
    findViewById(R.id.add_stones).setOnClickListener(v -> open(MainActivity.class, -1));
    findViewById(R.id.save_design).setOnClickListener(v -> saveCollection());
    findViewById(R.id.empty_action)
        .setOnClickListener(
            v -> {
              if (necklace.count() == 0) open(MainActivity.class, -1);
              else {
                min = 0;
                max = 100;
                refresh();
              }
            });
    if (state != null && state.getBoolean("filter_open")) showFilter(state);
    if (getIntent().getBooleanExtra("reset_filters", false)) {
      min = 0;
      max = 100;
      descending = true;
      getIntent().removeExtra("reset_filters");
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (intent.getBooleanExtra("reset_filters", false)) {
      min = 0;
      max = 100;
      descending = true;
      intent.removeExtra("reset_filters");
    }
    refresh();
  }

  private void showFilter(Bundle draft) {
    View form = getLayoutInflater().inflate(R.layout.dialog_filter, null);
    minInput = form.findViewById(R.id.min_input);
    maxInput = form.findViewById(R.id.max_input);
    sortInput = form.findViewById(R.id.descending_checkbox);
    minInput.setText(draft == null ? String.valueOf(min) : draft.getString("draft_min"));
    maxInput.setText(draft == null ? String.valueOf(max) : draft.getString("draft_max"));
    sortInput.setChecked(draft == null ? descending : draft.getBoolean("draft_sort"));
    filterDialog =
        new AlertDialog.Builder(this)
            .setTitle("Сүзгі және сұрыптау")
            .setView(form)
            .setNegativeButton("Бас тарту", null)
            .create();
    form.findViewById(R.id.search_button)
        .setOnClickListener(
            v -> {
              try {
                int first = Integer.parseInt(minInput.getText().toString().trim());
                int last = Integer.parseInt(maxInput.getText().toString().trim());
                necklace.find(first, last, sortInput.isChecked());
                min = first;
                max = last;
                descending = sortInput.isChecked();
                filterDialog.dismiss();
                refresh();
              } catch (IllegalArgumentException e) {
                TextView error = form.findViewById(R.id.filter_error);
                error.setText("0–100 аралығын енгізіңіз. Төменгі шек жоғарғы шектен аспасын.");
                error.setVisibility(View.VISIBLE);
              }
            });
    form.findViewById(R.id.reset_button)
        .setOnClickListener(
            v -> {
              min = 0;
              max = 100;
              descending = true;
              filterDialog.dismiss();
              refresh();
            });
    filterDialog.show();
  }

  private void itemActions(NecklaceItem item) {
    new AlertDialog.Builder(this)
        .setTitle(item.getStone().getName())
        .setItems(
            new String[] {"Санын өзгерту", "Тас туралы", "Алып тастау"},
            (d, which) -> {
              if (which == 0) quantity(item.getStone(), item);
              else if (which == 1) open(StoneActivity.class, item.getStone().getId());
              else remove(item);
            })
        .show();
  }

  @Override
  protected void refresh() {
    if (adapter == null) return;
    ((TextView) findViewById(R.id.necklace_summary)).setText(styledSummary(summary()));
    ((NecklaceView) findViewById(R.id.necklace_view)).setNecklace(necklace);
    findViewById(R.id.necklace_view)
        .setVisibility(
            getSharedPreferences("appearance", 0).getBoolean("preview", true)
                    && getResources().getConfiguration().orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ? View.VISIBLE
                : View.GONE);
    List<NecklaceItem> found = necklace.find(min, max, descending);
    int count = 0;
    for (NecklaceItem item : found) count += item.getQuantity();
    ((TextView) findViewById(R.id.result_info))
        .setText("Табылды: " + found.size() + " түр, " + count + " тас · " + min + "–" + max + "%");
    findViewById(R.id.filter_button).setContentDescription("Сүзгі: " + min + "–" + max + " пайыз");
    ((TextView) findViewById(R.id.empty_message))
        .setText(
            necklace.count() == 0
                ? "Алқа бос.\nКаталогтан тас қосыңыз."
                : "Бұл аралыққа сәйкес тас жоқ.");
    ((TextView) findViewById(R.id.empty_action))
        .setText(necklace.count() == 0 ? "Каталогқа өту" : "Сүзгіні тазарту");
    adapter.clear();
    adapter.addAll(found);
    adapter.notifyDataSetChanged();
  }

  @Override
  public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo info) {
    super.onCreateContextMenu(menu, view, info);
    AdapterView.AdapterContextMenuInfo row = (AdapterView.AdapterContextMenuInfo) info;
    menu.setHeaderTitle(adapter.getItem(row.position).getStone().getName());
    getMenuInflater().inflate(R.menu.menu_context, menu);
  }

  @Override
  public boolean onContextItemSelected(MenuItem menu) {
    AdapterView.AdapterContextMenuInfo row =
        (AdapterView.AdapterContextMenuInfo) menu.getMenuInfo();
    if (row == null) return super.onContextItemSelected(menu);
    NecklaceItem item = adapter.getItem(row.position);
    if (menu.getItemId() == R.id.menu_edit) quantity(item.getStone(), item);
    else if (menu.getItemId() == R.id.menu_remove) remove(item);
    else return super.onContextItemSelected(menu);
    return true;
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putInt("min", min);
    state.putInt("max", max);
    state.putBoolean("descending", descending);
    if (filterDialog != null && filterDialog.isShowing()) {
      state.putBoolean("filter_open", true);
      state.putString("draft_min", minInput.getText().toString());
      state.putString("draft_max", maxInput.getText().toString());
      state.putBoolean("draft_sort", sortInput.isChecked());
    }
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onDestroy() {
    if (filterDialog != null) filterDialog.dismiss();
    super.onDestroy();
  }
}
