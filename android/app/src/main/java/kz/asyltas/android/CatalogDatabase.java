package kz.asyltas.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kz.asyltas.model.Catalog;
import kz.asyltas.model.Stone;
import org.json.JSONArray;
import org.json.JSONObject;

/** Additive v4 database: existing account/order/necklace databases remain untouched. */
public final class CatalogDatabase extends SQLiteOpenHelper {
  private final Context context;

  public CatalogDatabase(Context context) { this(context, "asyl_catalog.db"); }

  CatalogDatabase(Context context, String name) {
    super(context, name, null, 1);
    this.context = context.getApplicationContext();
  }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE catalog_data(stone INTEGER PRIMARY KEY,name TEXT NOT NULL,category TEXT NOT NULL,carats TEXT NOT NULL,price_per_carat TEXT NOT NULL,cost TEXT NOT NULL,transparency INTEGER NOT NULL,color TEXT NOT NULL,aliases TEXT NOT NULL,description TEXT NOT NULL,care TEXT NOT NULL)");
    db.execSQL("CREATE TABLE comparison(owner INTEGER NOT NULL,stone INTEGER NOT NULL,added INTEGER NOT NULL,PRIMARY KEY(owner,stone))");
    db.execSQL("CREATE TABLE recent_stones(owner INTEGER NOT NULL,stone INTEGER NOT NULL,viewed INTEGER NOT NULL,PRIMARY KEY(owner,stone))");
    db.execSQL("CREATE TABLE stone_notes(owner INTEGER NOT NULL,stone INTEGER NOT NULL,body TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(owner,stone))");
    try (InputStream input = context.getAssets().open("catalog_v4.json")) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
      JSONArray records = new JSONArray(new String(output.toByteArray(), StandardCharsets.UTF_8));
      if (records.length() != Catalog.all().size()) throw new IllegalStateException("Catalog asset/model mismatch");
      for (int i = 0; i < records.length(); i++) {
        JSONObject row = records.getJSONObject(i);
        Stone stone = Catalog.get(row.getInt("id"));
        ContentValues values = new ContentValues();
        values.put("stone", stone.getId());
        values.put("name", stone.getName());
        values.put("category", stone.getCategory());
        values.put("carats", stone.getCarats().toPlainString());
        values.put("price_per_carat", stone.getPricePerCarat().toPlainString());
        values.put("cost", stone.getCost().toPlainString());
        values.put("transparency", stone.getTransparency());
        values.put("color", stone.getColor());
        values.put("aliases", row.optString("aliases", ""));
        values.put("description", row.optString("description", ""));
        values.put("care", row.optString("care", ""));
        db.insertOrThrow("catalog_data", null, values);
      }
    } catch (Exception error) { throw new IllegalStateException("Каталогты дайындау мүмкін болмады.", error); }
  }

  @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    // Version 1 adds new tables in its own database; no legacy database is replaced.
  }

  private long owner() { return Session.user(context); }

  public static final class Detail {
    public final int stone;
    public final String aliases, description, care;
    Detail(int stone, String aliases, String description, String care) {
      this.stone = stone; this.aliases = aliases; this.description = description; this.care = care;
    }
  }

  public Detail detail(int stone) {
    Catalog.get(stone);
    try (Cursor c = getReadableDatabase().rawQuery("SELECT aliases,description,care FROM catalog_data WHERE stone=?", new String[]{String.valueOf(stone)})) {
      if (!c.moveToFirst()) return new Detail(stone, "", "", "");
      return new Detail(stone, c.getString(0), c.getString(1), c.getString(2));
    }
  }

  public List<Stone> search(String query, CatalogFilters filters) {
    String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    List<Stone> result = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery("SELECT stone,name,aliases,description FROM catalog_data ORDER BY stone", null)) {
      while (c.moveToNext()) {
        Stone stone = Catalog.get(c.getInt(0));
        String words = (c.getString(1) + " " + c.getString(2) + " " + c.getString(3)).toLowerCase(Locale.ROOT);
        boolean match = true;
        for (String term : normalized.split("\\s+")) if (!words.contains(term)) { match = false; break; }
        if (match && filters.matches(stone)) result.add(stone);
      }
    }
    return result;
  }

  private List<Integer> ids(String sql, String... arguments) {
    List<Integer> result = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery(sql, arguments)) {
      while (c.moveToNext()) result.add(c.getInt(0));
    }
    return result;
  }

  public List<Integer> comparison() {
    return ids("SELECT stone FROM comparison WHERE owner=? ORDER BY added,stone", String.valueOf(owner()));
  }

  public boolean toggleCompare(int stone) {
    Catalog.get(stone);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      long owner = owner();
      if (db.delete("comparison", "owner=? AND stone=?", new String[]{String.valueOf(owner), String.valueOf(stone)}) > 0) {
        db.setTransactionSuccessful(); return false;
      }
      if (comparison().size() >= 3) throw new IllegalArgumentException("Бір уақытта 3 тасқа дейін салыстырылады. Алдымен бір тасты алып тастаңыз.");
      ContentValues value = new ContentValues(); value.put("owner", owner); value.put("stone", stone); value.put("added", System.currentTimeMillis());
      db.insertOrThrow("comparison", null, value);
      db.setTransactionSuccessful(); return true;
    } finally { db.endTransaction(); }
  }

  public void clearComparison() { getWritableDatabase().delete("comparison", "owner=?", new String[]{String.valueOf(owner())}); }

  public void viewed(int stone) {
    Catalog.get(stone);
    SQLiteDatabase db = getWritableDatabase(); long owner = owner();
    db.beginTransaction();
    try {
      long viewed = System.currentTimeMillis();
      try (Cursor c = db.rawQuery("SELECT MAX(viewed) FROM recent_stones WHERE owner=?", new String[]{String.valueOf(owner)})) {
        if (c.moveToFirst() && !c.isNull(0)) viewed = Math.max(viewed, c.getLong(0) + 1);
      }
      ContentValues value = new ContentValues(); value.put("owner", owner); value.put("stone", stone); value.put("viewed", viewed);
      db.insertWithOnConflict("recent_stones", null, value, SQLiteDatabase.CONFLICT_REPLACE);
      db.execSQL("DELETE FROM recent_stones WHERE owner=? AND stone NOT IN (SELECT stone FROM recent_stones WHERE owner=? ORDER BY viewed DESC,stone DESC LIMIT 12)", new Object[]{owner, owner});
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
  }

  public List<Integer> recent() { return ids("SELECT stone FROM recent_stones WHERE owner=? ORDER BY viewed DESC,stone DESC LIMIT 12", String.valueOf(owner())); }
  public void clearRecent() { getWritableDatabase().delete("recent_stones", "owner=?", new String[]{String.valueOf(owner())}); }

  public String note(int stone) {
    Catalog.get(stone);
    if (owner() <= 0) return "";
    try (Cursor c = getReadableDatabase().rawQuery("SELECT body FROM stone_notes WHERE owner=? AND stone=?", new String[]{String.valueOf(owner()), String.valueOf(stone)})) {
      return c.moveToFirst() ? c.getString(0) : "";
    }
  }

  public void saveNote(int stone, String body) {
    Catalog.get(stone);
    if (owner() <= 0) throw new IllegalArgumentException("Жеке жазба сақтау үшін аккаунтқа кіріңіз.");
    String value = body == null ? "" : body.trim();
    if (value.length() > 1000) throw new IllegalArgumentException("Жазба 1000 таңбадан аспасын.");
    SQLiteDatabase db = getWritableDatabase();
    if (value.isEmpty()) { db.delete("stone_notes", "owner=? AND stone=?", new String[]{String.valueOf(owner()), String.valueOf(stone)}); return; }
    ContentValues values = new ContentValues(); values.put("owner", owner()); values.put("stone", stone); values.put("body", value); values.put("updated", System.currentTimeMillis());
    db.insertWithOnConflict("stone_notes", null, values, SQLiteDatabase.CONFLICT_REPLACE);
  }

  public List<Integer> noted() { return owner() <= 0 ? new ArrayList<>() : ids("SELECT stone FROM stone_notes WHERE owner=? ORDER BY updated DESC,stone DESC", String.valueOf(owner())); }
}
