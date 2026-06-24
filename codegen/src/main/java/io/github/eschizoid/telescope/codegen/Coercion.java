package io.github.eschizoid.telescope.codegen;

import java.util.Optional;

/**
 * A per-field coercion from a raw {@code Map} value expression to the target field's type, emitted
 * as a Java expression. The processor resolves each field's declared type to one of these, then
 * asks it to {@link #emit(String, int)} the conversion around the raw {@code map.get("key")}
 * expression. Sealed so each strategy is a distinct, independently testable shape; container
 * strategies compose recursively over their element coercion.
 *
 * <p>{@code depth} disambiguates generated local/pattern variable names so nested containers (e.g.
 * {@code List<List<X>>}) don't shadow each other's lambda parameters.
 */
sealed interface Coercion
  permits
    Coercion.Cast,
    Coercion.Parse,
    Coercion.BoolParse,
    Coercion.CharParse,
    Coercion.EnumOf,
    Coercion.Nested,
    Coercion.Listed,
    Coercion.Setted,
    Coercion.MapValues,
    Coercion.OptionalOf,
    Coercion.StringFactory,
    Coercion.Unsupported
{
  /**
   * Emit a Java expression converting {@code raw} (an {@code Object}-typed expression) to the field
   * type.
   */
  String emit(String raw, int depth);

  /**
   * Whether the emitted expression performs an unchecked cast (so the enclosing method needs
   * {@code @SuppressWarnings}).
   */
  default boolean unchecked() {
    return false;
  }

  /**
   * The first {@link Unsupported} reason in this coercion tree (containers delegate to their
   * element/value), or empty when the field is coercible. The processor turns a present reason into
   * a compile error instead of emitting code that would {@code ClassCastException} at runtime.
   */
  default Optional<String> firstUnsupported() {
    return Optional.empty();
  }

  /**
   * Reference type (including {@code String}): a direct cast. A {@code null} raw casts to {@code
   * null}.
   */
  record Cast(String typeName) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      return "(" + typeName + ") " + raw;
    }
  }

  /**
   * {@code String}/{@code Number} to a primitive: take the {@code Number} directly when present,
   * else fall back to the primitive's JLS default when the key is absent, else parse the {@code
   * String} form.
   */
  record Parse(String narrowMethod, String parseMethod, String defaultLiteral) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var v = "__n" + depth;
      return (
        raw +
        " instanceof Number " +
        v +
        " ? " +
        v +
        "." +
        narrowMethod +
        "() : " +
        raw +
        " == null ? " +
        defaultLiteral +
        " : " +
        parseMethod +
        "(String.valueOf(" +
        raw +
        "))"
      );
    }
  }

  /**
   * {@code boolean}/{@code Boolean} target: take an existing {@code Boolean} directly, else parse a
   * {@code String}, else the default ({@code false} for the primitive, {@code null} for the
   * wrapper).
   */
  record BoolParse(String defaultLiteral) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var v = "__b" + depth;
      return (
        raw +
        " instanceof Boolean " +
        v +
        " ? " +
        v +
        " : " +
        raw +
        " == null ? " +
        defaultLiteral +
        " : Boolean.parseBoolean(String.valueOf(" +
        raw +
        "))"
      );
    }
  }

  /**
   * {@code char}/{@code Character} target: take an existing {@code Character} directly, else the
   * first char of the {@code String} form, else the default ({@code '\0'} primitive / {@code null}
   * wrapper).
   */
  record CharParse(String defaultLiteral) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var v = "__c" + depth;
      return (
        raw +
        " instanceof Character " +
        v +
        " ? " +
        v +
        " : " +
        raw +
        " == null || String.valueOf(" +
        raw +
        ").isEmpty() ? " +
        defaultLiteral +
        " : String.valueOf(" +
        raw +
        ").charAt(0)"
      );
    }
  }

  /**
   * Enum target: take an existing enum value directly, else map a {@code String} name via {@code
   * valueOf}.
   */
  record EnumOf(String enumType) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var v = "__e" + depth;
      return (
        raw +
        " instanceof " +
        enumType +
        " " +
        v +
        " ? " +
        v +
        " : " +
        raw +
        " == null ? null : " +
        enumType +
        ".valueOf(String.valueOf(" +
        raw +
        "))"
      );
    }
  }

  /**
   * Nested {@code @FromMap} target: a {@code null}-guarded recursion through its generated
   * converter.
   */
  record Nested(String converterClass) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      return raw + " == null ? null : " + converterClass + ".fromMap((Map<String, Object>) " + raw + ")";
    }

    @Override
    public boolean unchecked() {
      return true;
    }
  }

  /** {@code List<E>} target: stream each element through the element coercion into a fresh list. */
  record Listed(Coercion element) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var list = "__l" + depth;
      final var el = "__el" + depth;
      return (
        raw +
        " instanceof java.util.List<?> " +
        list +
        " ? " +
        list +
        ".stream().map(" +
        el +
        " -> " +
        element.emit(el, depth + 1) +
        ").collect(java.util.stream.Collectors.toList()) : java.util.List.of()"
      );
    }

    @Override
    public boolean unchecked() {
      return element.unchecked();
    }

    @Override
    public Optional<String> firstUnsupported() {
      return element.firstUnsupported();
    }
  }

  /** {@code Set<E>} target: stream each element through the element coercion into a fresh set. */
  record Setted(Coercion element) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var set = "__s" + depth;
      final var el = "__el" + depth;
      return (
        raw +
        " instanceof java.util.Set<?> " +
        set +
        " ? " +
        set +
        ".stream().map(" +
        el +
        " -> " +
        element.emit(el, depth + 1) +
        ").collect(java.util.stream.Collectors.toSet()) : java.util.Set.of()"
      );
    }

    @Override
    public boolean unchecked() {
      return element.unchecked();
    }

    @Override
    public Optional<String> firstUnsupported() {
      return element.firstUnsupported();
    }
  }

  /**
   * {@code Optional<E>} target: wrap the (null-coalescing) element coercion in {@code
   * Optional.ofNullable}.
   */
  record OptionalOf(Coercion element) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      return "java.util.Optional.ofNullable(" + element.emit(raw, depth) + ")";
    }

    @Override
    public boolean unchecked() {
      return element.unchecked();
    }

    @Override
    public Optional<String> firstUnsupported() {
      return element.firstUnsupported();
    }
  }

  /**
   * {@code Map<K, V>} target: coerce both key and value through their coercions into a fresh {@code
   * LinkedHashMap}. Uses a put-accumulating collect (not {@code Collectors.toMap}) so a {@code
   * null} value doesn't throw — matching the lenient spirit of {@code fromMap}.
   */
  record MapValues(Coercion key, Coercion value) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var map = "__m" + depth;
      final var acc = "__acc" + depth;
      final var entry = "__et" + depth;
      return (
        raw +
        " instanceof java.util.Map<?, ?> " +
        map +
        " ? " +
        map +
        ".entrySet().stream().collect(java.util.LinkedHashMap::new, (" +
        acc +
        ", " +
        entry +
        ") -> " +
        acc +
        ".put(" +
        key.emit(entry + ".getKey()", depth + 1) +
        ", " +
        value.emit(entry + ".getValue()", depth + 1) +
        "), java.util.LinkedHashMap::putAll) : java.util.Map.of()"
      );
    }

    @Override
    public boolean unchecked() {
      return true;
    }

    @Override
    public Optional<String> firstUnsupported() {
      return key.firstUnsupported().or(value::firstUnsupported);
    }
  }

  /**
   * A JDK value type with a well-known String factory ({@code Instant.parse}, {@code
   * UUID.fromString}, {@code new BigDecimal}, …): take an existing instance directly, else build it
   * from the value's {@code String} form — the shape these arrive in from an untyped map.
   */
  record StringFactory(String typeName, String factory) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var v = "__sf" + depth;
      return (
        raw +
        " instanceof " +
        typeName +
        " " +
        v +
        " ? " +
        v +
        " : " +
        raw +
        " == null ? null : " +
        factory +
        "(String.valueOf(" +
        raw +
        "))"
      );
    }
  }

  /**
   * A field type {@code @FromMap} can't coerce (a nested object that isn't {@code @FromMap}, a
   * collection subtype, a type variable). Never emitted — the processor reports {@link #reason} as
   * a compile error and skips the converter, upholding "if it compiles, it runs".
   */
  record Unsupported(String reason) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      throw new IllegalStateException("Unsupported coercion must not be emitted: " + reason);
    }

    @Override
    public Optional<String> firstUnsupported() {
      return Optional.of(reason);
    }
  }
}
