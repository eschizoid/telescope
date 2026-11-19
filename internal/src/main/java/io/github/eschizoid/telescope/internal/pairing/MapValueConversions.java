package io.github.eschizoid.telescope.internal.pairing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What a value read from an untyped {@code Map<String, Object>} becomes in the declared type {@link
 * MapValueTypes#classify} sorted it into, or why it is refused. The runtime {@code
 * Telescope.fromMap} calls these conversions directly, the binder generated for {@code @FromMap}
 * calls them through core's {@code FromMapValues}, and the JSON binder calls the ones whose input a
 * JSON token carries, so a value one of them takes is a value all of them take.
 *
 * <p>A conversion keeps every value it is given or refuses it. A number goes into an integral type
 * only when it is whole and in the type's range, so {@code 2.0} reads as {@code 2} and {@code 1.5}
 * and {@code 128} for a {@code byte} are refused. A number goes into a {@code float} or a {@code
 * double} at the nearest value, and is refused where that is an infinity the number is not. A
 * {@code double} or {@code float} is the shortest decimal its {@code toString} writes, for every
 * numeric type alike, so one double is one number wherever it is read. Text is any {@code
 * CharSequence}, and is parsed by the JDK's own parse for the type, as the built-in conversions
 * between two scalars parse it. A boolean is read from a {@code Boolean} or from {@code true} or
 * {@code false} in any case, a character from a {@code Character} or from text one character long,
 * an enum constant from a constant of its type or from text naming it, and a {@code List}, a {@code
 * Set} or a {@code Map} from a collection or a map of the matching shape, each element converted as
 * its own type argument. A JDK value type read from text, such as an {@code Instant} or a {@code
 * Locale}, is read from a value of its type or from text only, and a {@code Locale} from a
 * well-formed language tag.
 *
 * <p>Every refusal is a {@link ValueRefused}, which names the value, what it is not, and where it
 * is: {@code 'lines[1].quantity' got 1.5, not an int}. Each conversion takes the name of the
 * property the value fills, or null for an element, whose container adds its position.
 */
public final class MapValueConversions {

  /**
   * What a refusal says a value is not, for each scalar as a primitive and as its wrapper or class:
   * built once, so a value that converts pays nothing for the words it was never refused in.
   */
  private static final Map<Scalar, String> PRIMITIVE_PHRASES = phrases(true);

  private static final Map<Scalar, String> BOXED_PHRASES = phrases(false);

  private MapValueConversions() {}

  private static Map<Scalar, String> phrases(final boolean primitive) {
    final var phrases = new EnumMap<Scalar, String>(Scalar.class);
    for (final var scalar : Scalar.values()) {
      if (scalar == Scalar.ENUM) continue;
      phrases.put(scalar, PairingMessages.scalarPhrase(scalar, primitive && scalar.primitive() != null, null));
    }
    return phrases;
  }

  /** The phrase {@link PairingMessages#scalarPhrase} gives {@code scalar}, from the table. */
  private static String phrase(final Scalar scalar, final boolean primitive) {
    return (primitive ? PRIMITIVE_PHRASES : BOXED_PHRASES).get(scalar);
  }

  /** What a refusal says a value is not, for a constant of {@code type}. */
  private static String constantOf(final Class<?> type) {
    return PairingMessages.scalarPhrase(Scalar.ENUM, false, type.getSimpleName());
  }

  /** What a refusal says a value is not, for a JDK value type read from text. */
  private static String article(final Class<?> type) {
    return PairingMessages.withArticle(type.getSimpleName());
  }

  /** A {@code byte} or {@code Byte}; {@code primitive} says which a refusal names. */
  public static byte toByte(final Object raw, final boolean primitive, final String name) {
    return (byte) whole(raw, Byte.MIN_VALUE, Byte.MAX_VALUE, Scalar.BYTE, primitive, name);
  }

  /** A {@code short} or {@code Short}. */
  public static short toShort(final Object raw, final boolean primitive, final String name) {
    return (short) whole(raw, Short.MIN_VALUE, Short.MAX_VALUE, Scalar.SHORT, primitive, name);
  }

  /** An {@code int} or {@code Integer}. */
  public static int toInt(final Object raw, final boolean primitive, final String name) {
    return (int) whole(raw, Integer.MIN_VALUE, Integer.MAX_VALUE, Scalar.INT, primitive, name);
  }

  /** A {@code long} or {@code Long}. */
  public static long toLong(final Object raw, final boolean primitive, final String name) {
    return whole(raw, Long.MIN_VALUE, Long.MAX_VALUE, Scalar.LONG, primitive, name);
  }

  /** A {@code double} or {@code Double}. */
  public static double toDouble(final Object raw, final boolean primitive, final String name) {
    final var what = phrase(Scalar.DOUBLE, primitive);
    final double value;
    switch (raw) {
      case Double d -> {
        return d;
      }
      case Float f -> {
        return f;
      }
      case CharSequence text -> value = parsed(raw, text.toString(), Double::parseDouble, what, name);
      case Number n -> value = n.doubleValue();
      case null, default -> throw ValueRefused.value(raw, what, null).under(name);
    }
    if (Double.isInfinite(value) && !namesInfinity(raw)) throw ValueRefused.value(raw, what, null).under(name);
    return value;
  }

  /** A {@code float} or {@code Float}. */
  public static float toFloat(final Object raw, final boolean primitive, final String name) {
    final var what = phrase(Scalar.FLOAT, primitive);
    final float value;
    switch (raw) {
      case Float f -> {
        return f;
      }
      case CharSequence text -> value = parsed(raw, text.toString(), Float::parseFloat, what, name);
      case Number n -> value = n.floatValue();
      case null, default -> throw ValueRefused.value(raw, what, null).under(name);
    }
    if (Float.isInfinite(value) && !namesInfinity(raw)) throw ValueRefused.value(raw, what, null).under(name);
    return value;
  }

  /**
   * A {@code boolean} or {@code Boolean}: a {@code Boolean}, or {@code true} or {@code false} in
   * any case.
   */
  public static boolean toBoolean(final Object raw, final boolean primitive, final String name) {
    if (raw instanceof Boolean b) return b;
    if (raw instanceof CharSequence text) {
      if (text.toString().equalsIgnoreCase("true")) return true;
      if (text.toString().equalsIgnoreCase("false")) return false;
    }
    throw ValueRefused.value(raw, phrase(Scalar.BOOLEAN, primitive), null).under(name);
  }

  /** A {@code char} or {@code Character}: a {@code Character}, or text one character long. */
  public static char toChar(final Object raw, final boolean primitive, final String name) {
    if (raw instanceof Character c) return c;
    final var text = raw instanceof CharSequence sequence ? sequence.toString() : null;
    if (text != null && text.length() == 1) return text.charAt(0);
    throw ValueRefused.value(raw, phrase(Scalar.CHAR, primitive), null).under(name);
  }

  /** A {@code String}, from any text, a {@code CharSequence}; null for null. */
  public static String toText(final Object raw, final String name) {
    if (raw == null) return null;
    if (raw instanceof CharSequence text) return text.toString();
    throw ValueRefused.value(raw, "a String", null).under(name);
  }

  /** A {@code CharSequence}, from any {@code CharSequence}; null for null. */
  public static CharSequence toCharSequence(final Object raw, final String name) {
    if (raw == null || raw instanceof CharSequence) return (CharSequence) raw;
    throw ValueRefused.value(raw, "a CharSequence", null).under(name);
  }

  /**
   * A constant of {@code type}: a constant of it, or the constant text names exactly; null for
   * null. A value of any other class, a constant of another enum included, is refused.
   */
  public static <E extends Enum<E>> E toEnum(final Object raw, final Class<E> type, final String name) {
    if (raw == null || type.isInstance(raw)) return type.cast(raw);
    if (!(raw instanceof CharSequence text)) throw ValueRefused.value(raw, constantOf(type), null).under(name);
    try {
      return Enum.valueOf(type, text.toString());
    } catch (final IllegalArgumentException unknown) {
      throw ValueRefused.value(raw, constantOf(type), unknown).under(name);
    }
  }

  /**
   * A JDK value type {@link MapValueTypes#stringBuilt} names: a value of it, or one built from
   * text, which is any {@code CharSequence}; null for null. A value of any other class is refused
   * rather than read through its {@code toString}, which would turn {@code true} into a {@code
   * Locale} of the language {@code "true"}. {@code BigDecimal} also takes any finite number and
   * {@code BigInteger} any whole one, each exactly.
   */
  public static <T> T toValue(final Object raw, final Class<T> type, final String name) {
    if (raw == null || type.isInstance(raw)) return type.cast(raw);
    if (type == BigInteger.class) return type.cast(bigInteger(raw, phrase(Scalar.BIG_INTEGER, false), name));
    if (type == BigDecimal.class) return type.cast(bigDecimal(raw, phrase(Scalar.BIG_DECIMAL, false), name));
    final var built = MapValueTypes.stringBuilt(type.getName()).orElseThrow(() ->
      new IllegalStateException("no conversion from text into " + type.getName())
    );
    if (!(raw instanceof CharSequence text)) throw ValueRefused.value(raw, article(type), null).under(name);
    try {
      return type.cast(built.build().apply(text.toString()));
    } catch (final RuntimeException unparsable) {
      throw ValueRefused.value(raw, article(type), unparsable).under(name);
    }
  }

  /**
   * A {@code List}, from any collection, each element converted by {@code each}, which answers a
   * null element itself; an empty list for null.
   */
  public static <E> List<E> toList(final Object raw, final String name, final Function<Object, E> each) {
    if (raw == null) return List.of();
    if (!(raw instanceof Collection<?> source)) throw ValueRefused.value(raw, "a List", null).under(name);
    final var out = new ArrayList<E>(source.size());
    addEach(source, each, out, name);
    return out;
  }

  /**
   * A {@code Set}, from any collection, in the order it holds its elements; an empty set for null.
   */
  public static <E> Set<E> toSet(final Object raw, final String name, final Function<Object, E> each) {
    if (raw == null) return Set.of();
    if (!(raw instanceof Collection<?> source)) throw ValueRefused.value(raw, "a Set", null).under(name);
    final var out = LinkedHashSet.<E>newLinkedHashSet(source.size());
    addEach(source, each, out, name);
    return out;
  }

  /**
   * A {@code Map}, from any map, each key and value converted; an empty map for null. A null value
   * is answered by {@code eachValue}, and an element is named by its key.
   */
  public static <K, V> Map<K, V> toMap(
    final Object raw,
    final String name,
    final Function<Object, K> eachKey,
    final Function<Object, V> eachValue
  ) {
    if (raw == null) return Map.of();
    if (!(raw instanceof Map<?, ?> source)) throw ValueRefused.value(raw, "a Map", null).under(name);
    final var out = LinkedHashMap.<K, V>newLinkedHashMap(source.size());
    for (final var entry : source.entrySet()) {
      try {
        out.put(eachKey.apply(entry.getKey()), eachValue.apply(entry.getValue()));
      } catch (final ValueRefused refused) {
        throw refused.at(entry.getKey()).under(name);
      }
    }
    return out;
  }

  /**
   * A type with a binder of its own, built by {@code binder} from a nested map; null for null. A
   * refusal inside it is placed inside {@code name}.
   *
   * @param typeName the type's name in source, which a refusal of a value that is no map names
   */
  @SuppressWarnings("unchecked")
  public static <T> T nested(
    final Object raw,
    final String name,
    final String typeName,
    final Function<Map<String, Object>, T> binder
  ) {
    if (raw == null) return null;
    if (!(raw instanceof Map<?, ?> map)) {
      throw ValueRefused.value(raw, PairingMessages.withArticle(typeName), null).under(name);
    }
    try {
      return binder.apply((Map<String, Object>) map);
    } catch (final ValueRefused refused) {
      throw refused.under(name);
    }
  }

  private static <E> void addEach(
    final Collection<?> source,
    final Function<Object, E> each,
    final Collection<E> out,
    final String name
  ) {
    var index = 0;
    for (final var element : source) {
      try {
        out.add(each.apply(element));
      } catch (final ValueRefused refused) {
        throw refused.at(index).under(name);
      }
      index++;
    }
  }

  /**
   * A whole number in {@code [min, max]}: a number with no fraction, or text {@link Long#parseLong}
   * reads, which accepts what each narrower type's own parse accepts within that type's range.
   */
  private static long whole(
    final Object raw,
    final long min,
    final long max,
    final Scalar to,
    final boolean primitive,
    final String name
  ) {
    final var what = phrase(to, primitive);
    final long value = switch (raw) {
      case Integer i -> i;
      case Long l -> l;
      case Short s -> s;
      case Byte b -> b;
      case CharSequence text -> parsed(raw, text.toString(), Long::parseLong, what, name);
      case Number n -> {
        final var exact = exact(n);
        if (exact == null) throw ValueRefused.value(raw, what, null).under(name);
        try {
          yield exact.longValueExact();
        } catch (final ArithmeticException fractionOrRange) {
          throw ValueRefused.value(raw, what, fractionOrRange).under(name);
        }
      }
      case null, default -> throw ValueRefused.value(raw, what, null).under(name);
    };
    if (value < min || value > max) throw ValueRefused.value(raw, what, null).under(name);
    return value;
  }

  private static BigInteger bigInteger(final Object raw, final String what, final String name) {
    return switch (raw) {
      case Integer i -> BigInteger.valueOf(i);
      case Long l -> BigInteger.valueOf(l);
      case Short s -> BigInteger.valueOf(s);
      case Byte b -> BigInteger.valueOf(b);
      case CharSequence text -> parsed(raw, text.toString(), BigInteger::new, what, name);
      case Number n -> {
        final var exact = exact(n);
        if (exact == null) throw ValueRefused.value(raw, what, null).under(name);
        try {
          yield exact.toBigIntegerExact();
        } catch (final ArithmeticException fraction) {
          throw ValueRefused.value(raw, what, fraction).under(name);
        }
      }
      default -> throw ValueRefused.value(raw, what, null).under(name);
    };
  }

  /**
   * A {@code BigDecimal}: an integer exactly, a {@code double} or {@code float} as its shortest
   * decimal form writes it, and text {@code new BigDecimal} reads.
   */
  private static BigDecimal bigDecimal(final Object raw, final String what, final String name) {
    return switch (raw) {
      case BigInteger i -> new BigDecimal(i);
      case Integer i -> BigDecimal.valueOf(i);
      case Long l -> BigDecimal.valueOf(l);
      case Short s -> BigDecimal.valueOf(s);
      case Byte b -> BigDecimal.valueOf(b);
      case CharSequence text -> parsed(raw, text.toString(), BigDecimal::new, what, name);
      case Number n -> parsed(raw, String.valueOf(n), BigDecimal::new, what, name);
      default -> throw ValueRefused.value(raw, what, null).under(name);
    };
  }

  /**
   * {@code n} exactly, or null for a value no decimal holds, such as an infinity or NaN. A {@code
   * double} or {@code float} is taken at the shortest decimal that reads back as it, the value its
   * {@code toString} writes and a JSON parser reads from the same text, so {@code 1e23} is ten to
   * the 23rd for a {@code BigInteger} as for a {@code BigDecimal}, rather than the binary value
   * nearest it.
   */
  private static BigDecimal exact(final Number n) {
    return switch (n) {
      case BigDecimal d -> d;
      case BigInteger i -> new BigDecimal(i);
      case Double d -> Double.isFinite(d) ? BigDecimal.valueOf(d) : null;
      case Float f -> Float.isFinite(f) ? new BigDecimal(f.toString()) : null;
      default -> {
        try {
          yield new BigDecimal(n.toString());
        } catch (final NumberFormatException notDecimal) {
          yield null;
        }
      }
    };
  }

  /** Whether {@code raw} is itself an infinity, rather than a value that overflows into one. */
  private static boolean namesInfinity(final Object raw) {
    if (raw instanceof CharSequence text) {
      final var bare = text.toString().strip();
      final var unsigned = bare.startsWith("+") || bare.startsWith("-") ? bare.substring(1) : bare;
      return unsigned.equals("Infinity");
    }
    return exact((Number) raw) == null;
  }

  private static <T> T parsed(
    final Object raw,
    final String text,
    final Function<String, T> parse,
    final String what,
    final String name
  ) {
    try {
      return parse.apply(text);
    } catch (final NumberFormatException unparsable) {
      throw ValueRefused.value(raw, what, unparsable).under(name);
    }
  }
}
