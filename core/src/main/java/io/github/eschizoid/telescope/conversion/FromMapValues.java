package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.internal.pairing.MapValueConversions;
import io.github.eschizoid.telescope.internal.pairing.ValueRefused;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * <b>Codegen-support seam — NOT for hand-written call sites.</b> The conversions a binder generated
 * for {@code @FromMap} calls for each value it reads, which are the ones {@code Telescope.fromMap}
 * applies, so the two read the same map into the same object and refuse the same values in the same
 * words. Generated code cannot read telescope's internal modules, so this class hands their
 * conversions on unchanged.
 *
 * <p>A scalar conversion takes a value that is not null, since the binder answers a missing value
 * with its own default. Every other conversion answers null with what a missing value leaves: null,
 * or an empty {@code List}, {@code Set} or {@code Map}. {@code name} is the property the value
 * fills, or null for an element, whose container adds its position. A value the declared type does
 * not take is refused with an {@link IllegalArgumentException} naming where it is, what it holds
 * and what it is not: {@code 'lines[1].quantity' got 1.5, not an int}.
 */
public final class FromMapValues {

  private FromMapValues() {}

  /** A {@code byte}, or a {@code Byte} where {@code primitive} is false. */
  public static byte toByte(final Object raw, final boolean primitive, final String name) {
    return MapValueConversions.toByte(raw, primitive, name);
  }

  /** A {@code short}, or a {@code Short} where {@code primitive} is false. */
  public static short toShort(final Object raw, final boolean primitive, final String name) {
    return MapValueConversions.toShort(raw, primitive, name);
  }

  /** An {@code int}, or an {@code Integer} where {@code primitive} is false. */
  public static int toInt(final Object raw, final boolean primitive, final String name) {
    return MapValueConversions.toInt(raw, primitive, name);
  }

  /** A {@code long}, or a {@code Long} where {@code primitive} is false. */
  public static long toLong(final Object raw, final boolean primitive, final String name) {
    return MapValueConversions.toLong(raw, primitive, name);
  }

  /** A {@code float}, or a {@code Float} where {@code primitive} is false. */
  public static float toFloat(final Object raw, final boolean primitive, final String name) {
    return MapValueConversions.toFloat(raw, primitive, name);
  }

  /** A {@code double}, or a {@code Double} where {@code primitive} is false. */
  public static double toDouble(final Object raw, final boolean primitive, final String name) {
    return MapValueConversions.toDouble(raw, primitive, name);
  }

  /** A {@code boolean}, or a {@code Boolean} where {@code primitive} is false. */
  public static boolean toBoolean(final Object raw, final boolean primitive, final String name) {
    return MapValueConversions.toBoolean(raw, primitive, name);
  }

  /** A {@code char}, or a {@code Character} where {@code primitive} is false. */
  public static char toChar(final Object raw, final boolean primitive, final String name) {
    return MapValueConversions.toChar(raw, primitive, name);
  }

  /** A {@code String}. */
  public static String toText(final Object raw, final String name) {
    return MapValueConversions.toText(raw, name);
  }

  /** A {@code CharSequence}. */
  public static CharSequence toCharSequence(final Object raw, final String name) {
    return MapValueConversions.toCharSequence(raw, name);
  }

  /** A constant of {@code type}. */
  public static <E extends Enum<E>> E toEnum(final Object raw, final Class<E> type, final String name) {
    return MapValueConversions.toEnum(raw, type, name);
  }

  /** A JDK value type built from its text, such as an {@code Instant} or a {@code UUID}. */
  public static <T> T toValue(final Object raw, final Class<T> type, final String name) {
    return MapValueConversions.toValue(raw, type, name);
  }

  /** A {@code List}, each element converted by {@code each}. */
  public static <E> List<E> toList(final Object raw, final String name, final Function<Object, E> each) {
    return MapValueConversions.toList(raw, name, each);
  }

  /** A {@code Set}, each element converted by {@code each}. */
  public static <E> Set<E> toSet(final Object raw, final String name, final Function<Object, E> each) {
    return MapValueConversions.toSet(raw, name, each);
  }

  /** A {@code Map}, each key and value converted. */
  public static <K, V> Map<K, V> toMap(
    final Object raw,
    final String name,
    final Function<Object, K> eachKey,
    final Function<Object, V> eachValue
  ) {
    return MapValueConversions.toMap(raw, name, eachKey, eachValue);
  }

  /** A type with a generated binder of its own, built by it from a nested map. */
  public static <T> T nested(
    final Object raw,
    final String name,
    final String typeName,
    final Function<Map<String, Object>, T> binder
  ) {
    return MapValueConversions.nested(raw, name, typeName, binder);
  }

  /**
   * The refusal of a map that carries no value for some of the keys {@code target} requires. Each
   * entry names a key beside the property it fills, or is null for a key that carried a value.
   *
   * @param prefix the generated binder's method, which the message opens with
   * @return the refusal to throw
   */
  public static IllegalArgumentException missing(final String prefix, final String target, final String... entries) {
    final var named = new ArrayList<String>(entries.length);
    for (final var entry : entries) if (entry != null) named.add(entry);
    return ValueRefused.missing(prefix, named, target);
  }
}
