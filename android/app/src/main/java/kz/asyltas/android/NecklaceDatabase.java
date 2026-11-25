package kz.asyltas.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import kz.asyltas.model.Catalog;
import kz.asyltas.model.Necklace;
import kz.asyltas.model.NecklaceItem;

public class NecklaceDatabase extends SQLiteOpenHelper {
  private static final String TABLE = "necklace_items";
  private final Context context;

  public NecklaceDatabase(Context context) {
    this(context, "asyl_tas.db");
  }

  NecklaceDatabase(Context context, String name) {
    super(context, name, null, 3);
    this.context = context.getApplicationContext();
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    createCollections(db);
    createAccounts(db);
    db.execSQL(
        "CREATE TABLE "
            + TABLE
            + " (stone_id INTEGER PRIMARY KEY,"
            + " quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99))");

    // Ескі нұсқадағы алқаны дерекқор алғаш құрылғанда көшіреміз.
    Necklace previous = new Necklace();
    try {
      String saved =
          context
              .getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
              .getString("necklace", "");
      previous.restore(saved);
    } catch (RuntimeException error) {
      throw new IllegalStateException("Бұрын сақталған алқа оқылмады.", error);
    }
    for (NecklaceItem item : previous.all()) {
      db.insertOrThrow(TABLE, null, valuesFor(item));
    }
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2) createCollections(db);
    if (oldVersion < 3) {
      if (oldVersion >= 2)
        db.execSQL("ALTER TABLE saved_necklaces ADD COLUMN owner INTEGER NOT NULL DEFAULT 0");
      createAccounts(db);
    }
  }

  private void createCollections(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS saved_necklaces (id INTEGER PRIMARY KEY AUTOINCREMENT, name"
            + " TEXT NOT NULL, necklace TEXT NOT NULL, updated INTEGER NOT NULL, owner INTEGER NOT"
            + " NULL DEFAULT 0)");
  }

  private void createAccounts(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS account_necklaces (owner INTEGER PRIMARY KEY, necklace TEXT NOT"
            + " NULL)");
  }

  public static final class Saved {
    public final long id;
    public final String name;
    public final Necklace necklace;

    Saved(long id, String name, String encoded) {
      this.id = id;
      this.name = name;
      this.necklace = new Necklace();
      this.necklace.restore(encoded);
    }
  }

  public List<Saved> collections() {
    List<Saved> result = new ArrayList<>();
    try (Cursor cursor =
        getReadableDatabase()
            .query(
                "saved_necklaces",
                new String[] {"id", "name", "necklace"},
                "owner=?",
                new String[] {String.valueOf(Session.user(context))},
                null,
                null,
                "updated DESC, id DESC")) {
      while (cursor.moveToNext())
        result.add(new Saved(cursor.getLong(0), cursor.getString(1), cursor.getString(2)));
    }
    return result;
  }

  static String checkedName(String input) {
    String name = input.trim();
    if (name.isEmpty() || name.length() > 40)
      throw new IllegalArgumentException("Атауы 1–40 таңбадан тұруы керек.");
    return name;
  }

  public long saveCollection(String name, Necklace necklace) {
    if (necklace.count() == 0) throw new IllegalArgumentException("Алдымен алқаға тас қосыңыз.");
    ContentValues values = new ContentValues();
    values.put("name", checkedName(name));
    values.put("owner", Session.user(context));
    values.put("necklace", necklace.encode());
    values.put("updated", System.currentTimeMillis());
    return getWritableDatabase().insertOrThrow("saved_necklaces", null, values);
  }

  public void renameCollection(long id, String name) {
    ContentValues values = new ContentValues();
    values.put("name", checkedName(name));
    values.put("owner", Session.user(context));
    values.put("updated", System.currentTimeMillis());
    if (getWritableDatabase()
            .update(
                "saved_necklaces",
                values,
                "id=? AND owner=?",
                new String[] {String.valueOf(id), String.valueOf(Session.user(context))})
        != 1) throw new IllegalArgumentException("Жинақ табылмады.");
  }

  public void deleteCollection(long id) {
    getWritableDatabase()
        .delete(
            "saved_necklaces",
            "id=? AND owner=?",
            new String[] {String.valueOf(id), String.valueOf(Session.user(context))});
  }

  public Necklace load() {
    Necklace necklace = new Necklace();
    SQLiteDatabase db = getReadableDatabase();
    if (Session.user(context) > 0) {
      try (Cursor account =
          db.rawQuery(
              "SELECT necklace FROM account_necklaces WHERE owner=?",
              new String[] {String.valueOf(Session.user(context))})) {
        if (account.moveToFirst()) necklace.restore(account.getString(0));
      }
      return necklace;
    }
    try (Cursor cursor =
        db.query(
            TABLE, new String[] {"stone_id", "quantity"}, null, null, null, null, "stone_id")) {
      while (cursor.moveToNext()) {
        int id = cursor.getInt(0);
        int quantity = cursor.getInt(1);
        necklace.add(Catalog.get(id), quantity);
      }
    }
    return necklace;
  }

  public void save(Necklace necklace) {
    SQLiteDatabase db = getWritableDatabase();
    if (Session.user(context) > 0) {
      ContentValues values = new ContentValues();
      values.put("owner", Session.user(context));
      values.put("necklace", necklace.encode());
      db.insertWithOnConflict("account_necklaces", null, values, SQLiteDatabase.CONFLICT_REPLACE);
      return;
    }
    db.beginTransaction();
    try {
      List<Integer> previousIds = new ArrayList<>();
      try (Cursor cursor =
          db.query(TABLE, new String[] {"stone_id"}, null, null, null, null, null)) {
        while (cursor.moveToNext()) {
          previousIds.add(cursor.getInt(0));
        }
      }

      for (NecklaceItem item : necklace.all()) {
        int id = item.getStone().getId();
        ContentValues values = valuesFor(item);
        String[] selection = {String.valueOf(id)};
        int updated = db.update(TABLE, values, "stone_id = ?", selection);
        if (updated == 0) {
          db.insertOrThrow(TABLE, null, values);
        }
        previousIds.remove(Integer.valueOf(id));
      }

      // Жаңа алқада қалмаған тастарды кестеден өшіреміз.
      for (Integer id : previousIds) {
        db.delete(TABLE, "stone_id = ?", new String[] {String.valueOf(id)});
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  private ContentValues valuesFor(NecklaceItem item) {
    ContentValues values = new ContentValues();
    values.put("stone_id", item.getStone().getId());
    values.put("quantity", item.getQuantity());
    return values;
  }
}
