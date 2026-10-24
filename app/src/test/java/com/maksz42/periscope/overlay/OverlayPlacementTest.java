package com.maksz42.periscope.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OverlayPlacementTest {
  @Test
  public void storedValuesParseAndUnknownOnesFallBack() {
    assertEquals(OverlayPlacement.Corner.BOTTOM_LEFT, OverlayPlacement.Corner.parse("BOTTOM_LEFT"));
    assertEquals(OverlayPlacement.Corner.TOP_RIGHT, OverlayPlacement.Corner.parse(null));
    assertEquals(OverlayPlacement.Corner.TOP_RIGHT, OverlayPlacement.Corner.parse("middle"));
    assertEquals(OverlayPlacement.Size.LARGE, OverlayPlacement.Size.parse("LARGE"));
    assertEquals(OverlayPlacement.Size.MEDIUM, OverlayPlacement.Size.parse(null));
  }

  @Test
  public void cornersKnowTheirSides() {
    assertTrue(OverlayPlacement.Corner.TOP_LEFT.top);
    assertFalse(OverlayPlacement.Corner.TOP_LEFT.right);
    assertFalse(OverlayPlacement.Corner.BOTTOM_RIGHT.top);
    assertTrue(OverlayPlacement.Corner.BOTTOM_RIGHT.right);
  }

  @Test
  public void sizesGrow() {
    assertTrue(OverlayPlacement.Size.SMALL.share < OverlayPlacement.Size.MEDIUM.share);
    assertTrue(OverlayPlacement.Size.MEDIUM.share < OverlayPlacement.Size.LARGE.share);
  }
}
