package kz.asyltas.android;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.util.ArrayList;
import kz.asyltas.model.Format;
import kz.asyltas.model.NecklaceItem;
import kz.asyltas.model.Stone;

public class NecklaceAdapter extends ArrayAdapter<NecklaceItem> {
  public interface Actions {
    void adjust(NecklaceItem item, int delta);

    void more(NecklaceItem item);
  }

  private final Actions actions;

  public NecklaceAdapter(Context context, Actions actions) {
    super(context, 0, new ArrayList<NecklaceItem>());
    this.actions = actions;
  }

  @Override
  public View getView(int position, View convertView, ViewGroup parent) {
    View row = convertView;
    if (row == null) {
      row = LayoutInflater.from(getContext()).inflate(R.layout.row_necklace, parent, false);
    }

    NecklaceItem item = getItem(position);
    Stone stone = item.getStone();
    ((GemView) row.findViewById(R.id.item_gem)).setColor(stone.getColor());
    ((TextView) row.findViewById(R.id.item_quantity)).setText(String.valueOf(item.getQuantity()));
    row.findViewById(R.id.item_minus).setEnabled(item.getQuantity() > 1);
    row.findViewById(R.id.item_minus).setAlpha(item.getQuantity() > 1 ? 1f : .35f);
    row.findViewById(R.id.item_plus).setEnabled(item.getQuantity() < 99);
    row.findViewById(R.id.item_plus).setAlpha(item.getQuantity() < 99 ? 1f : .35f);
    row.findViewById(R.id.item_minus).setOnClickListener(v -> actions.adjust(item, -1));
    row.findViewById(R.id.item_plus).setOnClickListener(v -> actions.adjust(item, 1));
    row.findViewById(R.id.item_more).setOnClickListener(v -> actions.more(item));
    TextView name = row.findViewById(R.id.item_name);
    TextView details = row.findViewById(R.id.item_details);
    TextView cost = row.findViewById(R.id.item_cost);

    name.setText(stone.getName());
    details.setText(
        Format.weight(item.getWeight()) + " карат · " + stone.getTransparency() + "% мөлдірлік");
    cost.setText(Format.money(item.getCost()));
    return row;
  }
}
