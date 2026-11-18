package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.FromMapProvider;
import io.github.eschizoid.telescope.internal.pairing.MapValueConversions;
import io.github.eschizoid.telescope.internal.pairing.MapValueTypes;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * How a {@code fromMap} component no row names is filled from the map value under its own name: the
 * conversion for its declared type, or why that type has none. Which kind of conversion a type gets
 * is decided by {@link MapValueTypes#classify}, which the {@code @FromMap} processor calls too, and
 * what each kind does to a value by {@link MapValueConversions}, which the generated binder calls
 * through {@code FromMapValues}; this class puts the two together as a function per type.
 *
 * <p>A type with no conversion is refused while the mapper is built, as the processor refuses it.
 */
final class FromMapCoercions {

  private static final String ROW = "name it with an extract(key, accessor, converter) row";

  private FromMapCoercions() {}

  /** What a declared type resolves to: a conversion, or the reason it has none. */
  private sealed interface Resolved {}

  /**
   * A type that has a conversion. It is built only when asked for, so deciding whether a type is
   * accepted never asks a nested type's provider for its binder.
   */
  private record Converts(Supplier<Function<Object, Object>> build) implements Resolved {
    static Converts to(final Function<Object, Object> convert) {
      return new Converts(() -> convert);
    }
  }

  private record Refused(String reason) implements Resolved {}

  /**
   * Why {@code type} cannot be filled without a row, naming the innermost type at fault and the
   * remedy, or empty when it can.
   */
  static Optional<String> reasonFor(final Type type) {
    return resolve(type) instanceof Refused refused ? Optional.of(refused.reason()) : Optional.empty();
  }

  /**
   * The conversion from a raw map value to {@code type}. It answers for {@code null} the way the
   * generated binder does: the JLS default for a primitive, an empty container or {@code Optional},
   * and {@code null} otherwise.
   *
   * <p>A type with no conversion, and a nested type whose provider cannot hand over its binder, are
   * refused through {@code refusal}, which receives the reason and the exception behind it, if any.
   */
  static Function<Object, Object> converterFor(
    final Type type,
    final BiFunction<String, Throwable, RuntimeException> refusal
  ) {
    return switch (resolve(type)) {
      case Converts converts -> {
        try {
          yield converts.build().get();
        } catch (final UnsupportedOperationException e) {
          throw refusal.apply(e.getMessage(), e);
        }
      }
      case Refused refused -> throw refusal.apply(refused.reason(), null);
    };
  }

  /**
   * The keys {@code @FromMap(required = ...)} declares on {@code target}, or empty when {@code
   * target} has no registered binder.
   *
   * @throws UnsupportedOperationException when its provider cannot say
   */
  static List<String> requiredOf(final Class<?> target) {
    final var provider = providerFor(target);
    return provider == null ? List.of() : provider.required();
  }

  private static final MapValueTypes.TypeModel<Type> REFLECTED = new MapValueTypes.TypeModel<>() {
    @Override
    public String primitiveName(final Type type) {
      return type instanceof Class<?> c && c.isPrimitive() ? c.getName() : null;
    }

    @Override
    public String declaredName(final Type type) {
      final var raw = rawOf(type);
      return raw == null ? null : raw.getName();
    }

    @Override
    public List<Type> typeArguments(final Type type) {
      return type instanceof ParameterizedType p ? List.of(p.getActualTypeArguments()) : List.of();
    }

    @Override
    public boolean isEnum(final Type type) {
      return rawOf(type).isEnum();
    }

    @Override
    public boolean hasGeneratedBinder(final Type type) {
      return FromMapCoercions.hasGeneratedBinder(rawOf(type));
    }

    @Override
    public boolean isCollectionOrMap(final Type type) {
      final var raw = rawOf(type);
      return Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw);
    }
  };

  /**
   * The class a declared type names, or null for an array, a type variable, a wildcard or a
   * primitive. The reflection API reports the raw type of a parameterized type as a Class.
   */
  private static Class<?> rawOf(final Type type) {
    if (type instanceof Class<?> c) return c.isPrimitive() || c.isArray() ? null : c;
    return type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : null;
  }

  private static Type argument(final Type type, final int index) {
    return ((ParameterizedType) type).getActualTypeArguments()[index];
  }

  private static Resolved resolve(final Type type) {
    final var classified = MapValueTypes.classify(type, REFLECTED);
    final var raw = rawOf(type);
    return switch (classified.kind()) {
      case SCALAR -> Converts.to(scalar(classified.scalar(), classified.primitive()));
      case ENUM -> Converts.to(enumOf(raw));
      case NESTED -> new Converts(() -> nested(raw));
      case LIST -> each(argument(type, 0), FromMapCoercions::listOf);
      case SET -> each(argument(type, 0), FromMapCoercions::setOf);
      case OPTIONAL -> each(argument(type, 0), FromMapCoercions::optionalOf);
      case MAP -> {
        final var key = resolve(argument(type, 0));
        if (key instanceof Refused) yield key;
        final var value = resolve(argument(type, 1));
        if (value instanceof Refused) yield value;
        final var keyBuild = ((Converts) key).build();
        final var valueBuild = ((Converts) value).build();
        yield new Converts(() -> mapOf(keyBuild.get(), valueBuild.get()));
      }
      case CAST -> Converts.to(
        raw == String.class
          ? v -> MapValueConversions.toText(v, null)
          : v -> MapValueConversions.toCharSequence(v, null)
      );
      case AS_IS -> Converts.to(Function.identity());
      case STRING_BUILT -> Converts.to(v -> MapValueConversions.toValue(v, raw, null));
      case COLLECTION_SUBTYPE -> new Refused(
        raw.getName() + " is a collection subtype; declare it as List/Set/Map/Optional, or " + ROW
      );
      case UNKNOWN_JDK -> new Refused(raw.getName() + " can't be built from a map value; " + ROW);
      case NO_BINDER -> new Refused(
        raw.getName() +
          " has no registered @FromMap binder; annotate it with @FromMap and recompile (on the module path its" +
          " module-info must also declare \"provides " +
          FromMapProvider.class.getName() +
          " with " +
          raw.getCanonicalName() +
          "FromMap.Provider;\"), or " +
          ROW
      );
      case UNSUPPORTED -> new Refused(
        type.getTypeName() + " can't be coerced from a map value (type variable / array / unsupported kind); " + ROW
      );
    };
  }

  private static Resolved each(
    final Type element,
    final Function<Function<Object, Object>, Function<Object, Object>> lift
  ) {
    final var resolved = resolve(element);
    return resolved instanceof Converts converts ? new Converts(() -> lift.apply(converts.build().get())) : resolved;
  }

  /**
   * A primitive or its wrapper, converted as {@link MapValueConversions} converts it. A null value
   * is the primitive's default, or null for a wrapper.
   */
  private static Function<Object, Object> scalar(final String primitive, final boolean unboxed) {
    return switch (primitive) {
      case "int" -> absentOr(unboxed ? 0 : null, raw -> MapValueConversions.toInt(raw, unboxed, null));
      case "long" -> absentOr(unboxed ? 0L : null, raw -> MapValueConversions.toLong(raw, unboxed, null));
      case "double" -> absentOr(unboxed ? 0.0d : null, raw -> MapValueConversions.toDouble(raw, unboxed, null));
      case "float" -> absentOr(unboxed ? 0.0f : null, raw -> MapValueConversions.toFloat(raw, unboxed, null));
      case "short" -> absentOr(unboxed ? (short) 0 : null, raw -> MapValueConversions.toShort(raw, unboxed, null));
      case "byte" -> absentOr(unboxed ? (byte) 0 : null, raw -> MapValueConversions.toByte(raw, unboxed, null));
      case "boolean" -> absentOr(unboxed ? false : null, raw -> MapValueConversions.toBoolean(raw, unboxed, null));
      case "char" -> absentOr(unboxed ? '\0' : null, raw -> MapValueConversions.toChar(raw, unboxed, null));
      default -> throw new IllegalStateException("not a primitive: " + primitive);
    };
  }

  /** {@code absent} for a null value, and the conversion of any other. */
  private static Function<Object, Object> absentOr(final Object absent, final Function<Object, Object> convert) {
    return raw -> raw == null ? absent : convert.apply(raw);
  }

  /** An enum constant taken as itself, or looked up by the value's {@code String} form. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Function<Object, Object> enumOf(final Class<?> type) {
    return raw -> MapValueConversions.toEnum(raw, (Class) type, null);
  }

  /** A type with a generated binder, built by that binder from the nested map. */
  private static Function<Object, Object> nested(final Class<?> type) {
    final var binder = providerFor(type).binder();
    final var name = type.getSimpleName();
    return raw -> MapValueConversions.nested(raw, null, name, binder::forward);
  }

  /** A {@code List} of converted elements, from any collection. */
  private static Function<Object, Object> listOf(final Function<Object, Object> each) {
    return raw -> MapValueConversions.toList(raw, null, each);
  }

  /** A {@code Set} of converted elements, from any collection. */
  private static Function<Object, Object> setOf(final Function<Object, Object> each) {
    return raw -> MapValueConversions.toSet(raw, null, each);
  }

  /** A {@code Map} of converted keys and values, from any map. A null value is kept. */
  private static Function<Object, Object> mapOf(
    final Function<Object, Object> eachKey,
    final Function<Object, Object> eachValue
  ) {
    return raw -> MapValueConversions.toMap(raw, null, eachKey, eachValue);
  }

  /**
   * An empty {@code Optional} for a null value, otherwise the converted value wrapped. The absence
   * is tested before the element converts, because a container element answers a null with an empty
   * container, which would wrap into a present {@code Optional}.
   */
  private static Function<Object, Object> optionalOf(final Function<Object, Object> each) {
    return raw -> raw == null ? Optional.empty() : Optional.ofNullable(each.apply(raw));
  }

  /**
   * Whether a generated {@code @FromMap} binder is registered for {@code raw}, which is what the
   * annotation leaves behind once it is gone: it is source-retained. The binder registers a {@link
   * FromMapProvider} naming its target, and {@link ServiceLoader} finds it in whatever compilation
   * produced it and in a native image. A class that merely shares the binder's name registers
   * nothing, and a provider naming another class of the same name, from another loader, does not
   * count: the answer agrees with {@link #providerFor} by class identity.
   */
  private static boolean hasGeneratedBinder(final Class<?> raw) {
    if (MapValueTypes.isJdk(raw.getName())) return false;
    final Set<String> targets;
    synchronized (TARGETS_BY_LOADER) {
      targets = TARGETS_BY_LOADER.computeIfAbsent(loaderOf(raw), FromMapCoercions::registeredTargets);
    }
    return targets.contains(raw.getName()) && providerFor(raw) != null;
  }

  private static ClassLoader loaderOf(final Class<?> raw) {
    return raw.getClassLoader() != null ? raw.getClassLoader() : FromMapCoercions.class.getClassLoader();
  }

  /**
   * The registered provider whose target is {@code raw} itself, or null. It is looked up while a
   * mapper is built, not cached, so nothing here holds a provider and with it the loader that
   * defined it.
   */
  private static FromMapProvider providerFor(final Class<?> raw) {
    final var providers = ServiceLoader.load(FromMapProvider.class, loaderOf(raw)).iterator();
    while (true) {
      final FromMapProvider provider;
      try {
        if (!providers.hasNext()) return null;
        provider = providers.next();
      } catch (final ServiceConfigurationError e) {
        continue;
      }
      try {
        if (provider.targetType() == raw) return provider;
      } catch (final RuntimeException | LinkageError e) {
        // Logged once by registeredTargets; a provider that cannot name its target names none.
      }
    }
  }

  /**
   * The names of the types the providers visible to {@code loader} build, read once per loader.
   * Names rather than classes, so the cache holds nothing that keeps its loader alive.
   */
  private static final Map<ClassLoader, Set<String>> TARGETS_BY_LOADER = new WeakHashMap<>();

  private static final Logger LOG = System.getLogger("io.github.eschizoid.telescope.fromMap");

  /**
   * Every target a provider visible to {@code loader} names. A registration that cannot be loaded
   * or a provider that cannot answer is skipped and logged rather than thrown: it belongs to some
   * other binder, and refusing every mapper over it would break code that never names that type.
   * The iterator moves past a provider it failed to load, so one broken entry costs only itself.
   */
  private static Set<String> registeredTargets(final ClassLoader loader) {
    final var targets = new HashSet<String>();
    final var providers = ServiceLoader.load(FromMapProvider.class, loader).iterator();
    while (true) {
      final FromMapProvider provider;
      try {
        if (!providers.hasNext()) break;
        provider = providers.next();
      } catch (final ServiceConfigurationError e) {
        LOG.log(Level.WARNING, "Skipping a @FromMap binder registration that cannot be loaded: " + e.getMessage(), e);
        continue;
      }
      try {
        targets.add(provider.targetType().getName());
      } catch (final RuntimeException | LinkageError e) {
        LOG.log(
          Level.WARNING,
          "Skipping @FromMap binder provider " + provider.getClass().getName() + ", whose targetType() failed: " + e,
          e
        );
      }
    }
    return targets;
  }
}
