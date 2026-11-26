package io.github.eschizoid.telescope.collections;

import java.util.ArrayList;
import java.util.List;

/** An order with a no-argument constructor and an adder, and no setter at all. */
public class HOrder {

  public HOrder() {}

  private final List<HLine> lines = new ArrayList<>();

  public List<HLine> getLines() {
    return lines;
  }

  public void addLine(final HLine line) {
    lines.add(line);
  }
}
