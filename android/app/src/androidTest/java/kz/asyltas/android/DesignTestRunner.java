package kz.asyltas.android;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.math.BigDecimal;
import java.util.List;
import kz.asyltas.model.Necklace;

/** Uses only a separately named temporary database; never touches the user's database. */
public class DesignTestRunner extends Instrumentation {
  private int checks;
  private final StringBuilder log = new StringBuilder();
  private void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
    checks++; log.append("PASS ").append(name).append('\n');
  }
  private void rejected(Runnable action, String name) {
    try { action.run(); } catch (IllegalArgumentException expected) { check(true, name); return; }
    throw new AssertionError(name);
  }
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Context context = getTargetContext();
    long originalUser=Session.user(context);boolean originalActive=Session.active(context);
    String name = "redesign_verification_only.db";
    NecklaceDatabase helper = null;
    Bundle result = new Bundle();
    boolean success=false;
    try {
      Session.enter(context,0);
      context.deleteDatabase(name);
      SQLiteDatabase old = context.openOrCreateDatabase(name, 0, null);
      old.execSQL("CREATE TABLE necklace_items (stone_id INTEGER PRIMARY KEY, quantity INTEGER NOT NULL)");
      old.execSQL("INSERT INTO necklace_items VALUES (2,2),(3,1),(5,3)");
      old.setVersion(1); old.close();
      helper = new NecklaceDatabase(context, name);
      Necklace current = helper.load();
      check(current.count()==6, "v1 to v3 migration preserves 6 stones");
      check(current.totalWeight().compareTo(new BigDecimal("5.90"))==0, "migration preserves exact weight");
      check(current.totalCost().compareTo(new BigDecimal("616200"))==0, "migration preserves exact price");
      check(helper.getReadableDatabase().getVersion()==3, "schema upgraded to version 3");
      check(helper.collections().isEmpty(), "new collections begin empty");
      long id = helper.saveCollection("  Күнделікті алқа  ", current);
      check(id>0, "save returns stable ID");
      check(helper.collections().size()==1, "one saved snapshot");
      check(helper.collections().get(0).name.equals("Күнделікті алқа"), "Kazakh name trimmed and retained");
      check(helper.collections().get(0).necklace.encode().equals(current.encode()), "snapshot matches current necklace");
      current.clear(); helper.save(current);
      check(helper.load().count()==0, "current necklace can be cleared");
      check(helper.collections().get(0).necklace.count()==6, "saved snapshot unaffected by clearing current");
      helper.renameCollection(id,"Мерекелік алқа");
      check(helper.collections().get(0).name.equals("Мерекелік алқа"), "rename supports Kazakh");
      check(helper.collections().get(0).id==id, "rename preserves ID");
      helper.close(); helper = new NecklaceDatabase(context, name);
      check(helper.collections().get(0).name.equals("Мерекелік алқа"), "saved name survives database reopen");
      check(helper.collections().get(0).necklace.count()==6, "saved content survives database reopen");
      Necklace restored = helper.collections().get(0).necklace;
      helper.save(restored);
      check(helper.load().totalCost().compareTo(new BigDecimal("616200"))==0, "restored snapshot reproduces total");
      final NecklaceDatabase db = helper;
      rejected(() -> db.saveCollection("", restored), "empty name rejected");
      rejected(() -> db.saveCollection("   ", restored), "whitespace-only name rejected");
      rejected(() -> db.saveCollection("a".repeat(41), restored), "long name rejected");
      rejected(() -> db.saveCollection("Бос", new Necklace()), "empty necklace cannot be saved as design");
      rejected(() -> db.renameCollection(id," "), "blank rename rejected");
      check(db.collections().size()==1, "invalid saves do not create records");
      check(db.collections().get(0).name.equals("Мерекелік алқа"), "invalid rename keeps prior name");
      long another = db.saveCollection("Екінші", restored);
      check(another!=id && db.collections().size()==2, "multiple independent designs supported");
      db.deleteCollection(id);
      check(db.collections().size()==1 && db.collections().get(0).id==another, "delete affects only selected design");
      check(db.load().count()==6, "deleting saved design preserves current necklace");
      result.putString("stream",log.toString()+"PASS: "+checks+" database checks\n");
      result.putInt("checks",checks);
      success=true;
    } catch (Throwable error) {
      result.putString("stream",log.toString()+"FAIL: "+error.toString()+"\n");
      success=false;
    } finally { if(helper!=null)helper.close(); context.deleteDatabase(name); context.getSharedPreferences("session",0).edit().putLong("user",originalUser).putBoolean("guest",originalActive&&originalUser==0).commit(); }
    finish(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
  }
}
