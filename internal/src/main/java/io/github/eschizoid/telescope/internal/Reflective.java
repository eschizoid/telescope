package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.internal.optics.Lens;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The uniform reflective dispatch that {@link io.github.eschizoid.telescope.DeepMap DeepMap} drives
 * — abstracts over "this side is a record" vs "this side is a bean" so the recursive resolver
 * doesn't have to know. Per-side dispatch via {@link #of(Class)} lets a single deep-mapping call
 * mix and match records and POJOs at any depth: the source side of a given pair uses one {@code
 * Reflective}, the target side another, chosen independently from the pair's classes.
 *
 * <p>The record's fields are the five per-side function references; the forwarding instance methods
 * ({@link #names(Class)}, {@link #genericType(Class, String)}, {@link #read(Object, String)},
 * {@link #construct(Class, Function)}, {@link #normalize(String)}) preserve the prior
 * interface-method calling convention so call sites don't have to spell {@code .read().apply(...)}.
 *
 * <p>Two singletons:
 *
 * <ul>
 *   <li>{@link #RECORDS} — backed by {@link Records}. Canonical-constructor rebuild, identity name
 *       normalization (record components are already named the user-visible way).
 *   <li>{@link #BEANS} — backed by {@link Beans}. Auto-detected write strategy ({@code builder()} →
 *       no-arg ctor + setters → no-arg ctor + reflective field injection), {@code getX/isX}
 *       stripped to a property name. Immutable all-args-only POJOs (no setters, no builder, no
 *       no-arg ctor) are not supported by the auto path — use a record, or add a no-arg ctor.
 * </ul>
 *
 * <p>The lattice-first principle holds: {@link io.github.eschizoid.telescope.DeepMap DeepMap}
 * composes {@link io.github.eschizoid.telescope.internal.optics.Iso Iso}s; this record only handles
 * "how do I read one component value" and "how do I rebuild one object from a name-keyed function."
 * No bidirectional plumbing lives here.
 */
public record Reflective(
  Function<Class<?>, String[]> names,
  BiFunction<Class<?>, String, Type> genericType,
  BiFunction<Object, String, Object> read,
  BiFunction<Class<?>, Function<String, Object>, Object> construct,
  Function<String, String> normalize
) {
  public static final Reflective RECORDS = new Reflective(
    Records::componentNames,
    Records::componentType,
    Records::read,
    Reflective::constructRecord,
    name -> name
  );

  public static final Reflective BEANS = new Reflective(
    Beans::propertyNames,
    Beans::propertyType,
    Beans::readProperty,
    Reflective::constructBean,
    Beans::propertyOf
  );

  /**
   * Pick the right reflective for {@code cls}: records → {@link #RECORDS}; everything else → {@link
   * #BEANS}.
   */
  public static Reflective of(final Class<?> cls) {
    return cls.isRecord() ? RECORDS : BEANS;
  }

  /**
   * Bean reflective that consults {@code hints} before falling back to {@code defaultWriterFactory}
   * (when non-null) and ultimately to {@link Beans#autoWriter}. Used by {@link
   * io.github.eschizoid.telescope.DeepMap DeepMap} when the user supplies {@code
   * writeBean(targetClass, strategy)} rows and/or a single {@code writeBeans(strategy)} default —
   * the per-class hint map is keyed on target class and provides a pre-instantiated {@link
   * Beans.BeanWriter}; the default factory is consulted lazily on first encounter with each
   * not-explicitly-hinted target, then cached, so a default-strategy incompatible with a particular
   * target only throws when that target is actually constructed.
   */
  public static Reflective beansWithHints(
    final Map<Class<?>, Beans.BeanWriter<?>> hints,
    final Function<Class<?>, Beans.BeanWriter<?>> defaultWriterFactory
  ) {
    return new Reflective(
      BEANS.names,
      BEANS.genericType,
      BEANS.read,
      (cls, valueByName) -> constructBeanWithHints(hints, defaultWriterFactory, cls, valueByName),
      BEANS.normalize
    );
  }

  /** Component / property names in declaration order. */
  public String[] names(final Class<?> cls) {
    return names.apply(cls);
  }

  /** Generic type of the named component / property (for container shape detection). */
  public Type genericType(final Class<?> cls, final String name) {
    return genericType.apply(cls, name);
  }

  /** Read a value by name. */
  public Object read(final Object value, final String name) {
    return read.apply(value, name);
  }

  /** Construct a fresh instance by reading each component's value from the function. */
  public Object construct(final Class<?> cls, final Function<String, Object> valueByName) {
    return construct.apply(cls, valueByName);
  }

  /**
   * Translate a raw method name from an {@code Accessor} (recovered via {@code SerializedLambda})
   * into the component / property name DeepMap uses for lookups. For records: identity. For beans:
   * strip {@code get} / {@code is} prefix and decapitalize.
   */
  public String normalize(final String rawMethodName) {
    return normalize.apply(rawMethodName);
  }

  /**
   * The class as a structural {@link Iso} mediating between a name-keyed {@code Map<String,
   * Object>} and a concrete instance of {@code T}. Forward: {@link #construct} from the map.
   * Backward: read every component / property of the instance into a fresh {@link LinkedHashMap}
   * keyed by the structural name.
   *
   * <p>This is the lattice-routed shape of "rebuild an instance from named values" and "decompose
   * an instance into named values" — the two operations {@link
   * io.github.eschizoid.telescope.DeepMap DeepMap} previously performed inline in the body of
   * {@code assembleIso}. Lifting them to an {@link Iso} lets {@code assembleIso} express the
   * per-pair {@code Iso<S, T>} as the composition {@code
   * srcReader.reverse().then(middle).then(tgtBuilder)} — pure lattice {@code .then()}, no manual
   * function-body construction.
   *
   * <p>When {@code cls} carries a sibling {@code <X>Telescope} metadata holder (codegen by {@link
   * io.github.eschizoid.telescope.annotations.Focus @Focus} / {@link
   * io.github.eschizoid.telescope.annotations.BeanFocus @BeanFocus} / Lombok), the backward
   * direction's per-component reads route through the holder's pre-baked {@link Lens} constants
   * directly — bypassing the per-call {@link Records#read} / {@link Beans#readProperty} dispatch.
   * The forward branch ({@code Map → instance}) similarly short-circuits the reflective {@link
   * #construct} path through the holder's bound {@code construct(Function<String, Object>)} method.
   * When no holder is present (unannotated types), today's reflective {@link #read} / {@link
   * #construct} paths run unchanged.
   */
  public <T> Iso<Map<String, Object>, T> structuralIso(final Class<T> cls) {
    final var componentNames = names(cls);
    final var holderReaders = resolveHolderReaders(cls, componentNames);
    final var holderConstructor = resolveHolderConstructor(cls);
    return Iso.of(
      map -> {
        if (holderConstructor != null) return cls.cast(holderConstructor.apply(map::get));
        return cls.cast(construct(cls, map::get));
      },
      instance -> {
        final var out = new LinkedHashMap<String, Object>();
        if (holderReaders != null) {
          for (final var name : componentNames) out.put(name, holderReaders.get(name).get(instance));
        } else {
          for (final var name : componentNames) out.put(name, read(instance, name));
        }
        return out;
      }
    );
  }

  /**
   * If a sibling {@code <X>Telescope} holder is on the classpath, return its bound {@code
   * construct(Function<String, Object>)} function so the forward branch of {@link #structuralIso}
   * bypasses {@link #construct} entirely. Returns {@code null} when no holder is present — the
   * caller falls back to the reflective {@link #construct} path. Matches the
   * pre-resolution-or-nothing posture of {@link #resolveHolderReaders}: one branch outside the
   * {@link Iso}'s hot map, not inside.
   */
  private static Function<Function<String, Object>, Object> resolveHolderConstructor(final Class<?> cls) {
    return MetadataHolderProbe.probeFor(cls).map(MetadataHolderProbe.HolderRef::constructor).orElse(null);
  }

  /**
   * If a sibling {@code <X>Telescope} holder is on the classpath AND it exposes a lens constant for
   * every component in {@code componentNames}, return the {@code name -> Lens} table the backward
   * branch of {@link #structuralIso} uses to bypass {@link #read} entirely. Otherwise return {@code
   * null} — the caller falls back to the reflective {@link #read} path. The
   * pre-resolution-or-nothing posture avoids per-component branching inside the {@link Iso}'s hot
   * loop (one branch outside vs. {@code N} branches inside) and matches the Phase B dispatch shape
   * in {@link
   * io.github.eschizoid.telescope.Telescope#field(io.github.eschizoid.telescope.Telescope.Accessor)
   * Telescope.field(Accessor)}.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Lens<Object, Object>> resolveHolderReaders(
    final Class<?> cls,
    final String[] componentNames
  ) {
    final var maybeHolder = MetadataHolderProbe.probeFor(cls);
    if (maybeHolder.isEmpty()) return null;
    final var holder = maybeHolder.get();
    final var readers = new LinkedHashMap<String, Lens<Object, Object>>();
    for (final var name : componentNames) {
      final var lens = holder.lensFor(name);
      if (lens == null) return null;
      readers.put(name, (Lens<Object, Object>) lens);
    }
    return readers;
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static Object constructRecord(final Class<?> cls, final Function<String, Object> valueByName) {
    return Records.construct((Class) cls, valueByName);
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static Object constructBean(final Class<?> cls, final Function<String, Object> valueByName) {
    final var writer = Beans.autoWriter((Class) cls);
    return writer.construct(Beans.propertyNames(cls), valueByName);
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static Object constructBeanWithHints(
    final Map<Class<?>, Beans.BeanWriter<?>> hints,
    final Function<Class<?>, Beans.BeanWriter<?>> defaultWriterFactory,
    final Class<?> cls,
    final Function<String, Object> valueByName
  ) {
    // Resolution order: per-class hint → default-strategy factory → Beans.autoWriter. Each tier is
    // consulted lazily so a misconfigured fallback (e.g., writeBeans(BUILDER) on a target with no
    // builder) only throws when that target is actually constructed — never pre-emptively.
    Beans.BeanWriter writer = hints.get(cls);
    if (writer == null && defaultWriterFactory != null) writer = defaultWriterFactory.apply(cls);
    if (writer == null) writer = Beans.autoWriter((Class) cls);
    return writer.construct(Beans.propertyNames(cls), valueByName);
  }
}
