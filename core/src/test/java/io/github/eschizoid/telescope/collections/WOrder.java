package io.github.eschizoid.telescope.collections;

import java.util.ArrayList;
import java.util.List;

/** An order whose adder, named for its lines, takes the superclass of their element class. */
public class WOrder {

  private String name;
  private List<WLine> lines = new ArrayList<>();

  public WOrder() {}

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public List<WLine> getLines() {
    return lines;
  }

  public void setLines(final List<WLine> lines) {
    this.lines = lines;
  }

  public void addLine(final WBase line) {
    final var added = (WLine) line;
    added.setSku(added.getSku() + "!");
    lines.add(added);
  }
}
