package io.github.eschizoid.telescope.collections;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * An order whose lines are {@link Serializable}, with a method taking any {@code Serializable} that
 * fills another list, which is no adder of its lines.
 */
public class ZOrder {

  private String name;
  private List<ZLine> lines = new ArrayList<>();

  /** What {@link #addAttachment} was handed. */
  public final List<Serializable> attachments = new ArrayList<>();

  public ZOrder() {}

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public List<ZLine> getLines() {
    return lines;
  }

  public void setLines(final List<ZLine> lines) {
    this.lines = lines;
  }

  public void addAttachment(final Serializable attachment) {
    attachments.add(attachment);
  }
}
