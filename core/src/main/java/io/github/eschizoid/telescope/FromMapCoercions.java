package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
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
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * How a {@code fromMap} component no row names is filled from the map value under its own name: the
 * conversion for its declared type, or why that type has none. The binder generated for {@link
 * io.github.eschizoid.telescope.annotations.FromMap} makes the same decision at compile time, over
 * {@code javax.lang.model} types, as a sealed {@code Coercion} it writes out as Java. This class
 * makes it over reflected types, in the same order of checks, and each conversion below behaves as
 * the expression the processor writes for the same type. The reference types taken by a cast or
 * built from a {@code String} come from one table both read.
 *
 * <p>A type with no conversion is refused while the mapper is built, as the processor refuses it,
 * rather than left {@code null} for a reason nothing reports.
 */
final class FromMapCoercions {

  private static final String ROW = "name it with an extract(key, accessor, converter) row";

  private FromMapCoercions() {}

  /** What a declared type resolves to: a conversion, or the reason it has none. */
  private sealed interface Resolved {}

  /**
   * A type that has a conversion. It is built only when asked for, so deciding whether a type is
   * accepted never loads a nested type's binder.
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
   * @param refusal builds the exception thrown for a type with no conversion, from the reason
   */
  static Function<Object, Object> converterFor(final Type type, final Function<String, RuntimeException> refusal) {
    return switch (resolve(type)) {
      case Converts converts -> converts.build().get();
      case Refused refused -> throw refusal.apply(refused.reason());
    };
  }

  private static Resolved resolve(final Type type) {
    if (type instanceof Class<?> c) {
      if (c.isPrimitive()) return Converts.to(scalar(c, true));
      if (c.isArray()) return unsupportedKind(type);
      return declared(c, new Type[0]);
    }
    // The reflection API reports the raw type of a parameterized type as a Class.
    if (type instanceof ParameterizedType p) return declared((Class<?>) p.getRawType(), p.getActualTypeArguments());
    return unsupportedKind(type);
  }

  private static Resolved declared(final Class<?> raw, final Type[] args) {
    final var boxed = scalar(raw, false);
    if (boxed != null) return Converts.to(boxed);
    if (raw.isEnum()) return Converts.to(enumOf(raw));
    if (hasGeneratedBinder(raw)) return new Converts(() -> nested(raw));
    if (raw == List.class && args.length == 1) return each(args[0], FromMapCoercions::listOf);
    if (raw == Set.class && args.length == 1) return each(args[0], FromMapCoercions::setOf);
    if (raw == Optional.class && args.length == 1) return each(args[0], FromMapCoercions::optionalOf);
    if (raw == Map.class && args.length == 2) {
      final var key = resolve(args[0]);
      if (key instanceof Refused) return key;
      final var value = resolve(args[1]);
      if (value instanceof Refused) return value;
      final var keyBuild = ((Converts) key).build();
      final var valueBuild = ((Converts) value).build();
      return new Converts(() -> mapOf(keyBuild.get(), valueBuild.get()));
    }
    if (Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) {
      return new Refused(raw.getName() + " is a collection subtype; declare it as List/Set/Map/Optional, or " + ROW);
    }
    if (MapValueTypes.castAsIs(raw.getName())) {
      return Converts.to(raw == Object.class ? Function.identity() : raw::cast);
    }
    final var built = MapValueTypes.stringBuilt(raw.getName());
    if (built.isPresent()) return Converts.to(stringBuilt(raw, built.get().build()));
    if (isJdk(raw)) return new Refused(raw.getName() + " can't be built from a map value; " + ROW);
    return new Refused(
      raw.getName() +
        " has no registered @FromMap binder; annotate it with @FromMap and recompile (on the module path its" +
        " module-info must also declare \"provides " +
        FromMapProvider.class.getName() +
        " with " +
        raw.getCanonicalName() +
        "FromMap.Provider;\"), or " +
        ROW
    );
  }

  private static Resolved each(
    final Type element,
    final Function<Function<Object, Object>, Function<Object, Object>> lift
  ) {
    final var resolved = resolve(element);
    return resolved instanceof Converts converts ? new Converts(() -> lift.apply(converts.build().get())) : resolved;
  }

  private static Refused unsupportedKind(final Type type) {
    return new Refused(
      type.getTypeName() + " can't be coerced from a map value (type variable / array / unsupported kind); " + ROW
    );
  }

  /**
   * A primitive or its wrapper, or null for any other type. A {@code Number} is narrowed, a {@code
   * Boolean} or {@code Character} taken as itself, and anything else read from its {@code String}
   * form: only {@code "true"} (in any case) is truthy, and a character is the first of a non-empty
   * string. A null value is the primitive's default, or null for a wrapper.
   */
  private static Function<Object, Object> scalar(final Class<?> type, final boolean primitive) {
    final var name = primitive ? type.getName() : boxedName(type);
    if (name == null) return null;
    return switch (name) {
      case "int" -> number(Number::intValue, Integer::parseInt, primitive ? 0 : null);
      case "long" -> number(Number::longValue, Long::parseLong, primitive ? 0L : null);
      case "double" -> number(Number::doubleValue, Double::parseDouble, primitive ? 0.0d : null);
      case "float" -> number(Number::floatValue, Float::parseFloat, primitive ? 0.0f : null);
      case "short" -> number(Number::shortValue, Short::parseShort, primitive ? (short) 0 : null);
      case "byte" -> number(Number::byteValue, Byte::parseByte, primitive ? (byte) 0 : null);
      case "boolean" -> {
        final Object absent = primitive ? Boolean.FALSE : null;
        yield raw -> raw instanceof Boolean b ? b : raw == null ? absent : Boolean.parseBoolean(String.valueOf(raw));
      }
      case "char" -> {
        final Object absent = primitive ? '\0' : null;
        yield raw -> {
          if (raw instanceof Character c) return c;
          if (raw == null) return absent;
          final var text = String.valueOf(raw);
          return text.isEmpty() ? absent : text.charAt(0);
        };
      }
      default -> null;
    };
  }

  private static String boxedName(final Class<?> type) {
    if (type == Integer.class) return "int";
    if (type == Long.class) return "long";
    if (type == Double.class) return "double";
    if (type == Float.class) return "float";
    if (type == Short.class) return "short";
    if (type == Byte.class) return "byte";
    if (type == Boolean.class) return "boolean";
    if (type == Character.class) return "char";
    return null;
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
    final var binder = binderFor(type);
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

  private static boolean isJdk(final Class<?> raw) {
    return raw.getName().startsWith("java.") || raw.getName().startsWith("javax.");
  }

  /**
   * Whether a generated {@code @FromMap} binder is registered for {@code raw}, which is what the
   * annotation leaves behind once it is gone: it is source-retained. The binder registers a {@link
   * FromMapProvider} naming its target, and {@link ServiceLoader} finds it in whatever compilation
   * produced it and in a native image. A class that merely shares the binder's name registers
   * nothing.
   */
  private static boolean hasGeneratedBinder(final Class<?> raw) {
    if (isJdk(raw)) return false;
    final var loader = loaderOf(raw);
    final Set<String> targets;
    synchronized (TARGETS_BY_LOADER) {
      targets = TARGETS_BY_LOADER.computeIfAbsent(loader, FromMapCoercions::registeredTargets);
    }
    return targets.contains(raw.getName());
  }

  private static ClassLoader loaderOf(final Class<?> raw) {
    return raw.getClassLoader() != null ? raw.getClassLoader() : FromMapCoercions.class.getClassLoader();
  }

  /**
   * The generated binder for {@code raw}, found through the same registrations {@link
   * #hasGeneratedBinder} read. It is looked up while a mapper is built, not cached, so nothing here
   * holds a provider and with it the loader that defined it.
   */
  private static ForwardMapper<Map<String, Object>, ?> binderFor(final Class<?> raw) {
    final var providers = ServiceLoader.load(FromMapProvider.class, loaderOf(raw)).iterator();
    while (true) {
      final FromMapProvider provider;
      try {
        if (!providers.hasNext()) break;
        provider = providers.next();
      } catch (final ServiceConfigurationError e) {
        continue;
      }
      final Class<?> target;
      try {
        target = provider.targetType();
      } catch (final RuntimeException | LinkageError e) {
        continue;
      }
      if (target == raw) return provider.binder();
    }
    throw new IllegalStateException("no @FromMap binder is registered for " + raw.getName());
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
