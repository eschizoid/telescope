package io.github.eschizoid.telescope.demo.order;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Where an order is in its life. */
public enum OrderStatus {
  PLACED,
  PAID,
  SHIPPED,
  CANCELLED;

  /**
   * The status a client names, in any case.
   *
   * @throws IllegalArgumentException for a missing or unknown name, listing the statuses there are
   */
  public static OrderStatus named(final String name) {
    final var known = Arrays.stream(values()).map(OrderStatus::name).collect(Collectors.joining(", "));
    if (name == null || name.isBlank()) throw new IllegalArgumentException("a status is required: one of " + known);
    return Arrays.stream(values())
      .filter(status -> status.name().equalsIgnoreCase(name.strip()))
      .findFirst()
      .orElseThrow(() -> new IllegalArgumentException("there is no status '" + name + "': use one of " + known));
  }
}
