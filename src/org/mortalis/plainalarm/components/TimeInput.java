package org.mortalis.plainalarm.components;

import android.content.Context;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.VelocityTracker;

import androidx.appcompat.widget.AppCompatEditText;

import org.mortalis.plainalarm.Fun;


public class TimeInput extends AppCompatEditText {
  
  public static final float STEP_SIZE = Fun.dpToPx(20);
  private static final int SWIPE_JUMP = 10;
  
  private static final float SWIPE_MIN_DISTANCE = Fun.dpToPx(40);
  private static final float SWIPE_MIN_VELOCITY = 3000f;

  public enum Mode { HOURS, MINUTES }
  private enum GestureMode { NONE, DRAG, SWIPE }

  public interface OnUpdateListener {
    void onUpdate(int value, boolean isComplete);
  }

  private Mode mode = Mode.MINUTES;
  private GestureMode gestureMode = GestureMode.NONE;
  
  private OnUpdateListener updateListener;
  
  private int value;

  private int minValue = 0;
  private int maxValue = 59;
  
  private int currentStep;

  // Slot entry state
  private boolean waitingSecondDigit;
  private int firstDigit;

  // Selection guard
  private boolean enforcingSelection;
  
  private boolean touchCancelled;
  private boolean valueChanged;
  
  private float downX;
  private float downY;
  
  private int activePointerId = MotionEvent.INVALID_POINTER_ID;
  private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
  
  private VelocityTracker velocityTracker;


  public TimeInput(Context context) {
    super(context);
    init();
  }

  public TimeInput(Context context, android.util.AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public TimeInput(Context context, android.util.AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }


  private void init() {
    setSingleLine(true);
    setCursorVisible(false);
    setLongClickable(false);
    setTextIsSelectable(false);
    
    setBackground(null);
    
    // Numeric keyboard
    setInputType(InputType.TYPE_CLASS_NUMBER);

    // Disable selection action menu
    setCustomSelectionActionModeCallback(new ActionMode.Callback() {
      @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) { return false; }
      @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
      @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
      @Override public void onDestroyActionMode(ActionMode mode) {}
    });

    // Disable insertion handles/menu
    setCustomInsertionActionModeCallback(new ActionMode.Callback() {
      @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) { return false; }
      @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
      @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
      @Override public void onDestroyActionMode(ActionMode mode) {}
    });

    if (getText() == null || getText().length() == 0) {
      formatValue(0);
    }
    
    addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

      @Override
      public void afterTextChanged(Editable s) {
        try {
          value = Integer.parseInt(s.toString());
        }
        catch (NumberFormatException e) {
          value = 0;
        }
        onValueUpdated(value);
      }
    });
  }
  
  public void setRange(int min, int max) {
    this.minValue = min;
    this.maxValue = max;
  }

  public void setMode(Mode mode) {
    this.mode = mode;
  }
  
  public void setOnUpdateListener(OnUpdateListener listener) {
    this.updateListener = listener;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    int action = event.getActionMasked();

    if (velocityTracker == null) {
      velocityTracker = VelocityTracker.obtain();
    }
    velocityTracker.addMovement(event);

    if (action == MotionEvent.ACTION_DOWN) {
      this.currentStep = 0;

      touchCancelled = false;
      valueChanged = false;

      gestureMode = (this.mode == Mode.HOURS) ? GestureMode.DRAG : GestureMode.NONE;

      activePointerId = event.getPointerId(0);
      this.downX = event.getX();
      this.downY = event.getY();

      setPressed(true);
    }

    if (action == MotionEvent.ACTION_MOVE) {
      if (activePointerId == MotionEvent.INVALID_POINTER_ID) return true;

      int pointerIndex = event.findPointerIndex(activePointerId);
      if (pointerIndex < 0) return true;

      float x = event.getX(pointerIndex);
      float y = event.getY(pointerIndex);

      // Cancel if user drags outside
      boolean outside = !isPointInsideView(x, y);
      boolean movedFar = Math.abs(x - this.downX) > touchSlop || Math.abs(y - this.downY) > touchSlop;
      if (!touchCancelled && outside && movedFar) {
        touchCancelled = true;
        setPressed(false);
        cancelLongPress();
      }

      float dx = x - this.downX;
      float dy = y - this.downY;

      // If already recognized as swipe, ignore drag stepping
      if (gestureMode == GestureMode.SWIPE) {
        return true;
      }

      // Detect mostly-vertical fast movement as swipe candidate
      velocityTracker.computeCurrentVelocity(1000);
      float vx = velocityTracker.getXVelocity(activePointerId);
      float vy = velocityTracker.getYVelocity(activePointerId);

      boolean verticalEnough = Math.abs(dy) > Math.abs(dx) * 1.5f;
      boolean fastEnough = Math.abs(vy) >= SWIPE_MIN_VELOCITY;
      boolean farEnough = Math.abs(dy) >= SWIPE_MIN_DISTANCE;
      
      if (gestureMode == GestureMode.NONE && verticalEnough && fastEnough && farEnough) {
        gestureMode = GestureMode.SWIPE;
        applyJump(SWIPE_JUMP);

        setPressed(false);
        return true;
      }

      int step = (int) (dy / STEP_SIZE);

      if (dy != 0 && this.currentStep != step) {
        if (Math.abs(this.currentStep) > 0) {
          // At least one step was processed, discard swipe
          gestureMode = GestureMode.DRAG;
        }

        int delta = step - this.currentStep;
        
        int newValue = wrapValue(value - delta);
        setValue(newValue);
        
        this.currentStep = step;
        this.valueChanged = true;
      }
    }

    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      this.currentStep = 0;

      float x = event.getX();
      float y = event.getY();

      setPressed(false);

      if (velocityTracker != null) {
        velocityTracker.recycle();
        velocityTracker = null;
      }

      if (touchCancelled || !isPointInsideView(x, y)) {
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        touchCancelled = false;
        gestureMode = GestureMode.NONE;
        return true;
      }

      if (!this.valueChanged && gestureMode != GestureMode.SWIPE) {
        this.onClick();
      }

      activePointerId = MotionEvent.INVALID_POINTER_ID;
      touchCancelled = false;
      valueChanged = false;
      gestureMode = GestureMode.NONE;
    }

    return true;
  }
  
  private void applyJump(int delta) {
    Fun.log("applyJump " + delta);
    int newValue = wrapValue(value + delta);
    setValue(newValue);
    this.valueChanged = true;
  }
  
  private int wrapValue(int candidate) {
    int range = maxValue - minValue + 1;
    int normalized = (candidate - minValue) % range;
    if (normalized < 0) normalized += range;
    return minValue + normalized;
  }
  
  // IME-safe digit interception (soft keyboards call commitText, not onKeyDown)
  @Override
  public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
    InputConnection base = super.onCreateInputConnection(outAttrs);
    
    InputConnection result = new InputConnectionWrapper(base, true) {
      @Override
      public boolean commitText(CharSequence text, int newCursorPosition) {
        if (text != null && text.length() == 1) {
          char c = text.charAt(0);
          if (c >= '0' && c <= '9') {
            handleDigit(c - '0');
            return true;
          }
        }
        return super.commitText(text, newCursorPosition);
      }

      @Override
      public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        handleBackspace();
        return true;
      }

      @Override
      public boolean sendKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
          int digit = keyCodeToDigit(event.getKeyCode());
          if (digit >= 0) {
            handleDigit(digit);
            return true;
          }
          if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
            handleBackspace();
            return true;
          }
        }
        return super.sendKeyEvent(event);
      }
    };
    
    return result;
  }
  
  @Override
  protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previouslyFocusedRect) {
    super.onFocusChanged(focused, direction, previouslyFocusedRect);

    if (!focused) {
      resetSlotState();
      return;
    }

    resetSlotState();
    selectAll();
  }

  @Override
  public void clearFocus() {
    resetSlotState();
    super.clearFocus();
  }
  
  private void onClick() {
    if (hasFocus() && Fun.isKeyboardVisible(this)) {
      Fun.hideKeyboard(this);
      clearFocus();
    }
    else {
      requestFocus();
      Fun.showKeyboard(this);
      waitingSecondDigit = false;
      selectAll();
    }
    
    performClick();
  }
  
  private void setValue(int value) {
    this.value = value;
    formatValue(value);
    onValueUpdated(value);
  }
  
  private void resetSlotState() {
    waitingSecondDigit = false;
    firstDigit = 0;
  }

  private void handleBackspace() {
    waitingSecondDigit = false;
    formatValue(0);
    selectAll();
  }

  private void handleDigit(int digit) {
    // HOURS rule: first digit 3..9 => 03..09 and COMPLETE (jump)
    if (mode == Mode.HOURS && !waitingSecondDigit && digit > 2) {
      waitingSecondDigit = false;
      formatValue(digit);
      selectAll();
      fireComplete(digit);
      return;
    }

    if (!waitingSecondDigit) {
      // First digit is tens
      int tensValue = digit * 10;

      // If tensValue itself exceeds max, ignore (minutes: 60+, hours: 30+ not allowed)
      if (tensValue > maxValue) {
        return;
      }

      firstDigit = digit;
      waitingSecondDigit = true;
      formatValue(tensValue);
      selectAll();
      return;
    }

    // Second digit (ones)
    int candidate = firstDigit * 10 + digit;

    // HOURS rule: if > 23 => force to 0 + firstDigit (e.g. 2 then 9 => 02) and COMPLETE
    if (mode == Mode.HOURS && candidate > maxValue) {
      int forced = firstDigit; // 2 => 02
      waitingSecondDigit = false;
      formatValue(forced);
      selectAll();
      fireComplete(forced);
      return;
    }

    // Normal validation
    if (candidate < minValue || candidate > maxValue) {
      return;
    }

    waitingSecondDigit = false;
    formatValue(candidate);
    selectAll();
    fireComplete(candidate);
  }

  private void fireComplete(int value) {
    if (updateListener != null) {
      updateListener.onUpdate(value, true);
    }
  }

  private void onValueUpdated(int value) {
    if (updateListener != null) {
      updateListener.onUpdate(value, false);
    }
  }

  private void formatValue(int value) {
    String text = String.format("%02d", value);
    setText(text);
  }

  private int keyCodeToDigit(int keyCode) {
    if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
      return keyCode - KeyEvent.KEYCODE_0;
    }
    if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
      return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
    }
    return -1;
  }
  
  private boolean isPointInsideView(float x, float y) {
    return x >= 0 && x < getWidth() && y >= 0 && y < getHeight();
  }
  
}
