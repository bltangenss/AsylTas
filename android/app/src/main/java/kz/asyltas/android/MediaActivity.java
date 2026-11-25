package kz.asyltas.android;

import android.graphics.*;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.io.InputStream;

public class MediaActivity extends BaseActivity {
  private MediaPlayer audio;
  private VideoView video;
  private final Handler timer = new Handler(Looper.getMainLooper());
  private int position;
  private boolean ready, playing, failed;
  private Button toggle;
  private SeekBar seek;
  private TextView time;
  private long mediaId;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    prepare(R.layout.activity_hub, "Медиа");
    mediaId = getIntent().getLongExtra("media_id", -1);
    position = state == null ? 0 : state.getInt("position");
  }

  @Override
  protected void refresh() {
    releasePlayback();
    failed = false;
    if (!Session.active(this) || store.user() == null) return;
    android.widget.FrameLayout frame = findViewById(R.id.hub_content);
    frame.removeAllViews();
    ScrollView scroll = new ScrollView(this);
    LinearLayout page = Ui.column(this);
    int pad = Ui.dp(this, 20);
    page.setPadding(pad, pad, pad, pad);
    scroll.addView(page);
    frame.addView(scroll);
    try {
      AppStore.Row item = store.media(mediaId);
      getActionBar().setTitle(item.text("title"));
      Uri uri = Uri.parse(item.text("uri"));
      if (item.text("kind").equals("image")) {
        Movie decoded = null;
        if ("image/gif".equals(getContentResolver().getType(uri))) {
          try (android.content.res.AssetFileDescriptor f =
              getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (f != null && f.getLength() > 10 * 1024 * 1024)
              throw new IllegalArgumentException("GIF too large");
          }
          try (InputStream stream = getContentResolver().openInputStream(uri)) {
            decoded = Movie.decodeStream(stream);
          }
        }
        final Movie movie = decoded;
        if (movie != null && movie.duration() > 0) {
          View animation =
              new View(this) {
                long start;
                Paint paint = new Paint(3);

                @Override
                protected void onDraw(Canvas c) {
                  if (start == 0) start = SystemClock.uptimeMillis();
                  movie.setTime(
                      (int) ((SystemClock.uptimeMillis() - start) % Math.max(1, movie.duration())));
                  float scale =
                      Math.min(
                          (float) getWidth() / movie.width(), (float) getHeight() / movie.height());
                  c.save();
                  c.translate(
                      (getWidth() - movie.width() * scale) / 2,
                      (getHeight() - movie.height() * scale) / 2);
                  c.scale(scale, scale);
                  movie.draw(c, 0, 0, paint);
                  c.restore();
                  postInvalidateDelayed(40);
                }
              };
          page.addView(animation, new LinearLayout.LayoutParams(-1, Ui.dp(this, 350)));
        } else {
          BitmapFactory.Options options = new BitmapFactory.Options();
          options.inJustDecodeBounds = true;
          try (InputStream stream = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, options);
          }
          if (options.outWidth < 1 || options.outHeight < 1)
            throw new IllegalArgumentException("Invalid image");
          options.inSampleSize = 1;
          while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1600)
            options.inSampleSize *= 2;
          options.inJustDecodeBounds = false;
          Bitmap bitmap;
          try (InputStream stream = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
          }
          if (bitmap == null) throw new IllegalArgumentException("Invalid image");
          ImageView picture = new ImageView(this);
          picture.setContentDescription(item.text("title"));
          picture.setAdjustViewBounds(true);
          picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
          picture.setImageBitmap(bitmap);
          page.addView(picture, new LinearLayout.LayoutParams(-1, Ui.dp(this, 350)));
        }
        Ui.text(page, item.text("title"), 20, true);
        return;
      }
      if (item.text("kind").equals("video")) {
        video = new VideoView(this);
        page.addView(video, new LinearLayout.LayoutParams(-1, Ui.dp(this, 230)));
        video.setVideoURI(uri);
        video.setOnPreparedListener(
            player -> {
              applySound(player);
              ready = true;
              video.seekTo(position);
              update();
            });
        video.setOnErrorListener(
            (p, w, e) -> {
              mediaError("Видео ашылмады. Басқа файлды таңдаңыз.");
              return true;
            });
        video.setOnCompletionListener(
            p -> {
              playing = false;
              toggle.setText("Ойнату");
            });
      } else {
        Ui.text(page, "Аудио материал", 13, false);
        Ui.text(page, item.text("title"), 26, true);
        audio = new MediaPlayer();
        audio.setDataSource(this, uri);
        audio.setOnPreparedListener(
            p -> {
              ready = true;
              applySound(p);
              p.seekTo(position);
              update();
            });
        audio.setOnCompletionListener(
            p -> {
              playing = false;
              toggle.setText("Ойнату");
            });
        audio.setOnErrorListener(
            (p, w, e) -> {
              mediaError("Аудио ашылмады. Басқа файлды таңдаңыз.");
              return true;
            });
        audio.prepareAsync();
      }
      seek = new SeekBar(this);
      page.addView(seek, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
      time = Ui.text(page, "Дайындалып жатыр…", 13, false);
      toggle =
          Ui.button(
              page,
              "Ойнату",
              true,
              () -> {
                if (!ready) return;
                if (playing) {
                  if (audio != null) audio.pause();
                  if (video != null) video.pause();
                } else {
                  if (audio != null) audio.start();
                  if (video != null) video.start();
                }
                playing = !playing;
                toggle.setText(playing ? "Кідірту" : "Ойнату");
              });
      seek.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) {}

            public void onStopTrackingTouch(SeekBar s) {}

            public void onProgressChanged(SeekBar s, int value, boolean user) {
              if (user && ready) {
                position = value;
                if (audio != null) audio.seekTo(value);
                if (video != null) video.seekTo(value);
              }
            }
          });
      timer.post(
          new Runnable() {
            public void run() {
              update();
              timer.postDelayed(this, 500);
            }
          });
    } catch (Exception e) {
      releasePlayback();
      Ui.text(page, "Файл қолжетімсіз немесе тым үлкен. Басқа файлды таңдаңыз.", 17, true);
    }
  }

  private void applySound(MediaPlayer p) {
    float volume = getSharedPreferences("appearance", 0).getBoolean("sound", true) ? 1f : 0f;
    p.setVolume(volume, volume);
  }

  private void mediaError(String description) {
    if (failed) return;
    failed = true;
    ready = false;
    playing = false;
    timer.removeCallbacksAndMessages(null);
    if (time != null) time.setText(description);
    if (toggle != null) toggle.setEnabled(false);
    message(description);
  }

  private void update() {
    if (!ready || seek == null || time == null) return;
    try {
      int duration = audio != null ? audio.getDuration() : video.getDuration();
      position = audio != null ? audio.getCurrentPosition() : video.getCurrentPosition();
      if (seek.getMax() != duration) seek.setMax(duration);
      if (seek.getProgress() != position) seek.setProgress(position);
      String caption = clock(position) + " / " + clock(duration);
      if (!android.text.TextUtils.equals(time.getText(), caption)) time.setText(caption);
    } catch (IllegalStateException ignored) {
    }
  }

  private String clock(int ms) {
    return String.format(java.util.Locale.ROOT, "%d:%02d", ms / 60000, ms / 1000 % 60);
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    update();
    state.putInt("position", position);
    super.onSaveInstanceState(state);
  }

  private void releasePlayback() {
    update();
    timer.removeCallbacksAndMessages(null);
    if (audio != null) {
      audio.release();
      audio = null;
    }
    if (video != null) {
      video.stopPlayback();
      video = null;
    }
    ready = false;
    playing = false;
    seek = null;
    time = null;
  }

  @Override
  protected void onPause() {
    releasePlayback();
    super.onPause();
  }
}
