package com.maksz42.periscope.overlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The chooser's rows: one per camera, plus a last "close" row when a camera is showing.
 * Pure logic.
 */
final class ChooserModel {
  private final List<String> rows = new ArrayList<>();
  private final int cameraCount;
  private int index;

  /**
   * @param cameras the cameras to offer, in order
   * @param watching the camera on screen now, or null. While one is showing, the list starts
   *     on the close row, so closing takes the trigger and OK, and any camera is one step away
   *     because the list wraps
   * @param closeLabel the text of the close row, shown only while watching
   */
  ChooserModel(List<String> cameras, String watching, String closeLabel) {
    rows.addAll(cameras);
    cameraCount = cameras.size();
    if (watching != null) rows.add(closeLabel);
    index = watching != null ? rows.size() - 1 : 0;
  }

  boolean hasCameras() {
    return cameraCount > 0;
  }

  List<String> rows() {
    return Collections.unmodifiableList(rows);
  }

  int index() {
    return index;
  }

  /** Moves the highlight, wrapping around from either end. */
  void move(int by) {
    if (rows.isEmpty()) return;
    index = Math.floorMod(index + by, rows.size());
  }

  /**
   * The rows to show when at most {@code maxRows} fit: a run that contains the highlight,
   * as [first, end).
   */
  int[] window(int maxRows) {
    int n = rows.size();
    if (maxRows <= 0 || n <= maxRows) return new int[] {0, n};
    int first = Math.max(0, Math.min(index - maxRows / 2, n - maxRows));
    return new int[] {first, first + maxRows};
  }

  /** True when the close row is highlighted. */
  boolean closeSelected() {
    return index >= cameraCount && !rows.isEmpty();
  }

  /** The highlighted camera; null when the close row is highlighted or there are no rows. */
  String selectedCamera() {
    return index < cameraCount ? rows.get(index) : null;
  }
}
