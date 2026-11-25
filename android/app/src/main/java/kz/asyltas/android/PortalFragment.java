package kz.asyltas.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.math.BigDecimal;
import java.util.*;
import kz.asyltas.model.*;

/** Lab-style native Fragment for the account, store and request flows. */
public class PortalFragment extends Fragment {
  private LinearLayout page;
  private String screen, pendingKind = "audio";
  private String selectedDate = "Келісу бойынша";
  private Bundle draft;
  private ScrollView scroll;

  private HubActivity host() {
    return (HubActivity) getActivity();
  }

  private AppStore db() {
    return host().store;
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    screen = getArguments().getString("page", "profile");
    Bundle state = saved == null ? draft : saved;
    if (state != null) {
      pendingKind = state.getString("kind", "audio");
      selectedDate = state.getString("date", "Келісу бойынша");
    }
    scroll = new ScrollView(getActivity());
    scroll.setFillViewport(true);
    page = Ui.column(getActivity());
    int pad = Ui.dp(getActivity(), 20);
    page.setPadding(pad, pad / 2, pad, pad);
    scroll.addView(page);
    draw();
    if (state != null) {
      Bundle fields = state.getBundle("fields");
      if (fields != null) formState(page, "root", fields, true);
      int y = state.getInt("scroll");
      ScrollView restoredView = scroll;
      restoredView.post(() -> {
        if (scroll == restoredView) restoredView.scrollTo(0, y);
      });
    }
    return scroll;
  }

  private void saveDraft(Bundle state) {
    state.putString("kind", pendingKind);
    state.putString("date", selectedDate);
    if (page != null) {
      Bundle fields = new Bundle();
      formState(page, "root", fields, false);
      state.putBundle("fields", fields);
    }
    if (scroll != null) state.putInt("scroll", scroll.getScrollY());
  }

  private void formState(View view, String path, Bundle values, boolean restore) {
    String key =
        path + ":" + (view.getContentDescription() == null ? "" : view.getContentDescription());
    if (view instanceof EditText) {
      EditText e = (EditText) view;
      int type = e.getInputType();
      boolean secret = (type & 128) == 128 || (type & 16) == 16;
      if (!secret) {
        if (restore && values.containsKey(key)) e.setText(values.getString(key));
        else if (!restore) values.putString(key, e.getText().toString());
      }
    } else if (view instanceof CompoundButton) {
      CompoundButton b = (CompoundButton) view;
      if (restore && values.containsKey(key)) b.setChecked(values.getBoolean(key));
      else if (!restore) values.putBoolean(key, b.isChecked());
    } else if (view instanceof Spinner) {
      Spinner s = (Spinner) view;
      if (restore && values.containsKey(key)) s.setSelection(values.getInt(key));
      else if (!restore) values.putInt(key, s.getSelectedItemPosition());
    } else if (view instanceof RatingBar) {
      RatingBar r = (RatingBar) view;
      if (restore && values.containsKey(key)) r.setRating(values.getFloat(key));
      else if (!restore) values.putFloat(key, r.getRating());
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        formState(group.getChildAt(i), path + "/" + i, values, restore);
    }
  }

  @Override
  public void onSaveInstanceState(Bundle state) {
    saveDraft(state);
    super.onSaveInstanceState(state);
  }

  @Override
  public void onDestroyView() {
    draft = new Bundle();
    saveDraft(draft);
    page = null;
    scroll = null;
    super.onDestroyView();
  }

  private void title(String value, String subtitle) {
    host().getActionBar().setTitle(value);
    if (!subtitle.isEmpty()) Ui.text(page, subtitle, 13, false);
  }

  private void go(String destination) {
    host().show(destination, true);
  }

  private void perform(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException e) {
      host().message(e.getMessage());
    } catch (RuntimeException e) {
      host().message("Әрекет орындалмады. Қайта көріңіз.");
    }
  }

  private void toast(String text) {
    Toast.makeText(getActivity(), text, Toast.LENGTH_SHORT).show();
  }

  private boolean account() {
    if (db().user() != null) return true;
    title("Жеке кабинет", "");
    LinearLayout c = Ui.card(page);
    Ui.text(c, "Барлығы өз профиліңізде", 23, true);
    Ui.text(c, "Таңдаулылар, жинақтар және өтінімдер аккаунтыңызға байланысады.", 14, false);
    Ui.button(
        c,
        "Кіру немесе тіркелу",
        true,
        () -> {
          Session.clear(getActivity());
          startActivity(
              new Intent(getActivity(), LoginActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        });
    return false;
  }

  private void draw() {
    page.removeAllViews();
    perform(
        () -> {
          if (screen.equals("store")) {
            location();
            return;
          }
          if (screen.equals("credits")) {
            credits();
            return;
          }
          if (screen.equals("preferences")) {
            preferences();
            return;
          }
          if (!account()) {
            Ui.link(page, "ЕҰУ маңы және GPS", "Пушкин 9, корпус және маршрут", () -> host().open(CampusActivity.class, -1));
            Ui.link(page, "Тастарды салыстыру", "Үш тасқа дейін", () -> host().library("compare"));
            Ui.link(page, "Соңғы қаралған тастар", "Қонақ профилінің тарихы", () -> host().library("recent"));
            Ui.link(page, "Біз осындамыз", "Мекенжай және байланыс", () -> go("store"));
            return;
          }
          if (screen.equals("edit")) edit();
          else if (screen.equals("checkout")) checkout();
          else if (screen.equals("orders") || screen.equals("admin_orders")) orders();
          else if (screen.startsWith("order:")) order(Long.parseLong(screen.substring(6)));
          else if (screen.equals("media")) media();
          else if (screen.equals("support")) support();
          else if (screen.equals("admin")) admin();
          else if (screen.equals("store_edit")) storeEdit();
          else if (screen.startsWith("reviews:")) reviews(Integer.parseInt(screen.substring(8)));
          else profile();
        });
  }

  private void credits() {
    title(
        "Фотосуреттер",
        "Нақты тастардың фотосуреттері. Каталогтағы баға мен салмақ — оқу деректері.");
    try (java.io.InputStream stream = getActivity().getAssets().open("photo_credits.json")) {
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int n;
      while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
      org.json.JSONArray rows = new org.json.JSONArray(bytes.toString("UTF-8"));
      for (int i = 0; i < rows.length(); i++) {
        org.json.JSONObject r = rows.getJSONObject(i);
        LinearLayout card = Ui.card(page);
        Ui.text(card, Catalog.get(r.getInt("id")).getName(), 19, true);
        Ui.text(
            card,
            r.getString("author").isEmpty() ? "Public domain" : r.getString("author"),
            14,
            false);
        Ui.text(card, r.getString("license"), 12, false);
        String link = r.getString("source"), license = r.getString("license_url");
        Ui.button(
            card,
            "Дереккөзді ашу",
            false,
            () -> external(new Intent(Intent.ACTION_VIEW, Uri.parse(link)), null));
        if (!license.isEmpty())
          Ui.button(
              card,
              "Лицензия шарттары",
              false,
              () -> external(new Intent(Intent.ACTION_VIEW, Uri.parse(license)), null));
      }
    } catch (Exception e) {
      Ui.text(page, "Дереккөздер PHOTO_CREDITS.txt файлында берілген.", 14, false);
    }
  }

  private void profile() {
    AppStore.Row u = db().user();
    title("Жеке кабинет", "");
    LinearLayout c = Ui.card(page);
    Ui.text(c, u.text("name"), 26, true);
    Ui.text(c, u.text("email"), 14, false);
    Ui.text(c, db().admin() ? "Дүкен әкімшісі" : "Жеке аккаунт", 12, false);
    Ui.link(c, "Профильді өзгерту", "Аты-жөніңіз, телефон, мекенжай", () -> go("edit"));
    LinearLayout items = Ui.card(page);
    Ui.link(items, "Менің өтінімдерім", db().orders(false).size() + " өтінім", () -> go("orders"));
    Ui.link(
        items,
        "Таңдаулылар",
        db().favorites().size() + " тас",
        () ->
            startActivity(
                new Intent(getActivity(), MainActivity.class).putExtra("favorites", true)));
    Ui.link(
        items,
        "Сақталған алқалар",
        host().database.collections().size() + " жинақ",
        () -> host().open(SavedActivity.class, -1));
    Ui.link(items, "Медиа кітапхана", "Аудио, видео және суреттер", () -> go("media"));
    Ui.link(items, "ЕҰУ маңы және GPS", "Орныңыз, қашықтық және маршрут", () -> host().open(CampusActivity.class, -1));
    Ui.link(items, "Тастарды салыстыру", host().catalogData.comparison().size() + " / 3 тас", () -> host().library("compare"));
    Ui.link(items, "Соңғы қаралған", "Соңғы ашылған 12 тас", () -> host().library("recent"));
    Ui.link(items, "Жеке жазбалар", "Тастар туралы өз белгілеріңіз", () -> host().library("notes"));
    Ui.link(items, "Дүкен деректері", "Мекенжай және байланыс", () -> go("store"));
    Ui.link(items, "Хабарламалар", "Сұрақтар мен жауаптар", () -> go("support"));
    Ui.link(items, "Баптаулар", "Көрініс пен дыбыс", () -> go("preferences"));
    Ui.link(
        items,
        "Қолдану нұсқаулығы",
        "Алқаны құрастыру және сақтау",
        () -> host().open(GuideActivity.class, -1));
    if (db().admin()) Ui.button(page, "Дүкенді басқару", true, () -> go("admin"));
    Ui.button(
        page,
        "Аккаунттан шығу",
        false,
        () ->
            new AlertDialog.Builder(getActivity())
                .setTitle("Аккаунттан шығу?")
                .setMessage("Сақталған деректеріңіз профиліңізде қалады.")
                .setNegativeButton("Бас тарту", null)
                .setPositiveButton(
                    "Шығу",
                    (d, w) -> {
                      Session.clear(getActivity());
                      startActivity(
                          new Intent(getActivity(), LoginActivity.class)
                              .addFlags(
                                  Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    })
                .show());
  }

  private void edit() {
    title("Профиль", "");
    AppStore.Row u = db().user();
    LinearLayout c = Ui.card(page);
    EditText name = Ui.field(c, "Аты-жөніңіз", u.text("name"), 1),
        phone = Ui.field(c, "Телефон", u.text("phone"), InputType.TYPE_CLASS_PHONE),
        address = Ui.field(c, "Жеткізу мекенжайы", u.text("address"), 1);
    Ui.button(
        c,
        "Профильді сақтау",
        true,
        () ->
            perform(
                () -> {
                  db().updateProfile(Ui.value(name), Ui.value(phone), Ui.value(address));
                  toast("Профиль сақталды");
                }));
    LinearLayout security = Ui.card(page);
    Ui.text(security, "Құпиясөзді өзгерту", 19, true);
    EditText old = Ui.field(security, "Қазіргі құпиясөз", "", 129),
        next = Ui.field(security, "Жаңа құпиясөз", "", 129);
    Ui.button(
        security,
        "Құпиясөзді сақтау",
        false,
        () ->
            perform(
                () -> {
                  db().changePassword(old.getText().toString(), next.getText().toString());
                  old.setText("");
                  next.setText("");
                  toast("Құпиясөз өзгертілді");
                }));
  }

  private void checkout() {
    title("Өтінім рәсімдеу", "Алқаңыздың құрамы мен байланыс мәліметтерін тексеріңіз.");
    Necklace necklace = host().database.load();
    LinearLayout total = Ui.card(page);
    Ui.text(total, Format.money(necklace.totalCost()), 28, true);
    Ui.text(
        total,
        necklace.count() + " тас · " + Format.weight(necklace.totalWeight()) + " карат",
        14,
        false);
    if (necklace.count() == 0) {
      Ui.button(total, "Каталогқа өту", true, () -> host().open(MainActivity.class, -1));
      return;
    }
    AppStore.Row user = db().user();
    LinearLayout c = Ui.card(page);
    Ui.text(c, "Алу тәсілі", 17, true);
    RadioGroup methods = new RadioGroup(getActivity());
    c.addView(methods);
    RadioButton pickup = new RadioButton(getActivity()), delivery = new RadioButton(getActivity());
    pickup.setId(View.generateViewId());
    delivery.setId(View.generateViewId());
    pickup.setText("Өзім алып кетемін");
    delivery.setText("Жеткізу");
    methods.addView(pickup);
    methods.addView(delivery);
    pickup.setChecked(true);
    EditText
        phone = Ui.field(c, "Байланыс телефоны", user.text("phone"), InputType.TYPE_CLASS_PHONE),
        address = Ui.field(c, "Жеткізу мекенжайы", user.text("address"), 1);
    address.setEnabled(false);
    methods.setOnCheckedChangeListener((g, id) -> address.setEnabled(id == delivery.getId()));
    CheckBox gift = new CheckBox(getActivity());
    gift.setText("Сыйлық ретінде дайындау");
    c.addView(gift);
    Ui.text(c, "Жеткізу мен қаптама шарттары дүкенмен нақтыланады.", 12, false);
    Button choose = Ui.button(c, "Алу күні: " + selectedDate, false, () -> {});
    choose.setOnClickListener(
        v -> {
          Calendar now = Calendar.getInstance();
          DatePickerDialog dialog =
              new DatePickerDialog(
                  getActivity(),
                  (picker, y, m, d) -> {
                    selectedDate = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d);
                    choose.setText("Алу күні: " + selectedDate);
                  },
                  now.get(Calendar.YEAR),
                  now.get(Calendar.MONTH),
                  now.get(Calendar.DAY_OF_MONTH));
          dialog.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
          dialog.show();
        });
    EditText note =
        Ui.field(
            c,
            "Қосымша ескерту",
            "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    CheckBox checked = new CheckBox(getActivity());
    checked.setText("Мәліметтерді тексердім");
    c.addView(checked);
    Ui.text(
        c,
        "Өтінім осы құрылғыда сақталады. Оны кейін «Бөлісу» арқылы дүкенге жіберуге болады. Онлайн"
            + " төлем алынбайды.",
        12,
        false);
    Ui.button(
        c,
        "Өтінімді сақтау",
        true,
        () ->
            perform(
                () -> {
                  if (!checked.isChecked())
                    throw new IllegalArgumentException("Мәліметтерді тексергеніңізді белгілеңіз.");
                  long id =
                      db().createOrder(
                              necklace,
                              delivery.isChecked() ? "Жеткізу" : "Өзім алып кетемін",
                              Ui.value(address),
                              Ui.value(phone),
                              gift.isChecked(),
                              Ui.value(note),
                              selectedDate);
                  toast("Өтінім сақталды");
                  go("order:" + id);
                }));
  }

  private void orders() {
    boolean all = screen.equals("admin_orders");
    title(
        all ? "Барлық өтінімдер" : "Менің өтінімдерім",
        "Өтінімді ашып, құрамын және мәртебесін көріңіз.");
    List<AppStore.Row> rows = db().orders(all);
    if (rows.isEmpty()) {
      LinearLayout c = Ui.card(page);
      Ui.text(c, "Әзірге өтінім жоқ", 22, true);
      Ui.button(c, "Алқаны құрастыру", true, () -> host().open(NecklaceActivity.class, -1));
      return;
    }
    Spinner filter = new Spinner(getActivity());
    String[] labels = {"Барлығы", "Жоба", "Қабылданды", "Дайын", "Аяқталды", "Бас тартылды"};
    filter.setContentDescription("Өтінім мәртебесі");
    filter.setBackgroundResource(R.drawable.bg_field);
    filter.setPadding(Ui.dp(getActivity(), 12), 0, Ui.dp(getActivity(), 12), 0);
    filter.setAdapter(new ArrayAdapter<>(getActivity(), R.layout.spinner_dropdown, labels));
    page.addView(filter, new LinearLayout.LayoutParams(-1, Ui.dp(getActivity(), 48)));
    LinearLayout results = Ui.column(getActivity());
    page.addView(results);
    filter.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            results.removeAllViews();
            for (AppStore.Row r : rows)
              if (pos == 0 || r.number("status") == pos - 1) {
                LinearLayout c = Ui.card(results);
                Ui.text(
                    c,
                    "№" + r.text("id") + " · " + AppStore.STATUSES[(int) r.number("status")],
                    16,
                    true);
                Ui.text(c, Format.money(new BigDecimal(r.text("total"))), 24, true);
                Ui.text(c, (all ? r.text("name") + " · " : "") + r.text("delivery"), 13, false);
                Ui.button(c, "Өтінімді ашу", false, () -> go("order:" + r.text("id")));
              }
            if (results.getChildCount() == 0)
              Ui.text(results, "Бұл мәртебеде өтінім жоқ.", 15, false);
          }
        });
  }

  private String receipt(AppStore.Row r) {
    Necklace n = new Necklace();
    n.restore(r.text("necklace"));
    StringBuilder s = new StringBuilder("Асыл тас · Өтінім №" + r.text("id") + "\n");
    for (NecklaceItem i : n.all())
      s.append(i.getStone().getName())
          .append(" × ")
          .append(i.getQuantity())
          .append(" — ")
          .append(Format.money(i.getCost()))
          .append('\n');
    return s
        + "\nБарлығы: "
        + Format.money(n.totalCost())
        + "\n"
        + r.text("delivery")
        + "\n"
        + r.text("address")
        + "\nТелефон: "
        + r.text("phone")
        + "\nКүні: "
        + r.text("date")
        + "\nЕскерту: "
        + r.text("note");
  }

  private void order(long id) {
    AppStore.Row r = db().order(id);
    title("Өтінім №" + id, AppStore.STATUSES[(int) r.number("status")]);
    LinearLayout c = Ui.card(page);
    Ui.text(c, Format.money(new BigDecimal(r.text("total"))), 28, true);
    Ui.text(c, receipt(r), 15, false);
    Ui.text(c, r.number("gift") == 1 ? "Сыйлық қаптамасы сұралған" : "Қалыпты қаптама", 13, false);
    Ui.button(
        c,
        "Өтініммен бөлісу",
        true,
        () ->
            startActivity(
                Intent.createChooser(
                    new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, receipt(r)),
                    "Дүкенге жіберу")));
    Ui.button(
        c,
        "Осы алқаны қайта ашу",
        false,
        () ->
            new AlertDialog.Builder(getActivity())
                .setTitle("Ағымдағы алқаны ауыстыру?")
                .setMessage("Өтінімдегі алқа редакторға көшіріледі.")
                .setNegativeButton("Бас тарту", null)
                .setPositiveButton(
                    "Ашу",
                    (d, w) ->
                        perform(
                            () -> {
                              Necklace n = new Necklace();
                              n.restore(r.text("necklace"));
                              host().database.save(n);
                              startActivity(
                                  new Intent(getActivity(), NecklaceActivity.class)
                                      .putExtra("reset_filters", true));
                            }))
                .show());
    if (db().admin())
      Ui.button(
          page,
          "Мәртебені өзгерту",
          false,
          () ->
              new AlertDialog.Builder(getActivity())
                  .setTitle("Өтінім мәртебесі")
                  .setSingleChoiceItems(
                      AppStore.STATUSES,
                      (int) r.number("status"),
                      (d, which) ->
                          perform(
                              () -> {
                                db().status(id, which);
                                d.dismiss();
                                draw();
                              }))
                  .setNegativeButton("Бас тарту", null)
                  .show());
    if (r.number("status") == 0)
      Ui.button(
          page,
          "Өтінімнен бас тарту",
          false,
          () ->
              new AlertDialog.Builder(getActivity())
                  .setTitle("Өтінімнен бас тарту?")
                  .setNegativeButton("Қалдыру", null)
                  .setPositiveButton(
                      "Бас тарту",
                      (d, w) ->
                          perform(
                              () -> {
                                db().status(id, 4);
                                draw();
                              }))
                  .show());
  }

  private void location() {
    title("Біз осындамыз", "Дүкеннің мекенжайы және байланыс жолдары");
    Ui.button(page, "ЕҰУ маңы және GPS", true, () -> host().open(CampusActivity.class, -1));
    String address = db().setting("address", ""),
        phone = db().setting("phone", ""),
        hours = db().setting("hours", "");
    LinearLayout c = Ui.card(page);
    if (db().setting("address_example", "0").equals("1"))
      Ui.text(c, "ҮЛГІ ДҮКЕН · АЛМАТЫ", 11, false);
    Ui.text(c, "Асыл тас", 28, true);
    Ui.text(c, address.isEmpty() ? "Дүкен мекенжайы әлі енгізілмеген." : address, 17, true);
    if (!hours.isEmpty()) Ui.text(c, "Жұмыс уақыты · " + hours, 14, false);
    if (!address.isEmpty()) {
      Ui.button(
          c,
          "Картада ашу",
          true,
          () ->
              external(
                  new Intent(
                      Intent.ACTION_VIEW,
                      Uri.parse("geo:0,0?q=" + Uri.encode(address + " (Асыл тас)"))),
                  "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(address)));
      Ui.button(
          c,
          "Бағыт құру",
          false,
          () ->
              external(
                  new Intent(
                      Intent.ACTION_VIEW,
                      Uri.parse(
                          "https://www.google.com/maps/dir/?api=1&destination="
                              + Uri.encode(address))),
                  null));
      Ui.button(
          c,
          "Мекенжаймен бөлісу",
          false,
          () ->
              startActivity(
                  Intent.createChooser(
                      new Intent(Intent.ACTION_SEND)
                          .setType("text/plain")
                          .putExtra(Intent.EXTRA_TEXT, "Асыл тас\n" + address),
                      "Мекенжай")));
    }
    if (!phone.isEmpty()) {
      Ui.text(c, phone, 18, true);
      Ui.button(
          c,
          "Қоңырау шалу",
          false,
          () ->
              external(
                  new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone))), null));
    }
    if (db().admin()) Ui.button(page, "Дүкен деректерін өзгерту", false, () -> go("store_edit"));
    if (address.isEmpty())
      Ui.text(
          page,
          "Нақты мекенжайды дүкен әкімшісі енгізген соң карта мен бағыт батырмалары ашылады.",
          13,
          false);
  }

  private void external(Intent intent, String fallback) {
    try {
      startActivity(intent);
    } catch (ActivityNotFoundException e) {
      if (fallback != null) {
        try {
          startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
          return;
        } catch (ActivityNotFoundException ignored) {
        }
      }
      host().message("Бұл әрекетті ашатын қолданба табылмады.");
    }
  }

  private void storeEdit() {
    db().requireAdmin();
    title("Дүкен деректері", "");
    LinearLayout c = Ui.card(page);
    EditText address = Ui.field(c, "Дүкен мекенжайы", db().setting("address", ""), 1),
        phone =
            Ui.field(c, "Дүкен телефоны", db().setting("phone", ""), InputType.TYPE_CLASS_PHONE),
        hours = Ui.field(c, "Жұмыс уақыты", db().setting("hours", ""), 1);
    Ui.button(
        c,
        "Дүкен деректерін сақтау",
        true,
        () ->
            perform(
                () -> {
                  db().settings(Ui.value(address), Ui.value(phone), Ui.value(hours));
                  toast("Дүкен деректері сақталды");
                  go("store");
                }));
  }

  private void admin() {
    db().requireAdmin();
    title("Дүкенді басқару", "");
    List<AppStore.Row> orders = db().orders(true);
    BigDecimal sum = BigDecimal.ZERO;
    for (AppStore.Row o : orders)
      if (o.number("status") != 4) sum = sum.add(new BigDecimal(o.text("total")));
    LinearLayout c = Ui.card(page);
    Ui.text(c, "Өтінімдер сомасы", 13, false);
    Ui.text(c, Format.money(sum), 28, true);
    Ui.text(c, orders.size() + " өтінім · " + db().users().size() + " аккаунт", 14, false);
    Ui.link(c, "Өтінімдерді басқару", "Мәртебе және тапсырыс құрамы", () -> go("admin_orders"));
    Ui.link(c, "Дүкен мекенжайы", "Карта, телефон, жұмыс уақыты", () -> go("store_edit"));
    Ui.link(c, "Хабарламаларға жауап беру", "Пайдаланушы сұрақтары", () -> go("support"));
    Ui.text(page, "Тіркелген пайдаланушылар", 20, true);
    for (AppStore.Row user : db().users()) {
      LinearLayout row = Ui.card(page);
      Ui.text(row, user.text("name"), 16, true);
      Ui.text(row, user.text("email") + (user.number("admin") == 1 ? " · Әкімші" : ""), 13, false);
    }
  }

  private void preferences() {
    title("Баптаулар", "");
    SharedPreferences prefs = getActivity().getSharedPreferences("appearance", 0);
    LinearLayout c = Ui.card(page);
    Switch picture = new Switch(getActivity());
    picture.setText("Алқа бейнесін көрсету");
    picture.setTextSize(16);
    picture.setTextColor(Ui.INK);
    picture.setTextOn("Қосулы");
    picture.setTextOff("Өшірулі");
    picture.setChecked(prefs.getBoolean("preview", true));
    picture.setMinHeight(Ui.dp(getActivity(), 56));
    c.addView(picture, new LinearLayout.LayoutParams(-1, -2));
    picture.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("preview", on).apply());
    Ui.text(c, "Медиа дыбысы", 16, true);
    ToggleButton sound = new ToggleButton(getActivity());
    sound.setTextOn("Дыбыс қосулы");
    sound.setTextOff("Дыбыс өшірулі");
    sound.setAllCaps(false);
    sound.setTextSize(15);
    sound.setBackgroundResource(R.drawable.bg_toggle);
    sound.setTextColor(getActivity().getColorStateList(R.color.toggle_text));
    sound.setChecked(prefs.getBoolean("sound", true));
    c.addView(sound, new LinearLayout.LayoutParams(-1, Ui.dp(getActivity(), 48)));
    sound.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("sound", on).apply());
    LinearLayout info = Ui.card(page);
    Ui.text(info, "Асыл тас · 4.0", 22, true);
    Ui.link(info, "Фотосуреттер авторлары", "Лицензиялар және дереккөздер", () -> go("credits"));
    Ui.text(info, "Болат Тәңірберді\nЕргаш Назым\nҒалымжан Шынай", 14, false);
    Ui.text(
        info, "Бағалар — оқу мысалы. Аккаунттар мен өтінімдер осы құрылғыда сақталады.", 12, false);
  }

  private void support() {
    title(
        "Хабарламалар",
        "Осы құрылғыдағы пайдаланушылар мен дүкен әкімшісінің сұрақтары мен жауаптары.");
    LinearLayout form = Ui.card(page);
    EditText subject = Ui.field(form, "Хабарлама тақырыбы", "", 1),
        body =
            Ui.field(
                form,
                "Сұрағыңыз",
                "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    Ui.button(
        form,
        "Хабарламаны сақтау",
        true,
        () ->
            perform(
                () -> {
                  db().message(Ui.value(subject), Ui.value(body));
                  toast("Хабарлама сақталды");
                  draw();
                }));
    for (AppStore.Row r : db().messages()) {
      LinearLayout c = Ui.card(page);
      Ui.text(c, r.text("subject"), 18, true);
      Ui.text(c, r.text("name") + "\n" + r.text("body"), 14, false);
      Ui.text(
          c, r.text("reply").isEmpty() ? "Жауап күтілуде" : "Жауап: " + r.text("reply"), 14, false);
      if (db().admin())
        Ui.button(
            c,
            "Жауап жазу",
            false,
            () -> {
              LinearLayout box = Ui.column(getActivity());
              int pad = Ui.dp(getActivity(), 24);
              box.setPadding(pad, pad, pad, pad);
              EditText answer = Ui.field(box, "Жауап", r.text("reply"), 1);
              AlertDialog d =
                  new AlertDialog.Builder(getActivity())
                      .setTitle("Хабарламаға жауап")
                      .setView(box)
                      .setNegativeButton("Бас тарту", null)
                      .setPositiveButton("Сақтау", null)
                      .create();
              d.setOnShowListener(
                  v ->
                      d.getButton(-1)
                          .setOnClickListener(
                              w ->
                                  perform(
                                      () -> {
                                        db().reply(r.number("id"), Ui.value(answer));
                                        d.dismiss();
                                        draw();
                                      })));
              d.show();
            });
    }
  }

  private void reviews(int stone) {
    title(Catalog.get(stone).getName(), "Баға беріңіз және пікір қалдырыңыз.");
    LinearLayout form = Ui.card(page);
    RatingBar rating = new RatingBar(getActivity(), null, android.R.attr.ratingBarStyleSmall);
    rating.setNumStars(5);
    rating.setStepSize(1);
    rating.setIsIndicator(false);
    rating.setRating(5);
    form.addView(rating);
    EditText body =
        Ui.field(
            form, "Пікіріңіз", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    Ui.button(
        form,
        "Пікірді сақтау",
        true,
        () ->
            perform(
                () -> {
                  db().review(stone, (int) rating.getRating(), Ui.value(body));
                  toast("Пікір сақталды");
                  draw();
                }));
    for (AppStore.Row r : db().reviews(stone)) {
      LinearLayout c = Ui.card(page);
      Ui.text(c, r.text("name") + " · " + r.text("rating") + " / 5", 16, true);
      Ui.text(c, r.text("body"), 14, false);
    }
  }

  private void media() {
    title("Медиа кітапхана", "Зергерлік бұйымдардың суреттерін, аудио және видеоларын сақтаңыз.");
    Ui.button(
        page,
        "Файл қосу",
        true,
        () ->
            new AlertDialog.Builder(getActivity())
                .setTitle("Материал түрі")
                .setItems(
                    new String[] {"Аудио", "Видео", "Сурет / GIF"},
                    (d, which) -> {
                      pendingKind = new String[] {"audio", "video", "image"}[which];
                      Intent pick =
                          new Intent(Intent.ACTION_OPEN_DOCUMENT)
                              .addCategory(Intent.CATEGORY_OPENABLE)
                              .setType(pendingKind + "/*");
                      try {
                        startActivityForResult(pick, 71);
                      } catch (ActivityNotFoundException e) {
                        host().message("Файл таңдау қолданбасы табылмады.");
                      }
                    })
                .show());
    List<AppStore.Row> list = db().media();
    if (list.isEmpty()) {
      Ui.text(page, "Әзірге материал жоқ. Құрылғыдан файл қосыңыз.", 16, false);
      return;
    }
    EditText search = Ui.field(page, "Материал іздеу", "", 1);
    LinearLayout results = Ui.column(getActivity());
    page.addView(results);
    Runnable render =
        () -> {
          results.removeAllViews();
          String query = Ui.value(search).toLowerCase(Locale.ROOT);
          for (AppStore.Row r : list)
            if (r.text("title").toLowerCase(Locale.ROOT).contains(query)) {
              LinearLayout c = Ui.card(results);
              Ui.text(c, r.text("title"), 18, true);
              Ui.text(
                  c,
                  r.text("kind").equals("audio")
                      ? "Аудио"
                      : r.text("kind").equals("video") ? "Видео" : "Сурет / GIF",
                  12,
                  false);
              Ui.button(
                  c,
                  "Ашу / ойнату",
                  true,
                  () ->
                      startActivity(
                          new Intent(getActivity(), MediaActivity.class)
                              .putExtra("media_id", r.number("id"))));
              Button more = Ui.button(c, "Материал әрекеттері", false, () -> {});
              more.setOnClickListener(
                  v -> {
                    PopupMenu menu = new PopupMenu(getActivity(), more);
                    menu.getMenu().add("Атауын өзгерту");
                    menu.getMenu().add("Тізімнен жою");
                    menu.setOnMenuItemClickListener(
                        item -> {
                          if (item.getTitle().equals("Тізімнен жою")) {
                            new AlertDialog.Builder(getActivity())
                                .setTitle("Материалды тізімнен жою?")
                                .setMessage("Құрылғыдағы бастапқы файл сақталады.")
                                .setNegativeButton("Бас тарту", null)
                                .setPositiveButton(
                                    "Жою",
                                    (d, w) ->
                                        perform(
                                            () -> {
                                              db().deleteMedia(r.number("id"));
                                              draw();
                                            }))
                                .show();
                          } else renameMedia(r);
                          return true;
                        });
                    menu.show();
                  });
            }
          if (results.getChildCount() == 0) Ui.text(results, "Материал табылмады.", 15, false);
        };
    search.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            render.run();
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
    render.run();
  }

  private void renameMedia(AppStore.Row r) {
    LinearLayout form = Ui.column(getActivity());
    int p = Ui.dp(getActivity(), 24);
    form.setPadding(p, p, p, p);
    EditText title = Ui.field(form, "Материал атауы", r.text("title"), 1);
    AlertDialog dialog =
        new AlertDialog.Builder(getActivity())
            .setTitle("Атауын өзгерту")
            .setView(form)
            .setNegativeButton("Бас тарту", null)
            .setPositiveButton("Сақтау", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    w ->
                        perform(
                            () -> {
                              db().renameMedia(r.number("id"), Ui.value(title));
                              dialog.dismiss();
                              draw();
                            })));
    dialog.show();
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 71 || result != Activity.RESULT_OK || data == null || data.getData() == null)
      return;
    perform(
        () -> {
          Uri uri = data.getData();
          getActivity()
              .getContentResolver()
              .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
          String name = "Менің материалым";
          try (android.database.Cursor c =
              getActivity()
                  .getContentResolver()
                  .query(
                      uri,
                      new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                      null,
                      null,
                      null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
          }
          db().addMedia(
                  name.length() > 80 ? name.substring(0, 80) : name, pendingKind, uri.toString());
          draw();
        });
  }
}
