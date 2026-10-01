package io.github.eschizoid.telescope.codegen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A per-field coercion from a raw {@code Map} value expression to the target field's type, emitted
 * as a Java expression. The processor resolves each field's declared type to one of these, then
 * asks it to {@link #emit(String, int)} the conversion around the raw {@code map.get("key")}
 * expression. Sealed so each strategy is a distinct, independently testable shape; container
 * strategies compose recursively over their element coercion.
 *
 * <p>Every type an emitted expression names is written by its qualified name, {@code java.lang}
 * included. The binder lives in its target's package, where a simple name means whatever that
 * package declares: a type there named {@code String} shadows {@code java.lang.String}, and an
 * import would shadow a type there of the same name or collide with another import.
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
   * Private static methods the generated converter needs beside its binder, keyed by name so two
   * coercions asking for the same one contribute it once.
   *
   * <p>A coercion emits an expression, which is the whole reason this exists: a conversion that
   * wants a loop has nowhere to put one, and the alternatives an expression does offer — a stream
   * and a collector — cost a Spliterator and forfeit the size the source already knows.
   */
  default Map<String, String> helpers() {
    return Map.of();
  }

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

  /** Simple name of a fully-qualified name (the segment after the last dot). */
  static String simple(final String fqn) {
    final var dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }

  /**
   * A generated local/pattern variable name, scoped by {@code depth} so nested containers don't
   * shadow each other. The {@code __} prefix (avoiding any user identifier) lives here, in one
   * place, rather than inlined per permit.
   */
  static String gensym(final String tag, final int depth) {
    return "__" + tag + depth;
  }

  /**
   * How a JDK value type is rebuilt from its {@code String} form: a named static factory ({@code
   * Instant.parse}, {@code UUID.fromString}, …) or the {@code String} constructor ({@code new
   * BigDecimal(...)}). A sealed pair so {@link StringFactory} dispatches on the type, not a magic
   * string.
   */
  sealed interface Factory permits Factory.Static, Factory.Ctor {
    /** The build expression for {@code type} from the {@code String}-form {@code arg}. */
    String build(String type, String arg);

    /** Build via {@code Type.method(String)}. */
    record Static(String method) implements Factory {
      @Override
      public String build(final String type, final String arg) {
        return type + "." + method + "(" + arg + ")";
      }
    }

    /** Build via {@code new Type(String)}. */
    record Ctor() implements Factory {
      @Override
      public String build(final String type, final String arg) {
        return "new " + type + "(" + arg + ")";
      }
    }
  }

  /**
   * Reference type (including {@code String}): a direct cast. A {@code null} raw casts to {@code
   * null}.
   */
  record Cast(String fqn) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      // A reader hands back an Object, so a cast to Object narrows nothing and javac says so. The
      // other members of this family — String, CharSequence — are narrower than what the reader
      // returns, and their cast is what makes the assignment compile.
      return "java.lang.Object".equals(fqn) ? raw : "(" + fqn + ") " + raw;
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
      final var v = gensym("n", depth);
      return (
        raw +
        " instanceof java.lang.Number " +
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
        "(java.lang.String.valueOf(" +
        raw +
        "))"
      );
    }
  }

  /**
   * {@code boolean}/{@code Boolean} target: take an existing {@code Boolean} directly, else parse a
   * {@code String} ({@code Boolean.parseBoolean} — only {@code "true"} is truthy), else the
   * default.
   */
  record BoolParse(String defaultLiteral) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var v = gensym("b", depth);
      return (
        raw +
        " instanceof java.lang.Boolean " +
        v +
        " ? " +
        v +
        " : " +
        raw +
        " == null ? " +
        defaultLiteral +
        " : java.lang.Boolean.parseBoolean(java.lang.String.valueOf(" +
        raw +
        "))"
      );
    }
  }

  /**
   * {@code char}/{@code Character} target: take an existing {@code Character} directly, else the
   * first char of the {@code String} form, else the default.
   */
  record CharParse(String defaultLiteral) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var v = gensym("c", depth);
      return (
        raw +
        " instanceof java.lang.Character " +
        v +
        " ? " +
        v +
        " : " +
        raw +
        " == null || java.lang.String.valueOf(" +
        raw +
        ").isEmpty() ? " +
        defaultLiteral +
        " : java.lang.String.valueOf(" +
        raw +
        ").charAt(0)"
      );
    }
  }

  /**
   * Enum target: take an existing enum value directly, else map a {@code String} name via {@code
   * valueOf}.
   */
  record EnumOf(String fqn) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var type = fqn;
      final var v = gensym("e", depth);
      return (
        raw +
        " instanceof " +
        type +
        " " +
        v +
        " ? " +
        v +
        " : " +
        raw +
        " == null ? null : " +
        type +
        ".valueOf(java.lang.String.valueOf(" +
        raw +
        "))"
      );
    }
  }

  /**
   * A JDK value type with a well-known String factory ({@code Instant.parse}, {@code
   * UUID.fromString}, {@code new BigDecimal}, …): take an existing instance directly, else build it
   * from the value's {@code String} form — the shape these arrive in from an untyped map.
   */
  record StringFactory(String fqn, Factory factory) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var type = fqn;
      final var build = factory.build(type, "java.lang.String.valueOf(" + raw + ")");
      final var v = gensym("sf", depth);
      return raw + " instanceof " + type + " " + v + " ? " + v + " : " + raw + " == null ? null : " + build;
    }
  }

  /**
   * Nested {@code @FromMap} target: a {@code null}-guarded recursion through its generated
   * converter.
   */
  record Nested(String converterFqn) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      return (
        raw +
        " == null ? null : " +
        converterFqn +
        ".fromMap((java.util.Map<java.lang.String, java.lang.Object>) " +
        raw +
        ")"
      );
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
      final var el = gensym("el", depth);
      return ("__coerceList(" + raw + ", " + el + " -> " + element.emit(el, depth + 1) + ")");
    }

    @Override
    public Map<String, String> helpers() {
      final var all = new LinkedHashMap<>(element.helpers());
      all.put(
        "__coerceList",
        """
        private static <E> java.util.List<E> __coerceList(
          final java.lang.Object raw,
          final java.util.function.Function<java.lang.Object, E> each
        ) {
          if (!(raw instanceof java.util.List<?> src)) return java.util.List.of();
          final var out = new java.util.ArrayList<E>(src.size());
          for (final var element : src) out.add(each.apply(element));
          return out;
        }\
        """
      );
      return all;
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
      final var el = gensym("el", depth);
      return "__coerceSet(" + raw + ", " + el + " -> " + element.emit(el, depth + 1) + ")";
    }

    @Override
    public Map<String, String> helpers() {
      final var all = new LinkedHashMap<>(element.helpers());
      all.put(
        "__coerceSet",
        """
        private static <E> java.util.Set<E> __coerceSet(
          final java.lang.Object raw,
          final java.util.function.Function<java.lang.Object, E> each
        ) {
          if (!(raw instanceof java.util.Set<?> src)) return java.util.Set.of();
          final var out = java.util.LinkedHashSet.<E>newLinkedHashSet(src.size());
          for (final var element : src) out.add(each.apply(element));
          return out;
        }\
        """
      );
      return all;
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
    public String emit(final String raw, final int depth) {
      return (
        raw + " == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(" + element.emit(raw, depth) + ")"
      );
    }

    @Override
    public Map<String, String> helpers() {
      return element.helpers();
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
   * {@code Map<K, V>} target: coerce both key and value into a fresh {@code LinkedHashMap}. Uses a
   * put-accumulating collect (not {@code Collectors.toMap}) so a {@code null} value doesn't throw —
   * matching the lenient spirit of {@code fromMap}.
   */
  record MapValues(Coercion key, Coercion value) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var k = gensym("k", depth);
      final var v = gensym("v", depth);
      return (
        "__coerceMap(" +
        raw +
        ", " +
        k +
        " -> " +
        key.emit(k, depth + 1) +
        ", " +
        v +
        " -> " +
        value.emit(v, depth + 1) +
        ")"
      );
    }

    @Override
    public Map<String, String> helpers() {
      final var all = new LinkedHashMap<>(key.helpers());
      all.putAll(value.helpers());
      all.put(
        "__coerceMap",
        """
        private static <K, V> java.util.Map<K, V> __coerceMap(
          final java.lang.Object raw,
          final java.util.function.Function<java.lang.Object, K> eachKey,
          final java.util.function.Function<java.lang.Object, V> eachValue
        ) {
          if (!(raw instanceof java.util.Map<?, ?> src)) return java.util.Map.of();
          final var out = java.util.LinkedHashMap.<K, V>newLinkedHashMap(src.size());
          for (final var entry : src.entrySet()) {
            out.put(eachKey.apply(entry.getKey()), eachValue.apply(entry.getValue()));
          }
          return out;
        }\
        """
      );
      return all;
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
