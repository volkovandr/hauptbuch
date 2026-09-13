package volkovandr.hauptbuch.analytics.repository;

import java.util.List;

/** A page's whole Layout (reporting.md §11): its grid dimensions and every Frame in it. */
public record LayoutSnapshot(int rowCount, int columnCount, List<LayoutFrameRow> frames) {

  /** Defensively copies {@code frames} to an immutable list. */
  public LayoutSnapshot {
    frames = List.copyOf(frames);
  }
}
