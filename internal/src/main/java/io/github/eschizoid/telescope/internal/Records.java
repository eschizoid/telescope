package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Lens;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Lattice-routed machinery for building {@link Lens}es over record components by name. Backs the
 * field navigation of {@code Telescope}.
 *
 * <p>State is a single per-record-class {@code RecordInfo}, held in a {@link ClassValue} so a
 * stored entry doesn't pin its key's classloader. The cache carries (a) the {@link RecordComponent}
 * array for metadata (name, generic type, index lookup), (b) one {@link Function
 * Function&lt;Object, Object&gt;} per component built once via {@link LambdaMetafactory} for the
 * hot-path read, (c) the canonical {@link Constructor} kept for metadata, and (d) a single
 * canonical-constructor invoker built once over a {@link MethodHandle#asSpreader(Class, int)
 * asSpreader}-wrapped {@link MethodHandle} for the hot-path rebuild. The hot path never touches
 * {@link java.lang.reflect.Method#invoke} or {@link Constructor#newInstance} — reads dispatch
 * through the synthetic LMF call site (which the JIT inlines just like a direct method-ref
 * invocation), and rebuilds dispatch through a cached MethodHandle (LMF rejects the non-direct
 * spread adapter, so the JDK-standard MethodHandle path is used; it still skips the per-call access
 * check and varargs allocation that {@code Constructor.newInstance} pays).
 *
 * <p>Records only. Mutating helpers reject any non-record argument with {@code "Not a record"}, and
 * {@code RecordInfo.of} does the same when a class is first cached.
 */
public final class Records {

  private Records() {}

  // ClassValue (not ConcurrentHashMap) so the cached RecordInfo — which strongly references the
  // RecordComponent[], the Constructor, and the LambdaMetafactory-built reader Functions — can't
  // pin a classloader through the strongly-held value chain back to the weak key. ClassValue
  // holds entries off-heap from the Class itself, the JDK-native pattern for class-keyed caches
  // that permit classloader unloading.
  private static final ClassValue<RecordInfo> CACHE = new ClassValue<>() {
    @Override
    protected RecordInfo computeValue(final Class<?> type) {
      return RecordInfo.of(type);
    }
  };

  /**
   * A {@link Lens} over a record component, identified by name. The {@code get} reads the
   * component; {@code set}/{@code modify} return a copy of the record with that one component
   * replaced. Backs {@code Telescope}'s {@code .fieldByName(String)} overload — the late-bound case
   * where the runtime class isn't known until the source flows in.
   *
   * <p>This overload pays a per-call {@code (class, name) → idx} lookup. When the declaring class
   * is known at construction time (every method-reference-based {@code .field(...)} call site),
   * prefer {@link #fieldLens(Class, String)} which resolves the reader once and bakes the index
   * into the returned lens.
   *
   * <pre>{@code
   * record User(String name, int age) {}
   * final Lens<User, String> name = Records.fieldLens("name");
   * final var renamed = name.set(new User("ann", 30), "bob"); // User[name=bob, age=30]
   * }</pre>
   */
  public static <S, A> Lens<S, A> fieldLens(final String fieldName) {
    return new Lens<>() {
      @SuppressWarnings("unchecked")
      @Override
      public A get(final S source) {
        return (A) readField(source, fieldName);
      }

      @Override
      public S set(final S source, final A value) {
        return updateField(source, fieldName, ignored -> value);
      }

      @Override
      @SuppressWarnings("unchecked")
      public S modify(final S source, final Function<? super A, ? extends A> f) {
        return updateField(source, fieldName, current -> ((Function<Object, Object>) f).apply(current));
      }
    };
  }

  /**
   * Class-aware variant of {@link #fieldLens(String)}. Resolves the per-class {@link RecordInfo},
   * the component index, and the LMF-built reader once at construction; the returned lens's {@code
   * get} / {@code set} / {@code modify} dispatch directly through the captured handles with no
   * per-call name lookup.
   *
   * <p>Used by the method-reference {@code .field(...)} path on {@code Telescope}, which already
   * knows the declaring class via the captured method reference. The runtime escape hatch {@code
   * fieldByName(String)} still uses {@link #fieldLens(String)} because the source class isn't known
   * until call time.
   *
   * <pre>{@code
   * record User(String name, int age) {}
   * final Lens<User, String> name = Records.fieldLens(User.class, "name");
   * final var renamed = name.set(new User("ann", 30), "bob"); // User[name=bob, age=30]
   * }</pre>
   */
  public static <S, A> Lens<S, A> fieldLens(final Class<S> cls, final String fieldName) {
    final var info = info(cls);
    final var idx = info.indexOf(fieldName);
    if (idx < 0) throw noField(fieldName, cls);
    final var reader = info.readers()[idx];
    return new Lens<>() {
      @SuppressWarnings("unchecked")
      @Override
      public A get(final S source) {
        if (source == null) return null;
        return (A) reader.apply(source);
      }

      @Override
      public S set(final S source, final A value) {
        return rebuildWith(info, idx, source, ignored -> value);
      }

      @Override
      @SuppressWarnings("unchecked")
      public S modify(final S source, final Function<? super A, ? extends A> f) {
        return rebuildWith(info, idx, source, current -> ((Function<Object, Object>) f).apply(current));
      }
    };
  }

  /**
   * Record component names in canonical-constructor order. Throws {@code "Not a record"} if {@code
   * recordClass} is not a record.
   *
   * <pre>{@code
   * record User(String name, int age) {}
   * final var names = Records.componentNames(User.class); // ["name", "age"]
   * }</pre>
   */
  public static String[] componentNames(final Class<?> recordClass) {
    final var comps = info(recordClass).components();
    final var names = new String[comps.length];
    for (var i = 0; i < comps.length; i++) names[i] = comps[i].getName();
    return names;
  }

  /**
   * The generic type of a record component by name (used by {@code DeepMap} for container shape
   * detection — {@code List<X>}, {@code Map<K, V>}, {@code Optional<X>}).
   *
   * @throws IllegalArgumentException if the name doesn't match a component on {@code recordClass}
   */
  public static Type componentType(final Class<?> recordClass, final String name) {
    for (final var c : info(recordClass).components()) if (c.getName().equals(name)) return c.getGenericType();
    throw noField(name, recordClass);
  }

  /**
   * Read a record component by name. Public mirror of the internal accessor. Returns {@code null}
   * for a {@code null} source; throws "No field" if the name is unknown.
   *
   * <pre>{@code
   * record User(String name, int age) {}
   * final var n = Records.read(new User("ann", 30), "name"); // "ann"
   * }</pre>
   */
  public static Object read(final Object source, final String fieldName) {
    return readField(source, fieldName);
  }

  /**
   * Return a copy of {@code source} with one part replaced by {@code value}; all other components
   * carry over. Returns {@code null} for a {@code null} source; throws "Not a record" for a
   * non-record, "No field" for an unknown name.
   *
   * <pre>{@code
   * record User(String name, int age) {}
   * final var older = Records.with(new User("ann", 30), "age", 31); // User[name=ann, age=31]
   * }</pre>
   */
  public static <R> R with(final R source, final String fieldName, final Object value) {
    return updateField(source, fieldName, ignored -> value);
  }

  /**
   * Construct a record by supplying each component's value via {@code valueByName}, invoked once
   * per component in canonical-constructor order. Used by the mapping builder to synthesize the
   * target type from a declared set of field correspondences. Throws "Not a record" if {@code
   * recordClass} is not a record.
   *
   * <pre>{@code
   * record User(String name, int age) {}
   * final var values = Map.<String, Object>of("name", "ann", "age", 30);
   * final var user = Records.construct(User.class, values::get); // User[name=ann, age=30]
   * }</pre>
   */
  @SuppressWarnings("unchecked")
  public static <R> R construct(final Class<R> recordClass, final Function<String, Object> valueByName) {
    final var info = info(recordClass);
    final var comps = info.components();
    final var args = new Object[comps.length];
    for (var i = 0; i < comps.length; i++) args[i] = valueByName.apply(comps[i].getName());
    return (R) info.ctorFn().apply(args);
  }

  /**
   * Tier-A runtime fast-path: build a {@code Function<S, T>} that maps a source record to a target
   * record by name-positional reads + canonical-constructor invocation, bypassing the structural
   * {@link io.github.eschizoid.telescope.internal.optics.Iso} composition chain entirely.
   *
   * <p>Eligibility (all must hold):
   *
   * <ul>
   *   <li>Both {@code sourceClass} and {@code targetClass} are records.
   *   <li>Every target component has a same-named source component.
   *   <li>Every same-named pair has assignable types: source component's type is assignable to
   *       target component's type (covers identity matches; cross-type conversions still take the
   *       slow path).
   * </ul>
   *
   * <p>When eligible, returns a {@code Function<S, T>} that resolves the reader array once and the
   * constructor function once at composition time, then per-call does only:
   *
   * <pre>{@code
   * Object[] args = new Object[N];
   * args[0] = reader[i0].apply(s);   // LMF-built reader, JIT-inlines
   * args[1] = reader[i1].apply(s);
   * ...
   * return (T) ctorFn.apply(args);   // LMF-built canonical ctor invoker
   * }</pre>
   *
   * <p>Returns {@code null} when not eligible — the caller falls back to the slow path.
   *
   * <p><b>Per-pair cache.</b> Eligibility + Function build is memoized via {@link #FAST_PATH_CACHE}
   * so the per-pair setup runs once and subsequent {@code mapper.forward(s)} calls dispatch through
   * a single virtual call.
   */
  @SuppressWarnings("unchecked")
  public static <S, T> Function<S, T> fastPathForward(final Class<S> sourceClass, final Class<T> targetClass) {
    final var key = new FastPathKey(sourceClass, targetClass);
    final var cached = FAST_PATH_CACHE.get(key);
    if (cached == FastPathBuilt.NOT_ELIGIBLE) return null;
    if (cached != null) return (Function<S, T>) cached.fn;
    final var built = buildFastPath(sourceClass, targetClass);
    FAST_PATH_CACHE.put(key, built);
    return built == FastPathBuilt.NOT_ELIGIBLE ? null : (Function<S, T>) built.fn;
  }

  private record FastPathKey(Class<?> source, Class<?> target) {}

  private record FastPathBuilt(Function<?, ?> fn) {
    static final FastPathBuilt NOT_ELIGIBLE = new FastPathBuilt(null);
  }

  private static final ConcurrentHashMap<FastPathKey, FastPathBuilt> FAST_PATH_CACHE = new ConcurrentHashMap<>();

  private static FastPathBuilt buildFastPath(final Class<?> sourceClass, final Class<?> targetClass) {
    if (!sourceClass.isRecord() || !targetClass.isRecord()) return FastPathBuilt.NOT_ELIGIBLE;
    final var srcInfo = info(sourceClass);
    final var tgtInfo = info(targetClass);
    final var tgtComps = tgtInfo.components();
    final var srcComps = srcInfo.components();
    // Tier-A scope: flat scalar records only. Containers (List/Set/Map/Optional) and nested
    // record/bean types still take the slow path because (a) deep-copy semantics need the
    // structural Iso, (b) cycle detection lives there, (c) cross-type element conversions live
    // there. Fast-path is the dominant win for the flat tier (5 scalar fields, 91× MapStruct);
    // nested cases are 40× — the slow path already amortises constant overhead at that size.
    final var sourceIndexPerTargetSlot = new int[tgtComps.length];
    for (var ti = 0; ti < tgtComps.length; ti++) {
      final var tName = tgtComps[ti].getName();
      final var tType = tgtComps[ti].getType();
      if (!isFlatScalar(tType)) return FastPathBuilt.NOT_ELIGIBLE;
      var found = -1;
      for (var si = 0; si < srcComps.length; si++) {
        if (!srcComps[si].getName().equals(tName)) continue;
        if (tType != srcComps[si].getType()) return FastPathBuilt.NOT_ELIGIBLE;
        found = si;
        break;
      }
      if (found < 0) return FastPathBuilt.NOT_ELIGIBLE;
      sourceIndexPerTargetSlot[ti] = found;
    }
    final var readers = srcInfo.readers();
    final var ctorFn = tgtInfo.ctorFn();
    final var positions = sourceIndexPerTargetSlot;
    final var arity = positions.length;
    final Function<Object, Object> fn = s -> {
      if (s == null) return null;
      final var args = new Object[arity];
      for (var i = 0; i < arity; i++) args[i] = readers[positions[i]].apply(s);
      return ctorFn.apply(args);
    };
    return new FastPathBuilt(fn);
  }

  private static boolean isFlatScalar(final Class<?> type) {
    if (type.isPrimitive()) return true;
    if (type.isEnum()) return true;
    if (type == String.class) return true;
    if (type == Integer.class || type == Long.class || type == Double.class || type == Float.class) return true;
    if (type == Short.class || type == Byte.class || type == Boolean.class || type == Character.class) return true;
    return false;
  }

  private static Object readField(final Object source, final String fieldName) {
    if (source == null) return null;
    final var info = info(source.getClass());
    final var idx = info.indexOf(fieldName);
    if (idx < 0) throw noField(fieldName, source.getClass());
    return info.readers()[idx].apply(source);
  }

  @SuppressWarnings("unchecked")
  private static <S> S updateField(final S source, final String fieldName, final Function<Object, Object> fn) {
    if (source == null) return null;
    final var cls = source.getClass();
    if (!cls.isRecord()) throw new IllegalArgumentException("Not a record: " + cls.getName());
    final var info = info(cls);
    final var idx = info.indexOf(fieldName);
    if (idx < 0) throw noField(fieldName, cls);
    return rebuildWith(info, idx, source, fn);
  }

  /**
   * Rebuild {@code source} with the component at {@code idx} replaced by {@code fn} applied to its
   * current value. Captures-friendly: the caller supplies the pre-resolved {@link RecordInfo} and
   * {@code idx}, so there's no per-call {@code (class, name) → idx} lookup. The class-aware {@link
   * #fieldLens(Class, String)} overload uses this directly; the late-bound {@link
   * #updateField(Object, String, Function)} resolves info+idx then delegates here.
   */
  @SuppressWarnings("unchecked")
  private static <S> S rebuildWith(
    final RecordInfo info,
    final int idx,
    final S source,
    final Function<Object, Object> fn
  ) {
    if (source == null) return null;
    final var readers = info.readers();
    final var args = new Object[readers.length];
    for (var i = 0; i < args.length; i++) {
      final var current = readers[i].apply(source);
      args[i] = (i == idx) ? fn.apply(current) : current;
    }
    // Wrap with the rebuild-specific context the pre-LMF Constructor#newInstance path used: the
    // shared `ctorFn` would otherwise rethrow as "Failed to construct <Record>" from
    // `RecordInfo.buildCtorFn`, losing the "this happened during a single-field update" context
    // useful for debugging Records.with / lens modifications.
    try {
      return (S) info.ctorFn().apply(args);
    } catch (final RuntimeException re) {
      throw new RuntimeException("Failed to rebuild " + source.getClass().getSimpleName(), re);
    }
  }

  private static RecordInfo info(final Class<?> cls) {
    return CACHE.get(cls);
  }

  private static IllegalArgumentException noField(final String fieldName, final Class<?> cls) {
    // Include the component names so a config-driven fieldByName(...) typo surfaces a usable hint
    // instead of forcing the user to read the record source. Load-bearing for the runtime-checked
    // surface — see the "Runtime-checked" bucket of compile-safety scoring in CLAUDE.md.
    return new IllegalArgumentException(
      "No field '" + fieldName + "' on " + cls.getName() + " — known fields: " + Arrays.toString(componentNames(cls))
    );
  }

  /**
   * Cached per-class metadata: the component array (canonical order) for name / type lookups, one
   * {@link Function Function&lt;Object, Object&gt;} per component built once via {@link
   * LambdaMetafactory} for hot-path reads, the canonical {@link Constructor} retained for its
   * metadata, and a single {@link Function Function&lt;Object, Object&gt;} canonical-constructor
   * invoker that wraps a cached spread {@link MethodHandle} for hot-path rebuild. {@code indexOf}
   * maps a component name to its position; the cache lives in {@link #CACHE}.
   *
   * <p>The {@code ctorFn} is typed {@code Function<Object, Object>} (Function's only SAM) but its
   * input is interpreted as an {@code Object[]} of canonical-constructor arguments — the wrapper
   * casts it back to {@code Object[]} before invoking the spread handle. Callers always pass a
   * sized-{@code components.length} {@code Object[]}; the spread handle pulls each element by index
   * and the JVM applies the same implicit boxed/primitive conversions a direct constructor
   * invocation would.
   */
  private record RecordInfo(
    RecordComponent[] components,
    Function<Object, Object>[] readers,
    Constructor<?> ctor,
    Function<Object, Object> ctorFn
  ) {
    static RecordInfo of(final Class<?> cls) {
      if (!cls.isRecord()) throw new IllegalArgumentException("Not a record: " + cls.getName());
      final var comps = cls.getRecordComponents();
      final var paramTypes = Arrays.stream(comps).map(RecordComponent::getType).toArray(Class<?>[]::new);
      try {
        final var ctor = cls.getDeclaredConstructor(paramTypes);
        // No raw setAccessible — access flows through privateLookupIn, which routes JPMS failures
        // through the tailored opens-pointing IllegalStateException below. A raw setAccessible
        // before that path would throw InaccessibleObjectException with a less-actionable message.
        final var lookup = privateLookupIn(cls);
        final var readers = buildReaders(cls, comps, lookup);
        final var ctorFn = buildCtorFn(cls, ctor, lookup);
        return new RecordInfo(comps, readers, ctor, ctorFn);
      } catch (final NoSuchMethodException e) {
        throw new IllegalStateException("Cannot find canonical constructor for " + cls.getName(), e);
      }
    }

    /**
     * One {@link MethodHandles.Lookup} per class — used for both the reader and the ctor LMF call
     * sites so we only walk the JPMS access check once per cache-warm.
     */
    private static MethodHandles.Lookup privateLookupIn(final Class<?> cls) {
      try {
        // `privateLookupIn` is needed when the record (or its module's package) isn't open to the
        // telescope module; for fully-public records in the same module this is equivalent to a
        // plain `MethodHandles.lookup()`. Same JPMS constraint as `setAccessible(true)` — no
        // worse than the previous reflection path.
        return MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
      } catch (final IllegalAccessException e) {
        throw new IllegalStateException(
          "Cannot access " +
            cls.getName() +
            " to build LambdaMetafactory call sites. Add 'opens " +
            cls.getPackageName() +
            " to io.github.eschizoid.telescope;' to that module's module-info.java.",
          e
        );
      }
    }

    /**
     * Build one {@link Function} per component via {@link LambdaMetafactory}. Forward: the
     * metafactory synthesizes a class implementing {@link Function} whose {@code apply(Object)}
     * directly calls the component accessor and auto-boxes any primitive return. After the first
     * call per class the dispatch is a single virtual call the JIT inlines — no {@link
     * java.lang.reflect.Method#invoke}, no per-call argument array, no access-check.
     */
    @SuppressWarnings("unchecked")
    private static Function<Object, Object>[] buildReaders(
      final Class<?> cls,
      final RecordComponent[] comps,
      final MethodHandles.Lookup lookup
    ) {
      final var readers = (Function<Object, Object>[]) new Function<?, ?>[comps.length];
      for (var i = 0; i < comps.length; i++) {
        final var comp = comps[i];
        try {
          final var handle = lookup.unreflect(comp.getAccessor());
          // SAM signature is `Object apply(Object)`; the instantiatedMethodType pins the actual
          // (recordClass) -> componentType signature so the metafactory generates the right
          // bridge — including auto-boxing for primitive returns (`int`, `long`, etc).
          final var callSite = LambdaMetafactory.metafactory(
            lookup,
            "apply",
            MethodType.methodType(Function.class),
            MethodType.methodType(Object.class, Object.class),
            handle,
            MethodType.methodType(comp.getType(), cls)
          );
          readers[i] = (Function<Object, Object>) callSite.getTarget().invoke();
        } catch (final Throwable t) {
          throw new IllegalStateException(
            "Failed to build LambdaMetafactory reader for " + cls.getName() + "." + comp.getName(),
            t
          );
        }
      }
      return readers;
    }

    /**
     * Build a {@code Function<Object, Object>} canonical-constructor invoker over an {@link
     * MethodHandle#asSpreader(Class, int) asSpreader}-wrapped {@link MethodHandle}. The raw
     * constructor handle has type {@code (T1, T2, …, Tn) → R}; {@code asSpreader(Object[].class,
     * arity)} converts it to {@code (Object[]) → R}, and {@link MethodHandle#asType(MethodType)
     * asType} relaxes the return to {@code Object} so {@link MethodHandle#invokeExact invokeExact}
     * can be called from a generic context. Primitive args auto-unbox per the same implicit
     * conversions a direct constructor call would apply.
     *
     * <p>This intentionally does <em>not</em> route through {@link LambdaMetafactory}: {@code
     * asSpreader} returns a non-direct adapter handle, and LMF rejects non-direct handles with
     * {@code "MethodHandle(Object[])R is not direct or cannot be cracked"}. The MethodHandle path
     * is the standard JDK alternative — it still skips the per-call access check and varargs
     * allocation that {@link Constructor#newInstance} pays, and the JIT inlines {@code invokeExact}
     * calls through {@code final} fields. The hot path never reaches {@code
     * Constructor.newInstance}.
     */
    private static Function<Object, Object> buildCtorFn(
      final Class<?> cls,
      final Constructor<?> ctor,
      final MethodHandles.Lookup lookup
    ) {
      try {
        final var spread = lookup
          .unreflectConstructor(ctor)
          .asSpreader(Object[].class, ctor.getParameterCount())
          .asType(MethodType.methodType(Object.class, Object[].class));
        return args -> {
          try {
            return (Object) spread.invokeExact((Object[]) args);
          } catch (final Throwable t) {
            throw new RuntimeException("Failed to construct " + cls.getSimpleName(), t);
          }
        };
      } catch (final IllegalAccessException e) {
        throw new IllegalStateException("Failed to build canonical-constructor invoker for " + cls.getName(), e);
      }
    }

    int indexOf(final String name) {
      for (var i = 0; i < components.length; i++) {
        if (components[i].getName().equals(name)) return i;
      }
      return -1;
    }
  }
}
