package kz.asyltas.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;
import kz.asyltas.model.Format;
import kz.asyltas.model.Necklace;
import kz.asyltas.model.NecklaceItem;
import kz.asyltas.model.Stone;

/** Үш экранға ортақ мәзір, ауысу және сақтау әрекеттері. */
public abstract class BaseActivity extends Activity implements View.OnClickListener {
  protected Necklace necklace = new Necklace();
  protected NecklaceDatabase database;
  protected AppStore store;
  private boolean storageReady;
  private String savedState = "";

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    database = new NecklaceDatabase(this);
    store = new AppStore(this);
  }

  protected void prepare(int layout, String title) {
    setContentView(layout);
    View root = findViewById(R.id.screen_root);
    root.setFocusableInTouchMode(true);
    if (android.os.Build.VERSION.SDK_INT >= 26) root.setDefaultFocusHighlightEnabled(false);
    root.requestFocus();
    root.setOnApplyWindowInsetsListener(
        new View.OnApplyWindowInsetsListener() {
          @Override
          public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
            view.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
          }
        });
    root.requestApplyInsets();
    Toolbar toolbar = findViewById(R.id.toolbar);
    toolbar.setTitle(title);
    setActionBar(toolbar);
    int selected =
        this instanceof HomeActivity
            ? R.id.nav_home
            : this instanceof MainActivity || this instanceof StoneActivity
                ? R.id.nav_catalog
                : this instanceof NecklaceActivity ? R.id.nav_necklace : R.id.nav_profile;
    View selectedView = findViewById(selected);
    if (selectedView != null) selectedView.setSelected(true);
    for (int id :
        new int[] {R.id.nav_home, R.id.nav_catalog, R.id.nav_necklace, R.id.nav_profile}) {
      View item = findViewById(id);
      if (item != null) item.setOnClickListener(this);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (!Session.active(this)) {
      startActivity(
          new Intent(this, LoginActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      return;
    }
    try {
      necklace = database.load();
      savedState = necklace.encode();
      storageReady = true;
    } catch (RuntimeException error) {
      storageReady = false;
      message("Сақталған алқа оқылмады. Деректер өзгертілген жоқ.");
    }
    refreshAll();
  }

  private void refreshAll() {
    Button button = findViewById(R.id.nav_necklace);
    if (button != null) button.setText("Алқа");
    refresh();
  }

  protected abstract void refresh();

  protected String summary() {
    return necklace.count()
        + " тас  ·  "
        + Format.weight(necklace.totalWeight())
        + " карат\n"
        + Format.money(necklace.totalCost());
  }

  /** Ақпарат өзгермейді; жалпы баға ғана қаріппен ерекшеленеді. */
  protected CharSequence styledSummary(String text) {
    SpannableString value = new SpannableString(text);
    int start = text.lastIndexOf('\n') + 1;
    value.setSpan(
        new AbsoluteSizeSpan(27, true), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    value.setSpan(
        new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    value.setSpan(
        new ForegroundColorSpan(0xFF342A25),
        start,
        text.length(),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return value;
  }

  @Override
  public void onClick(View view) {
    int id = view.getId();
    if (id == R.id.nav_home) open(HomeActivity.class, -1);
    else if (id == R.id.nav_saved) open(SavedActivity.class, -1);
    else if (id == R.id.nav_catalog) open(MainActivity.class, -1);
    else if (id == R.id.nav_necklace) open(NecklaceActivity.class, -1);
    else if (id == R.id.nav_profile) hub("profile");
  }

  protected void hub(String page) {
    Intent intent = new Intent(this, HubActivity.class).putExtra("page", page);
    if ("profile".equals(page)) intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    startActivity(intent);
  }

  protected boolean requireAccount() {
    if (store.user() != null) return true;
    new AlertDialog.Builder(this)
        .setTitle("Аккаунтқа кіріңіз")
        .setMessage("Таңдаулылар мен өтінімдер жеке профиліңізде сақталады.")
        .setNegativeButton("Кейін", null)
        .setPositiveButton(
            "Кіру",
            (d, w) -> {
              Session.clear(this);
              startActivity(
                  new Intent(this, LoginActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            })
        .show();
    return false;
  }

  protected void favorite(int stone) {
    if (!requireAccount()) return;
    try {
      boolean added = store.toggleFavorite(stone);
      Toast.makeText(this, added ? "Таңдаулыға қосылды" : "Таңдаулыдан алынды", Toast.LENGTH_SHORT)
          .show();
      refresh();
    } catch (RuntimeException e) {
      message(e.getMessage());
    }
  }

  protected void open(Class<? extends Activity> screen, int stoneId) {
    if (getClass() == screen && stoneId == -1 && !(this instanceof GuideActivity)) return;
    Intent intent = new Intent(this, screen);
    intent.putExtra("stone_id", stoneId);
    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    startActivity(intent);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main_menu, menu);
    menu.add(0, 9101, 3, "Біз осындамыз");
    menu.add(0, 9102, 4, "Фотосуреттер авторлары");
    menu.add(0, 9103, 5, "Профиль");
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == 9101) hub("store");
    else if (id == 9102) hub("credits");
    else if (id == 9103) hub("profile");
    else if (id == R.id.menu_demo) confirmDemo();
    else if (id == R.id.menu_clear) confirmClear();
    else if (id == R.id.menu_guide) open(GuideActivity.class, -1);
    else return super.onOptionsItemSelected(item);
    return true;
  }

  protected void confirmDemo() {
    if (!canChange()) return;
    new AlertDialog.Builder(this)
        .setTitle("Үлгі алқа")
        .setMessage("Алқаны екі лағыл, бір зүбәржат және үш аметистпен ауыстырамыз ба?")
        .setNegativeButton("Бас тарту", null)
        .setPositiveButton(
            "Растау",
            new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                necklace.demo();
                if (save()) openNecklaceReset();
              }
            })
        .show();
  }

  private void confirmClear() {
    if (!canChange()) return;
    new AlertDialog.Builder(this)
        .setTitle("Алқаны тазарту")
        .setMessage("Алқадағы барлық тасты алып тастаймыз ба?")
        .setNegativeButton("Бас тарту", null)
        .setPositiveButton(
            "Тазарту",
            new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                necklace.clear();
                save();
              }
            })
        .show();
  }

  protected void remove(final NecklaceItem item) {
    if (!canChange()) return;
    new AlertDialog.Builder(this)
        .setTitle("Тасты алып тастау")
        .setMessage(item.getStone().getName() + " алқадан алынады.")
        .setNegativeButton("Бас тарту", null)
        .setPositiveButton(
            "Алып тастау",
            new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                necklace.remove(item.getStone().getId());
                save();
              }
            })
        .show();
  }

  protected void quantity(final Stone stone, final NecklaceItem item) {
    if (!canChange()) return;
    View form = getLayoutInflater().inflate(R.layout.dialog_quantity, null);
    TextView description = form.findViewById(R.id.quantity_description);
    description.setText("Бір тас: " + Format.money(stone.getCost()) + "\nТас саны: 1–99");
    final EditText input = form.findViewById(R.id.quantity_input);
    final TextView errorText = form.findViewById(R.id.quantity_error);
    input.setText(item == null ? "1" : String.valueOf(item.getQuantity()));
    input.selectAll();
    final AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(stone.getName() + " · тас саны")
            .setView(form)
            .setNegativeButton("Бас тарту", null)
            .setPositiveButton(item == null ? "Қосу" : "Сақтау", null)
            .create();
    dialog.setOnShowListener(
        new DialogInterface.OnShowListener() {
          @Override
          public void onShow(DialogInterface ignored) {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    new View.OnClickListener() {
                      @Override
                      public void onClick(View view) {
                        if (!canChange()) return;
                        try {
                          int count = Integer.parseInt(input.getText().toString().trim());
                          if (item == null) necklace.add(stone, count);
                          else necklace.setQuantity(stone.getId(), count);
                          if (save()) dialog.dismiss();
                        } catch (IllegalArgumentException error) {
                          errorText.setText(
                              "1–99 аралығындағы бүтін сан енгізіңіз. Бір түрдің жалпы саны 99-дан"
                                  + " аспасын.");
                          errorText.setVisibility(View.VISIBLE);
                        }
                      }
                    });
          }
        });
    dialog.show();
  }

  protected void saveCollection() {
    if (!canChange()) return;
    if (necklace.count() == 0) {
      message("Алдымен каталогтан тас қосыңыз.");
      return;
    }
    collectionName(null);
  }

  protected void collectionName(final NecklaceDatabase.Saved existing) {
    View form = getLayoutInflater().inflate(R.layout.dialog_name, null);
    EditText input = form.findViewById(R.id.name_input);
    TextView error = form.findViewById(R.id.name_error);
    input.setText(existing == null ? "Менің алқам" : existing.name);
    input.selectAll();
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(existing == null ? "Жинаққа сақтау" : "Атауын өзгерту")
            .setView(form)
            .setNegativeButton("Бас тарту", null)
            .setPositiveButton("Сақтау", null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        if (existing == null)
                          database.saveCollection(input.getText().toString(), necklace);
                        else database.renameCollection(existing.id, input.getText().toString());
                        dialog.dismiss();
                        refreshAll();
                        Toast.makeText(this, "Жинақ сақталды", Toast.LENGTH_SHORT).show();
                      } catch (IllegalArgumentException e) {
                        error.setText(e.getMessage());
                        error.setVisibility(View.VISIBLE);
                      } catch (RuntimeException e) {
                        error.setText("Жинақ сақталмады. Қайта көріңіз.");
                        error.setVisibility(View.VISIBLE);
                      }
                    }));
    dialog.show();
  }

  protected void loadCollection(final NecklaceDatabase.Saved saved) {
    new AlertDialog.Builder(this)
        .setTitle(saved.name)
        .setMessage("Осы жинақты ағымдағы алқаға ашамыз ба? Сақталған жинақ өзгермейді.")
        .setNegativeButton("Бас тарту", null)
        .setPositiveButton(
            "Ашу",
            (d, w) -> {
              if (!canChange()) return;
              necklace.restore(saved.necklace.encode());
              if (save()) openNecklaceReset();
            })
        .show();
  }

  private void openNecklaceReset() {
    Intent intent = new Intent(this, NecklaceActivity.class);
    intent.putExtra("reset_filters", true);
    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    startActivity(intent);
  }

  protected void changeQuantity(NecklaceItem item, int delta) {
    if (!canChange()) return;
    try {
      necklace.setQuantity(item.getStone().getId(), item.getQuantity() + delta);
      save();
    } catch (IllegalArgumentException e) {
      message("Бір түрдің саны 1–99 аралығында болуы керек.");
    }
  }

  private boolean canChange() {
    if (!storageReady) message("Дерекқор ашылмады. Қосымшаны қайта ашып көріңіз.");
    return storageReady;
  }

  protected boolean save() {
    try {
      database.save(necklace);
    } catch (RuntimeException error) {
      necklace.restore(savedState);
      storageReady = false;
      refreshAll();
      message("Өзгеріс сақталмады. Бұрынғы алқа көрсетілді.");
      return false;
    }
    savedState = necklace.encode();
    refreshAll();
    Toast.makeText(this, "Алқа сақталды", Toast.LENGTH_SHORT).show();
    return true;
  }

  protected void message(String text) {
    new AlertDialog.Builder(this)
        .setTitle("Асыл тас")
        .setMessage(text)
        .setPositiveButton("Түсінікті", null)
        .show();
  }

  @Override
  protected void onDestroy() {
    database.close();
    store.close();
    super.onDestroy();
  }
}
