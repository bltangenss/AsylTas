package kz.asyltas.android;

import android.content.Intent;
import android.os.Bundle;

public class HubActivity extends BaseActivity {
  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    prepare(R.layout.activity_hub, "Профиль");
    if (state == null)
      show(
          getIntent().getStringExtra("page") == null
              ? "profile"
              : getIntent().getStringExtra("page"),
          false);
  }

  void show(String page, boolean back) {
    PortalFragment fragment = new PortalFragment();
    Bundle args = new Bundle();
    args.putString("page", page);
    fragment.setArguments(args);
    android.app.FragmentTransaction t =
        getFragmentManager().beginTransaction().replace(R.id.hub_content, fragment);
    if (back) t.addToBackStack(null);
    t.commit();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    getFragmentManager().popBackStackImmediate(null, android.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    String page = intent.getStringExtra("page");
    show(page == null ? "profile" : page, false);
  }

  @Override
  protected void refresh() {}

  @Override
  public void onBackPressed() {
    if (getFragmentManager().getBackStackEntryCount() > 0) getFragmentManager().popBackStack();
    else super.onBackPressed();
  }
}
