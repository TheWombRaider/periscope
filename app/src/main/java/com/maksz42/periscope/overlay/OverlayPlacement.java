package com.maksz42.periscope.overlay;

/** Where the camera floats and how big it is. Pure logic. */
public final class OverlayPlacement {
  private OverlayPlacement() { }

  public enum Corner {
    TOP_RIGHT(true, true),
    TOP_LEFT(true, false),
    BOTTOM_RIGHT(false, true),
    BOTTOM_LEFT(false, false);

    public final boolean top;
    public final boolean right;

    Corner(boolean top, boolean right) {
      this.top = top;
      this.right = right;
    }

    /** The stored value, or the top right corner for anything unknown. */
    public static Corner parse(String value) {
      for (Corner c : values()) {
        if (c.name().equals(value)) return c;
      }
      return TOP_RIGHT;
    }
  }

  public enum Size {
    SMALL(0.22f),
    MEDIUM(0.28f),
    LARGE(0.36f);

    /** The camera's width as a share of the screen's longer side. */
    public final float share;

    Size(float share) {
      this.share = share;
    }

    /** The stored value, or medium for anything unknown. */
    public static Size parse(String value) {
      for (Size s : values()) {
        if (s.name().equals(value)) return s;
      }
      return MEDIUM;
    }
  }
}
