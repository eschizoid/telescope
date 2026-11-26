package io.github.eschizoid.telescope.collections;

import java.util.ArrayList;
import java.util.List;

/**
 * An order with a setter for its lines and two methods that each take a line, neither named for
 * them, which fill another list.
 */
public class KOrder {

  private List<KLine> lines = new ArrayList<>();

  /** What {@link #addPrimary} and {@link #addBackup} were handed. */
  public final List<KLine> other = new ArrayList<>();

  public KOrder() {}

  public List<KLine> getLines() {
    return lines;
  }

  public void setLines(final List<KLine> lines) {
    this.lines = lines;
  }

  public void addPrimary(final KLine line) {
    other.add(line);
  }

  public void addBackup(final KLine line) {
    other.add(line);
  }
}
