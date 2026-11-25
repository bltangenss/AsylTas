package kz.asyltas.android;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.util.Base64;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import kz.asyltas.model.*;

/** Local account, order and media database. Prices always come from the unchanged model. */
public class AppStore extends SQLiteOpenHelper {
  private final Context context;
  static final int ROUNDS = 120000;
  public static final String[] STATUSES = {
    "Жоба", "Қабылданды", "Дайын", "Аяқталды", "Бас тартылды"
  };

  public AppStore(Context c) {
    this(c, "asyl_portal.db");
  }

  AppStore(Context c, String name) {
    super(c, name, null, 1);
    context = c.getApplicationContext();
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,email TEXT NOT"
            + " NULL UNIQUE,phone TEXT NOT NULL DEFAULT '',address TEXT NOT NULL DEFAULT '',salt"
            + " TEXT NOT NULL,hash TEXT NOT NULL,admin INTEGER NOT NULL DEFAULT 0,failures INTEGER"
            + " NOT NULL DEFAULT 0,locked INTEGER NOT NULL DEFAULT 0)");
    db.execSQL(
        "CREATE TABLE favorites(owner INTEGER NOT NULL,stone INTEGER NOT NULL,PRIMARY"
            + " KEY(owner,stone))");
    db.execSQL(
        "CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT,owner INTEGER NOT NULL,necklace"
            + " TEXT NOT NULL,total TEXT NOT NULL,delivery TEXT NOT NULL,address TEXT NOT"
            + " NULL,phone TEXT NOT NULL,gift INTEGER NOT NULL,note TEXT NOT NULL,date TEXT NOT"
            + " NULL,status INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE media(id INTEGER PRIMARY KEY AUTOINCREMENT,owner INTEGER NOT NULL,title TEXT"
            + " NOT NULL,kind TEXT NOT NULL,uri TEXT NOT NULL)");
    db.execSQL(
        "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,owner INTEGER NOT NULL,subject"
            + " TEXT NOT NULL,body TEXT NOT NULL,reply TEXT NOT NULL DEFAULT '',created INTEGER NOT"
            + " NULL)");
    db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
    db.insert("settings", null, values("key", "address", "value", "Алматы, Қазақстан"));
    db.insert("settings", null, values("key", "address_example", "value", "1"));
    db.execSQL(
        "CREATE TABLE reviews(owner INTEGER NOT NULL,stone INTEGER NOT NULL,rating INTEGER NOT"
            + " NULL,body TEXT NOT NULL,PRIMARY KEY(owner,stone))");
  }

  @Override
  public void onUpgrade(SQLiteDatabase d, int a, int b) {}

  public static class Row extends HashMap<String, String> {
    public String text(String key) {
      return getOrDefault(key, "");
    }

    public long number(String key) {
      return Long.parseLong(text(key));
    }
  }

  List<Row> rows(String sql, String... args) {
    List<Row> result = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
      while (c.moveToNext()) {
        Row row = new Row();
        for (int i = 0; i < c.getColumnCount(); i++)
          row.put(c.getColumnName(i), c.isNull(i) ? "" : c.getString(i));
        result.add(row);
      }
    }
    return result;
  }

  Row one(String sql, String... args) {
    List<Row> list = rows(sql, args);
    return list.isEmpty() ? null : list.get(0);
  }

  static ContentValues values(String... pairs) {
    ContentValues v = new ContentValues();
    for (int i = 0; i < pairs.length; i += 2) v.put(pairs[i], pairs[i + 1]);
    return v;
  }

  static String required(String input, int max, String label) {
    String s = input == null ? "" : input.trim();
    if (s.isEmpty() || s.length() > max)
      throw new IllegalArgumentException(label + ": 1–" + max + " таңба енгізіңіз.");
    return s;
  }

  static String email(String input) {
    String s = required(input, 120, "Email").toLowerCase(Locale.ROOT);
    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(s).matches())
      throw new IllegalArgumentException("Email мекенжайын дұрыс енгізіңіз.");
    return s;
  }

  static String hash(String password, byte[] salt) {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256);
    try {
      return Base64.encodeToString(
          SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded(),
          Base64.NO_WRAP);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Құпиясөз өңделмеді.", e);
    } finally {
      spec.clearPassword();
    }
  }

  static void password(String value) {
    if (value == null || value.length() < 8 || value.length() > 128)
      throw new IllegalArgumentException("Құпиясөз 8–128 таңбадан тұруы керек.");
  }

  public long register(String name, String email, String password) {
    String n = required(name, 60, "Атыңыз"), e = email(email);
    password(password);
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    String h = hash(password, salt);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      if (one("SELECT id FROM users WHERE email=?", e) != null)
        throw new IllegalArgumentException("Бұл email тіркелген. Кіру бетін пайдаланыңыз.");
      boolean first = one("SELECT id FROM users LIMIT 1") == null;
      long id =
          db.insertOrThrow(
              "users",
              null,
              values(
                  "name",
                  n,
                  "email",
                  e,
                  "salt",
                  Base64.encodeToString(salt, Base64.NO_WRAP),
                  "hash",
                  h,
                  "admin",
                  first ? "1" : "0"));
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  public long login(String email, String password) {
    String e = email(email);
    Row user = one("SELECT * FROM users WHERE email=?", e);
    if (user != null && user.number("locked") > System.currentTimeMillis())
      throw new IllegalArgumentException(
          "Бірнеше қате әрекет болды. Бір минуттан кейін қайталаңыз.");
    byte[] salt = user == null ? new byte[16] : Base64.decode(user.text("salt"), Base64.NO_WRAP);
    String actual = hash(password == null ? "" : password, salt);
    if (user == null
        || !MessageDigest.isEqual(
            actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            user.text("hash").getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      if (user != null) {
        long failures = user.number("failures") + 1;
        getWritableDatabase()
            .update(
                "users",
                values(
                    "failures",
                    String.valueOf(failures >= 5 ? 0 : failures),
                    "locked",
                    String.valueOf(failures >= 5 ? System.currentTimeMillis() + 60000 : 0)),
                "id=?",
                new String[] {user.text("id")});
      }
      throw new IllegalArgumentException("Email немесе құпиясөз қате.");
    }
    getWritableDatabase()
        .update(
            "users",
            values("failures", "0", "locked", "0"),
            "id=?",
            new String[] {user.text("id")});
    return user.number("id");
  }

  public Row user() {
    return user(Session.user(context));
  }

  Row user(long id) {
    return id == 0
        ? null
        : one("SELECT id,name,email,phone,address,admin FROM users WHERE id=?", String.valueOf(id));
  }

  long owner() {
    long id = Session.user(context);
    if (user(id) == null) throw new IllegalArgumentException("Алдымен аккаунтқа кіріңіз.");
    return id;
  }

  public boolean admin() {
    Row u = user();
    return u != null && u.number("admin") == 1;
  }

  void requireAdmin() {
    owner();
    if (!admin()) throw new IllegalArgumentException("Бұл әрекет әкімшіге қолжетімді.");
  }

  public void updateProfile(String name, String phone, String address) {
    long id = owner();
    String p = phone.trim(), a = address.trim();
    if (p.length() > 30 || a.length() > 200)
      throw new IllegalArgumentException("Телефон немесе мекенжай тым ұзын.");
    getWritableDatabase()
        .update(
            "users",
            values("name", required(name, 60, "Атыңыз"), "phone", p, "address", a),
            "id=?",
            new String[] {String.valueOf(id)});
  }

  public void changePassword(String old, String next) {
    Row u = user();
    if (u == null) throw new IllegalArgumentException("Алдымен кіріңіз.");
    login(u.text("email"), old);
    password(next);
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    getWritableDatabase()
        .update(
            "users",
            values("salt", Base64.encodeToString(salt, Base64.NO_WRAP), "hash", hash(next, salt)),
            "id=?",
            new String[] {u.text("id")});
  }

  public Set<Integer> favorites() {
    Set<Integer> ids = new HashSet<>();
    for (Row r :
        rows("SELECT stone FROM favorites WHERE owner=?", String.valueOf(Session.user(context))))
      ids.add((int) r.number("stone"));
    return ids;
  }

  public boolean toggleFavorite(int stone) {
    Catalog.get(stone);
    long id = owner();
    SQLiteDatabase db = getWritableDatabase();
    if (db.delete(
            "favorites",
            "owner=? AND stone=?",
            new String[] {String.valueOf(id), String.valueOf(stone)})
        > 0) return false;
    db.insertOrThrow(
        "favorites", null, values("owner", String.valueOf(id), "stone", String.valueOf(stone)));
    return true;
  }

  public long createOrder(
      Necklace necklace,
      String delivery,
      String address,
      String phone,
      boolean gift,
      String note,
      String date) {
    long id = owner();
    if (necklace.count() == 0) throw new IllegalArgumentException("Алдымен алқаға тас қосыңыз.");
    if (!delivery.equals("Өзім алып кетемін") && !delivery.equals("Жеткізу"))
      throw new IllegalArgumentException("Алу тәсілін таңдаңыз.");
    String p = required(phone, 30, "Телефон");
    if (p.replaceAll("\\D", "").length() < 10)
      throw new IllegalArgumentException("Телефон нөмірін толық енгізіңіз.");
    String a =
        delivery.equals("Жеткізу") ? required(address, 200, "Мекенжай") : setting("address", "");
    if (note.length() > 500 || date.length() > 30)
      throw new IllegalArgumentException("Ескерту тым ұзын.");
    return getWritableDatabase()
        .insertOrThrow(
            "orders",
            null,
            values(
                "owner",
                String.valueOf(id),
                "necklace",
                necklace.encode(),
                "total",
                necklace.totalCost().toPlainString(),
                "delivery",
                delivery,
                "address",
                a,
                "phone",
                p,
                "gift",
                gift ? "1" : "0",
                "note",
                note.trim(),
                "date",
                date,
                "created",
                String.valueOf(System.currentTimeMillis())));
  }

  public List<Row> orders(boolean all) {
    if (all) requireAdmin();
    else owner();
    return rows(
        "SELECT o.*,u.name FROM orders o JOIN users u ON u.id=o.owner"
            + (all ? "" : " WHERE o.owner=?")
            + " ORDER BY o.created DESC,o.id DESC",
        all ? new String[] {} : new String[] {String.valueOf(owner())});
  }

  public Row order(long id) {
    Row r =
        one(
            "SELECT o.*,u.name FROM orders o JOIN users u ON u.id=o.owner WHERE o.id=?",
            String.valueOf(id));
    if (r == null || r.number("owner") != owner() && !admin())
      throw new IllegalArgumentException("Өтінім табылмады.");
    return r;
  }

  public void status(long id, int status) {
    Row r = order(id);
    if (status < 0 || status >= STATUSES.length)
      throw new IllegalArgumentException("Мәртебе қате.");
    if (!admin() && (status != 4 || r.number("status") != 0))
      throw new IllegalArgumentException("Тек жоба күйіндегі өз өтініміңізден бас тартуға болады.");
    getWritableDatabase()
        .update(
            "orders",
            values("status", String.valueOf(status)),
            "id=?",
            new String[] {String.valueOf(id)});
  }

  public String setting(String key, String fallback) {
    Row r = one("SELECT value FROM settings WHERE key=?", key);
    return r == null ? fallback : r.text("value");
  }

  public void settings(String address, String phone, String hours) {
    requireAdmin();
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      for (String[] p :
          new String[][] {
            {"address", required(address, 200, "Дүкен мекенжайы")},
            {"phone", phone.trim()},
            {"hours", hours.trim()}
          })
        db.insertWithOnConflict(
            "settings", null, values("key", p[0], "value", p[1]), SQLiteDatabase.CONFLICT_REPLACE);
      db.insertWithOnConflict(
          "settings",
          null,
          values("key", "address_example", "value", "0"),
          SQLiteDatabase.CONFLICT_REPLACE);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public long addMedia(String title, String kind, String uri) {
    long id = owner();
    if (!Arrays.asList("audio", "video", "image").contains(kind))
      throw new IllegalArgumentException("Файл түрі қолдау таппады.");
    return getWritableDatabase()
        .insertOrThrow(
            "media",
            null,
            values(
                "owner",
                String.valueOf(id),
                "title",
                required(title, 80, "Атау"),
                "kind",
                kind,
                "uri",
                required(uri, 2000, "Файл")));
  }

  public List<Row> media() {
    return rows("SELECT * FROM media WHERE owner=? ORDER BY id DESC", String.valueOf(owner()));
  }

  public Row media(long id) {
    Row row =
        one(
            "SELECT * FROM media WHERE id=? AND owner=?",
            String.valueOf(id),
            String.valueOf(owner()));
    if (row == null) throw new IllegalArgumentException("Материал табылмады.");
    return row;
  }

  public void renameMedia(long id, String title) {
    media(id);
    getWritableDatabase()
        .update(
            "media",
            values("title", required(title, 80, "Атау")),
            "id=?",
            new String[] {String.valueOf(id)});
  }

  public void deleteMedia(long id) {
    media(id);
    getWritableDatabase().delete("media", "id=?", new String[] {String.valueOf(id)});
  }

  public void message(String subject, String body) {
    getWritableDatabase()
        .insertOrThrow(
            "messages",
            null,
            values(
                "owner",
                String.valueOf(owner()),
                "subject",
                required(subject, 80, "Тақырып"),
                "body",
                required(body, 1000, "Хабарлама"),
                "created",
                String.valueOf(System.currentTimeMillis())));
  }

  public List<Row> messages() {
    owner();
    return rows(
        "SELECT m.*,u.name FROM messages m JOIN users u ON u.id=m.owner"
            + (admin() ? "" : " WHERE m.owner=?")
            + " ORDER BY m.id DESC",
        admin() ? new String[] {} : new String[] {String.valueOf(owner())});
  }

  public void reply(long id, String reply) {
    requireAdmin();
    getWritableDatabase()
        .update(
            "messages",
            values("reply", required(reply, 1000, "Жауап")),
            "id=?",
            new String[] {String.valueOf(id)});
  }

  public void review(int stone, int rating, String body) {
    Catalog.get(stone);
    if (rating < 1 || rating > 5) throw new IllegalArgumentException("1–5 жұлдыз таңдаңыз.");
    getWritableDatabase()
        .insertWithOnConflict(
            "reviews",
            null,
            values(
                "owner",
                String.valueOf(owner()),
                "stone",
                String.valueOf(stone),
                "rating",
                String.valueOf(rating),
                "body",
                required(body, 500, "Пікір")),
            SQLiteDatabase.CONFLICT_REPLACE);
  }

  public List<Row> reviews(int stone) {
    return rows(
        "SELECT r.*,u.name FROM reviews r JOIN users u ON u.id=r.owner WHERE r.stone=? ORDER BY"
            + " r.owner",
        String.valueOf(stone));
  }

  public List<Row> users() {
    requireAdmin();
    return rows("SELECT id,name,email,phone,admin FROM users ORDER BY id");
  }
}
