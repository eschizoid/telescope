package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.FromMapProvider;
import io.github.eschizoid.telescope.internal.pairing.MapValueTypes;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * is decided by {@link MapValueTypes#classify}, which the {@code @FromMap} processor calls too;
 * this class renders each kind as a function that behaves as the expression the processor writes
 * for it.
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
      case CAST -> Converts.to(raw == String.class ? v -> (String) v : v -> (CharSequence) v);
      case AS_IS -> Converts.to(Function.identity());
      case STRING_BUILT -> Converts.to(
        stringBuilt(raw, MapValueTypes.stringBuilt(raw.getName()).orElseThrow().build())
      );
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
   * A primitive or its wrapper. A {@code Number} is narrowed as a Java cast narrows it, a {@code
   * Boolean} or {@code Character} taken as itself, and anything else read from its {@code String}
   * form: {@code "true"} in any case is the only truthy string, and a character is the first of a
   * non-empty string. A null value is the primitive's default, or null for a wrapper.
   */
  private static Function<Object, Object> scalar(final String primitive, final boolean unboxed) {
    return switch (primitive) {
      case "int" -> number(Number::intValue, Integer::parseInt, unboxed ? 0 : null);
      case "long" -> number(Number::longValue, Long::parseLong, unboxed ? 0L : null);
      case "double" -> number(Number::doubleValue, Double::parseDouble, unboxed ? 0.0d : null);
      case "float" -> number(Number::floatValue, Float::parseFloat, unboxed ? 0.0f : null);
      case "short" -> number(Number::shortValue, Short::parseShort, unboxed ? (short) 0 : null);
      case "byte" -> number(Number::byteValue, Byte::parseByte, unboxed ? (byte) 0 : null);
      case "boolean" -> {
        final Object absent = unboxed ? Boolean.FALSE : null;
        yield raw -> raw instanceof Boolean b ? b : raw == null ? absent : Boolean.parseBoolean(String.valueOf(raw));
      }
      case "char" -> {
        final Object absent = unboxed ? '\0' : null;
        yield raw -> {
          if (raw instanceof Character c) return c;
          if (raw == null) return absent;
          final var text = String.valueOf(raw);
          return text.isEmpty() ? absent : text.charAt(0);
        };
      }
      default -> throw new IllegalStateException("not a primitive: " + primitive);
    };
  }

  private static Function<Object, Object> number(
    final Function<Number, Object> narrow,
    final Function<String, Object> parse,
    final Object absent
  ) {
    return raw -> raw instanceof Number n ? narrow.apply(n) : raw == null ? absent : parse.apply(String.valueOf(raw));
  }

  /** An enum constant taken as itself, or looked up by the value's {@code String} form. */
  private static Function<Object, Object> enumOf(final Class<?> type) {
    return raw -> type.isInstance(raw) ? raw : raw == null ? null : constant(type, String.valueOf(raw));
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Object constant(final Class<?> type, final String name) {
    return Enum.valueOf((Class) type, name);
  }

  /** A JDK value type taken as itself, or built from the value's {@code String} form. */
  private static Function<Object, Object> stringBuilt(final Class<?> type, final Function<String, Object> build) {
    return raw -> type.isInstance(raw) ? raw : raw == null ? null : build.apply(String.valueOf(raw));
  }

  /**
   * A type with a generated binder, built by that binder from the nested map. The value must be a
   * map; anything else fails the cast as the generated expression's cast fails.
   */
  @SuppressWarnings("unchecked")
  private static Function<Object, Object> nested(final Class<?> type) {
    final var binder = providerFor(type).binder();
    return raw -> raw == null ? null : binder.forward((Map<String, Object>) raw);
  }

  /**
   * A {@code List} of converted elements, or an empty one for a value that is not a {@code List}.
   */
  private static Function<Object, Object> listOf(final Function<Object, Object> each) {
    return raw -> {
      if (!(raw instanceof List<?> src)) return List.of();
      final var out = new ArrayList<>(src.size());
      for (final var element : src) out.add(each.apply(element));
      return out;
    };
  }

  /** A {@code Set} of converted elements, or an empty one for a value that is not a {@code Set}. */
  private static Function<Object, Object> setOf(final Function<Object, Object> each) {
    return raw -> {
      if (!(raw instanceof Set<?> src)) return Set.of();
      final var out = LinkedHashSet.newLinkedHashSet(src.size());
      for (final var element : src) out.add(each.apply(element));
      return out;
    };
  }

  /**
   * A {@code Map} of converted keys and values, or an empty one for a value that is not a {@code
   * Map}. A null value is kept rather than refused.
   */
  private static Function<Object, Object> mapOf(
    final Function<Object, Object> eachKey,
    final Function<Object, Object> eachValue
  ) {
    return raw -> {
      if (!(raw instanceof Map<?, ?> src)) return Map.of();
      final var out = LinkedHashMap.newLinkedHashMap(src.size());
      for (final var entry : src.entrySet()) out.put(eachKey.apply(entry.getKey()), eachValue.apply(entry.getValue()));
      return out;
    };
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
