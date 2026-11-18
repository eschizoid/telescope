package io.github.eschizoid.telescope.codegen;

import java.util.Optional;

/**
 * A per-field coercion from a raw {@code Map} value expression to the target field's type, emitted
 * as a Java expression. The processor resolves each field's declared type to one of these, then
 * asks it to {@link #emit} the conversion around the local the map value was read into. Sealed so
 * each strategy is a distinct, independently testable shape; container strategies compose
 * recursively over their element coercion.
 *
 * <p>Every conversion is a call into core's {@code FromMapValues}, which hands on the conversions
 * the runtime {@code Telescope.fromMap} applies, so the generated binder takes and refuses the same
 * values in the same words. What an expression adds itself is the default a missing value takes.
 *
 * <p>Every type an emitted expression names is written by its qualified name, {@code java.lang}
 * included. The binder lives in its target's package, where a simple name means whatever that
 * package declares: a type there named {@code String} shadows {@code java.lang.String}, and an
 * import would shadow a type there of the same name or collide with another import.
 *
 * <p>{@code depth} disambiguates generated lambda parameter names so nested containers (e.g. {@code
 * List<List<X>>}) don't shadow each other's.
 */
sealed interface Coercion
  permits
    Coercion.Scalar,
    Coercion.Text,
    Coercion.AsIs,
    Coercion.EnumOf,
    Coercion.Value,
    Coercion.Nested,
    Coercion.Listed,
    Coercion.Setted,
    Coercion.MapValues,
    Coercion.OptionalOf,
    Coercion.Unsupported
{
  /** The class every emitted conversion calls. */
  String VALUES = "io.github.eschizoid.telescope.conversion.FromMapValues";

  /**
   * Emit a Java expression converting {@code raw} (an {@code Object}-typed expression) to the field
   * type.
   *
   * @param name a Java expression for the property's name, a string literal, or {@code null} for an
   *     element, whose container names where it is
   */
  String emit(String raw, String name, int depth);

  /**
   * The first {@link Unsupported} reason in this coercion tree (containers delegate to their
   * element/value), or empty when the field is coercible. The processor turns a present reason into
   * a compile error instead of emitting code that would fail on every map.
   */
  default Optional<String> firstUnsupported() {
    return Optional.empty();
  }

  /** Simple name of a fully-qualified name (the segment after the last dot). */
  static String simple(final String fqn) {
    final var dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }

  /**
   * A generated lambda parameter name, scoped by {@code depth} so nested containers don't shadow
   * each other. The {@code __} prefix (avoiding any user identifier) lives here, in one place,
   * rather than inlined per permit.
   */
  static String gensym(final String tag, final int depth) {
    return "__" + tag + depth;
  }

  /**
   * A primitive or its wrapper, through the {@code FromMapValues} method named {@code method}. A
   * missing value is {@code defaultLiteral}: the primitive's default, or {@code null} for the
   * wrapper.
   */
  record Scalar(String method, boolean primitive, String defaultLiteral) implements Coercion {
    @Override
    public String emit(final String raw, final String name, final int depth) {
      return (
        raw +
        " == null ? " +
        defaultLiteral +
        " : " +
        VALUES +
        "." +
        method +
        "(" +
        raw +
        ", " +
        primitive +
        ", " +
        name +
        ")"
      );
    }
  }

  /** {@code String} or {@code CharSequence}, through the {@code FromMapValues} method named. */
  record Text(String method) implements Coercion {
    @Override
    public String emit(final String raw, final String name, final int depth) {
      return VALUES + "." + method + "(" + raw + ", " + name + ")";
    }
  }

  /** {@code Object}, which takes the value as it is. */
  record AsIs() implements Coercion {
    @Override
    public String emit(final String raw, final String name, final int depth) {
      return raw;
    }
  }

  /** An enum: a constant of it, or the constant the value's text names. */
  record EnumOf(String fqn) implements Coercion {
    @Override
    public String emit(final String raw, final String name, final int depth) {
      return VALUES + ".toEnum(" + raw + ", " + fqn + ".class, " + name + ")";
    }
  }

  /**
   * A JDK value type built from its text ({@code Instant}, {@code UUID}, {@code BigDecimal}, …): a
   * value of it, or one built from its text form.
   */
  record Value(String fqn) implements Coercion {
    @Override
    public String emit(final String raw, final String name, final int depth) {
      return VALUES + ".toValue(" + raw + ", " + fqn + ".class, " + name + ")";
    }
  }

  /**
   * Nested {@code @FromMap} target: built by its generated converter from a nested map.
   *
   * @param simpleName the target's name in source, which a refusal of a value that is no map names
   */
  record Nested(String converterFqn, String simpleName) implements Coercion {
    @Override
    public String emit(final String raw, final String name, final int depth) {
      return (VALUES + ".nested(" + raw + ", " + name + ", \"" + simpleName + "\", " + converterFqn + "::fromMap)");
    }
  }

  /** {@code List<E>} target: each element through the element coercion into a fresh list. */
  record Listed(Coercion element) implements Coercion {
    @Override
    public String emit(final String raw, final String name, final int depth) {
      final var el = gensym("el", depth);
      return VALUES + ".toList(" + raw + ", " + name + ", " + el + " -> " + element.emit(el, "null", depth + 1) + ")";
    }

    @Override
    public Optional<String> firstUnsupported() {
      return element.firstUnsupported();
    }
  }

  /** {@code Set<E>} target: each element through the element coercion into a fresh set. */
  record Setted(Coercion element) implements Coercion {
    @Override
    public String emit(final String raw, final String name, final int depth) {
      final var el = gensym("el", depth);
      return VALUES + ".toSet(" + raw + ", " + name + ", " + el + " -> " + element.emit(el, "null", depth + 1) + ")";
    }

    @Override
    public Optional<String> firstUnsupported() {
      return element.firstUnsupported();
    }
  }

  /**
   * {@code Optional<E>} target: an absent value is an empty {@code Optional}, and a present one is
   * wrapped after the element coercion has run.
   *
   * <p>The absence is tested here rather than left to {@code ofNullable}, because an element
   * coercion answers for its own type before this sees the result: a container answers with an
   * empty container for a null, so the wrap would receive something non-null and report a key that
   * carried nothing as a key that carried an empty list. An empty {@code Optional} is what the
   * runtime mapper leaves for the same source, and it is the reading the source supports.
   */
  record OptionalOf(Coercion element) implements Coercion {
    @Override
    public String emit(final String raw, final String name, final int depth) {
      return (
        raw +
        " == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(" +
        element.emit(raw, name, depth) +
        ")"
      );
    }

    @Override
    public Optional<String> firstUnsupported() {
      return element.firstUnsupported();
    }
  }

  /** {@code Map<K, V>} target: both key and value coerced into a fresh map, a null value kept. */
  record MapValues(Coercion key, Coercion value) implements Coercion {
    @Override
    public String emit(final String raw, final String name, final int depth) {
      final var k = gensym("k", depth);
      final var v = gensym("v", depth);
      return (
        VALUES +
        ".toMap(" +
        raw +
        ", " +
        name +
        ", " +
        k +
        " -> " +
        key.emit(k, "null", depth + 1) +
        ", " +
        v +
        " -> " +
        value.emit(v, "null", depth + 1) +
        ")"
      );
    }

    @Override
    public Optional<String> firstUnsupported() {
      return key.firstUnsupported().or(value::firstUnsupported);
    }
  }

  /**
   * A field type {@code @FromMap} can't coerce (a nested object that isn't {@code @FromMap}, a
   * collection subtype, a type variable). Never emitted — the processor reports {@link #reason} as
   * a compile error and skips the converter, upholding "if it compiles, it runs".
   */
  record Unsupported(String reason) implements Coercion {
    @Override
    public String emit(final String raw, final String name, final int depth) {
      throw new IllegalStateException("Unsupported coercion must not be emitted: " + reason);
    }

    @Override
    public Optional<String> firstUnsupported() {
      return Optional.of(reason);
    }
  }
}
