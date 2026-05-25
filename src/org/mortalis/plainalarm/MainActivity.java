package org.mortalis.plainalarm;

import java.io.File;
import java.util.Calendar;

import org.home.file_chooser_lib.PickerDialog;
import org.home.file_chooser_lib.FilePickerDialog;
import org.home.file_chooser_lib.DirectoryPickerDialog;

import android.os.Environment;
import android.net.Uri;
import android.provider.Settings;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.widget.SeekBar;
import android.graphics.drawable.LayerDrawable;
import android.graphics.PorterDuff;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.drawable.Animatable;
import android.text.Editable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.WindowManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.app.NotificationManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.vectordrawable.graphics.drawable.Animatable2Compat.AnimationCallback;
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat;

import org.mortalis.plainalarm.components.PlainSliderView;
import org.mortalis.plainalarm.components.TimeInput;


public class MainActivity extends AppCompatActivity {
  
  private static int DRAWABLE_ALARM_PANEL_BACKGROUND_STOP = R.drawable.alarm_switcher_background_stop;
  private static int DRAWABLE_ALARM_PANEL_BACKGROUND_START = R.drawable.alarm_switcher_background_start;
  
  private boolean isAlarmWakeup;
  
  private PickerDialog soundPickerDialog;
  
  private Context context;
  private AudioManager audioManager;
  
  private View parentView;
  
  private LinearLayout soundSelector;
  
  private PlainSliderView volumeSlider;
  private PlainSliderView snoozeSlider;
  
  private TextView volumeValueView;
  private TextView snoozeValueView;
  
  private LinearLayout panelAlarmState;
  
  private ImageView imageAlarmWakeupAnimation;
  
  private TextView soundPathView;
  
  private TimeInput hoursField;
  private TimeInput minutesField;
  
  private Animatable alarmWakeupAnimation;
  
  
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    Fun.logd("MainActivity.onCreate()");
    
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    
    context = this;
    MainService.context = context;
    
    requestAppPermissions(context);
    createNotificationChannel();
    
    init();
    configUI();
    restoreState();
    
    setVolumeControlStream(AudioManager.STREAM_ALARM);
  }
  
  @Override
  protected void onResume() {
    Fun.logd("MainActivity.onResume()");
    super.onResume();
    
    Fun.logd("onResume -> isWakeupIntent: " + isWakeupIntent(getIntent()));
    if (isWakeupIntent(getIntent())) {
      getIntent().putExtra(AlarmReceiver.ALARM_WAKEUP_INTENT, false);
      wakeupAlarm();
    }
  }
  
  @Override
  protected void onNewIntent(Intent intent) {
    Fun.logd("MainActivity.onNewIntent()");
    super.onNewIntent(intent);
    // setIntent(intent);
    Fun.logd("onNewIntent -> isWakeupIntent: " + isWakeupIntent(intent));
    if (isWakeupIntent(intent)) wakeupAlarm();
  }
  
  @Override
  public void onBackPressed() {
    finishAndRemoveTask();
  }
  
  
  // -----------------------------------------------------------
  
  private void requestAppPermissions(Context context) {
    if (Build.VERSION.SDK_INT < 33) {
      String permission = Manifest.permission.WRITE_EXTERNAL_STORAGE;
      boolean isGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
      if (!isGranted) {
        requestPermissions(new String[] { permission }, Vars.APP_PERMISSION_REQUEST_ACCESS_EXTERNAL_STORAGE);
      }
    }
    else {
      var permissions = new String[] {
          Manifest.permission.READ_MEDIA_AUDIO,
          Manifest.permission.POST_NOTIFICATIONS
      };
      
      boolean permissionsGranted = true;
      for (String permission: permissions) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
          permissionsGranted = false;
        }
      }
      
      if (!permissionsGranted) {
        requestPermissions(permissions, Vars.APP_PERMISSION_REQUEST_CODE);
      }
    }
    
    var packageUri = Uri.parse("package:" + getPackageName());
    
    if (!Settings.canDrawOverlays(context)) {
      Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri);
      startActivity(intent);
    }
    
    // --> Change to USE_EXACT_ALARM, grants automatically
    AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
    if (!alarmManager.canScheduleExactAlarms()) {
      Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri);
      startActivity(intent);
    }
  }
  
  private void init() {
    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    
    soundPickerDialog = new PickerDialog(context, true, true);
    soundPickerDialog.setFileSelectedListener(file -> {
      String path = file.getPath();
      Fun.saveSharedPref(context, Vars.PREF_KEY_SOUND_PATH, path);
      soundPathView.setText(path);
    });
    
    Fun.storagePath = Environment.getExternalStorageDirectory().getPath();
  }
  
  private void configUI() {
    // -- Views
    parentView = findViewById(R.id.parentView);
    hoursField = findViewById(R.id.hoursField);
    minutesField = findViewById(R.id.minutesField);

    soundSelector = findViewById(R.id.soundSelector);
    
    volumeSlider = findViewById(R.id.volumeSlider);
    snoozeSlider = findViewById(R.id.snoozeSlider);
    
    volumeValueView = findViewById(R.id.volumeValueView);
    snoozeValueView = findViewById(R.id.snoozeValueView);
    
    panelAlarmState = findViewById(R.id.panelAlarmState);

    imageAlarmWakeupAnimation = findViewById(R.id.imageAlarmWakeupAnimation);

    soundPathView = findViewById(R.id.soundPathView);
    
    var volumeSliderIcon = findViewById(R.id.volumeSliderIcon);
    var snoozeSliderIcon = findViewById(R.id.snoozeSliderIcon);

    // -- Config
    parentView.setOnFocusChangeListener((v, hasFocus) -> {
      if (hasFocus) Fun.hideKeyboard(v);
    });

    hoursField.setMode(TimeInput.Mode.HOURS);
    minutesField.setMode(TimeInput.Mode.MINUTES);

    hoursField.setRange(0, 23);
    minutesField.setRange(0, 59);
    
    hoursField.setOnTwoDigitCompleteListener((view, value) -> {
      if (MainService.isAlarmStarted()) startAlarm();
      Fun.saveSharedPref(context, Vars.PREF_KEY_ALARM_TEXT, getClockText());

      View nextView = hoursField.focusSearch(View.FOCUS_FORWARD);
      if (nextView != null) nextView.post(nextView::requestFocus);
    });

    minutesField.setOnTwoDigitCompleteListener((view, value) -> {
      if (MainService.isAlarmStarted()) startAlarm();
      Fun.saveSharedPref(context, Vars.PREF_KEY_ALARM_TEXT, getClockText());
      unfocusTimeInput();
    });
    
    volumeSliderIcon.setOnClickListener(v -> {
      updateVolume(0, 0);
    });

    snoozeSliderIcon.setOnClickListener(v -> {
      updateSnoozeTime(0);
    });

    soundSelector.setOnClickListener(v -> {
      unfocusTimeInput();
      selectSound();
    });
    
    soundSelector.setOnLongClickListener(v -> {
      unfocusTimeInput();
      Fun.removeSharedPref(context, Vars.PREF_KEY_SOUND_PATH);
      soundPathView.setText(Vars.DEFAULT_SOUND_PATH_LABEL);
      return true;
    });
    
    panelAlarmState.setOnClickListener(v -> {
      unfocusTimeInput();

      boolean alarmStarted = MainService.isAlarmStarted();
      int snoozeTime = (int) Fun.getSharedPrefLong(context, Vars.PREF_KEY_SNOOZE_TIME);
      boolean snoozeOn = snoozeTime > 0;
      if (!isAlarmWakeup || !snoozeOn) {
        updateAlarmState(!alarmStarted);
      }

      if (!alarmStarted) {
        if (Vars.DEBUG_MODE || Vars.DEMO_MODE) {
          Fun.saveSharedPref(context, Vars.PREF_KEY_ALARM_STARTED, true);
          wakeupAlarm();
          return;
        }

        Fun.logd("Starting Alarm");
        startAlarm();
      }
      else {
        Fun.logd("Stopping Alarm");
        stopAlarm();
      }
    });

    initVolumeSlider();
    initSnoozeSlider();
  }

  private void restoreState() {
    try {
      String alarmText = Fun.getSharedPref(context, Vars.PREF_KEY_ALARM_TEXT);
      if (alarmText == null) alarmText = Vars.DEFAULT_ALARM_TEXT;
      if (Vars.DEMO_MODE) alarmText = Vars.DEMO_TIME;
      updateAlarmText(alarmText);
      
      String soundPath = Fun.getSharedPref(context, Vars.PREF_KEY_SOUND_PATH);
      if (soundPath == null) soundPath = Vars.DEFAULT_SOUND_PATH_LABEL;
      if (Vars.DEMO_MODE) soundPath = Vars.DEMO_SOUND_PATH;
      soundPathView.setText(soundPath);
      
      updateAlarmState(MainService.isAlarmStarted());
    }
    catch (Exception e) {
      e.printStackTrace();
    }
  }
  
  private void initVolumeSlider() {
    int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
    int curVolume = (int) Fun.getSharedPrefLong(context, Vars.PREF_KEY_ALARM_VOLUME);

    if (curVolume == -1) curVolume = Vars.DEFAULT_ALARM_VOLUME;
    if (curVolume < 0) curVolume = 0;
    if (curVolume > maxVolume) curVolume = maxVolume;
    
    volumeSlider.setMax(maxVolume);
    updateVolume(curVolume, maxVolume);

    volumeSlider.setProgressChangeListener(value -> {
      updateVolume(value, maxVolume);
    });
  }

  private void initSnoozeSlider() {
    int snoozeSeconds = (int) Fun.getSharedPrefLong(context, Vars.PREF_KEY_SNOOZE_TIME);
    if (snoozeSeconds == -1) snoozeSeconds = Vars.DEFAULT_SNOOZE_TIME;

    boolean snoozeOn = Fun.getSharedPrefBool(context, Vars.PREF_KEY_SNOOZE_ON);
    int snoozeMinutes = snoozeOn ? snoozeSeconds / 60 : 0;

    if (snoozeMinutes < 0) snoozeMinutes = 0;
    if (snoozeMinutes > 60) snoozeMinutes = 60;
    
    snoozeSlider.setMax(60);
    updateSnoozeTime(snoozeMinutes);

    snoozeSlider.setProgressChangeListener(value -> {
      updateSnoozeTime(value);
    });
  }

  // ------------------ Main Engine ------------------

  private void startAlarm(long timeMillis) {
    this.isAlarmWakeup = false;

    int alarmVolume = (int) Fun.getSharedPrefLong(context, Vars.PREF_KEY_ALARM_VOLUME);
    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, alarmVolume, 0);

    MainService.startAlarm(timeMillis);
  }
  
  private void startAlarm() {
    int alarmHour = Integer.parseInt(hoursField.getText().toString());
    int alarmMinute = Integer.parseInt(minutesField.getText().toString());

    Calendar calendar = Calendar.getInstance();
    calendar.set(Calendar.HOUR_OF_DAY, alarmHour);
    calendar.set(Calendar.MINUTE, alarmMinute);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    if (System.currentTimeMillis() >= calendar.getTimeInMillis()) {
      calendar.add(Calendar.DATE, 1);
    }
    
    long timeMillis = calendar.getTimeInMillis();
    startAlarm(timeMillis);
  }
  
  private void snoozeAlarm(int seconds) {
    Fun.logd("snoozeAlarm(): " + seconds);
    Calendar calendar = Calendar.getInstance();
    calendar.add(Calendar.SECOND, seconds);
    
    long timeMillis = calendar.getTimeInMillis();
    startAlarm(timeMillis);
  }
  
  private void stopAlarm() {
    MainService.stopAlarm();
    
    if (alarmWakeupAnimation != null) alarmWakeupAnimation.stop();
    imageAlarmWakeupAnimation.setVisibility(View.GONE);
    
    stopService(new Intent(this, PlayerService.class));
    
    int snoozeTime = (int) Fun.getSharedPrefLong(context, Vars.PREF_KEY_SNOOZE_TIME);
    boolean snoozeOn = snoozeTime > 0;
    if (snoozeOn && isAlarmWakeup) {
      if (Vars.DEBUG_MODE) snoozeTime = Vars.SNOOZE_TIME_DEBUG;
      snoozeAlarm(snoozeTime);
    }
    isAlarmWakeup = false;
    
    disableScreenOn();
  }
  
  private void wakeupAlarm() {
    Fun.logd("wakeupAlarm()");
    Fun.logd("Timestamp: " + System.currentTimeMillis());
    
    isAlarmWakeup = true;
    playSound();
    animateClock();
    
    enableScreenOn();
  }
  
  // ------------------ UI Actions ------------------
  
  private void selectSound() {
    Fun.logd("selectSound()");
    
    String soundPath = Fun.getSharedPref(context, Vars.PREF_KEY_SOUND_PATH);
    
    String startPath = null;
    if (soundPath != null) {
      startPath = soundPath;
      if (new File(startPath).isFile()) {
        startPath = new File(startPath).getParent();
      }
    }
    
    soundPickerDialog.showDialog(startPath);
  }
  
  // ------------------ UI Utils ------------------

  private void enableScreenOn() {
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }
  
  private void disableScreenOn() {
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }
  
  private void updateAlarmText(String text) {
    String[] items = text.split(":");
    hoursField.setText(items[0]);
    minutesField.setText(items[1]);
  }
  
  private void updateAlarmState(boolean enabled) {
    int stateBackgroundId = enabled ? DRAWABLE_ALARM_PANEL_BACKGROUND_STOP : DRAWABLE_ALARM_PANEL_BACKGROUND_START;
    panelAlarmState.setBackgroundResource(stateBackgroundId);
  }
  
  private void updateVolume(int volume, int maxVolume) {
    Fun.saveSharedPref(context, Vars.PREF_KEY_ALARM_VOLUME, volume);
    volumeSlider.setProgress(volume);
    
    int percent = maxVolume == 0 ? 0 : Math.round((volume * 100f) / maxVolume);
    volumeValueView.setText(percent + "%");
  }
  
  private void updateSnoozeTime(int minutes) {
    Fun.saveSharedPref(context, Vars.PREF_KEY_SNOOZE_TIME, minutes * 60);
    Fun.saveSharedPref(context, Vars.PREF_KEY_SNOOZE_ON, minutes > 0);
    
    snoozeSlider.setProgress(minutes);
    
    snoozeValueView.setText(minutes > 0 ? String.valueOf(minutes) : "off");
  }

  private void animateClock() {
    imageAlarmWakeupAnimation.setVisibility(View.VISIBLE);
    
    Drawable alarmWakeupDrawable = imageAlarmWakeupAnimation.getDrawable();
    alarmWakeupAnimation = (Animatable) alarmWakeupDrawable;
    AnimatedVectorDrawableCompat.registerAnimationCallback(alarmWakeupDrawable, new AnimationCallback() {
      public void onAnimationEnd(Drawable drawable) {
        new Handler().postDelayed(() -> alarmWakeupAnimation.start(), 1000);
      }
    });
    alarmWakeupAnimation.start();
  }
  
  private String getClockText() {
    String hours = hoursField.getText().toString();
    String minutes = minutesField.getText().toString();
    return getClockText(hours, minutes);
  }
  
  private String getClockText(String hours, String minutes) {
    if (hours.length() == 1) hours = "0" + hours;
    if (minutes.length() == 1) minutes = "0" + minutes;
    
    String result = hours + ":" + minutes;
    return result;
  }
  
  private boolean isWakeupIntent(Intent intent) {
    boolean result = false;
    Bundle extras = intent.getExtras();
    if (extras != null) {
      result = extras.getBoolean(AlarmReceiver.ALARM_WAKEUP_INTENT);
    }
    
    return result;
  }
  
  // ------------------ Utils ------------------
  
  private void playSound() {
    Fun.logd("playSound()");
    
    long volume = Fun.getSharedPrefLong(context, Vars.PREF_KEY_ALARM_VOLUME);
    if (volume <= 0) return;
    
    Intent playerIntent = new Intent(this, PlayerService.class);
    playerIntent.putExtra(Vars.EXTRA_AUDIO_VOLUME, volume);
    
    String soundPath = Fun.getSharedPref(context, Vars.PREF_KEY_SOUND_PATH);
    playerIntent.putExtra(Vars.EXTRA_SOUND_PATH, soundPath);
    
    startService(playerIntent);
  }
  
  private void unfocusTimeInput() {
    hoursField.clearFocus();
    minutesField.clearFocus();
    
    if (Fun.isKeyboardVisible(hoursField)) Fun.hideKeyboard(hoursField);
    if (Fun.isKeyboardVisible(minutesField)) Fun.hideKeyboard(minutesField);
  }
  
  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      String id = Vars.NOTIFICATIONS_CHANNEL_ID;
      CharSequence name = getString(R.string.notification_channel_name);
      String description = getString(R.string.notification_channel_description);
      int importance = NotificationManager.IMPORTANCE_DEFAULT;

      NotificationChannel channel = new NotificationChannel(id, name, importance);
      channel.setDescription(description);
      NotificationManager notificationManager = getSystemService(NotificationManager.class);
      notificationManager.createNotificationChannel(channel);
    }
  }
  
  // --------------------
  @Override
  protected void onStart() {
    Fun.logd("MainActivity.onStart()");
    super.onStart();
  }
  
  @Override
  protected void onPause() {
    Fun.logd("MainActivity.onPause()");
    super.onPause();
  }
  
  @Override
  protected void onStop() {
    Fun.logd("MainActivity.onStop()");
    super.onStop();
  }
  
  @Override
  protected void onRestart() {
    Fun.logd("MainActivity.onRestart()");
    super.onStop();
  }
  
  @Override
  protected void onDestroy() {
    Fun.logd("MainActivity.onDestroy()");
    super.onDestroy();
  }
  // --------------------
  
}
