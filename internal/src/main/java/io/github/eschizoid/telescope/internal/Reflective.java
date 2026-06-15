package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.internal.optics.Lens;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The uniform reflective dispatch that {@code DeepMap} drives — abstracts over "this side is a
 * record" vs "this side is a bean" so the recursive resolver doesn't have to know. Per-side
 * dispatch via {@link #of(Class)} lets a single deep-mapping call mix and match records and POJOs
 * at any depth: the source side of a given pair uses one {@code Reflective}, the target side
 * another, chosen independently from the pair's classes.
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
 * <p>The lattice-first principle holds: {@code DeepMap} composes {@link
 * io.github.eschizoid.telescope.internal.optics.Iso Iso}s; this record only handles "how do I read
 * one component value" and "how do I rebuild one object from a name-keyed function." No
 * bidirectional plumbing lives here.
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
   * (when non-null) and ultimately to {@link Beans#autoWriter}. Used by {@code DeepMap} when the
   * user supplies {@code writeBean(targetClass, strategy)} rows and/or a single {@code
   * writeBeans(strategy)} default — the per-class hint map is keyed on target class and provides a
   * pre-instantiated {@link Beans.BeanWriter}; the default factory is consulted lazily on first
   * encounter with each not-explicitly-hinted target, then cached, so a default-strategy
   * incompatible with a particular target only throws when that target is actually constructed.
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
   * an instance into named values" — the two operations {@code DeepMap} previously performed inline
   * in the body of {@code assembleIso}. Lifting them to an {@link Iso} lets {@code assembleIso}
   * express the per-pair {@code Iso<S, T>} as the composition {@code
   * srcReader.reverse().then(middle).then(tgtBuilder)} — pure lattice {@code .then()}, no manual
   * function-body construction.
   *
   * <p>When {@code cls} carries a sibling {@code <X>Telescope} metadata holder (codegen by
   * {@code @Focus} / {@code @BeanFocus} / Lombok), the backward direction's per-component reads
   * route through the holder's pre-baked {@link Lens} constants directly — bypassing the per-call
   * {@link Records#read} / {@link Beans#readProperty} dispatch. The forward branch ({@code Map →
   * instance}) similarly short-circuits the reflective {@link #construct} path through the holder's
   * bound {@code construct(Function<String, Object>)} method. When no holder is present
   * (unannotated types), today's reflective {@link #read} / {@link #construct} paths run unchanged.
   */
  public <T> Iso<Map<String, Object>, T> structuralIso(final Class<T> cls) {
    return structuralIso(cls, null, null);
  }

  /**
   * Position-indexed structural Iso — the same mediation as {@link #structuralIso(Class)} but with
   * {@code Object[]} as the intermediate instead of {@code Map<String, Object>}. Eliminates the
   * per-call {@code LinkedHashMap} allocation + hash bucket puts/gets that dominate the runtime
   * mapper's allocation profile (776 B/op on the flat 5-field bean→record benchmark).
   *
   * <p>The returned Iso's forward direction takes an {@code Object[]} whose slots are positioned in
   * the same order as {@link #names(Class)}; each value flows into the canonical constructor
   * (records) or the corresponding setter (beans) at its declared position. The backward direction
   * reads each component / property by position via cached {@link Function} readers and returns a
   * freshly allocated {@code Object[]}.
   *
   * <p>Per-call cost: one {@code Object[arity]} allocation per direction (typically scalar-
   * replaceable when the call site is monomorphic) + N inlined virtual {@link Function#apply}
   * dispatches through cached LMF-bound readers. No hashing, no map allocation.
   *
   * <p>Holder-aware: when {@code holderReaders} / {@code holderConstructor} are supplied, the
   * backward branch reads via the holder's pre-baked {@link Lens} constants and the forward branch
   * builds via the holder's bound {@code construct(Function<String, Object>)} method (which still
   * costs a per-call name→value lookup; future work could add a positional holder shape).
   */
  public <T> Iso<Object[], T> structuralIsoArr(final Class<T> cls) {
    return structuralIsoArr(cls, null, null);
  }

  /**
   * Holder-aware overload of {@link #structuralIsoArr(Class)}. See {@link #structuralIso(Class,
   * Map, Function)} for the holder contract.
   */
  @SuppressWarnings("unchecked")
  public <T> Iso<Object[], T> structuralIsoArr(
    final Class<T> cls,
    final Map<String, Lens<Object, Object>> holderReaders,
    final Function<Function<String, Object>, Object> holderConstructor
  ) {
    final var componentNames = names(cls);
    final var arity = componentNames.length;
    if (cls.isRecord() && holderReaders == null && holderConstructor == null) {
      // Records fast path — Records.RecordInfo already exposes positional readers[] + ctorFn that
      // takes Object[] directly. No name dispatch anywhere on the hot path.
      final var info = Records.info(cls);
      final var readers = info.readers();
      final var ctorFn = info.ctorFn();
      return Iso.of(
        arr -> (T) ctorFn.apply(arr),
        instance -> {
          final var out = new Object[arity];
          for (var i = 0; i < arity; i++) out[i] = readers[i].apply(instance);
          return out;
        }
      );
    }
    // Bean (or hint-aware bean) path. Pre-resolve the positional reader array AND the name→index
    // map once at composition time. Forward still goes through the existing construct path
    // (Function<String, Object>) but with O(1) HashMap lookup instead of O(N) indexOf — kills the
    // O(N²) per-call name dispatch that previously dominated the bean-target write hot path on
    // every field of every flat conversion.
    final Function<Object, Object>[] readersByPos = resolveReadersByPosition(cls, componentNames, holderReaders);
    final var nameIndex = indexMap(componentNames);
    return Iso.of(
      arr -> {
        if (holderConstructor != null) return cls.cast(holderConstructor.apply(i -> arr[nameIndex.get(i)]));
        return cls.cast(construct(cls, i -> arr[nameIndex.get(i)]));
      },
      instance -> {
        final var out = new Object[arity];
        for (var i = 0; i < arity; i++) out[i] = readersByPos[i].apply(instance);
        return out;
      }
    );
  }

  /**
   * Expose the positional reader array used inside {@link #structuralIsoArr(Class, Map, Function)}.
   * Callers (typically {@code DeepMap} on the assembly hot path) can use this to bypass the
   * source-side {@link Iso} wrapper entirely and read instance → array values inline, eliminating
   * one virtual hop per call. Same substrate as {@link #structuralIsoArr}: holder-aware
   * (uses {@link Lens#get} when {@code holderReaders} is non-null), records short-circuit to
   * cached {@code RecordInfo.readers[]}, bean fallback captures the LMF reader via {@link #read}.
   */
  public Function<Object, Object>[] positionalReaders(
    final Class<?> cls,
    final Map<String, Lens<Object, Object>> holderReaders
  ) {
    return resolveReadersByPosition(cls, names(cls), holderReaders);
  }

  /**
   * Expose the positional builder ({@code Object[arity] → T}) used inside {@link #structuralIsoArr}.
   * Same shape as {@link #positionalReaders}: lets callers skip the Iso wrapper to invoke the
   * canonical constructor (records — direct LMF-built {@code ctorFn}) or the named-construct path
   * (beans — wrapped over an O(1) HashMap-indexed array lookup, no per-call name scan).
   */
  @SuppressWarnings("unchecked")
  public <T> Function<Object[], T> positionalBuilder(
    final Class<T> cls,
    final Map<String, Lens<Object, Object>> holderReaders,
    final Function<Function<String, Object>, Object> holderConstructor
  ) {
    if (cls.isRecord() && holderReaders == null && holderConstructor == null) {
      final var ctorFn = Records.info(cls).ctorFn();
      return arr -> (T) ctorFn.apply(arr);
    }
    final var componentNames = names(cls);
    final var nameIndex = indexMap(componentNames);
    if (holderConstructor != null) {
      return arr -> (T) holderConstructor.apply(i -> arr[nameIndex.get(i)]);
    }
    return arr -> cls.cast(construct(cls, i -> arr[nameIndex.get(i)]));
  }

  @SuppressWarnings("unchecked")
  private Function<Object, Object>[] resolveReadersByPosition(
    final Class<?> cls,
    final String[] componentNames,
    final Map<String, Lens<Object, Object>> holderReaders
  ) {
    @SuppressWarnings({ "unchecked", "rawtypes" })
    final var arr = (Function<Object, Object>[]) new Function[componentNames.length];
    // For records that fall through to this branch (holder present), short-circuit the wrapping
    // `instance -> read(instance, capturedName)` lambda by binding the LMF-built positional reader
    // from RecordInfo directly. Skips the per-call name→accessor dispatch.
    final var recordReaders = cls.isRecord() ? Records.info(cls).readers() : null;
    for (var i = 0; i < componentNames.length; i++) {
      final var name = componentNames[i];
      if (holderReaders != null) {
        final var lens = holderReaders.get(name);
        arr[i] = lens::get;
      } else if (recordReaders != null) {
        arr[i] = recordReaders[i];
      } else {
        // Bean fallback — captures the cached LMF Function via Beans.readProperty's substrate.
        // One virtual Function#apply dispatch per call, into the LMF-built reader.
        final var capturedName = name;
        arr[i] = instance -> read(instance, capturedName);
      }
    }
    return arr;
  }

  private static Map<String, Integer> indexMap(final String[] names) {
    final var m = new HashMap<String, Integer>(names.length * 2);
    for (var i = 0; i < names.length; i++) m.put(names[i], i);
    return m;
  }

  private static int indexOf(final String[] names, final String name) {
    for (var i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
    throw new IllegalArgumentException("No such field: " + name);
  }

  /**
   * Holder-aware overload of {@link #structuralIso(Class)}: when {@code holderReaders} / {@code
   * holderConstructor} are non-{@code null}, the returned Iso short-circuits the reflective read /
   * construct paths through the pre-resolved holder data. Both inputs are resolved by the caller in
   * {@code :core} (where {@code Telescope} is visible to unwrap holder constants); passing them
   * here keeps {@code :internal} compile-time-oblivious to {@code :core} — no callback, no global
   * state, no static-init bridge.
   *
   * <p>{@code holderConstructor} is the bound {@code construct(Function<String, Object>)} function
   * the holder emits; {@code holderReaders} is the {@code name -> Lens} table that bypasses {@link
   * #read} on the backward branch. Both null when the class carries no sibling holder.
   */
  public <T> Iso<Map<String, Object>, T> structuralIso(
    final Class<T> cls,
    final Map<String, Lens<Object, Object>> holderReaders,
    final Function<Function<String, Object>, Object> holderConstructor
  ) {
    final var componentNames = names(cls);
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
