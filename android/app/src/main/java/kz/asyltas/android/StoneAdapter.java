package kz.asyltas.android;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.util.ArrayList;
import kz.asyltas.model.Format;
import kz.asyltas.model.Stone;

public class StoneAdapter extends ArrayAdapter<Stone> {
  public interface Actions {
    void add(Stone stone);

    void details(Stone stone);

    void favorite(Stone stone);
    void compare(Stone stone);
  }

  private final Actions actions;
  private java.util.Set<Integer> favorites = new java.util.HashSet<>();
  private java.util.Set<Integer> comparison = new java.util.HashSet<>();
  public void setComparison(java.util.Set<Integer> ids) { comparison = ids; }

  public void setFavorites(java.util.Set<Integer> ids) {
    favorites = ids;
  }

  public StoneAdapter(Context context, Actions actions) {
    super(context, 0, new ArrayList<Stone>());
    this.actions = actions;
  }

  @Override
  public View getView(int position, View convertView, ViewGroup parent) {
    View row = convertView;
    if (row == null) {
      row = LayoutInflater.from(getContext()).inflate(R.layout.row_stone, parent, false);
    }

    Stone stone = getItem(position);
    android.widget.TextView heart = row.findViewById(R.id.stone_favorite);
    heart.setText(favorites.contains(stone.getId()) ? "♥" : "♡");
    heart.setContentDescription(stone.getName() + ": таңдаулы");
    heart.setOnClickListener(v -> actions.favorite(stone));
    row.findViewById(R.id.stone_add).setOnClickListener(v -> actions.add(stone));
    row.findViewById(R.id.stone_info_action).setOnClickListener(v -> actions.details(stone));
    android.widget.CheckBox compare = row.findViewById(R.id.stone_compare);
    compare.setOnCheckedChangeListener(null);
    compare.setChecked(comparison.contains(stone.getId()));
    compare.setContentDescription(stone.getName() + ": салыстыру");
    compare.setOnClickListener(v -> actions.compare(stone));
    ((GemView) row.findViewById(R.id.stone_gem)).setColor(stone.getColor());
    TextView name = row.findViewById(R.id.stone_name);
    TextView category = row.findViewById(R.id.stone_category);
    TextView details = row.findViewById(R.id.stone_details);
    TextView price = row.findViewById(R.id.stone_price);

    name.setText(stone.getName());
    category.setText(stone.getCategory());
    details.setText(
        Format.weight(stone.getCarats()) + " карат · мөлдірлік " + stone.getTransparency() + "%");
    price.setText(Format.money(stone.getCost()));
    return row;
  }
}
