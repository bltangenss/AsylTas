package kz.asyltas.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.os.Bundle;
import java.math.BigDecimal;
import java.util.List;
import kz.asyltas.model.Catalog;
import kz.asyltas.model.Necklace;
import kz.asyltas.model.Stone;

/** V4 integration checks use only explicitly named temporary databases. No GPS or UI mutation. */
final class V4Checks {
  private int checks;
  private final StringBuilder output=new StringBuilder();
  private void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;output.append("PASS ").append(name).append('\n');}
  private void rejected(Runnable action,String name){try{action.run();}catch(IllegalArgumentException expected){check(true,name);return;}throw new AssertionError(name);}

  void run(Instrumentation runner){
    Context c=runner.getTargetContext();long original=Session.user(c);boolean active=Session.active(c);
    String catalogName="catalog_v4_checks_only.db",portalName="portal_v4_checks_only.db",neckName="necklace_v4_checks_only.db";
    CatalogDatabase helper=null;AppStore account=null;NecklaceDatabase necklaces=null;boolean passed=false;Bundle report=new Bundle();
    try{
      c.deleteDatabase(catalogName);c.deleteDatabase(portalName);c.deleteDatabase(neckName);
      Session.enter(c,0);account=new AppStore(c,portalName);long first=account.register("Бірінші","v4first@example.test","Password123"),second=account.register("Екінші","v4second@example.test","Password123");
      Necklace sample=new Necklace();sample.demo();necklaces=new NecklaceDatabase(c,neckName);necklaces.save(sample);long legacyCollection=necklaces.saveCollection("Бұрынғы үлгі",sample);
      helper=new CatalogDatabase(c,catalogName);final CatalogDatabase data=helper;
      check(Catalog.all().size()==48,"catalog exposes 48 stable stone IDs");
      check(data.search("",new CatalogFilters()).size()==48,"SQLite seeds all 48 catalog rows");
      for(Stone stone:Catalog.all()){
        CatalogDatabase.Detail detail=data.detail(stone.getId());
        check(!detail.description.isEmpty()&&!detail.care.isEmpty(),"metadata exists for stone "+stone.getId());
        check(c.getResources().getIdentifier("stone_"+stone.getId(),"drawable",c.getPackageName())!=0,"photo resource is packaged for stone "+stone.getId());
      }
      try(Cursor row=data.getReadableDatabase().rawQuery("SELECT cost,carats FROM catalog_data WHERE stone=2",null)){
        check(row.moveToFirst()&&new BigDecimal(row.getString(0)).compareTo(new BigDecimal("216000"))==0&&new BigDecimal(row.getString(1)).compareTo(new BigDecimal("1.20"))==0,"original ruby price/weight remain exact in SQLite");
      }
      check(!data.search("garnet",new CatalogFilters()).isEmpty(),"English alias search finds new gemstone");
      check(data.search("none-matches-404",new CatalogFilters()).isEmpty(),"unknown query produces empty result");
      CatalogFilters price=new CatalogFilters("216000","216000","","",0,100);
      check(price.matches(Catalog.get(2)),"price boundaries include equality");check(!price.matches(Catalog.get(3)),"price filter excludes different price");
      check(new CatalogFilters("","","1,10","1.10",80,80).matches(Catalog.get(3)),"comma decimal and transparency boundaries stay exact");
      rejected(()->new CatalogFilters("20","10","","",0,100),"reversed price range rejected");
      rejected(()->new CatalogFilters("","","2","1",0,100),"reversed carat range rejected");
      rejected(()->new CatalogFilters("-1","","","",0,100),"negative price rejected");
      rejected(()->new CatalogFilters("1e9","","","",0,100),"scientific price input rejected");
      rejected(()->new CatalogFilters("","","","",80,70),"reversed transparency range rejected");
      check(!new CatalogFilters().active()&&price.active(),"filter active marker reflects limits");
      check(data.toggleCompare(13)&&data.toggleCompare(14)&&data.toggleCompare(48),"guest can compare new catalog stones");
      rejected(()->data.toggleCompare(15),"comparison limit of three is enforced atomically");check(data.comparison().size()==3,"rejected fourth comparison leaves original three");
      check(!data.toggleCompare(13)&&data.comparison().size()==2,"comparison toggles off");
      Session.enter(c,first);check(data.comparison().isEmpty(),"account comparison excludes guest selection");data.toggleCompare(24);
      Session.enter(c,second);check(data.comparison().isEmpty(),"comparison isolates different accounts");data.clearComparison();
      Session.enter(c,first);check(data.comparison().contains(24),"foreign clear cannot delete account comparison");
      for(int id=1;id<=20;id++)data.viewed(id);
      check(data.recent().size()==12,"recent history is limited to twelve");check(data.recent().get(0)==20&&!data.recent().contains(1),"recent history sorts newest first and trims oldest");
      data.viewed(6);data.viewed(6);check(data.recent().get(0)==6&&data.recent().size()==12,"reopening a stone moves it first without duplication");
      rejected(()->data.viewed(999),"unknown history ID is rejected");
      Session.enter(c,second);check(data.recent().isEmpty(),"recent history is private to current account");data.clearRecent();
      Session.enter(c,first);check(data.recent().size()==12,"other account history clear is isolated");
      data.saveNote(48,"Гематитті осы алқаға салыстырып көремін.");check(data.note(48).startsWith("Гематит"),"Kazakh personal note persists");
      check(data.noted().size()==1,"personal note list points to saved stone");
      rejected(()->data.saveNote(48,new String(new char[1001]).replace('\0','x')),"oversized personal note rejected");
      Session.enter(c,second);check(data.note(48).isEmpty()&&data.noted().isEmpty(),"notes are inaccessible to another account");data.saveNote(48,"Екінші жазба");
      Session.enter(c,0);check(data.note(48).isEmpty(),"guest cannot read account notes");rejected(()->data.saveNote(48,"Қонақ"),"guest cannot save personal note");
      Session.enter(c,first);check(data.note(48).startsWith("Гематит"),"another account note does not overwrite original");data.saveNote(48," ");check(data.note(48).isEmpty(),"empty note removes own record");
      data.saveNote(24,"Жеке белгі");helper.close();helper=new CatalogDatabase(c,catalogName);check(helper.note(24).equals("Жеке белгі")&&helper.comparison().contains(24),"comparison and note survive database reopen");
      Session.enter(c,0);check(necklaces.load().count()==6&&necklaces.load().totalCost().compareTo(new BigDecimal("616200"))==0,"new database leaves guest necklace untouched");
      check(necklaces.collections().size()==1&&necklaces.collections().get(0).id==legacyCollection,"existing saved necklace survives catalog creation");
      Session.enter(c,first);check(account.login("v4first@example.test","Password123")==first,"existing account credentials survive new feature writes");
      Necklace extended=new Necklace();extended.add(Catalog.get(48),2);necklaces.save(extended);check(necklaces.load().all().get(0).getStone().getId()==48,"new stone ID persists through necklace database");
      long order=account.createOrder(extended,"Өзім алып кетемін","","+77010000000",false,"","");extended.clear();Necklace stored=new Necklace();stored.restore(account.order(order).text("necklace"));check(stored.count()==2&&stored.all().get(0).getStone().getId()==48,"order snapshot supports added catalog IDs");
      List<CampusPlaces.Place> places=CampusPlaces.load(c);check(places.size()==3,"three verified campus places are bundled");
      check(places.get(0).id.equals("pushkin9")&&places.get(0).address.contains("9"),"user requested Pushkin 9 is the first meeting point");
      for(CampusPlaces.Place place:places)check(place.coordinates()&&!place.source.isEmpty()&&place.coordinateSource.startsWith("https://"),"coordinates and references exist for "+place.id);
      check(CampusPlaces.distance(51.157546,71.458645,51.157546,71.458645)==0,"same-coordinate distance is zero");
      double meters=CampusPlaces.distance(51.157546,71.458645,51.159897,71.465239);check(meters>400&&meters<700,"ENU straight-line distance is geographic, not route length");
      rejected(()->CampusPlaces.distance(100,71,51,71),"invalid latitude rejected");
      Location fix=new Location("test");fix.setLatitude(51.157546);fix.setLongitude(71.458645);fix.setElapsedRealtimeNanos(1000000000L);fix.setAccuracy(20);
      check(CampusActivity.fresh(fix,1000000000L),"current GPS fix accepted");
      check(CampusActivity.fresh(fix,1000000000L+CampusActivity.MAX_AGE_NANOS),"two-minute GPS boundary included");
      check(!CampusActivity.fresh(fix,1000000001L+CampusActivity.MAX_AGE_NANOS),"stale GPS fix is not used for routes");
      check(!CampusActivity.fresh(fix,999999999L),"future GPS timestamp is rejected");
      check(!CampusActivity.fresh(null,1000000000L),"missing GPS fix is handled");
      java.util.List<String> permissions=java.util.Arrays.asList(c.getPackageManager().getPackageInfo(c.getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions);
      check(permissions.contains("android.permission.ACCESS_FINE_LOCATION")&&permissions.contains("android.permission.ACCESS_COARSE_LOCATION")&&!permissions.contains("android.permission.ACCESS_BACKGROUND_LOCATION"),"location declares foreground permissions and no background tracking");
      passed=true;
    }catch(Throwable error){output.append("FAIL ").append(error).append('\n');}
    finally{
      if(helper!=null)helper.close();if(account!=null)account.close();if(necklaces!=null)necklaces.close();
      c.deleteDatabase(catalogName);c.deleteDatabase(portalName);c.deleteDatabase(neckName);
      c.getSharedPreferences("session",0).edit().putLong("user",original).putBoolean("guest",active&&original==0).commit();
    }
    report.putInt("checks",checks);report.putString("stream",output+(passed?"PASS: "+checks+" v4 checks\n":"FAILED V4\n"));runner.finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);
  }
}
