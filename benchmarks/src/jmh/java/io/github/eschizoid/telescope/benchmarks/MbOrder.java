package io.github.eschizoid.telescope.benchmarks;

import java.util.ArrayList;
import java.util.List;

/** An order holding a list of entity lines, written in place by {@link MbMappers}. */
public class MbOrder {

  private List<MbLine> lines = new ArrayList<>();

  public MbOrder() {}

  public List<MbLine> getLines() {
    return lines;
  }

  public void setLines(final List<MbLine> lines) {
    this.lines = lines;
  }
}
