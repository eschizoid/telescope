package io.github.eschizoid.telescope.internal.pairing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Which kind of conversion a value read from an untyped {@code Map<String, Object>} gets, by the
 * declared type it is converted into. The binder generated for {@code @FromMap} and the runtime
 * {@code Telescope.fromMap} both call {@link #classify} over their own type model and render the
 * kind it returns: the processor as Java source, the runtime as a function. The order of the checks
 * and the tables of types taken by a cast or built from a {@code String} live here once, so a type
 * one path accepts is a type the other accepts, as the same kind.
 *
 * <p>What each kind does to a value is written once per path, since generated code cannot call into
 * this module; the cross-path tests hold the two renderings to the same results.
 */
public final class MapValueTypes {

  /**
   * The facts about a declared type that {@link #classify} reads. Each world implements it over its
   * own type handle, {@code java.lang.reflect.Type} or {@code TypeMirror}, and every method is a
   * single fact with no policy.
   *
   * @param <T> the world's type handle
   */
  public interface TypeModel<T> {
    /**
     * The primitive's keyword ({@code "int"}, {@code "boolean"}, …), or null for any other type.
     */
    String primitiveName(T type);

    /**
     * The qualified name of the class or interface a declared type names, its type arguments
     * dropped, or null for an array, a type variable, a wildcard or any other type that names none.
     */
    String declaredName(T type);

    /** The type arguments a declared type is written with, empty for a raw or plain type. */
    List<T> typeArguments(T type);

    /** Whether the declared type is an enum. */
    boolean isEnum(T type);

    /** Whether the declared type has a registered binder generated for {@code @FromMap}. */
    boolean hasGeneratedBinder(T type);

    /**
     * Whether the declared type is a {@code Collection} or a {@code Map}, or a subtype of either.
     */
    boolean isCollectionOrMap(T type);
  }

  /** What a declared type converts as, or why it does not. */
  public enum Kind {
    /** A primitive or its wrapper: {@link Classified#scalar} names the primitive. */
    SCALAR,
    ENUM,
    /** A type with a generated binder, built by that binder from a nested map. */
    NESTED,
    /** {@code List<E>}, the element converting as its own type argument. */
    LIST,
    /** {@code Set<E>}, the element converting as its own type argument. */
    SET,
    /** {@code Optional<E>}, the value converting as its own type argument. */
    OPTIONAL,
    /** {@code Map<K, V>}, key and value converting as their own type arguments. */
    MAP,
    /** A type an untyped map plausibly holds as itself: {@code String}, {@code CharSequence}. */
    CAST,
    /** {@code Object}, which takes the value as it is. */
    AS_IS,
    /** A JDK value type built from its {@code String} form, see {@link #stringBuilt}. */
    STRING_BUILT,
    /** Refused: a collection or map that is not one of the four interfaces above. */
    COLLECTION_SUBTYPE,
    /** Refused: a JDK type with no conversion. */
    UNKNOWN_JDK,
    /** Refused: a class with no generated binder. */
    NO_BINDER,
    /** Refused: an array, a type variable, a wildcard, or anything else that names no class. */
    UNSUPPORTED,
  }

  /**
   * A classification.
   *
   * @param kind what the type converts as
   * @param scalar the primitive's keyword for {@link Kind#SCALAR}, otherwise null
   * @param primitive whether a {@link Kind#SCALAR} is the primitive itself rather than its wrapper,
   *     which decides whether a missing value is the primitive's default or null
   */
  public record Classified(Kind kind, String scalar, boolean primitive) {
    /** Whether the kind refuses the type. */
    public boolean refused() {
      return switch (kind) {
        case COLLECTION_SUBTYPE, UNKNOWN_JDK, NO_BINDER, UNSUPPORTED -> true;
        default -> false;
      };
    }
  }

  /**
   * How a JDK value type is rebuilt from the {@code String} form it arrives in.
   *
   * @param type the value type
   * @param factory the static method taking that {@code String}, or {@code null} for the type's
   *     {@code String} constructor
   * @param build the same conversion, for the runtime
   */
  public record StringBuilt(Class<?> type, String factory, Function<String, Object> build) {}

  private static final Map<String, String> WRAPPERS = Map.of(
    "java.lang.Integer",
    "int",
    "java.lang.Long",
    "long",
    "java.lang.Double",
    "double",
    "java.lang.Float",
    "float",
    "java.lang.Short",
    "short",
    "java.lang.Byte",
    "byte",
    "java.lang.Boolean",
    "boolean",
    "java.lang.Character",
    "char"
  );

  private static final Set<String> CAST = Set.of("java.lang.String", "java.lang.CharSequence");

  private static final Map<String, StringBuilt> STRING_BUILT = table(
    new StringBuilt(Instant.class, "parse", Instant::parse),
    new StringBuilt(LocalDate.class, "parse", LocalDate::parse),
    new StringBuilt(LocalDateTime.class, "parse", LocalDateTime::parse),
    new StringBuilt(LocalTime.class, "parse", LocalTime::parse),
    new StringBuilt(OffsetDateTime.class, "parse", OffsetDateTime::parse),
    new StringBuilt(ZonedDateTime.class, "parse", ZonedDateTime::parse),
    new StringBuilt(Duration.class, "parse", Duration::parse),
    new StringBuilt(Period.class, "parse", Period::parse),
    new StringBuilt(UUID.class, "fromString", UUID::fromString),
    new StringBuilt(BigDecimal.class, null, BigDecimal::new),
    new StringBuilt(BigInteger.class, null, BigInteger::new),
    new StringBuilt(URI.class, "create", URI::create),
    new StringBuilt(Currency.class, "getInstance", Currency::getInstance),
    new StringBuilt(Locale.class, "forLanguageTag", Locale::forLanguageTag),
    new StringBuilt(Pattern.class, "compile", Pattern::compile)
  );

  private MapValueTypes() {}

  /**
   * What {@code type} converts as. A container kind says nothing about its type arguments: each
   * path classifies those itself and refuses the container when one of them is refused.
   */
  public static <T> Classified classify(final T type, final TypeModel<T> model) {
    final var primitive = model.primitiveName(type);
    if (primitive != null) return new Classified(Kind.SCALAR, primitive, true);
    final var name = model.declaredName(type);
    if (name == null) return of(Kind.UNSUPPORTED);
    final var wrapped = WRAPPERS.get(name);
    if (wrapped != null) return new Classified(Kind.SCALAR, wrapped, false);
    if (model.isEnum(type)) return of(Kind.ENUM);
    if (model.hasGeneratedBinder(type)) return of(Kind.NESTED);
    final var arity = model.typeArguments(type).size();
    if (name.equals("java.util.List") && arity == 1) return of(Kind.LIST);
    if (name.equals("java.util.Set") && arity == 1) return of(Kind.SET);
    if (name.equals("java.util.Optional") && arity == 1) return of(Kind.OPTIONAL);
    if (name.equals("java.util.Map") && arity == 2) return of(Kind.MAP);
    if (model.isCollectionOrMap(type)) return of(Kind.COLLECTION_SUBTYPE);
    if (CAST.contains(name)) return of(Kind.CAST);
    if (name.equals("java.lang.Object")) return of(Kind.AS_IS);
    if (STRING_BUILT.containsKey(name)) return of(Kind.STRING_BUILT);
    if (isJdk(name)) return of(Kind.UNKNOWN_JDK);
    return of(Kind.NO_BINDER);
  }

  /** How the type with this qualified name is built from a {@code String}, if it is one of them. */
  public static Optional<StringBuilt> stringBuilt(final String qualifiedName) {
    return Optional.ofNullable(STRING_BUILT.get(qualifiedName));
  }

  /** Whether a class of this qualified name belongs to the JDK, and so carries no binder. */
  public static boolean isJdk(final String qualifiedName) {
    return qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.");
  }

  private static Classified of(final Kind kind) {
    return new Classified(kind, null, false);
  }

  private static Map<String, StringBuilt> table(final StringBuilt... entries) {
    final var byName = LinkedHashMap.<String, StringBuilt>newLinkedHashMap(entries.length);
    for (final var entry : entries) byName.put(entry.type().getName(), entry);
    return Collections.unmodifiableMap(byName);
  }
}
