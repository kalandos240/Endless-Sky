package io.github.kalandos240.endlesssky;

/** Stable JNI protocol IDs; match AndroidTouch.cpp, never reorder existing actions. */
public final class TouchActions {
    private TouchActions() {}
    public static final int FORWARD = 0;
    public static final int LEFT = 1;
    public static final int RIGHT = 2;
    public static final int BACK = 3;
    public static final int PRIMARY = 4;
    public static final int SECONDARY = 5;
    public static final int AFTERBURNER = 6;
    public static final int SELECT = 7;
    public static final int TARGET = 8;
    public static final int NEAREST = 9;
    public static final int LAND = 10;
    public static final int JUMP = 11;
    public static final int BOARD = 12;
    public static final int HAIL = 13;
    public static final int MAP = 14;
    public static final int INFO = 15;
    public static final int PAUSE = 16;
    public static final int MENU = 17;
    public static final int COUNT = 18;
}
