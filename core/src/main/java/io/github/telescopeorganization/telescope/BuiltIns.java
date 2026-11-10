package io.github.telescopeorganization.telescope;

import io.github.telescopeorganization.telescope.internal.optics.Iso;
import io.github.telescopeorganization.telescope.internal.pairing.PairingMessages;
import io.github.telescopeorganization.telescope.internal.pairing.Scalar;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.function.Function;

/**
 * The runtime side of the conversions the shared pairing spec builds in between two scalars: the
 * {@code Iso} a property of one scalar takes into a property of another, each direction calling the
 * JDK method the generated bridge calls for the same pair, so both paths parse, write and widen a
 * value alike and fail on the same values with the same message.
 *
 * <p>A null converts to null, or to the default of a primitive it is written into. A {@code String}
 * is parsed by the number's own {@code parseX} or {@code String} constructor, an enum's {@code
 * valueOf}, and for a boolean as {@code true} or {@code false} in any case; a {@code String} none
 * of these accepts fails with the property and the value named. A value is written as text by
 * {@code String.valueOf}, which for an enum constant is its name.
 */
final class BuiltIns {

  private BuiltIns() {}

  /**
   * The conversion between a property of {@code source}, the scalar {@code from}, and one of {@code
   * target}, the scalar {@code to}. A pair the spec converts forward only, a widening, has a
   * backward that refuses, which no forward-only mapper calls. {@code enumName} is the name in
   * source of the enum on either side, or null where neither is one.
   */
  static Iso<Object, Object> iso(
    final Class<?> source,
    final Class<?> target,
    final Scalar from,
    final Scalar to,
    final String componentName,
    final String enumName
  ) {
    final var forward = direction(source, target, from, to, componentName, enumName);
    if (from != Scalar.STRING && to != Scalar.STRING) {
      return Iso.of(forward, value -> {
        throw new UnsupportedOperationException(
          PairingMessages.wideningOneWay(componentName, source.getTypeName(), target.getTypeName())
        );
      });
    }
    return Iso.of(forward, direction(target, source, to, from, componentName, enumName));
  }

  /** One direction: from a value of {@code in}, the scalar {@code from}, to {@code out}'s. */
  private static Function<Object, Object> direction(
    final Class<?> in,
    final Class<?> out,
    final Scalar from,
    final Scalar to,
    final String componentName,
    final String enumName
  ) {
    final Object onNull = out.isPrimitive() ? Placeholders.primitiveDefault(out) : null;
    final Function<Object, Object> convert;
    if (from == Scalar.STRING) {
      convert = parse(out, to, componentName, PairingMessages.scalarPhrase(to, out.isPrimitive(), enumName));
    } else if (to == Scalar.STRING) {
      convert = from == Scalar.ENUM ? value -> ((Enum<?>) value).name() : String::valueOf;
    } else {
      convert = widen(from, to);
    }
    return value -> value == null ? onNull : convert.apply(value);
  }

  /** The parse of a {@code String} into {@code to}, held in the class {@code out}. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Function<Object, Object> parse(
    final Class<?> out,
    final Scalar to,
    final String componentName,
    final String what
  ) {
    final Function<String, Object> parse = switch (to) {
      case BYTE -> Byte::parseByte;
      case SHORT -> Short::parseShort;
      case INT -> Integer::parseInt;
      case LONG -> Long::parseLong;
      case FLOAT -> Float::parseFloat;
      case DOUBLE -> Double::parseDouble;
      case BIG_INTEGER -> BigInteger::new;
      case BIG_DECIMAL -> BigDecimal::new;
      case BOOLEAN -> BuiltIns::parseBoolean;
      case ENUM -> text -> Enum.valueOf((Class) out, text);
      case CHAR, STRING -> throw new IllegalStateException("no built-in parse into " + to);
    };
    return value -> {
      final var text = (String) value;
      try {
        return parse.apply(text);
      } catch (final IllegalArgumentException e) {
        // NumberFormatException is one, and so is what Enum.valueOf throws for an unknown name.
        throw new IllegalArgumentException(PairingMessages.notAValue(componentName, text, what), e);
      }
    };
  }

  /** {@code true} or {@code false} in any case, and nothing else. */
  private static Boolean parseBoolean(final String text) {
    if (text.equalsIgnoreCase("true")) return Boolean.TRUE;
    if (text.equalsIgnoreCase("false")) return Boolean.FALSE;
    throw new IllegalArgumentException(text);
  }

  /** The widening of a value of the number {@code from} into {@code to}. */
  private static Function<Object, Object> widen(final Scalar from, final Scalar to) {
    final Function<Object, Number> number =
      from == Scalar.CHAR ? value -> (int) (Character) value : value -> (Number) value;
    return switch (to) {
      case SHORT -> value -> number.apply(value).shortValue();
      case INT -> value -> number.apply(value).intValue();
      case LONG -> value -> number.apply(value).longValue();
      case FLOAT -> value -> number.apply(value).floatValue();
      case DOUBLE -> value -> number.apply(value).doubleValue();
      case BIG_INTEGER -> value -> BigInteger.valueOf(number.apply(value).longValue());
      case BIG_DECIMAL -> from == Scalar.BIG_INTEGER
        ? value -> new BigDecimal((BigInteger) value)
        : value -> BigDecimal.valueOf(number.apply(value).longValue());
      default -> throw new IllegalStateException("no built-in widening from " + from + " to " + to);
    };
  }
}
