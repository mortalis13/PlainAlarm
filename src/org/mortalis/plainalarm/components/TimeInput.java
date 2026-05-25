package org.mortalis.plainalarm.components;

import android.content.Context;
import android.text.InputType;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

import androidx.appcompat.widget.AppCompatEditText;

import org.mortalis.plainalarm.Fun;


public class TimeInput extends AppCompatEditText {

  public enum Mode { HOURS, MINUTES }

  public interface OnTwoDigitCompleteListener {
    void onComplete(TimeInput view, int value);
  }

  private Mode mode = Mode.MINUTES;
  private OnTwoDigitCompleteListener completeListener;

  private int minValue = 0;
  private int maxValue = 59;

  // Slot entry state
  private boolean waitingSecondDigit;
  private int firstDigit;

  // Selection guard
  private boolean enforcingSelection;
  
  private boolean touchCancelled;
  private int activePointerId = MotionEvent.INVALID_POINTER_ID;
  private float downX;
  private float downY;
  private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();


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
      setTwoDigitText(0);
    }
  }
  
  public void setRange(int min, int max) {
    this.minValue = min;
    this.maxValue = max;
  }

  public void setMode(Mode mode) {
    this.mode = mode;
  }

  public void setOnTwoDigitCompleteListener(OnTwoDigitCompleteListener listener) {
    this.completeListener = listener;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    int action = event.getActionMasked();
    
    if (action == MotionEvent.ACTION_DOWN) {
      activePointerId = event.getPointerId(0);
      downX = event.getX();
      downY = event.getY();
      touchCancelled = false;

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
      boolean movedFar = Math.abs(x - downX) > touchSlop || Math.abs(y - downY) > touchSlop;

      if (!touchCancelled && outside && movedFar) {
        touchCancelled = true;
        setPressed(false);
        cancelLongPress();
      }
    }

    if (action == MotionEvent.ACTION_UP) {
      float x = event.getX();
      float y = event.getY();

      setPressed(false);

      if (touchCancelled || !isPointInsideView(x, y)) {
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        touchCancelled = false;
        return true;
      }
      
      this.onClick();

      activePointerId = MotionEvent.INVALID_POINTER_ID;
      touchCancelled = false;
    }

    return true;
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
  
  private void resetSlotState() {
    waitingSecondDigit = false;
    firstDigit = 0;
  }

  private void handleBackspace() {
    waitingSecondDigit = false;
    setTwoDigitText(0);
  }

  private void handleDigit(int digit) {
    // HOURS rule: first digit 3..9 => 03..09 and COMPLETE (jump)
    if (mode == Mode.HOURS && !waitingSecondDigit && digit > 2) {
      waitingSecondDigit = false;
      setTwoDigitText(digit);
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
      setTwoDigitText(tensValue);
      return;
    }

    // Second digit (ones)
    int candidate = firstDigit * 10 + digit;

    // HOURS rule: if > 23 => force to 0 + firstDigit (e.g. 2 then 9 => 02) and COMPLETE
    if (mode == Mode.HOURS && candidate > maxValue) {
      int forced = firstDigit; // 2 => 02
      waitingSecondDigit = false;
      setTwoDigitText(forced);
      fireComplete(forced);
      return;
    }

    // Normal validation
    if (candidate < minValue || candidate > maxValue) {
      return;
    }

    waitingSecondDigit = false;
    setTwoDigitText(candidate);
    fireComplete(candidate);
  }

  private void fireComplete(int value) {
    if (completeListener != null) {
      completeListener.onComplete(this, value);
    }
  }

  private void setTwoDigitText(int value) {
    String text = String.format("%02d", value);
    setText(text);
    selectAll();
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
