package kz.asyltas.android;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import kz.asyltas.model.Catalog;
import kz.asyltas.model.Format;
import kz.asyltas.model.Stone;

/** Personal comparison and recent activity, stored per account (including guest). */
public class LibraryActivity extends BaseActivity {
  private LinearLayout page;
  private String mode;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state); mode = getIntent().getStringExtra("mode");
    if (!"recent".equals(mode) && !"notes".equals(mode)) mode = "compare";
    prepare(R.layout.activity_hub, mode.equals("recent") ? "Соңғы қаралған" : mode.equals("notes") ? "Жеке жазбалар" : "Тастарды салыстыру");
    ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
    page = Ui.column(this); int p = Ui.dp(this,20); page.setPadding(p,p / 2,p,p); scroll.addView(page);
    ((FrameLayout) findViewById(R.id.hub_content)).addView(scroll);
  }

  @Override protected void refresh() {
    if (page == null) return;
    page.removeAllViews();
    if (mode.equals("notes") && !requireAccount()) return;
    List<Integer> ids = mode.equals("compare") ? catalogData.comparison() : mode.equals("recent") ? catalogData.recent() : catalogData.noted();
    Ui.text(page, mode.equals("compare") ? "Үш тасқа дейін баға, салмақ және мөлдірлік бойынша салыстырыңыз." : mode.equals("recent") ? "Соңғы ашқан 12 тас. Бұл тізім осы профиліңізге тиесілі." : "Өзіңіз сақтаған жазбалар. Басқа аккаунттарға көрінбейді.",14,false);
    if (ids.isEmpty()) {
      LinearLayout empty = Ui.card(page);
      Ui.text(empty, mode.equals("compare") ? "Салыстыру тізімі бос" : mode.equals("recent") ? "Әзірге қаралған тас жоқ" : "Әзірге жазба жоқ",22,true);
      Ui.text(empty, mode.equals("compare") ? "Каталогтағы «Салыстыру» белгісін немесе тас бетіндегі батырманы пайдаланыңыз." : "Каталогтан тас бетін ашыңыз.",14,false);
      Ui.button(empty,"Каталогқа өту",true,()->open(MainActivity.class,-1)); return;
    }
    Ui.text(page, ids.size() + (mode.equals("compare") ? " / 3 тас" : " тас"),18,true);
    if (mode.equals("compare")) comparison(ids); else for (int id : ids) {
      Stone stone = Catalog.get(id); LinearLayout card = Ui.card(page);
      Ui.link(card,stone.getName(),Format.money(stone.getCost()) + " · " + Format.weight(stone.getCarats()) + " карат",()->open(StoneActivity.class,id));
      if (mode.equals("notes")) Ui.text(card,catalogData.note(id),14,false);
    }
    if (!mode.equals("notes")) Ui.button(page,mode.equals("compare") ? "Салыстыру тізімін тазарту" : "Қарау тарихын тазарту",false,()->new AlertDialog.Builder(this)
        .setTitle("Тізімді тазарту?").setMessage("Осы профиліңіздің тізімі ғана тазартылады.").setNegativeButton("Қалдыру",null)
        .setPositiveButton("Тазарту",(d,w)->{if(mode.equals("compare"))catalogData.clearComparison();else catalogData.clearRecent();refresh();}).show());
  }

  private void comparison(List<Integer> ids) {
    if (ids.size() > 1) Ui.text(page,"Барлық бағанды көру үшін көлденең сырғытыңыз.",12,false);
    HorizontalScrollView horizontal = new HorizontalScrollView(this); horizontal.setHorizontalScrollBarEnabled(true);
    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); horizontal.addView(row);
    page.addView(horizontal,new LinearLayout.LayoutParams(-1,-2));
    for (int id : ids) {
      Stone stone = Catalog.get(id);
      LinearLayout holder = Ui.column(this);
      LinearLayout.LayoutParams column = new LinearLayout.LayoutParams(Ui.dp(this,225),-2); column.rightMargin=Ui.dp(this,12); row.addView(holder,column);
      LinearLayout card = Ui.card(holder);
      GemView image = new GemView(this,null); image.setColor(stone.getColor()); image.setContentDescription(stone.getName()+" фотосуреті");card.addView(image,new LinearLayout.LayoutParams(-1,Ui.dp(this,125)));
      TextView name = Ui.text(card,stone.getName(),20,true);name.setMinHeight(Ui.dp(this,60));
      metric(card,"Бір тас бағасы",Format.money(stone.getCost()));
      metric(card,"Бір карат бағасы",Format.money(stone.getPricePerCarat()));
      metric(card,"Салмағы",Format.weight(stone.getCarats())+" карат");
      metric(card,"Мөлдірлігі",stone.getTransparency()+"%");
      metric(card,"Санаты",stone.getCategory());
      Ui.button(card,"Тас туралы",false,()->open(StoneActivity.class,id));
      Ui.button(card,"Алқаға қосу",true,()->quantity(stone,null));
      Ui.button(card,"Тізімнен алу",false,()->compare(id));
    }
  }

  private void metric(LinearLayout parent,String label,String value) {
    Ui.text(parent,label,11,false); TextView text = Ui.text(parent,value,16,true);text.setMinHeight(Ui.dp(this,42));
  }
}
