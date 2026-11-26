package io.github.eschizoid.telescope.collections;

import java.util.ArrayList;
import java.util.List;

/** An order whose lines name the order each was carried over from, which is no back-reference. */
public class ROrder {

  private String name;
  private List<RLine> lines = new ArrayList<>();

  public ROrder() {}

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public List<RLine> getLines() {
    return lines;
  }

  public void setLines(final List<RLine> lines) {
    this.lines = lines;
  }
}
