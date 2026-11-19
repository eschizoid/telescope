package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.internal.pairing.MapValueConversions;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.Scalar;
import io.github.eschizoid.telescope.internal.pairing.ValueRefused;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

/**
 * <b>Codegen-support seam — NOT for hand-written call sites.</b> How one JSON value becomes a value
 * of one declared type, for the binder {@link TelescopeJson#fromJson} builds and for the binder
 * generated for {@link FromJson}, which call the same methods, so the two read the same document
 * into the same value and refuse the same values in the same words.
 *
 * <p>Each method is called with the parser on the value's first token, which is never a JSON {@code
 * null}: the caller answers that itself with the property's default. It leaves the parser on the
 * value's last token. A value the declared type does not take is refused with an exception that a
 * binder turns into the {@link IllegalArgumentException} its caller sees, once every level has
 * added where the value is through {@link #under}.
 */
public final class JsonReads {

  private JsonReads() {}

  /**
   * How a value of one declared type is read from the parser, for an element of a container and for
   * a nested object.
   *
   * @param <T> the declared type
   */
  @FunctionalInterface
  public interface Reader<T> {
    /** The value under the parser's current token. */
    T read(JsonParser parser);
  }

  /** An {@code int}, or an {@code Integer} where {@code primitive} is false, from an integer. */
  public static int readInt(final JsonParser parser, final boolean primitive) {
    integer(parser, Scalar.INT, primitive);
    if (parser.getNumberType() == JsonParser.NumberType.INT) return parser.getIntValue();
    try {
      return MapValueConversions.toInt(parser.getNumberValue(), primitive, null);
    } catch (final ValueRefused outOfRange) {
      throw refused(parser, Scalar.INT, primitive, outOfRange);
    }
  }

  /** A {@code long}, or a {@code Long}, from an integer. */
  public static long readLong(final JsonParser parser, final boolean primitive) {
    integer(parser, Scalar.LONG, primitive);
    final var type = parser.getNumberType();
    if (type == JsonParser.NumberType.INT || type == JsonParser.NumberType.LONG) return parser.getLongValue();
    try {
      return MapValueConversions.toLong(parser.getNumberValue(), primitive, null);
    } catch (final ValueRefused outOfRange) {
      throw refused(parser, Scalar.LONG, primitive, outOfRange);
    }
  }

  /** A {@code short}, or a {@code Short}, from an integer. */
  public static short readShort(final JsonParser parser, final boolean primitive) {
    integer(parser, Scalar.SHORT, primitive);
    try {
      return MapValueConversions.toShort(parser.getNumberValue(), primitive, null);
    } catch (final ValueRefused outOfRange) {
      throw refused(parser, Scalar.SHORT, primitive, outOfRange);
    }
  }

  /**
   * A {@code byte}, or a {@code Byte}, from an integer. jackson-core's own byte reading takes 128
   * through 255 as unsigned and wraps them, so the range is the shared rule's.
   */
  public static byte readByte(final JsonParser parser, final boolean primitive) {
    integer(parser, Scalar.BYTE, primitive);
    try {
      return MapValueConversions.toByte(parser.getNumberValue(), primitive, null);
    } catch (final ValueRefused outOfRange) {
      throw refused(parser, Scalar.BYTE, primitive, outOfRange);
    }
  }

  /**
   * A {@code double}, or a {@code Double}, from any number in its range. A JSON number is never an
   * infinity, so one that reads as an infinity is past the range.
   */
  public static double readDouble(final JsonParser parser, final boolean primitive) {
    number(parser, Scalar.DOUBLE, primitive);
    final var value = parser.getDoubleValue();
    if (Double.isInfinite(value)) throw refused(parser, Scalar.DOUBLE, primitive, null);
    return value;
  }

  /** A {@code float}, or a {@code Float}, from any number in its range. */
  public static float readFloat(final JsonParser parser, final boolean primitive) {
    number(parser, Scalar.FLOAT, primitive);
    final var value = parser.getFloatValue();
    if (Float.isInfinite(value)) throw refused(parser, Scalar.FLOAT, primitive, null);
    return value;
  }

  /** A {@code boolean}, or a {@code Boolean}, from {@code true} or {@code false}. */
  public static boolean readBoolean(final JsonParser parser, final boolean primitive) {
    return switch (parser.currentToken()) {
      case VALUE_TRUE -> true;
      case VALUE_FALSE -> false;
      default -> throw refused(parser, Scalar.BOOLEAN, primitive, null);
    };
  }

  /** A {@code char}, or a {@code Character}, from a string one character long. */
  public static char readChar(final JsonParser parser, final boolean primitive) {
    if (parser.currentToken() != JsonToken.VALUE_STRING) throw refused(parser, Scalar.CHAR, primitive, null);
    try {
      return MapValueConversions.toChar(parser.getString(), primitive, null);
    } catch (final ValueRefused notOne) {
      throw refused(parser, Scalar.CHAR, primitive, notOne);
    }
  }

  /** A {@code String}, from a string as it stands. */
  public static String readText(final JsonParser parser) {
    if (parser.currentToken() != JsonToken.VALUE_STRING) throw Refused.value(parser, "a String", null);
    return parser.getString();
  }

  /** A {@code CharSequence}, from a string as it stands. */
  public static CharSequence readCharSequence(final JsonParser parser) {
    if (parser.currentToken() != JsonToken.VALUE_STRING) throw Refused.value(parser, "a CharSequence", null);
    return parser.getString();
  }

  /** A constant of {@code type}, from a string holding its exact name. */
  public static <E extends Enum<E>> E readEnum(final JsonParser parser, final Class<E> type) {
    if (parser.currentToken() == JsonToken.VALUE_STRING) {
      try {
        return MapValueConversions.toEnum(parser.getString(), type, null);
      } catch (final ValueRefused unknown) {
        throw Refused.value(parser, constantOf(type), unknown);
      }
    }
    throw Refused.value(parser, constantOf(type), null);
  }

  /**
   * A JDK value type. {@code BigDecimal} and {@code BigInteger} are numbers in JSON and are read
   * from a number token, every digit kept; every other one is read from its text, by the shared
   * rule.
   */
  public static <T> T readValue(final JsonParser parser, final Class<T> type) {
    final var token = parser.currentToken();
    if (type == BigDecimal.class) {
      if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
        throw Refused.value(parser, "a BigDecimal", null);
      }
      return type.cast(parser.getDecimalValue());
    }
    if (type == BigInteger.class) {
      if (token != JsonToken.VALUE_NUMBER_INT) throw Refused.value(parser, "a BigInteger", null);
      return type.cast(parser.getBigIntegerValue());
    }
    if (token != JsonToken.VALUE_STRING) throw Refused.value(
      parser,
      PairingMessages.withArticle(type.getSimpleName()),
      null
    );
    try {
      return MapValueConversions.toValue(parser.getString(), type, null);
    } catch (final ValueRefused unparsable) {
      throw Refused.value(parser, PairingMessages.withArticle(type.getSimpleName()), unparsable.getCause());
    }
  }

  /**
   * The value as the document writes it, for a property declared {@code Object} and for a row's
   * converter: an object as a {@code Map<String, Object>}, an array as a {@code List<Object>}, an
   * integer as the smallest of {@code Integer}, {@code Long} and {@code BigInteger} that holds it,
   * and a fraction as a {@code BigDecimal}, so no digit is lost.
   */
  public static Object readNatural(final JsonParser parser) {
    return switch (parser.currentToken()) {
      case START_OBJECT -> {
        final var out = new LinkedHashMap<String, Object>();
        for (var name = parser.nextName(); name != null; name = parser.nextName()) {
          parser.nextToken();
          out.put(name, readNatural(parser));
        }
        yield out;
      }
      case START_ARRAY -> {
        final var out = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) out.add(readNatural(parser));
        yield out;
      }
      case VALUE_STRING -> parser.getString();
      case VALUE_NUMBER_INT -> parser.getNumberValue();
      case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
      case VALUE_TRUE -> Boolean.TRUE;
      case VALUE_FALSE -> Boolean.FALSE;
      case VALUE_NULL -> null;
      default -> throw new IllegalStateException("not a value token: " + parser.currentToken());
    };
  }

  /**
   * A record or bean from a JSON object, built by {@code object}, which is handed the parser on the
   * object's {@code START_OBJECT} and leaves it on the matching {@code END_OBJECT}.
   *
   * @param typeName the type's name in source, which a refusal of a value that is no object names
   */
  public static <T> T readObject(final JsonParser parser, final String typeName, final Reader<T> object) {
    if (parser.currentToken() != JsonToken.START_OBJECT) {
      throw Refused.value(parser, PairingMessages.withArticle(typeName), null);
    }
    return object.read(parser);
  }

  /** A {@code List} from an array; a {@code null} element is {@code absent}. */
  public static <E> List<E> readList(final JsonParser parser, final Reader<E> element, final E absent) {
    if (parser.currentToken() != JsonToken.START_ARRAY) throw Refused.value(parser, "a List", null);
    final var out = new ArrayList<E>();
    addElements(parser, element, absent, out);
    return out;
  }

  /** A {@code Set} from an array, in the order the array holds its elements. */
  public static <E> Set<E> readSet(final JsonParser parser, final Reader<E> element, final E absent) {
    if (parser.currentToken() != JsonToken.START_ARRAY) throw Refused.value(parser, "a Set", null);
    final var out = new LinkedHashSet<E>();
    addElements(parser, element, absent, out);
    return out;
  }

  /** An {@code Optional} holding the value; the caller answers a {@code null} with empty. */
  public static <E> Optional<E> readOptional(final JsonParser parser, final Reader<E> element) {
    return Optional.ofNullable(element.read(parser));
  }

  /**
   * A {@code Map} from an object. A JSON key is text, so the key is read from its text by the
   * shared rule for {@code keyType}: a {@code String} as it stands, an enum constant by its exact
   * name, a primitive's wrapper by its literal, or a JDK value type by its text form. A key that
   * appears twice keeps the value it holds last, and a {@code null} value is {@code absent}.
   */
  public static <K, V> Map<K, V> readMap(
    final JsonParser parser,
    final Class<K> keyType,
    final Reader<V> value,
    final V absent
  ) {
    if (parser.currentToken() != JsonToken.START_OBJECT) throw Refused.value(parser, "a Map", null);
    final var out = new LinkedHashMap<K, V>();
    for (var name = parser.nextName(); name != null; name = parser.nextName()) {
      try {
        final var key = key(parser, keyType, name);
        out.put(key, parser.nextToken() == JsonToken.VALUE_NULL ? absent : value.read(parser));
      } catch (final Refused refused) {
        throw refused.at(name);
      }
    }
    return out;
  }

  /**
   * {@code failure}, placed inside the property {@code name}, read from the key {@code key}, when
   * it is a refusal of a value, and {@code failure} itself otherwise.
   *
   * @return the exception to throw
   */
  public static RuntimeException under(final RuntimeException failure, final String name, final String key) {
    return failure instanceof Refused refused ? refused.under(name, key) : failure;
  }

  /**
   * The refusal of an object, the parser on its {@code END_OBJECT}, that carries no value for some
   * of the keys {@code target} requires. Each entry names a key beside the property it fills,
   * {@code "sku" (component 'sku')}, or is null for a key that carried a value.
   *
   * @return the refusal to throw
   */
  public static RuntimeException missing(final JsonParser parser, final String target, final String... entries) {
    final var named = new ArrayList<String>(entries.length);
    for (final var entry : entries) if (entry != null) named.add(entry);
    return Refused.missing(parser, List.copyOf(named), target);
  }

  private static <E> void addElements(
    final JsonParser parser,
    final Reader<E> element,
    final E absent,
    final Collection<E> out
  ) {
    var index = 0;
    for (var token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
      if (token == JsonToken.VALUE_NULL) {
        out.add(absent);
      } else {
        try {
          out.add(element.read(parser));
        } catch (final Refused refused) {
          throw refused.at(index);
        }
      }
      index++;
    }
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <K> K key(final JsonParser parser, final Class<K> type, final String name) {
    if (type == String.class || type == CharSequence.class) return type.cast(name);
    final Object key;
    try {
      if (type.isEnum()) key = MapValueConversions.toEnum(name, (Class) type, null);
      else if (type == Integer.class) key = MapValueConversions.toInt(name, false, null);
      else if (type == Long.class) key = MapValueConversions.toLong(name, false, null);
      else if (type == Short.class) key = MapValueConversions.toShort(name, false, null);
      else if (type == Byte.class) key = MapValueConversions.toByte(name, false, null);
      else if (type == Double.class) key = MapValueConversions.toDouble(name, false, null);
      else if (type == Float.class) key = MapValueConversions.toFloat(name, false, null);
      else if (type == Boolean.class) key = MapValueConversions.toBoolean(name, false, null);
      else if (type == Character.class) key = MapValueConversions.toChar(name, false, null);
      else key = MapValueConversions.toValue(name, type, null);
    } catch (final ValueRefused unparsable) {
      throw Refused.value(parser, keyPhrase(type), unparsable);
    }
    return type.cast(key);
  }

  private static String keyPhrase(final Class<?> type) {
    if (type.isEnum()) return constantOf(type);
    for (final var scalar : Scalar.values()) {
      if (scalar.primitive() != null && type.getName().equals(scalar.className())) {
        return PairingMessages.scalarPhrase(scalar, false, null);
      }
    }
    return PairingMessages.withArticle(type.getSimpleName());
  }

  private static String constantOf(final Class<?> type) {
    return PairingMessages.scalarPhrase(Scalar.ENUM, false, type.getSimpleName());
  }

  private static void integer(final JsonParser parser, final Scalar scalar, final boolean primitive) {
    if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw refused(parser, scalar, primitive, null);
  }

  private static void number(final JsonParser parser, final Scalar scalar, final boolean primitive) {
    final var token = parser.currentToken();
    if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
      throw refused(parser, scalar, primitive, null);
    }
  }

  private static Refused refused(
    final JsonParser parser,
    final Scalar scalar,
    final boolean primitive,
    final Throwable cause
  ) {
    return Refused.value(parser, PairingMessages.scalarPhrase(scalar, primitive, null), cause);
  }
}
