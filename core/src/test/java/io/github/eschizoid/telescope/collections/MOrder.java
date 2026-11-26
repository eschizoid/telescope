package io.github.eschizoid.telescope.collections;

import java.util.ArrayList;
import java.util.List;

/** An order whose adder and remover keep each line's back-reference, and record each call. */
public class MOrder {

  private String name;
  private List<MLine> lines = new ArrayList<>();

  public MOrder() {}

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public List<MLine> getLines() {
    return lines;
  }

  public void setLines(final List<MLine> lines) {
    this.lines = lines;
  }

  /** Each adder and remover call, in order. */
  public final List<String> calls = new ArrayList<>();

  public void addLine(final MLine line) {
    calls.add("add " + line.getSku());
    line.setOrder(this);
    lines.add(line);
  }

  public void removeLine(final MLine line) {
    calls.add("remove " + line.getSku());
    lines.remove(line);
    line.setOrder(null);
  }
}
