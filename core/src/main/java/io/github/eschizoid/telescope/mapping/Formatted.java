package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import io.github.eschizoid.telescope.internal.pairing.Scalar;
import io.github.eschizoid.telescope.internal.pairing.TextFormats;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.ParsePosition;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * The runtime side of the formatted rows: the conversion each one writes into a {@link
 * TypedTransformTo} or a {@link ForwardOnlyTransformTo}, after the shared spec in {@link
 * TextFormats} accepts its pattern for the property's class. The generated bridge makes the same
 * JDK calls, so both paths write the same text and read back the same values.
 *
 * <p>A null value converts to null in either direction. A date or a time is written and read by one
 * {@link DateTimeFormatter}, which is immutable. A number is written and read by a copy of one
 * {@link DecimalFormat}, made for each call, since a {@code DecimalFormat} is not safe to share
 * between threads. Text that does not read back fails with the target property, the text and the
 * format named, in the words of {@link PairingMessages#notAValue}.
 */
final class Formatted {

  private Formatted() {}

  /**
   * The row {@code row} formatting the property {@code src} reads into {@code tgt} through {@code
   * pattern}, in {@code zone} where it is not null, and with the number symbols of {@code locale},
   * or of the default format locale where it is null.
   *
   * @throws IllegalArgumentException where the shared spec refuses the pattern for the property
   */
  static <A, B, X> Mapping<A, B> row(
    final String row,
    final Accessor<A, X> src,
    final Accessor<B, String> tgt,
    final String pattern,
    final ZoneId zone,
    final Locale locale
  ) {
    Objects.requireNonNull(src, () -> "Mapping." + row + ": source accessor is null");
    Objects.requireNonNull(tgt, () -> "Mapping." + row + ": target accessor is null");
    Objects.requireNonNull(pattern, () -> "Mapping." + row + ": pattern is null");
    final var returned = LambdaIntrospection.returnedNameOf(src);
    final var primitive = returned != null && !returned.contains(".");
    final var typeName = primitive ? wrapperOf(returned) : returned;
    final var shown =
      returned == null
        ? "an array"
        : returned.substring(Math.max(returned.lastIndexOf('.'), returned.lastIndexOf('$')) + 1);
    final var refusal = TextFormats.refusal(
      row,
      propertyOf(src),
      String.valueOf(typeName),
      shown,
      pattern,
      zone != null
    );
    if (refusal != null) throw new IllegalArgumentException(refusal);
    final var target = propertyOf(tgt);
    final var phrase = PairingMessages.formattedPhrase(shown, pattern);
    final Function<Object, String> forward;
    final Function<String, Object> backward;
    if (TextFormats.formatsNumber(row)) {
      final var prototype = TextFormats.numberFormat(
        pattern,
        locale != null ? locale : Locale.getDefault(Locale.Category.FORMAT)
      );
      final var scalar = TextFormats.numberOf(typeName);
      forward = value -> value == null ? null : ((DecimalFormat) prototype.clone()).format(value);
      backward = text ->
        text == null ? null : parseNumber((DecimalFormat) prototype.clone(), scalar, text, target, phrase);
    } else {
      final var compiled = DateTimeFormatter.ofPattern(pattern);
      final var formatter = zone != null ? compiled.withZone(zone) : compiled;
      final var query = TextFormats.temporalQuery(typeName);
      forward = value -> value == null ? null : formatter.format((TemporalAccessor) value);
      backward = text -> {
        if (text == null) return null;
        try {
          return formatter.parse(text, query);
        } catch (final DateTimeException e) {
          throw new IllegalArgumentException(PairingMessages.notAValue(target, text, phrase), e);
        }
      };
    }
    @SuppressWarnings("unchecked")
    final var forwardX = (Function<? super X, ? extends String>) (Function<?, ?>) new Forward(row, forward);
    @SuppressWarnings("unchecked")
    final var backwardX = (Function<? super String, ? extends X>) (Function<?, ?>) backward;
    return TextFormats.oneWay(row)
      ? new ForwardOnlyTransformTo<>(src, tgt, forwardX)
      : new TypedTransformTo<>(src, tgt, forwardX, backwardX);
  }

  /**
   * The forward conversion of a formatted row, carrying {@code row}, the name of the factory that
   * built it, which a refusal of a forward-only row names.
   */
  record Forward(String row, Function<Object, String> format) implements Function<Object, String> {
    @Override
    public String apply(final Object value) {
      return format.apply(value);
    }
  }

  /**
   * The number {@code text} reads as through {@code format}, which must read all of it, converted
   * into {@code scalar}: exactly for every number but a {@code float} or a {@code double}, which
   * take the nearest value.
   */
  private static Object parseNumber(
    final DecimalFormat format,
    final Scalar scalar,
    final String text,
    final String target,
    final String phrase
  ) {
    final var position = new ParsePosition(0);
    final var parsed = format.parse(text, position);
    if (parsed == null || position.getIndex() != text.length()) {
      throw new IllegalArgumentException(PairingMessages.notAValue(target, text, phrase));
    }
    if (scalar == Scalar.FLOAT) return parsed.floatValue();
    if (scalar == Scalar.DOUBLE) return parsed.doubleValue();
    if (!(parsed instanceof BigDecimal exact)) {
      throw new IllegalArgumentException(PairingMessages.notAValue(target, text, phrase));
    }
    try {
      return switch (scalar) {
        case BYTE -> exact.byteValueExact();
        case SHORT -> exact.shortValueExact();
        case INT -> exact.intValueExact();
        case LONG -> exact.longValueExact();
        case BIG_INTEGER -> exact.toBigIntegerExact();
        default -> exact;
      };
    } catch (final ArithmeticException e) {
      throw new IllegalArgumentException(PairingMessages.notAValue(target, text, phrase), e);
    }
  }

  /** The binary name of the wrapper of the primitive named {@code primitive}. */
  private static String wrapperOf(final String primitive) {
    return MethodType.methodType(primitiveClass(primitive)).wrap().returnType().getName();
  }

  private static Class<?> primitiveClass(final String name) {
    return switch (name) {
      case "boolean" -> boolean.class;
      case "byte" -> byte.class;
      case "short" -> short.class;
      case "char" -> char.class;
      case "int" -> int.class;
      case "long" -> long.class;
      case "float" -> float.class;
      default -> double.class;
    };
  }

  /** The property an accessor reads, named as the generated bridge names it. */
  private static String propertyOf(final Accessor<?, ?> accessor) {
    final var method = LambdaIntrospection.methodNameOf(accessor);
    return LambdaIntrospection.receiverClassOf(accessor).isRecord() ? method : PropertyNames.property(method);
  }
}
