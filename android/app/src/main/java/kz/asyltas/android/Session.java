package kz.asyltas.android;

import android.content.Context;

final class Session {
  static long user(Context c) {
    return c.getSharedPreferences("session", 0).getLong("user", 0);
  }

  static boolean active(Context c) {
    return user(c) > 0 || c.getSharedPreferences("session", 0).getBoolean("guest", false);
  }

  static void enter(Context c, long id) {
    c.getSharedPreferences("session", 0)
        .edit()
        .putLong("user", id)
        .putBoolean("guest", id == 0)
        .apply();
  }

  static void clear(Context c) {
    c.getSharedPreferences("session", 0).edit().clear().apply();
  }
}
