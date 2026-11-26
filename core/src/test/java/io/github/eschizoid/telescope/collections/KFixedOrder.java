package io.github.eschizoid.telescope.collections;

import java.util.ArrayList;
import java.util.List;

/** As {@link KOrder}, with no setter for its lines, so nothing but an adder can fill them. */
public class KFixedOrder {

  private final List<KLine> lines = new ArrayList<>();

  public KFixedOrder() {}

  public List<KLine> getLines() {
    return lines;
  }

  public void addPrimary(final KLine line) {
    lines.add(line);
  }

  public void addBackup(final KLine line) {
    lines.add(line);
  }
}
