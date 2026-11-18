package io.github.eschizoid.telescope.internal.pairing;

import java.io.Serial;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Function;

/**
 * A value an untyped map holds that its declared type does not take, or a map without a value its
 * target requires, on its way out of the nested conversions. Each level it passes through adds
 * where it is, a property or an element position, so the innermost conversion needs no path and a
 * value that converts pays nothing for one. The message is rendered from the complete path when it
 * is read, in the words {@link PairingMessages#mapValueRefused} and {@link
 * PairingMessages#mapMissingRequired} give.
 *
 * <p>It is the {@link IllegalArgumentException} a caller of {@code Telescope.fromMap} or of a
 * generated {@code @FromMap} binder sees.
 */
public final class ValueRefused extends IllegalArgumentException {

  @Serial
  private static final long serialVersionUID = 1L;

  private final transient ArrayDeque<String> segments = new ArrayDeque<>();

  /** The message, given the path. */
  private final transient Function<String, String> message;

  private ValueRefused(final Function<String, String> message, final Throwable cause) {
    super((String) null, cause);
    this.message = message;
  }

  /**
   * The refusal of {@code value}, which is not {@code what}: a value of a kind the declared type
   * does not take, or one it takes but cannot hold, such as an integer past its range.
   *
   * @param what the declared type with its article, as {@link PairingMessages#scalarPhrase} writes
   *     a scalar
   */
  public static ValueRefused value(final Object value, final String what, final Throwable cause) {
    final var got = PairingMessages.mapValueShown(value);
    return new ValueRefused(path -> PairingMessages.mapValueRefused(path, got, what), cause);
  }

  /**
   * The refusal of a map that carries no value for the required keys {@code missing}, each named
   * beside the property it fills, {@code "sku" (component 'sku')}.
   *
   * @param prefix what refuses, {@code Telescope.fromMap} or the generated binder's method
   */
  public static ValueRefused missing(final String prefix, final List<String> missing, final String target) {
    final var named = List.copyOf(missing);
    return new ValueRefused(path -> PairingMessages.mapMissingRequired(prefix, path, named, target), null);
  }

  /**
   * The refusal, now inside the property {@code name}; unchanged for a null {@code name}, which is
   * an element whose container adds its position.
   */
  public ValueRefused under(final String name) {
    if (name != null) segments.addFirst(name);
    return this;
  }

  /** The refusal, now inside the element at {@code position} of a list, a set or a map. */
  public ValueRefused at(final Object position) {
    segments.addFirst(PairingMessages.elementPosition(position));
    return this;
  }

  /**
   * The path the levels added, properties joined by a dot and positions written after what holds
   * them: {@code lines[1].quantity}. Empty before any level has added one.
   */
  public String path() {
    final var path = new StringBuilder();
    for (final var segment : segments) {
      if (!segment.startsWith("[") && !path.isEmpty()) path.append('.');
      path.append(segment);
    }
    return path.toString();
  }

  @Override
  public String getMessage() {
    return message == null ? super.getMessage() : message.apply(path());
  }
}
