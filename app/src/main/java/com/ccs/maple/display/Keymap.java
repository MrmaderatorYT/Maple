package com.ccs.maple.display;

import android.view.KeyEvent;

/**
 * Android key codes to PC scancodes in QEMU's "qnum" form: the set 1 make code, with bit 7 set
 * for keys that carry an E0 prefix.
 */
public final class Keymap {
    public static final int ESC = 0x01;
    public static final int BACKSPACE = 0x0e;
    public static final int TAB = 0x0f;
    public static final int ENTER = 0x1c;
    public static final int CTRL = 0x1d;
    public static final int SHIFT = 0x2a;
    public static final int ALT = 0x38;
    public static final int SPACE = 0x39;
    public static final int WIN = 0xdb;
    public static final int MENU = 0xdd;
    public static final int INSERT = 0xd2;
    public static final int DELETE = 0xd3;
    public static final int HOME = 0xc7;
    public static final int END = 0xcf;
    public static final int PGUP = 0xc9;
    public static final int PGDN = 0xd1;
    public static final int UP = 0xc8;
    public static final int DOWN = 0xd0;
    public static final int LEFT = 0xcb;
    public static final int RIGHT = 0xcd;
    public static final int PRINT = 0xb7;
    public static final int F1 = 0x3b;
    public static final int F11 = 0x57;
    public static final int F12 = 0x58;

    private static final int[] LETTERS = {
            0x1e, 0x30, 0x2e, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
            0x31, 0x18, 0x19, 0x10, 0x13, 0x1f, 0x14, 0x16, 0x2f, 0x11, 0x2d, 0x15, 0x2c
    };
    private static final int[] NUMPAD = {0x52, 0x4f, 0x50, 0x51, 0x4b, 0x4c, 0x4d, 0x47, 0x48,
            0x49};

    private Keymap() {
    }

    /** F1..F12 as qnum. */
    public static int fkey(int n) {
        if (n >= 1 && n <= 10) return F1 + n - 1;
        return n == 11 ? F11 : F12;
    }

    /** Returns 0 for keys that have no PC equivalent. */
    public static int qnum(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return LETTERS[keyCode - KeyEvent.KEYCODE_A];
        }
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) {
            return 0x02 + keyCode - KeyEvent.KEYCODE_1;
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return fkey(keyCode - KeyEvent.KEYCODE_F1 + 1);
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return NUMPAD[keyCode - KeyEvent.KEYCODE_NUMPAD_0];
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_0:
                return 0x0b;
            case KeyEvent.KEYCODE_ENTER:
                return ENTER;
            case KeyEvent.KEYCODE_ESCAPE:
                return ESC;
            case KeyEvent.KEYCODE_DEL:
                return BACKSPACE;
            case KeyEvent.KEYCODE_TAB:
                return TAB;
            case KeyEvent.KEYCODE_SPACE:
                return SPACE;
            case KeyEvent.KEYCODE_MINUS:
                return 0x0c;
            case KeyEvent.KEYCODE_EQUALS:
                return 0x0d;
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                return 0x1a;
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                return 0x1b;
            case KeyEvent.KEYCODE_BACKSLASH:
                return 0x2b;
            case KeyEvent.KEYCODE_SEMICOLON:
                return 0x27;
            case KeyEvent.KEYCODE_APOSTROPHE:
                return 0x28;
            case KeyEvent.KEYCODE_GRAVE:
                return 0x29;
            case KeyEvent.KEYCODE_COMMA:
                return 0x33;
            case KeyEvent.KEYCODE_PERIOD:
                return 0x34;
            case KeyEvent.KEYCODE_SLASH:
                return 0x35;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return 0x3a;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return SHIFT;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return 0x36;
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return CTRL;
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return 0x9d;
            case KeyEvent.KEYCODE_ALT_LEFT:
                return ALT;
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return 0xb8;
            case KeyEvent.KEYCODE_META_LEFT:
                return WIN;
            case KeyEvent.KEYCODE_META_RIGHT:
                return 0xdc;
            case KeyEvent.KEYCODE_MENU:
                return MENU;
            case KeyEvent.KEYCODE_INSERT:
                return INSERT;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return DELETE;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return HOME;
            case KeyEvent.KEYCODE_MOVE_END:
                return END;
            case KeyEvent.KEYCODE_PAGE_UP:
                return PGUP;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return PGDN;
            case KeyEvent.KEYCODE_DPAD_UP:
                return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return RIGHT;
            case KeyEvent.KEYCODE_NUM_LOCK:
                return 0x45;
            case KeyEvent.KEYCODE_SCROLL_LOCK:
                return 0x46;
            case KeyEvent.KEYCODE_SYSRQ:
                return PRINT;
            case KeyEvent.KEYCODE_BREAK:
                return 0xc6;
            case KeyEvent.KEYCODE_NUMPAD_DOT:
                return 0x53;
            case KeyEvent.KEYCODE_NUMPAD_ADD:
                return 0x4e;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                return 0x4a;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY:
                return 0x37;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE:
                return 0xb5;
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return 0x9c;
            default:
                return 0;
        }
    }

    public static boolean isModifier(int qnum) {
        return qnum == CTRL || qnum == SHIFT || qnum == ALT || qnum == WIN || qnum == 0x36
                || qnum == 0x9d || qnum == 0xb8 || qnum == 0xdc;
    }

    /**
     * X11 keysym for the handful of keys worth supporting if a server ever lacks the scancode
     * extension (QEMU always has it); 0 otherwise.
     */
    static int keysymForQnum(int q) {
        switch (q) {
            case ESC:
                return 0xff1b;
            case BACKSPACE:
                return 0xff08;
            case TAB:
                return 0xff09;
            case ENTER:
                return 0xff0d;
            case SPACE:
                return 0x20;
            case CTRL:
                return 0xffe3;
            case SHIFT:
                return 0xffe1;
            case ALT:
                return 0xffe9;
            case DELETE:
                return 0xffff;
            case UP:
                return 0xff52;
            case DOWN:
                return 0xff54;
            case LEFT:
                return 0xff51;
            case RIGHT:
                return 0xff53;
            default:
                for (int i = 0; i < LETTERS.length; i++) {
                    if (LETTERS[i] == q) return 'a' + i;
                }
                if (q >= 0x02 && q <= 0x0a) return '1' + (q - 0x02);
                if (q == 0x0b) return '0';
                return 0;
        }
    }
}
