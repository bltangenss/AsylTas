package kz.asyltas.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Test-APK-only fixture. Restores guest data and deletes only the exact allowlisted accounts. */
final class V4UiFixture {
  private static final String[] EMAILS = {"qa4.verify@asyltas.test", "qa4.customer@asyltas.test"};
  private static final String[] CATALOG_TABLES = {"comparison", "recent_stones", "stone_notes"};
  private static final String[] PREFERENCES = {"session", "appearance", "campus"};

  void run(Instrumentation runner, boolean begin) {
    Context context = runner.getTargetContext();
    SharedPreferences saved = context.getSharedPreferences("qa_v4_snapshot", 0);
    Bundle result = new Bundle();
    JSONObject snapshot = null;
    boolean passed = false;
    try (AppStore store = new AppStore(context);
        NecklaceDatabase necklaces = new NecklaceDatabase(context);
        CatalogDatabase catalog = new CatalogDatabase(context)) {
      SQLiteDatabase portalDb = store.getWritableDatabase();
      SQLiteDatabase necklaceDb = necklaces.getWritableDatabase();
      SQLiteDatabase catalogDb = catalog.getWritableDatabase();
      if (begin) {
        if (saved.contains("payload")) throw new IllegalStateException("A v4 UI fixture is already active");
        if (context.getSharedPreferences("qa_v3_snapshot", 0).contains("session"))
          throw new IllegalStateException("The v3 UI fixture must be cleaned up before v4 UI testing");
        for (String email : EMAILS)
          if (store.one("SELECT id FROM users WHERE email=?", email) != null)
            throw new IllegalStateException("QA account already exists; no data was changed: " + email);
        snapshot = new JSONObject();
        snapshot.put("version", 1);
        for (String name : PREFERENCES)
          snapshot.put(name, capturePreferences(context.getSharedPreferences(name, 0)));
        snapshot.put("settings", rows(portalDb, "settings", null));
        snapshot.put("guest_necklace", rows(necklaceDb, "necklace_items", null));
        snapshot.put("guest_collections", rows(necklaceDb, "saved_necklaces", "owner=0"));
        for (String table : CATALOG_TABLES) snapshot.put(table, rows(catalogDb, table, "owner=0"));
        if (!saved.edit().putString("payload", snapshot.toString()).commit())
          throw new IllegalStateException("Could not persist the fixture snapshot");
        result.putString("stream", "V4 UI fixture begun. Session, store settings, guest necklace/collections, guest comparison/history/notes and appearance/campus preferences preserved. Allowlisted emails: " + EMAILS[0] + ", " + EMAILS[1] + ".\n");
      } else {
        if (!saved.contains("payload")) throw new IllegalStateException("No active v4 UI fixture to clean up");
        snapshot = new JSONObject(saved.getString("payload", "{}"));
        if (snapshot.getInt("version") != 1) throw new IllegalStateException("Unknown fixture version");
        int deleted = 0;
        for (String email : EMAILS) {
          AppStore.Row user = store.one("SELECT id FROM users WHERE email=?", email);
          if (user == null) continue;
          String[] owner = {user.text("id")};
          // Delete the account last, so a failed cleanup can be retried using the same allowlist.
          catalogDb.beginTransaction();
          try {
            for (String table : CATALOG_TABLES) catalogDb.delete(table, "owner=?", owner);
            catalogDb.setTransactionSuccessful();
          } finally { catalogDb.endTransaction(); }
          necklaceDb.beginTransaction();
          try {
            necklaceDb.delete("account_necklaces", "owner=?", owner);
            necklaceDb.delete("saved_necklaces", "owner=?", owner);
            necklaceDb.setTransactionSuccessful();
          } finally { necklaceDb.endTransaction(); }
          portalDb.beginTransaction();
          try {
            for (String table : new String[]{"favorites", "orders", "media", "messages", "reviews"})
              portalDb.delete(table, "owner=?", owner);
            portalDb.delete("users", "id=?", owner);
            portalDb.setTransactionSuccessful();
          } finally { portalDb.endTransaction(); }
          deleted++;
        }
        portalDb.beginTransaction();
        try {
          restoreRows(portalDb, "settings", null, snapshot.getJSONArray("settings"));
          portalDb.setTransactionSuccessful();
        } finally { portalDb.endTransaction(); }
        necklaceDb.beginTransaction();
        try {
          restoreRows(necklaceDb, "necklace_items", null, snapshot.getJSONArray("guest_necklace"));
          restoreRows(necklaceDb, "saved_necklaces", "owner=0", snapshot.getJSONArray("guest_collections"));
          necklaceDb.setTransactionSuccessful();
        } finally { necklaceDb.endTransaction(); }
        catalogDb.beginTransaction();
        try {
          for (String table : CATALOG_TABLES) restoreRows(catalogDb, table, "owner=0", snapshot.getJSONArray(table));
          catalogDb.setTransactionSuccessful();
        } finally { catalogDb.endTransaction(); }
        for (String name : PREFERENCES)
          restorePreferences(context.getSharedPreferences(name, 0), snapshot.getJSONObject(name));
        for (String email : EMAILS)
          if (store.one("SELECT id FROM users WHERE email=?", email) != null)
            throw new AssertionError("Allowlisted QA account remains");
        assertRows(portalDb, "settings", null, snapshot.getJSONArray("settings"));
        assertRows(necklaceDb, "necklace_items", null, snapshot.getJSONArray("guest_necklace"));
        assertRows(necklaceDb, "saved_necklaces", "owner=0", snapshot.getJSONArray("guest_collections"));
        for (String table : CATALOG_TABLES) assertRows(catalogDb, table, "owner=0", snapshot.getJSONArray(table));
        if (!saved.edit().clear().commit()) throw new IllegalStateException("Could not remove completed fixture marker");
        result.putString("stream", "V4 UI cleanup passed. Removed " + deleted + " allowlisted QA account(s) and only their records. Restored guest necklace/collections/comparison/history/notes, original session, store settings and appearance/campus preferences. Other accounts were not modified.\n");
      }
      passed = true;
    } catch (Throwable error) {
      result.putString("stream", "FAIL V4 UI fixture: " + error + ". Snapshot retained for retry.\n");
    } finally {
      // Restore the original login even after an interrupted cleanup; never leave a deleted QA login active.
      if (!begin && !passed && snapshot != null && snapshot.has("session")) {
        try { restorePreferences(context.getSharedPreferences("session", 0), snapshot.getJSONObject("session")); }
        catch (Exception error) { passed = false; result.putString("stream", result.getString("stream", "") + "Session restore failed: " + error + "\n"); }
      }
    }
    runner.finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }

  private static JSONArray rows(SQLiteDatabase db, String table, String where) throws Exception {
    JSONArray result = new JSONArray();
    try (Cursor cursor = db.query(table, null, where, null, null, null, "rowid")) {
      while (cursor.moveToNext()) {
        JSONObject row = new JSONObject();
        for (int index = 0; index < cursor.getColumnCount(); index++) {
          Object value;
          switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_NULL: value = JSONObject.NULL; break;
            case Cursor.FIELD_TYPE_INTEGER: value = cursor.getLong(index); break;
            case Cursor.FIELD_TYPE_FLOAT: value = cursor.getDouble(index); break;
            case Cursor.FIELD_TYPE_STRING: value = cursor.getString(index); break;
            default: throw new IllegalStateException("Unexpected binary value in fixture table");
          }
          row.put(cursor.getColumnName(index), value);
        }
        result.put(row);
      }
    }
    return result;
  }

  private static void restoreRows(SQLiteDatabase db, String table, String where, JSONArray rows) throws Exception {
    db.delete(table, where, null);
    for (int index = 0; index < rows.length(); index++) {
      JSONObject row = rows.getJSONObject(index); ContentValues values = new ContentValues();
      Iterator<String> keys = row.keys();
      while (keys.hasNext()) {
        String key = keys.next(); Object value = row.get(key);
        if (value == JSONObject.NULL) values.putNull(key);
        else if (value instanceof Number) {
          if (value instanceof Float || value instanceof Double) values.put(key, ((Number) value).doubleValue());
          else values.put(key, ((Number) value).longValue());
        } else values.put(key, (String) value);
      }
      db.insertOrThrow(table, null, values);
    }
  }

  private static void assertRows(SQLiteDatabase db, String table, String where, JSONArray expected) throws Exception {
    if (!rows(db, table, where).toString().equals(expected.toString()))
      throw new AssertionError("Fixture rows differ after restore: " + table);
  }

  private static JSONObject capturePreferences(SharedPreferences preferences) throws Exception {
    JSONObject values = new JSONObject();
    for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
      Object value = entry.getValue(); JSONObject encoded = new JSONObject();
      if (value instanceof String) encoded.put("type", "string").put("value", value);
      else if (value instanceof Boolean) encoded.put("type", "boolean").put("value", value);
      else if (value instanceof Integer) encoded.put("type", "integer").put("value", value);
      else if (value instanceof Long) encoded.put("type", "long").put("value", value);
      else if (value instanceof Float) encoded.put("type", "float").put("value", value.toString());
      else if (value instanceof Set) encoded.put("type", "strings").put("value", new JSONArray((Set<?>) value));
      else throw new IllegalStateException("Unknown preference type");
      values.put(entry.getKey(), encoded);
    }
    return values;
  }

  private static void restorePreferences(SharedPreferences preferences, JSONObject values) throws Exception {
    SharedPreferences.Editor editor = preferences.edit().clear(); Iterator<String> keys = values.keys();
    while (keys.hasNext()) {
      String key = keys.next(); JSONObject item = values.getJSONObject(key);
      switch (item.getString("type")) {
        case "string": editor.putString(key, item.getString("value")); break;
        case "boolean": editor.putBoolean(key, item.getBoolean("value")); break;
        case "integer": editor.putInt(key, item.getInt("value")); break;
        case "long": editor.putLong(key, item.getLong("value")); break;
        case "float": editor.putFloat(key, Float.parseFloat(item.getString("value"))); break;
        case "strings":
          Set<String> entries = new HashSet<>(); JSONArray array = item.getJSONArray("value");
          for (int index = 0; index < array.length(); index++) entries.add(array.getString(index));
          editor.putStringSet(key, entries); break;
        default: throw new IllegalStateException("Unknown stored preference type");
      }
    }
    if (!editor.commit()) throw new IllegalStateException("Could not restore preferences");
  }
}
