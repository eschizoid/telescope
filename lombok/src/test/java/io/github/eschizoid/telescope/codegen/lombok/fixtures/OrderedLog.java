package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import io.github.eschizoid.telescope.conversion.BridgeFn;

/** Fixture: a transform that appends each value it converts to {@link #LOG}, each way. */
public final class OrderedLog implements BridgeFn<String, String> {

  /** Every value converted, in the order the conversions ran. */
  public static final StringBuilder LOG = new StringBuilder();

  /** The instance a {@code toOneWay} row on a {@code @Bridge} field converts through. */
  public static final OrderedLog INSTANCE = new OrderedLog();

  @Override
  public String forward(final String value) {
    LOG.append(value).append(';');
    return value;
  }

  @Override
  public String backward(final String value) {
    LOG.append(value).append(';');
    return value;
  }
}
