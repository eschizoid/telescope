package io.github.eschizoid.telescope.codegen;

/**
 * A per-field coercion from a raw {@code Map} value expression to the target field's type, emitted
 * as a Java expression. The processor resolves each field's declared type (and any {@code @Extract}
 * override) to one of these, then asks it to {@link #emit(String, int)} the conversion around the
 * raw {@code map.get("key")} expression. Sealed so each strategy is a distinct, independently
 * testable shape; container strategies compose recursively over their element coercion.
 *
 * <p>{@code depth} disambiguates generated local/pattern variable names so nested containers (e.g.
 * {@code List<List<X>>}) don't shadow each other's lambda parameters.
 */
sealed interface Coercion
  permits
    Coercion.Cast,
    Coercion.Parse,
    Coercion.EnumOf,
    Coercion.Nested,
    Coercion.Listed,
    Coercion.Setted,
    Coercion.MapValues
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
   * else parse a {@code String}, else fall back to the primitive's JLS default for an absent key.
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
  }

  /** {@code Map<K, V>} target: coerce each value through the value coercion, preserving keys. */
  record MapValues(String keyType, Coercion value) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var map = "__m" + depth;
      final var entry = "__et" + depth;
      return (
        raw +
        " instanceof java.util.Map<?, ?> " +
        map +
        " ? " +
        map +
        ".entrySet().stream().collect(java.util.stream.Collectors.toMap(" +
        entry +
        " -> (" +
        keyType +
        ") " +
        entry +
        ".getKey(), " +
        entry +
        " -> " +
        value.emit(entry + ".getValue()", depth + 1) +
        ")) : java.util.Map.of()"
      );
    }

    @Override
    public boolean unchecked() {
      return true;
    }
  }
}
