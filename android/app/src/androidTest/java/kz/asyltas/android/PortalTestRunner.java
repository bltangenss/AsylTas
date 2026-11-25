package kz.asyltas.android;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.math.BigDecimal;
import kz.asyltas.model.*;

/** Real SQLite and account integration checks in separately named temporary databases. */
public class PortalTestRunner extends Instrumentation {
  private int count;
  private String mode="checks";
  private final StringBuilder output=new StringBuilder();
  private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);count++;output.append("PASS ").append(label).append('\n');}
  private void rejected(Runnable action,String label){try{action.run();}catch(IllegalArgumentException expected){check(true,label);return;}throw new AssertionError(label);}
  @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null)mode=args.getString("mode","checks");start();}
  @Override public void onStart(){
    if(mode.equals("v4_checks")){new V4Checks().run(this);return;}
    if(mode.equals("v4_ui_begin")||mode.equals("v4_ui_cleanup")){new V4UiFixture().run(this,mode.equals("v4_ui_begin"));return;}
    if(mode.equals("ui_begin")||mode.equals("ui_cleanup")){uiFixture();return;}
    Context c=getTargetContext();long original=Session.user(c);boolean active=Session.active(c);String portal="portal_verification_only.db",neck="necklace_v3_verification_only.db",v1="necklace_v1_v3_verification_only.db";
    AppStore store=null;NecklaceDatabase designs=null;Bundle result=new Bundle();boolean success=false;
    try{
      Session.clear(c);c.deleteDatabase(portal);c.deleteDatabase(neck);store=new AppStore(c,portal);final AppStore db=store;
      rejected(()->db.register("","a@test.kz","Password123"),"empty registration name rejected");
      rejected(()->db.register("A","invalid","Password123"),"invalid email rejected");
      rejected(()->db.register("A","a@test.kz","123"),"short password rejected");
      long owner=db.register(" Дүкен иесі "," OWNER@TEST.KZ ","Password123"),client=db.register("Клиент","client@test.kz","Password123");
      check(owner!=client,"unique account IDs");Session.enter(c,owner);check(db.admin(),"first registered account owns local store");
      check(db.user().text("email").equals("owner@test.kz"),"email normalized");check(db.user().text("name").equals("Дүкен иесі"),"Kazakh name trimmed");
      rejected(()->db.register("Again","owner@test.kz","Password123"),"duplicate email rejected");
      check(db.login("OWNER@test.kz","Password123")==owner,"case-insensitive login succeeds");
      rejected(()->db.login("owner@test.kz","WrongPassword"),"incorrect password rejected");
      rejected(()->db.login("missing@test.kz","Password123"),"unknown account rejected");
      for(int i=1;i<=5;i++)rejected(()->db.login("client@test.kz","WrongPassword"),"failed client login "+i+" rejected");
      check(db.one("SELECT locked FROM users WHERE id=?",String.valueOf(client)).number("locked")>System.currentTimeMillis(),"five failures create timed account lock");
      rejected(()->db.login("client@test.kz","Password123"),"correct password cannot bypass active lock");
      db.getWritableDatabase().update("users",AppStore.values("locked",String.valueOf(System.currentTimeMillis()-1)),"id=?",new String[]{String.valueOf(client)});
      check(db.login("client@test.kz","Password123")==client,"login succeeds after lock expires");
      check(db.one("SELECT failures FROM users WHERE id=?",String.valueOf(client)).number("failures")==0,"successful login resets failure counter");
      AppStore.Row secret=db.one("SELECT salt,hash FROM users WHERE id=?",String.valueOf(owner));
      check(!secret.text("hash").contains("Password123"),"password is not stored as plain text");
      check(!secret.text("salt").equals(db.one("SELECT salt FROM users WHERE id=?",String.valueOf(client)).text("salt")),"accounts use unique random salts");
      db.updateProfile("Иесі","+77010000000","Алматы");check(db.user().text("phone").equals("+77010000000"),"profile updates persist");
      rejected(()->db.changePassword("WrongPassword","Changed123"),"password change verifies current password");db.changePassword("Password123","Changed123");
      check(db.login("owner@test.kz","Changed123")==owner,"changed password authenticates");rejected(()->db.login("owner@test.kz","Password123"),"old password no longer authenticates");
      check(db.toggleFavorite(2),"favorite added");check(db.favorites().contains(2),"favorite read back");check(!db.toggleFavorite(2),"favorite toggled off");db.toggleFavorite(3);
      rejected(()->db.toggleFavorite(999),"unknown stone favorite rejected");
      Necklace sample=new Necklace();sample.demo();
      rejected(()->db.createOrder(new Necklace(),"Жеткізу","Address","+77010000000",false,"",""),"empty order rejected");
      rejected(()->db.createOrder(sample,"Unknown","Address","+77010000000",false,"",""),"invalid delivery method rejected");
      rejected(()->db.createOrder(sample,"Жеткізу","","+77010000000",false,"",""),"delivery requires address");
      rejected(()->db.createOrder(sample,"Жеткізу","Address","123",false,"",""),"short contact phone rejected");
      long order=db.createOrder(sample,"Жеткізу","Алматы","+77010000000",true,"Сыйлық","2026-12-10");
      check(new BigDecimal(db.order(order).text("total")).compareTo(new BigDecimal("616200"))==0,"order stores exact 616200 total");
      check(db.order(order).text("gift").equals("1"),"gift choice persisted");check(db.order(order).text("date").equals("2026-12-10"),"requested date persisted");
      sample.clear();Necklace snapshot=new Necklace();snapshot.restore(db.order(order).text("necklace"));check(snapshot.count()==6,"order has immutable necklace snapshot");
      check(snapshot.totalWeight().compareTo(new BigDecimal("5.90"))==0,"snapshot weight remains exact");
      db.settings("Алматы, Абай даңғылы","+77010000000","10:00–19:00");check(db.setting("address_example","1").equals("0"),"editing store removes example flag");
      Session.enter(c,client);check(!db.admin(),"second account has customer role");check(db.favorites().isEmpty(),"favorites isolated by account");
      check(db.orders(false).isEmpty(),"orders isolated by account");rejected(()->db.order(order),"customer cannot read another account order");
      rejected(()->db.orders(true),"customer cannot list all orders");rejected(()->db.users(),"customer cannot list users");rejected(()->db.settings("Other","",""),"customer cannot edit store settings");
      rejected(()->db.status(order,1),"customer cannot change another order status");
      sample.demo();long customerOrder=db.createOrder(sample,"Өзім алып кетемін","","+77020000000",false,"","");db.status(customerOrder,4);check(db.order(customerOrder).number("status")==4,"customer can cancel own draft");
      rejected(()->db.status(customerOrder,1),"customer cannot accept own order");
      db.message("Сұрақ","Алқаны қашан алуға болады?");long message=db.messages().get(0).number("id");check(db.messages().size()==1,"customer message persisted");rejected(()->db.reply(message,"No"),"customer cannot write admin reply");
      db.review(2,5,"Жақсы тас");db.review(2,4,"Жаңартылған пікір");check(db.reviews(2).size()==1,"review update does not duplicate");check(db.reviews(2).get(0).number("rating")==4,"updated rating persisted");rejected(()->db.review(2,6,"Bad"),"invalid rating rejected");
      long media=db.addMedia("Әуен","audio","content://verification/audio");check(db.media().size()==1,"media reference stored");db.renameMedia(media,"Жаңа атау");check(db.media(media).text("title").equals("Жаңа атау"),"media renamed");
      rejected(()->db.addMedia("File","unknown","content://file"),"unknown media type rejected");rejected(()->db.renameMedia(media,""),"empty media name rejected");
      Session.enter(c,owner);check(db.favorites().contains(3),"owner favorites unaffected by other account");check(db.orders(true).size()==2,"admin can list all requests");
      db.status(customerOrder,2);check(db.order(customerOrder).number("status")==2,"admin can update request status");rejected(()->db.status(order,8),"invalid request status rejected");
      check(db.messages().size()==1,"admin sees customer messages");db.reply(message,"Ертең дайын болады.");check(db.messages().get(0).text("reply").startsWith("Ертең"),"admin reply persisted");
      check(db.media().isEmpty(),"media library isolated by account");rejected(()->db.media(media),"foreign media access rejected");rejected(()->db.deleteMedia(media),"foreign media deletion rejected");
      Session.enter(c,client);db.deleteMedia(media);check(db.media().isEmpty(),"own media reference deleted");
      Session.enter(c,0);rejected(()->db.toggleFavorite(2),"guest cannot save personal favorites");rejected(()->db.orders(false),"guest cannot access private orders");
      SQLiteDatabase legacy=c.openOrCreateDatabase(neck,0,null);legacy.execSQL("CREATE TABLE necklace_items(stone_id INTEGER PRIMARY KEY,quantity INTEGER NOT NULL)");legacy.execSQL("INSERT INTO necklace_items VALUES(2,2),(3,1),(5,3)");legacy.execSQL("CREATE TABLE saved_necklaces(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,necklace TEXT NOT NULL,updated INTEGER NOT NULL)");legacy.execSQL("INSERT INTO saved_necklaces(name,necklace,updated) VALUES(?,?,?)",new Object[]{"Бұрынғы алқа",sample.encode(),1});legacy.setVersion(2);legacy.close();
      designs=new NecklaceDatabase(c,neck);final NecklaceDatabase collection=designs;
      check(collection.getReadableDatabase().getVersion()==3,"v2 database migrates to v3");check(collection.load().count()==6,"migration preserves guest stones");check(collection.load().totalCost().compareTo(new BigDecimal("616200"))==0,"migration preserves exact guest price");check(collection.collections().size()==1,"migration preserves guest saved collection");
      Session.enter(c,owner);check(collection.load().count()==0,"new account starts with own necklace");check(collection.collections().isEmpty(),"guest saved collections remain separate");collection.save(sample);long design=collection.saveCollection("Жеке жинақ",sample);check(collection.collections().size()==1,"account collection created");
      Session.enter(c,client);check(collection.load().count()==0,"current necklace isolated across accounts");check(collection.collections().isEmpty(),"saved collections isolated across accounts");rejected(()->collection.renameCollection(design,"Foreign"),"cannot rename foreign collection");collection.deleteCollection(design);
      Session.enter(c,owner);check(collection.collections().size()==1,"foreign deletion leaves collection intact");check(collection.load().count()==6,"account necklace persisted");
      Session.enter(c,0);check(collection.load().count()==6,"guest necklace remains unchanged");check(collection.collections().get(0).name.equals("Бұрынғы алқа"),"legacy collection remains unchanged");
      c.deleteDatabase(v1);SQLiteDatabase oldest=c.openOrCreateDatabase(v1,0,null);oldest.execSQL("CREATE TABLE necklace_items(stone_id INTEGER PRIMARY KEY,quantity INTEGER NOT NULL)");oldest.execSQL("INSERT INTO necklace_items VALUES(2,2),(3,1),(5,3)");oldest.setVersion(1);oldest.close();
      try(NecklaceDatabase upgraded=new NecklaceDatabase(c,v1)){check(upgraded.getReadableDatabase().getVersion()==3,"v1 database upgrades directly to v3");check(upgraded.load().count()==6,"v1 migration preserves all guest stones");check(upgraded.load().totalCost().compareTo(new BigDecimal("616200"))==0,"v1 migration preserves exact total");check(upgraded.collections().isEmpty(),"v1 migration creates empty collections safely");}
      store.close();store=new AppStore(c,portal);Session.enter(c,client);check(store.login("client@test.kz","Password123")==client,"account survives database reopen");check(store.orders(false).size()==1,"orders survive database reopen");check(store.setting("hours","").equals("10:00–19:00"),"store settings survive reopen");
      result.putString("stream",output+"PASS: "+count+" portal checks\n");result.putInt("checks",count);success=true;
    }catch(Throwable e){result.putString("stream",output+"FAIL: "+e+"\n");success=false;}
    finally{if(store!=null)store.close();if(designs!=null)designs.close();c.deleteDatabase(portal);c.deleteDatabase(neck);c.deleteDatabase(v1);c.getSharedPreferences("session",0).edit().putLong("user",original).putBoolean("guest",active&&original==0).commit();}
    finish(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
  }
  /** Narrow, test-APK-only cleanup. Never clears the application's database. */
  private void uiFixture(){
    Context c=getTargetContext();Bundle result=new Bundle();
    String[] emails={"qa3.verify@asyltas.test","qa3.customer@asyltas.test"};
    SharedPreferences snapshot=c.getSharedPreferences("qa_v3_snapshot",0);
    try(AppStore store=new AppStore(c);NecklaceDatabase necklaces=new NecklaceDatabase(c)){
      if(mode.equals("ui_begin")){
        if(snapshot.contains("session"))throw new IllegalArgumentException("A UI fixture is already active");
        for(String email:emails)if(store.one("SELECT id FROM users WHERE email=?",email)!=null)throw new IllegalArgumentException("QA email already exists: "+email);
        org.json.JSONObject settings=new org.json.JSONObject();for(AppStore.Row row:store.rows("SELECT key,value FROM settings"))settings.put(row.text("key"),row.text("value"));
        long original=Session.user(c);boolean active=Session.active(c);Session.enter(c,0);String guest=necklaces.load().encode();c.getSharedPreferences("session",0).edit().putLong("user",original).putBoolean("guest",active&&original==0).commit();
        SharedPreferences appearance=c.getSharedPreferences("appearance",0);
        snapshot.edit().putLong("session",original).putBoolean("active",active).putString("settings",settings.toString()).putString("guest",guest).putBoolean("preview",appearance.getBoolean("preview",true)).putBoolean("sound",appearance.getBoolean("sound",true)).commit();
        result.putString("stream","UI fixture begun; previous session, guest necklace and store settings preserved.\n");
      }else{
        if(!snapshot.contains("session"))throw new IllegalArgumentException("No active UI fixture to clean up");
        for(String email:emails){AppStore.Row user=store.one("SELECT id FROM users WHERE email=?",email);if(user==null)continue;String[] id={user.text("id")};SQLiteDatabase d=store.getWritableDatabase();d.beginTransaction();try{for(String table:new String[]{"favorites","orders","media","messages","reviews"})d.delete(table,"owner=?",id);d.delete("users","id=?",id);d.setTransactionSuccessful();}finally{d.endTransaction();}necklaces.getWritableDatabase().delete("account_necklaces","owner=?",id);necklaces.getWritableDatabase().delete("saved_necklaces","owner=?",id);}
        SQLiteDatabase db=store.getWritableDatabase();org.json.JSONObject settings=new org.json.JSONObject(snapshot.getString("settings","{}"));db.beginTransaction();try{db.delete("settings",null,null);java.util.Iterator<String> keys=settings.keys();while(keys.hasNext()){String key=keys.next();db.insertOrThrow("settings",null,AppStore.values("key",key,"value",settings.getString(key)));}db.setTransactionSuccessful();}finally{db.endTransaction();}
        Session.enter(c,0);if(!necklaces.load().encode().equals(snapshot.getString("guest","")))throw new AssertionError("Guest necklace changed during UI testing");
        long original=snapshot.getLong("session",0);c.getSharedPreferences("session",0).edit().putLong("user",original).putBoolean("guest",snapshot.getBoolean("active",false)&&original==0).commit();
        c.getSharedPreferences("appearance",0).edit().putBoolean("preview",snapshot.getBoolean("preview",true)).putBoolean("sound",snapshot.getBoolean("sound",true)).commit();snapshot.edit().clear().commit();
        result.putString("stream","UI fixture removed. Only the two allowlisted QA accounts and their records were deleted. Guest necklace unchanged; original session, settings and preferences restored.\n");
      }
      finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream","FAIL UI fixture: "+error+"\n");finish(Activity.RESULT_CANCELED,result);}
  }
}
