package io.github.eschizoid.telescope.codegen;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A per-field coercion from a raw {@code Map} value expression to the target field's type, emitted
 * as a Java expression. The processor resolves each field's declared type to one of these, then
 * asks it to {@link #emit(String, int)} the conversion around the raw {@code map.get("key")}
 * expression and to {@link #imports()} the types it references — so the generated converter uses
 * imports and simple names, not inline fully-qualified names. Sealed so each strategy is a
 * distinct, independently testable shape; container strategies compose recursively over their
 * element coercion.
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
   * Fully-qualified names this coercion references, to be imported by the generated converter
   * (simple names emitted).
   */
  default Set<String> imports() {
    return Set.of();
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

  /** Import set for a type — empty for {@code java.lang} (auto-imported), else the single FQN. */
  static Set<String> importing(final String fqn) {
    return fqn.startsWith("java.lang.") ? Set.of() : Set.of(fqn);
  }

  /** Union of a fixed import set with a child coercion's imports. */
  static Set<String> with(final Set<String> own, final Coercion child) {
    final var all = new HashSet<>(own);
    all.addAll(child.imports());
    return all;
  }

  /**
   * Reference type (including {@code String}): a direct cast. A {@code null} raw casts to {@code
   * null}.
   */
  record Cast(String fqn) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      return "(" + simple(fqn) + ") " + raw;
    }

    @Override
    public Set<String> imports() {
      return importing(fqn);
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
   * {@code String} ({@code Boolean.parseBoolean} — only {@code "true"} is truthy), else the
   * default.
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
   * first char of the {@code String} form, else the default.
   */
  record CharParse(String defaultLiteral) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      return (
        raw +
        " instanceof Character __c" +
        depth +
        " ? __c" +
        depth +
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
  record EnumOf(String fqn) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var type = simple(fqn);
      final var v = "__e" + depth;
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
        ".valueOf(String.valueOf(" +
        raw +
        "))"
      );
    }

    @Override
    public Set<String> imports() {
      return importing(fqn);
    }
  }

  /**
   * A JDK value type with a well-known String factory ({@code Instant.parse}, {@code
   * UUID.fromString}, {@code new BigDecimal}, …): take an existing instance directly, else build it
   * from the value's {@code String} form — the shape these arrive in from an untyped map.
   */
  record StringFactory(String fqn, String factory) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var type = simple(fqn);
      // factory is either a static method ("parse"/"fromString"/…) or "new" for a String
      // constructor.
      final var build = "new".equals(factory)
        ? "new " + type + "(String.valueOf(" + raw + "))"
        : type + "." + factory + "(String.valueOf(" + raw + "))";
      final var v = "__sf" + depth;
      return raw + " instanceof " + type + " " + v + " ? " + v + " : " + raw + " == null ? null : " + build;
    }

    @Override
    public Set<String> imports() {
      return importing(fqn);
    }
  }

  /**
   * Nested {@code @FromMap} target: a {@code null}-guarded recursion through its generated
   * converter.
   */
  record Nested(String converterFqn) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      return raw + " == null ? null : " + simple(converterFqn) + ".fromMap((Map<String, Object>) " + raw + ")";
    }

    @Override
    public Set<String> imports() {
      return importing(converterFqn);
    }

    @Override
    public boolean unchecked() {
      return true;
    }
  }

  /** {@code List<E>} target: stream each element through the element coercion into a fresh list. */
  record Listed(String elementType, Coercion element, Set<String> typeImports) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var list = "__l" + depth;
      final var el = "__el" + depth;
      return (
        raw +
        " instanceof List<?> " +
        list +
        " ? " +
        list +
        ".stream().map(" +
        el +
        " -> " +
        element.emit(el, depth + 1) +
        ").collect(Collectors.toList()) : List.<" +
        elementType +
        ">of()"
      );
    }

    @Override
    public Set<String> imports() {
      final var all = with(Set.of("java.util.List", "java.util.stream.Collectors"), element);
      all.addAll(typeImports);
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
  record Setted(String elementType, Coercion element, Set<String> typeImports) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var set = "__s" + depth;
      final var el = "__el" + depth;
      return (
        raw +
        " instanceof Set<?> " +
        set +
        " ? " +
        set +
        ".stream().map(" +
        el +
        " -> " +
        element.emit(el, depth + 1) +
        ").collect(Collectors.toSet()) : Set.<" +
        elementType +
        ">of()"
      );
    }

    @Override
    public Set<String> imports() {
      final var all = with(Set.of("java.util.Set", "java.util.stream.Collectors"), element);
      all.addAll(typeImports);
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
   * {@code Optional<E>} target: wrap the (null-coalescing) element coercion in {@code
   * Optional.ofNullable}.
   */
  record OptionalOf(Coercion element) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      return "Optional.ofNullable(" + element.emit(raw, depth) + ")";
    }

    @Override
    public Set<String> imports() {
      return with(Set.of("java.util.Optional"), element);
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
  record MapValues(
    String keyType,
    String valueType,
    Coercion key,
    Coercion value,
    Set<String> typeImports
  ) implements Coercion {
    @Override
    public String emit(final String raw, final int depth) {
      final var map = "__m" + depth;
      final var acc = "__acc" + depth;
      final var entry = "__et" + depth;
      final var mapType = "Map<" + keyType + ", " + valueType + ">";
      // Explicit <Map<K,V>> witness so the 3-arg collect types even when nested inside another
      // container (javac can't otherwise infer the accumulator's element types there).
      return (
        raw +
        " instanceof Map<?, ?> " +
        map +
        " ? " +
        map +
        ".entrySet().stream().<" +
        mapType +
        ">collect(LinkedHashMap::new, (" +
        acc +
        ", " +
        entry +
        ") -> " +
        acc +
        ".put(" +
        key.emit(entry + ".getKey()", depth + 1) +
        ", " +
        value.emit(entry + ".getValue()", depth + 1) +
        "), Map::putAll) : Map.<" +
        keyType +
        ", " +
        valueType +
        ">of()"
      );
    }

    @Override
    public Set<String> imports() {
      final var all = new HashSet<>(Set.of("java.util.Map", "java.util.LinkedHashMap"));
      all.addAll(typeImports);
      all.addAll(key.imports());
      all.addAll(value.imports());
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
