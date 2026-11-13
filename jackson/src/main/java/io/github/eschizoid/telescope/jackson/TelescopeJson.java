package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.mapping.MapExtractStep;
import java.util.Objects;

/**
 * The entry point for reading JSON into a typed value with telescope. It lives here rather than on
 * {@code Telescope} so that {@code telescope-core} never names a Jackson type.
 *
 * <pre>{@code
 * JsonBinder<Order> orders = TelescopeJson.fromJson(Order.class);
 * Order order = orders.read(body);
 * }</pre>
 */
public final class TelescopeJson {

  private TelescopeJson() {}

  /**
   * A binder for {@code target}. Every property reads the JSON key of its own name, and every
   * record or bean outside the JDK that the target holds is read from a nested object the same way.
   * A row overrides one property, of the target or of any type it holds: {@link
   * MapExtractStep#extract extract} reads another key or converts the value with a function of its
   * own, and {@link MapExtractStep#required required} refuses a document without the key. They are
   * the rows {@code Telescope.fromMap} takes, and a converter receives the value as an untyped map
   * would hold it, with a fraction as a {@code BigDecimal}.
   *
   * <p>A target whose generated {@code @FromMap} binder declares keys required is held to the same
   * keys.
   *
   * @param target the record or bean to build
   * @param rows overrides for single properties
   * @param <T> the type built
   * @return the binder
   * @throws IllegalArgumentException when a property's type has no reading from JSON, or a row
   *     names a property that is not there
   */
  public static <T> JsonBinder<T> fromJson(final Class<T> target, final MapExtractStep... rows) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(rows, "rows");
    return new JsonBinder<>(Plan.build(target, rows));
  }
}
