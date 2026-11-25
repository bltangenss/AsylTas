package kz.asyltas.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import kz.asyltas.model.Necklace;

public class LoginActivity extends Activity {
  private boolean registration, busy;
  private EditText name, email, password;
  private TextView error;
  private CheckBox copy;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    if (Session.active(this)) {
      home();
      return;
    }
    setContentView(R.layout.activity_login);
    Ui.insets(findViewById(R.id.login_root));
    name = findViewById(R.id.login_name);
    email = findViewById(R.id.login_email);
    password = findViewById(R.id.login_password);
    error = findViewById(R.id.login_error);
    copy = findViewById(R.id.login_copy);
    ((CheckBox) findViewById(R.id.login_show))
        .setOnCheckedChangeListener(
            (b, checked) -> {
              int position = password.getSelectionStart();
              password.setInputType(
                  InputType.TYPE_CLASS_TEXT
                      | (checked
                          ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                          : InputType.TYPE_TEXT_VARIATION_PASSWORD));
              password.setSelection(Math.max(0, position));
            });
    findViewById(R.id.login_submit).setOnClickListener(v -> submit());
    findViewById(R.id.login_switch)
        .setOnClickListener(
            v -> {
              registration = !registration;
              render();
            });
    findViewById(R.id.login_guest)
        .setOnClickListener(
            v -> {
              Session.enter(this, 0);
              home();
            });
    registration = state != null && state.getBoolean("register");
    render();
  }

  private void render() {
    name.setVisibility(registration ? View.VISIBLE : View.GONE);
    copy.setVisibility(registration ? View.VISIBLE : View.GONE);
    ((TextView) findViewById(R.id.login_title))
        .setText(registration ? "Жеке кеңістік ашыңыз" : "Қош келдіңіз");
    ((TextView) findViewById(R.id.login_submit)).setText(registration ? "Тіркелу" : "Кіру");
    ((TextView) findViewById(R.id.login_switch))
        .setText(registration ? "Аккаунтыңыз бар ма? Кіру" : "Жаңа аккаунт ашу");
    error.setVisibility(View.GONE);
    ((TextView) findViewById(R.id.login_note))
        .setText(
            registration
                ? "Аккаунт осы құрылғыда сақталады. Алғашқы тіркелген аккаунт дүкен параметрлері"
                      + " мен өтінімдерді басқарады."
                : "Аккаунт осы құрылғыда сақталады. Каталогты тіркелмей де қарауға болады.");
  }

  private void submit() {
    if (busy) return;
    busy = true;
    error.setVisibility(View.GONE);
    findViewById(R.id.login_progress).setVisibility(View.VISIBLE);
    for (int id : new int[] {R.id.login_submit, R.id.login_switch, R.id.login_guest})
      findViewById(id).setEnabled(false);
    final String n = Ui.value(name), e = Ui.value(email), p = password.getText().toString();
    final boolean register = registration, importGuest = copy.isChecked();
    new Thread(
            () -> {
              long id = 0;
              String problem = null;
              Necklace guest = null;
              try (AppStore store = new AppStore(getApplicationContext());
                  NecklaceDatabase necklaces = new NecklaceDatabase(getApplicationContext())) {
                if (register && importGuest) guest = necklaces.load();
                id = register ? store.register(n, e, p) : store.login(e, p);
              } catch (IllegalArgumentException ex) {
                problem = ex.getMessage();
              } catch (RuntimeException ex) {
                problem = "Кіру орындалмады. Қайта көріңіз.";
              }
              final long user = id;
              final String message = problem;
              final Necklace previous = guest;
              runOnUiThread(
                  () -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy = false;
                    findViewById(R.id.login_progress).setVisibility(View.GONE);
                    for (int key :
                        new int[] {R.id.login_submit, R.id.login_switch, R.id.login_guest})
                      findViewById(key).setEnabled(true);
                    if (message != null) {
                      error.setText(message);
                      error.setVisibility(View.VISIBLE);
                      return;
                    }
                    Session.enter(this, user);
                    if (previous != null && previous.count() > 0)
                      try (NecklaceDatabase db = new NecklaceDatabase(this)) {
                        db.save(previous);
                      } catch (RuntimeException ex) {
                        Toast.makeText(
                                this,
                                "Қонақ алқасы көшірілмеді. Ол қонақ режимінде сақталған.",
                                Toast.LENGTH_LONG)
                            .show();
                      }
                    home();
                  });
            })
        .start();
  }

  private void home() {
    startActivity(
        new Intent(this, HomeActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    finish();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    b.putBoolean("register", registration);
    super.onSaveInstanceState(b);
  }
}
