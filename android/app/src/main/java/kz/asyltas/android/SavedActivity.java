package kz.asyltas.android;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import kz.asyltas.model.Format;

public class SavedActivity extends BaseActivity {
  private ArrayAdapter<NecklaceDatabase.Saved> adapter;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    prepare(R.layout.activity_saved, "Менің жинақтарым");
    ListView list = findViewById(R.id.saved_list);
    adapter =
        new ArrayAdapter<NecklaceDatabase.Saved>(this, 0, new ArrayList<>()) {
          @Override
          public View getView(int position, View view, ViewGroup parent) {
            if (view == null) view = getLayoutInflater().inflate(R.layout.row_saved, parent, false);
            NecklaceDatabase.Saved saved = getItem(position);
            ((TextView) view.findViewById(R.id.saved_name)).setText(saved.name);
            ((TextView) view.findViewById(R.id.saved_metrics))
                .setText(
                    saved.necklace.count()
                        + " тас · "
                        + Format.weight(saved.necklace.totalWeight())
                        + " карат");
            ((TextView) view.findViewById(R.id.saved_cost))
                .setText(Format.money(saved.necklace.totalCost()));
            ((NecklaceView) view.findViewById(R.id.saved_preview)).setNecklace(saved.necklace);
            view.findViewById(R.id.saved_open).setOnClickListener(v -> loadCollection(saved));
            view.findViewById(R.id.saved_more).setOnClickListener(v -> actions(saved));
            return view;
          }
        };
    list.setAdapter(adapter);
    list.setEmptyView(findViewById(R.id.saved_empty_state));
    findViewById(R.id.save_current).setOnClickListener(v -> saveCollection());
    findViewById(R.id.saved_empty_action).setOnClickListener(v -> open(NecklaceActivity.class, -1));
  }

  private void actions(NecklaceDatabase.Saved saved) {
    new AlertDialog.Builder(this)
        .setTitle(saved.name)
        .setItems(
            new String[] {"Атауын өзгерту", "Жинақты жою"},
            (d, which) -> {
              if (which == 0) collectionName(saved);
              else
                new AlertDialog.Builder(this)
                    .setTitle("Жинақты жою")
                    .setMessage(
                        "«"
                            + saved.name
                            + "» сақталған жинақтардан жойылады. Ағымдағы алқа өзгермейді.")
                    .setNegativeButton("Бас тарту", null)
                    .setPositiveButton(
                        "Жою",
                        (dialog, w) -> {
                          try {
                            database.deleteCollection(saved.id);
                            refresh();
                          } catch (RuntimeException e) {
                            message("Жинақ жойылмады. Қайта көріңіз.");
                          }
                        })
                    .show();
            })
        .show();
  }

  @Override
  protected void refresh() {
    if (adapter == null) return;
    try {
      adapter.clear();
      adapter.addAll(database.collections());
      adapter.notifyDataSetChanged();
      ((TextView) findViewById(R.id.saved_count)).setText(adapter.getCount() + " сақталған жинақ");
    } catch (RuntimeException e) {
      message("Жинақтар оқылмады. Деректер өзгертілген жоқ.");
    }
  }
}
