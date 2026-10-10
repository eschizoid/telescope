package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Getter;
import io.github.eschizoid.telescope.internal.optics.Lens;
import io.github.eschizoid.telescope.internal.pairing.BeanWriteStrategy;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import io.github.eschizoid.telescope.internal.pairing.ReflectionProps;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reflection-based machinery for navigating and constructing JavaBeans-style POJOs. Powers {@code
 * io.github.eschizoid.telescope.Telescope.ofBean(...)} (runtime navigation), {@code
 * Telescope.map(A.class, B.class, ...)} / {@code Telescope.mapper(...)} when either side is a POJO,
 * and the {@code WriteHint.writeBean(target, strategy)} explicit-strategy escape hatch.
 *
 * <p><b>Read direction (POJO &rarr; record/Map).</b> Getters are discovered by convention — a
 * no-arg {@code getX()} with a non-void return, or {@code isX()} returning {@code boolean}/ {@code
 * Boolean} — and {@code X} is decapitalized to a property name by the shared {@code PropertyNames}
 * rule. This deliberately avoids {@code java.beans.Introspector} so the library keeps zero
 * dependencies ({@code Introspector} lives in the {@code java.desktop} module). The discovered
 * getter map is cached per class via {@link ClassValue}. Alongside the {@link Method} cache, a
 * sibling {@link ClassValue} caches one {@link Function Function&lt;Object, Object&gt;} per
 * property, built once via {@link LambdaMetafactory} from the resolved accessor — the metafactory
 * synthesizes a {@link Function}-implementing class whose {@code apply(Object)} directly calls the
 * getter and auto-boxes any primitive return, so the hot path never touches {@link Method#invoke}.
 * The lattice-primitive read for one property is {@link #getter(Class, String)} — a {@link Getter
 * Getter&lt;P, Object&gt;} whose body delegates to the cached {@link Function} and allocates a
 * fresh capturing lambda per call (the lattice-shape entry for composing the read with other
 * optics). {@link #readProperty} is the hot-path shortcut that calls the cached {@link Function}
 * directly, skipping the per-call lambda allocation — preferred from inner loops (e.g. {@code
 * Reflective.structuralIso(...).from(...)} reads every property of a target).
 *
 * <p><b>Write direction (Map/record &rarr; POJO).</b> Three strategies behind the sealed {@link
 * BeanWriter} — {@link BuilderWriter}, {@link SettersWriter}, {@link ConstructorWriter} — chosen by
 * {@link #autoWriter} or selected explicitly by a {@code WriteHint.writeBean(target, strategy)} row
 * passed to {@code Telescope.map(...)}. Each one writes through a member the class declares for
 * writing; none injects a value into a private field.
 */
public final class Beans {

  private Beans() {}

  private static final ReflectionProps PROPS = new ReflectionProps();

  // ClassValue is the JDK-provided cache that genuinely permits class unloading: the entry is held
  // off-heap from the Class, so a cached value (which strongly references reflective members and
  // therefore the Class itself) cannot prevent its key from becoming unreachable. WeakHashMap
  // wouldn't work here — its strong values would chain back to the weak key, keeping the entry
  // alive. ClassValue is threadsafe by construction. Both the per-class getter map and the
  // auto-writer use it for the same reason — keep classloader-unload behavior consistent.
  private static final ClassValue<Map<String, Method>> GETTERS = new ClassValue<>() {
    @Override
    protected Map<String, Method> computeValue(final Class<?> type) {
      return scanGetters(type);
    }
  };

  // Sibling cache: one LambdaMetafactory-built Function<Object, Object> per property, derived from
  // the GETTERS map on first miss. Same ClassValue rationale as GETTERS / AUTO_WRITER_CACHE — the
  // value strongly references reflective members + synthetic-class function instances; ClassValue
  // is the JDK-native pattern for class-keyed caches that doesn't pin the class through the value
  // chain. After build, dispatch is a single virtual call the JIT inlines — no Method.invoke, no
  // per-call argument array, no access-check.
  private static final ClassValue<Map<String, Function<Object, Object>>> GETTER_INVOKERS = new ClassValue<>() {
    @Override
    protected Map<String, Function<Object, Object>> computeValue(final Class<?> type) {
      return buildGetterInvokers(type, GETTERS.get(type));
    }
  };

  // Per-class setter-invoker cache for {@link #capturedWriter}. Mirrors the GETTER_INVOKERS
  // shape: ClassValue at the class layer, ConcurrentHashMap at the per-property layer for lazy
  // single-property resolution (one Method scan + LMF compile per (cls, name) the in-place
  // mutation path actually visits). Separate from SettersWriter's per-instance setterInvokers
  // because binding a writer for in-place mutation needs setters but no no-arg constructor — the
  // caller supplies the already-constructed target.
  private static final ClassValue<Map<String, BiConsumer<Object, Object>>> SETTER_INVOKERS = new ClassValue<>() {
    @Override
    protected Map<String, BiConsumer<Object, Object>> computeValue(final Class<?> type) {
      return new ConcurrentHashMap<>();
    }
  };

  // (Class, Throwable) -> Object == constructorFailed. Bound into a composed constructor's handler.
  private static final MethodHandle CONSTRUCTOR_FAILED;
  // (BuilderWriter, Object) -> Object == BuilderWriter.verifyBuilt. Filters a composed build()'s
  // return value.
  private static final MethodHandle VERIFY_BUILT;

  static {
    final var lookup = MethodHandles.lookup();
    try {
      CONSTRUCTOR_FAILED = lookup.findStatic(
        Beans.class,
        "constructorFailed",
        MethodType.methodType(Object.class, Class.class, Throwable.class)
      );
      VERIFY_BUILT = lookup.findVirtual(
        BuilderWriter.class,
        "verifyBuilt",
        MethodType.methodType(Object.class, Object.class)
      );
    } catch (final ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final ClassValue<BeanWriter<?>> AUTO_WRITER_CACHE = new ClassValue<>() {
    @Override
    protected BeanWriter<?> computeValue(final Class<?> type) {
      return computeAutoWriter(type);
    }
  };

  // Raw, primitive-typed handles for the MethodHandle-combinator assembly path (MhIso) — the bean
  // mirror of Records.RecordInfo's accessorHandles / ctorHandle. Unlike GETTER_INVOKERS /
  // SETTER_INVOKERS (typed Function / BiConsumer, which box every primitive), these keep the real
  // getter/setter/constructor signatures so a composed (S) -> BeanT handle stays unboxed end to
  // end.
  // Same ClassValue rationale as the sibling caches — the value strongly references reflective
  // members; ClassValue keeps it off-heap from the Class so the key can still unload.
  private static final ClassValue<BeanMhInfo> BEAN_MH_INFO = new ClassValue<>() {
    @Override
    protected BeanMhInfo computeValue(final Class<?> type) {
      return BeanMhInfo.of(type);
    }
  };

  /**
   * Per-class cached {@link Supplier} that returns a fresh allocation of {@code cls}: tries a
   * public no-arg constructor first, then a public static {@code builder().build()} pair, and
   * finally yields {@code null} if neither shape works. Used by {@code DeepMap} for intermediate
   * bean allocations during recursive default-tree materialisation. LMF-cached so the per-call cost
   * is one virtual {@code Supplier.get()} regardless of which shape applies — no per-call {@code
   * Class.getDeclaredConstructor} / {@code Method.getMethod("build")} reflection.
   */
  private static final ClassValue<Supplier<Object>> INTERMEDIATE_ALLOCATORS = new ClassValue<>() {
    @Override
    protected Supplier<Object> computeValue(final Class<?> type) {
      return computeIntermediateAllocator(type);
    }
  };

  /**
   * The {@code org.hibernate.proxy.HibernateProxy} interface, or {@code null} when Hibernate isn't
   * on the classpath. Resolved once via reflection so the {@code :core} module stays free of any
   * Hibernate dependency.
   */
  private static final Class<?> HIBERNATE_PROXY = loadOptionalClass("org.hibernate.proxy.HibernateProxy");

  /**
   * LMF-bound dispatch for {@code HibernateProxy#getHibernateLazyInitializer()}. Built once at
   * class init when Hibernate is on the classpath; null otherwise (the {@link #persistentClassOf}
   * fast-path short-circuits before consulting it). Replaces the per-call {@code
   * Method.getMethod("getHibernateLazyInitializer")} + {@code Method.invoke} pair that previously
   * fired on every proxied bean read.
   */
  private static final Function<Object, Object> HIBERNATE_LAZY_INITIALIZER = buildHibernateLazyInitializerFn();

  /**
   * LMF-bound dispatch for {@code LazyInitializer#getPersistentClass()}. Same shape as {@link
   * #HIBERNATE_LAZY_INITIALIZER} — built once, called per proxy read.
   */
  private static final Function<Object, Class<?>> HIBERNATE_PERSISTENT_CLASS = buildHibernatePersistentClassFn();

  private static Class<?> loadOptionalClass(final String fqn) {
    try {
      return Class.forName(fqn, false, Beans.class.getClassLoader());
    } catch (final ClassNotFoundException e) {
      return null;
    }
  }

  /**
   * Public, cached entry point for intermediate bean allocation during recursive default-tree
   * materialisation in {@code DeepMap.recursiveDefault}. Returns a {@link Supplier} that yields a
   * fresh instance via either a public no-arg constructor or a public static {@code
   * builder().build()} pair; yields {@code null} when neither shape works. Cached per class via
   * {@link ClassValue} — the per-call cost is one virtual {@code Supplier.get()}.
   */
  public static Supplier<Object> intermediateAllocator(final Class<?> cls) {
    return INTERMEDIATE_ALLOCATORS.get(cls);
  }

  private static Supplier<Object> computeIntermediateAllocator(final Class<?> type) {
    // An abstract class has no instance to make, but it declares constructors like any other and
    // they bind like any other -- so a supplier built from one raises InstantiationError the first
    // time anything calls it, turning "can this be allocated?" from a question with the answer no
    // into a linkage error thrown out of whoever asked.
    //
    // That is true of the constructor arm alone. A static builder() on an abstract class returns a
    // builder whose build() returns something concrete, so the second arm below allocates for such
    // a type perfectly well and this must not reach over it.
    if (!Modifier.isAbstract(type.getModifiers())) {
      // Try a public no-arg ctor first — matches DeepMap.recursiveDefault's prior strategy.
      try {
        final var ctor = type.getDeclaredConstructor();
        if (Modifier.isPublic(ctor.getModifiers())) {
          final var lookup = ModuleAccess.privateLookupIn(type);
          return buildCtorSupplier(type, ctor, lookup);
        }
      } catch (final NoSuchMethodException ignored) {
        // No no-arg ctor at all — try the builder pattern.
      } catch (final IllegalAccessException e) {
        // privateLookupIn is refused by a module that does not open itself to us, and java.base is
        // the one every JDK container declaration runs into. Calling a public constructor does not
        // need private access; only binding one through LambdaMetafactory does. So bind a handle.
        final var viaPublicLookup = publicCtorSupplier(type);
        if (viaPublicLookup != null) return viaPublicLookup;
      }
    }
    try {
      final var builderMethod = type.getMethod("builder");
      if (Modifier.isStatic(builderMethod.getModifiers()) && Modifier.isPublic(builderMethod.getModifiers())) {
        return builderDefaultSupplier(type, builderMethod);
      }
    } catch (final NoSuchMethodException ignored) {
      // No static builder() — fall through to the null supplier.
    }
    return () -> null;
  }

  /**
   * A public no-argument constructor reached through the public lookup, or {@code null} when there
   * is not one. The handle is invoked directly rather than bridged through {@link
   * LambdaMetafactory}, which requires a lookup holding private access the public lookup does not
   * have.
   *
   * <p>What lands here is every type whose module declines {@code privateLookupIn}, which is every
   * platform container — {@code ArrayList} and {@code LinkedHashMap} among them, named by a
   * hard-coded allocator elsewhere and still arriving here whenever they are reached by a route
   * that asks this method rather than that table.
   *
   * <p>Under a native image such a constructor needs reachability metadata, where a hard-coded
   * allocator needs none. A missing-registration error is deliberately not caught: it names the
   * class the image was built without, which is worth more to an adopter than this method's
   * fallthrough.
   */
  private static Supplier<Object> publicCtorSupplier(final Class<?> type) {
    try {
      return MhAccessors.supplier(
        MethodHandles.publicLookup().findConstructor(type, MethodType.methodType(void.class))
      );
    } catch (final NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  /**
   * A handle on {@code type}'s public constructor taking exactly {@code parameters}, or {@code
   * null} when it declares none that can be bound.
   *
   * <p>It is bound the way {@link #intermediateAllocator} binds the no-argument constructor, so a
   * class whose no-argument constructor is reached has its other public constructors reached too: a
   * private lookup in the class's own module, which reaches a public constructor on a class that is
   * not itself public, and the public lookup where that module declines one, which is how a {@code
   * java.base} class is reached.
   */
  public static MethodHandle publicConstructor(final Class<?> type, final Class<?>... parameters) {
    final Constructor<?> ctor;
    try {
      ctor = type.getConstructor(parameters);
    } catch (final NoSuchMethodException e) {
      return null;
    }
    try {
      return ModuleAccess.privateLookupIn(type).unreflectConstructor(ctor);
    } catch (final IllegalAccessException privateRefused) {
      try {
        return MethodHandles.publicLookup().unreflectConstructor(ctor);
      } catch (final IllegalAccessException publicRefused) {
        return null;
      }
    }
  }

  /**
   * A supplier calling {@code type}'s declared no-argument constructor, whatever its access, or
   * {@code null} when there is none or it cannot be bound. Which constructors a rebuild may call is
   * the shared allocation rules' decision; this only makes the call.
   *
   * <p>It is bound through a private lookup in the class's own module, which reaches a constructor
   * of any access on a class that is not public, and through the public lookup where that module
   * declines, which reaches a public constructor of a {@code java.base} class. Cached per class, so
   * the decision being asked twice binds the constructor once.
   */
  public static Supplier<Object> noArgConstructor(final Class<?> type) {
    return NO_ARG_CONSTRUCTORS.get(type).orElse(null);
  }

  private static final ClassValue<Optional<Supplier<Object>>> NO_ARG_CONSTRUCTORS = new ClassValue<>() {
    @Override
    protected Optional<Supplier<Object>> computeValue(final Class<?> type) {
      return Optional.ofNullable(computeNoArgConstructor(type));
    }
  };

  private static Supplier<Object> computeNoArgConstructor(final Class<?> type) {
    if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) return null;
    final Constructor<?> ctor;
    try {
      ctor = type.getDeclaredConstructor();
    } catch (final NoSuchMethodException e) {
      return null;
    }
    try {
      return buildCtorSupplier(type, ctor, ModuleAccess.privateLookupIn(type));
    } catch (final IllegalAccessException e) {
      return Modifier.isPublic(ctor.getModifiers()) ? publicCtorSupplier(type) : null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Supplier<Object> builderDefaultSupplier(final Class<?> type, final Method builderMethod) {
    try {
      // A private lookup reaches the user's builder method across JPMS module and package
      // boundaries, once the user's package is open to this module (the same requirement as the
      // rest of the runtime path); without that, the lookup throws IllegalAccessException and we
      // fall through to the null supplier.
      final var lookup = ModuleAccess.privateLookupIn(type);
      final var builderHandle = lookup.unreflect(builderMethod);
      final var builderReturnType = builderMethod.getReturnType();
      final var buildMethod = builderReturnType.getMethod("build");
      // The Builder class itself usually lives in the same package as `type`, but for nested
      // builders (Builder is an inner class of the enclosing type) the same lookup already
      // covers it. For builders in different packages we'd need a second privateLookupIn keyed
      // on the builder class; the tests cover the common nested-Builder shape that the same
      // lookup handles.
      final var buildLookup = builderReturnType == type ? lookup : ModuleAccess.privateLookupIn(builderReturnType);
      final var buildHandle = buildLookup.unreflect(buildMethod);
      // Native-image: MethodHandle closures for both halves (no runtime class synthesis, see
      // MhAccessors); stock JVM: the LambdaMetafactory bridges.
      final var builderSpinner = ModuleAccess.spinner(lookup, builderMethod);
      final var buildSpinner = ModuleAccess.spinner(buildLookup, buildMethod);
      if (builderSpinner == null || buildSpinner == null) {
        final var mhBuilderFn = MhAccessors.supplier(builderHandle);
        final var mhBuildFn = MhAccessors.function(buildHandle);
        return () -> mhBuildFn.apply(mhBuilderFn.get());
      }
      final var builderCallSite = LambdaMetafactory.metafactory(
        builderSpinner,
        "get",
        MethodType.methodType(Supplier.class),
        MethodType.methodType(Object.class),
        builderHandle,
        MethodType.methodType(builderReturnType)
      );
      final var builderFn = (Supplier<Object>) builderCallSite.getTarget().invoke();
      final var buildCallSite = LambdaMetafactory.metafactory(
        buildSpinner,
        "apply",
        MethodType.methodType(Function.class),
        MethodType.methodType(Object.class, Object.class),
        buildHandle,
        MethodType.methodType(buildMethod.getReturnType(), builderReturnType)
      );
      final var buildFn = (Function<Object, Object>) buildCallSite.getTarget().invoke();
      return () -> buildFn.apply(builderFn.get());
    } catch (final Throwable t) {
      // If we can't bind either half of the chain, yield null instead of failing eagerly — the
      // caller (DeepMap.recursiveDefault) treats null as "no intermediate available" and skips
      // that branch of the default tree.
      return () -> null;
    }
  }

  /**
   * Build an LMF-bound no-arg constructor as a {@link Supplier} — the hot-path construct step of
   * {@link SettersWriter}, kept beside the other LMF builders on this class.
   */
  @SuppressWarnings("unchecked")
  static Supplier<Object> buildCtorSupplier(
    final Class<?> cls,
    final Constructor<?> ctor,
    final MethodHandles.Lookup lookup
  ) {
    try {
      final var handle = lookup.unreflectConstructor(ctor);
      // Native-image path: a MethodHandle closure, no runtime class synthesis — see MhAccessors.
      final var spinner = ModuleAccess.spinner(lookup, ctor);
      if (spinner == null) return MhAccessors.supplier(handle);
      final var callSite = LambdaMetafactory.metafactory(
        spinner,
        "get",
        MethodType.methodType(Supplier.class),
        MethodType.methodType(Object.class),
        handle,
        MethodType.methodType(cls)
      );
      return (Supplier<Object>) callSite.getTarget().invoke();
    } catch (final Throwable t) {
      throw new RuntimeException("Failed to instantiate " + cls.getName(), t);
    }
  }

  @SuppressWarnings("unchecked")
  private static Function<Object, Object> buildHibernateLazyInitializerFn() {
    // In a native image, proxy unwrapping is off by contract (the one documented JVM/codegen-only
    // accessor path). The guard also prevents a build-time-initialized LMF hidden class from being
    // captured into the image heap when Hibernate is on the image classpath — these builders run at
    // class init, which is build time under core's native-image.properties.
    if (HIBERNATE_PROXY == null || NativeImage.IN_IMAGE) return null;
    try {
      final var method = HIBERNATE_PROXY.getMethod("getHibernateLazyInitializer");
      final var lookup = MethodHandles.lookup();
      final var handle = lookup.unreflect(method);
      final var site = LambdaMetafactory.metafactory(
        lookup,
        "apply",
        MethodType.methodType(Function.class),
        MethodType.methodType(Object.class, Object.class),
        handle,
        MethodType.methodType(method.getReturnType(), HIBERNATE_PROXY)
      );
      return (Function<Object, Object>) site.getTarget().invokeExact();
    } catch (final Throwable t) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Function<Object, Class<?>> buildHibernatePersistentClassFn() {
    // Same native-image guard as buildHibernateLazyInitializerFn — see the comment there.
    if (HIBERNATE_PROXY == null || NativeImage.IN_IMAGE) return null;
    try {
      final var lazyInitializer = Class.forName(
        "org.hibernate.proxy.LazyInitializer",
        false,
        Beans.class.getClassLoader()
      );
      final var method = lazyInitializer.getMethod("getPersistentClass");
      final var lookup = MethodHandles.lookup();
      final var handle = lookup.unreflect(method);
      final var site = LambdaMetafactory.metafactory(
        lookup,
        "apply",
        MethodType.methodType(Function.class),
        MethodType.methodType(Object.class, Object.class),
        handle,
        MethodType.methodType(Class.class, lazyInitializer)
      );
      return (Function<Object, Class<?>>) site.getTarget().invokeExact();
    } catch (final Throwable t) {
      return null;
    }
  }

  /**
   * Return the persistent (declared-by-the-user) class of {@code pojo}, unwrapping a {@code
   * HibernateProxy} when one is present. Used as the cache key for {@link #GETTER_INVOKERS} so a
   * LAZY-fetched entity routed through telescope's bean reflection doesn't accumulate one cache
   * entry per Hibernate-generated proxy subclass (and one corresponding {@link
   * io.github.eschizoid.telescope.internal.MetadataHolderProbe} miss). Falls through to {@code
   * pojo.getClass()} when Hibernate isn't on the classpath or the reflective unwrap fails.
   *
   * <p>The unwrap calls {@code HibernateProxy#getHibernateLazyInitializer().getPersistentClass()},
   * neither of which initializes the proxy — so this is safe to call before the entity is actually
   * read, the same shape Hibernate's own {@code
   * HibernateProxyHelper.getClassWithoutInitializingProxy} follows.
   *
   * <p>Dispatch is LMF-cached: the two {@code Method.invoke} pairs that previously fired on every
   * call are now one {@link Function#apply} each, bound once at class init. Hot-path savings are
   * material when LAZY-fetched entities flow through deep-mapping.
   */
  public static Class<?> persistentClassOf(final Object pojo) {
    if (pojo == null) return null;
    final var raw = pojo.getClass();
    if (HIBERNATE_PROXY == null || !HIBERNATE_PROXY.isInstance(pojo)) return raw;
    if (HIBERNATE_LAZY_INITIALIZER == null || HIBERNATE_PERSISTENT_CLASS == null) return raw;
    try {
      final var initializer = HIBERNATE_LAZY_INITIALIZER.apply(pojo);
      if (initializer == null) return raw;
      final var persistentClass = HIBERNATE_PERSISTENT_CLASS.apply(initializer);
      return persistentClass != null ? persistentClass : raw;
    } catch (final RuntimeException e) {
      return raw;
    }
  }

  /**
   * The lattice-primitive form of "read one bean property" — a {@link Getter Getter&lt;P,
   * Object&gt;} over the {@code getX()} / {@code isX()} accessor. The underlying read is the {@link
   * LambdaMetafactory}-built {@link Function} from the {@link #GETTER_INVOKERS} ClassValue cache
   * (so the per-class probe is one-shot and per-call dispatch is a single virtual call the JIT
   * inlines — no {@link Method#invoke}, no per-call argument array), but each call to {@code
   * getter(...)} <em>does</em> allocate a fresh capturing lambda — this is the lattice-shape entry
   * point for callers that want to compose a {@code Getter} into other optics. Hot paths that just
   * want the value (e.g. {@link io.github.eschizoid.telescope.internal.Reflective Reflective}'s
   * bean-side {@code read}) should call {@link #readProperty(Object, String)} instead; it calls the
   * cached {@link Function} directly without the lambda allocation.
   *
   * <p>Throws {@link IllegalArgumentException} at build time if the named property has no getter.
   */
  public static <P> Getter<P, Object> getter(final Class<P> beanClass, final String name) {
    final var reader = GETTER_INVOKERS.get(beanClass).get(name);
    if (reader == null) throw new IllegalArgumentException(
      "No getter for property '" + name + "' on " + beanClass.getName()
    );
    return reader::apply;
  }

  /**
   * The raw cached LMF reader {@link Function} for {@code (beanClass, name)} — resolved once and
   * returned directly, with no wrapping capture lambda (unlike {@link #getter}). Lets a hot
   * positional-read loop (e.g. {@code Reflective.positionalReaders} on the {@code
   * Telescope.mapper(...)} assembly path) bind the reader at build time and pay only one virtual
   * {@code Function#apply} dispatch per call, instead of the per-call {@link #persistentClassOf}
   * unwrap + {@link #GETTER_INVOKERS} {@link ClassValue} probe + name&rarr;reader {@code HashMap}
   * lookup that {@link #readProperty(Object, String)} repeats on every read.
   *
   * <p>The reader dispatches {@code invokevirtual} on the getter, so a subtype instance — including
   * a Hibernate proxy that overrides the accessor — still reads correctly even though the reader is
   * bound to {@code beanClass}. The {@code persistentClassOf} unwrap that {@code readProperty}
   * performs exists only to keep {@code GETTER_INVOKERS} from accumulating one cache entry per
   * proxy subclass; binding the reader once for the declared class sidesteps that bloat entirely.
   *
   * <p>Throws {@link IllegalArgumentException} at build time if the named property has no getter.
   */
  public static Function<Object, Object> capturedReader(final Class<?> beanClass, final String name) {
    final var reader = GETTER_INVOKERS.get(beanClass).get(name);
    if (reader == null) throw new IllegalArgumentException(
      "No getter for property '" + name + "' on " + beanClass.getName()
    );
    return reader;
  }

  /**
   * Write-side sibling of {@link #capturedReader}: the cached {@link BiConsumer} for one property,
   * resolved once so a caller writing the same property repeatedly pays the {@link ClassValue}
   * probe and the name lookup at bind time rather than per write. Dispatch is a {@link
   * LambdaMetafactory}-bound {@link BiConsumer} the JIT inlines — no {@link Method#invoke}, no
   * per-call argument array. For a primitive-typed property that bound writer is wrapped in a null
   * guard, so a null value is skipped rather than unboxed and the target property keeps its current
   * value.
   *
   * <p>Used by {@code Mapper.into(target, source)} — the {@code @MappingTarget} equivalent — for
   * in-place mutation of an existing target. Unlike {@link #settersWriter(Class)} it does NOT
   * require a no-arg constructor: the caller supplies the already-constructed target, so only the
   * setters need to be public.
   *
   * <p>Unlike {@link #capturedReader}, a name with no matching setter is <em>not</em> an error: it
   * yields a no-op writer, so a getter-only or computed property is skipped rather than throwing.
   * That asymmetry is deliberate and matches both what the rebuild strategies do for an unwritable
   * property and MapStruct's {@code @MappingTarget} semantics, so such a property never breaks an
   * otherwise valid mapping. Callers wanting a name checked must check it themselves.
   *
   * <p>Pass the class the write will actually target. Binding to a declared supertype silently
   * skips a property whose setter exists only on the concrete subclass, which is the ordinary shape
   * for an entity hierarchy.
   *
   * @throws IllegalStateException via {@link MethodHandles#privateLookupIn} when the setter's
   *     declaring class lives in a closed-package module without an {@code opens} directive
   */
  public static BiConsumer<Object, Object> capturedWriter(final Class<?> beanClass, final String name) {
    return SETTER_INVOKERS.get(beanClass).computeIfAbsent(name, n -> buildSetterInvoker(beanClass, n));
  }

  /**
   * Read a bean property by name via its {@code getX()} / {@code isX()} accessor. Throws if no
   * getter matches {@code name}.
   *
   * <p>Calls the cached {@link Function} directly rather than routing through {@link #getter} — the
   * latter allocates a capturing lambda per call, which matters in hot loops (e.g. {@code
   * Reflective.structuralIso(...).from(...)} reads every property of a target). The underlying
   * function is built once per {@code (beanClass, property)} via {@link LambdaMetafactory} and
   * cached in {@link #GETTER_INVOKERS}; subsequent dispatch is a single virtual call the JIT
   * inlines — no {@link Method#invoke}.
   *
   * <pre>{@code
   * final var name = (String) Beans.readProperty(userPojo, "name"); // userPojo.getName()
   * }</pre>
   */
  public static Object readProperty(final Object pojo, final String name) {
    // Null-source short-circuit: multi-hop telescope paths (e.g.
    // `Telescope.ofBean(Order.class).field(Order::getCustomer).field(Customer::getEmail)`) read
    // each hop through this method. When an intermediate object is null at runtime
    // (`order.getCustomer() == null`), the next hop arrives here with `pojo == null` and the
    // pipeline needs the null to propagate gracefully — same shape as MapStruct's generated
    // `if (source.getCustomer() != null) target.setEmail(source.getCustomer().getEmail())`
    // null-guard. Without this short-circuit, `persistentClassOf(null)` returns null and
    // `ClassValue.get(null)` NPEs.
    if (pojo == null) return null;
    // Unwrap HibernateProxy (when present) so a LAZY-fetched entity routes through the
    // persistent class's cache entry, not a per-proxy-subclass one. See persistentClassOf.
    final var beanClass = persistentClassOf(pojo);
    final var reader = GETTER_INVOKERS.get(beanClass).get(name);
    if (reader == null) throw new IllegalArgumentException(
      "No getter for property '" + name + "' on " + beanClass.getName()
    );
    return reader.apply(pojo);
  }

  @SuppressWarnings("unchecked")
  private static BiConsumer<Object, Object> buildSetterInvoker(final Class<?> cls, final String name) {
    final var set = "set" + capitalize(name);
    Method setter = null;
    for (final var m : cls.getMethods()) {
      if (
        m.getParameterCount() == 1 && m.getName().equals(set) && !Modifier.isStatic(m.getModifiers()) && !m.isBridge()
      ) {
        setter = m;
        break;
      }
    }
    // Getter-only / computed / immutable properties are silently skipped — without this,
    // Mapper.into(target, source) would throw on the same property pair that Mapper.forward
    // (via SettersWriter) silently skipped, producing an asymmetric same-mapper contract.
    // MapStruct's @MappingTarget ignores unwritable target fields too; match.
    if (setter == null) return (pojo, value) -> {};
    final var declaringClass = setter.getDeclaringClass();
    final var lookup = privateLookupOrThrow(declaringClass, cls, "setter");
    final var paramType = setter.getParameterTypes()[0];
    final var instantiatedParamType = wrap(paramType);
    try {
      final var handle = lookup.unreflect(setter);
      // Native-image path: a MethodHandle closure, no runtime class synthesis — see MhAccessors.
      // asType relaxes the receiver to Object and unboxes a primitive param from the boxed value,
      // matching the auto-unbox the LMF instantiatedMethodType installs below.
      final BiConsumer<Object, Object> base;
      final var spinner = ModuleAccess.spinner(lookup, setter);
      if (spinner == null) {
        base = MhAccessors.biConsumer(handle);
      } else {
        final var callSite = LambdaMetafactory.metafactory(
          spinner,
          "accept",
          MethodType.methodType(BiConsumer.class),
          MethodType.methodType(void.class, Object.class, Object.class),
          handle,
          MethodType.methodType(void.class, declaringClass, instantiatedParamType)
        );
        base = (BiConsumer<Object, Object>) callSite.getTarget().invoke();
      }
      // Same primitive-null guard as SettersWriter: null into a primitive setter must leave the
      // target property unchanged, not NPE at the unbox — Mapper.into must not crash on the input
      // Mapper.forward silently defaults (same-mapper symmetry).
      if (paramType.isPrimitive()) {
        return (pojo, value) -> {
          if (value == null) return;
          base.accept(pojo, value);
        };
      }
      return base;
    } catch (final Throwable t) {
      throw new RuntimeException("Failed to set '" + name + "' on " + cls.getName(), t);
    }
  }

  /**
   * Whether {@code beanClass} exposes a getter for {@code name} (per the {@code getX()} / {@code
   * isX()} scan). Reuses the same cached getter map as {@link #readProperty(Object, String)}.
   */
  public static boolean hasProperty(final Class<?> beanClass, final String name) {
    return getters(beanClass).containsKey(name);
  }

  /**
   * The generic return type of {@code beanClass}'s getter for {@code name} (used by {@code DeepMap}
   * for container shape detection — {@code List<X>}, {@code Map<K, V>}, {@code Optional<X>}).
   *
   * @throws IllegalArgumentException if no getter is found
   */
  public static Type propertyType(final Class<?> beanClass, final String name) {
    final var getter = getters(beanClass).get(name);
    if (getter == null) throw new IllegalArgumentException(
      "No getter for property '" + name + "' on " + beanClass.getName()
    );
    return getter.getGenericReturnType();
  }

  /**
   * {@link #propertyType} as a member of {@code beanClass}. A getter inherited from a generic
   * superclass returns that superclass's type variable, which {@code class Node extends Base<Node>}
   * fixes to {@code Node}; see {@link ReflectionProps#memberOf} for what is left as declared.
   *
   * @throws IllegalArgumentException if no getter is found
   */
  public static Type memberPropertyType(final Class<?> beanClass, final String name) {
    final var getter = getters(beanClass).get(name);
    if (getter == null) throw new IllegalArgumentException(
      "No getter for property '" + name + "' on " + beanClass.getName()
    );
    return PROPS.memberOf(getter.getGenericReturnType(), getter.getDeclaringClass(), beanClass);
  }

  private static Map<String, Method> getters(final Class<?> cls) {
    return GETTERS.get(cls);
  }

  /**
   * Build one {@link Function} per discovered getter, each a direct getter call that auto-boxes a
   * primitive return, with no {@link Method#invoke}, per-call argument array, or access-check. On a
   * stock JVM each is a {@link LambdaMetafactory}-synthesized class ({@link #lmfGetter}); inside a
   * native image, where runtime class definition is banned, each is a {@link MethodHandle} closure
   * ({@link MhAccessors#function}). Both dispatch as a single virtual call the JIT inlines.
   *
   * <p>JPMS access has the same rules as {@link AccessibleObject#setAccessible(boolean)}: if the
   * bean's package is not {@code opens}-exposed to {@code io.github.eschizoid.telescope}, the
   * {@code privateLookupIn} call will fail with {@link IllegalAccessException}. That's re-thrown as
   * an {@link IllegalStateException} pointing the caller at the {@code opens} directive — the exact
   * same constraint and message shape as Phase 1's record-reader path.
   */
  private static Map<String, Function<Object, Object>> buildGetterInvokers(
    final Class<?> cls,
    final Map<String, Method> getters
  ) {
    if (getters.isEmpty()) return Map.of();
    // Cache one Lookup per declaring class — inherited getters live on a superclass / interface
    // whose package may be in a different module than `cls`. A single lookup keyed to `cls` would
    // fail to unreflect an inherited accessor whose declaring package isn't open to telescope.
    final var lookupByDeclaringClass = new LinkedHashMap<Class<?>, MethodHandles.Lookup>();
    final var invokers = new LinkedHashMap<String, Function<Object, Object>>(getters.size());
    for (final var entry : getters.entrySet()) {
      final var name = entry.getKey();
      final var method = entry.getValue();
      final var declaringClass = method.getDeclaringClass();
      final var lookup = lookupByDeclaringClass.computeIfAbsent(declaringClass, dc ->
        privateLookupOrThrow(dc, cls, "getter")
      );
      try {
        final var handle = lookup.unreflect(method);
        invokers.put(
          name,
          ModuleAccess.spinner(lookup, method) == null
            ? MhAccessors.function(handle)
            : lmfGetter(handle, method, declaringClass, ModuleAccess.spinner(lookup, method))
        );
      } catch (final Throwable t) {
        throw new IllegalStateException("Failed to build getter invoker for " + cls.getName() + "." + name, t);
      }
    }
    return invokers;
  }

  /**
   * JVM hot path: {@link LambdaMetafactory} synthesizes a {@link Function} whose {@code
   * apply(Object)} calls the getter directly and boxes a primitive return. The
   * instantiatedMethodType pins the actual {@code (declaringClass) -> returnType} signature so the
   * metafactory generates the boxing bridge.
   */
  @SuppressWarnings("unchecked")
  private static Function<Object, Object> lmfGetter(
    final MethodHandle handle,
    final Method method,
    final Class<?> declaringClass,
    final MethodHandles.Lookup lookup
  ) throws Throwable {
    final var callSite = LambdaMetafactory.metafactory(
      lookup,
      "apply",
      MethodType.methodType(Function.class),
      MethodType.methodType(Object.class, Object.class),
      handle,
      MethodType.methodType(method.getReturnType(), declaringClass)
    );
    return (Function<Object, Object>) callSite.getTarget().invoke();
  }

  /**
   * Resolve a {@link MethodHandles.Lookup} with private access to {@code declaringClass}, or throw
   * an {@link IllegalStateException} naming the {@code opens} directive that grants it. When {@code
   * declaringClass} is inherited (i.e. differs from {@code ownerClass}), the message points to the
   * declaring class's package — that's the one that needs the {@code opens} directive, not the
   * inheritor.
   */
  private static MethodHandles.Lookup privateLookupOrThrow(
    final Class<?> declaringClass,
    final Class<?> ownerClass,
    final String accessorKind
  ) {
    try {
      return ModuleAccess.privateLookupIn(declaringClass);
    } catch (final IllegalAccessException e) {
      final var inheritedNote =
        declaringClass == ownerClass
          ? ""
          : " (declaring class of an inherited " + accessorKind + " on " + ownerClass.getName() + ")";
      throw new IllegalStateException(
        "Cannot access " +
          declaringClass.getName() +
          inheritedNote +
          " to build its " +
          accessorKind +
          " invokers. " +
          ModuleAccess.opensRemedy(declaringClass),
        e
      );
    }
  }

  /**
   * Box a primitive type to its wrapper; non-primitives pass through unchanged. Required for the
   * LambdaMetafactory {@code instantiatedMethodType}: a SAM parameter typed {@code Object} can't
   * directly match an instantiated primitive {@code int} (validator rejects "int is not a subtype
   * of class java.lang.Object"); the wrapper class is the bridge type, and the metafactory
   * generates the corresponding unbox.
   */
  private static Class<?> wrap(final Class<?> c) {
    if (!c.isPrimitive()) return c;
    if (c == int.class) return Integer.class;
    if (c == long.class) return Long.class;
    if (c == double.class) return Double.class;
    if (c == float.class) return Float.class;
    if (c == boolean.class) return Boolean.class;
    if (c == byte.class) return Byte.class;
    if (c == short.class) return Short.class;
    if (c == char.class) return Character.class;
    if (c == void.class) return Void.class;
    throw new IllegalStateException("Unknown primitive type: " + c);
  }

  private static Map<String, Method> scanGetters(final Class<?> cls) {
    final var map = new LinkedHashMap<String, Method>();
    for (final var m : cls.getMethods()) {
      if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) continue;
      // Static getter-shaped methods (getInstance(), getLogger()) are not bean properties — and
      // scanning one poisons the whole class: the eager LMF invoker build fails on the arity
      // mismatch and takes every property down with it. Bridge/synthetic methods are the erased
      // Object-typed duplicates of generic accessors; keeping them makes the winning method (and
      // therefore the property TYPE) dependent on getMethods() order, which is unspecified.
      if (Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.isSynthetic()) continue;
      // Skip inherited methods declared in platform modules (java.*, jdk.*). A class extending
      // ArrayList would otherwise surface `isEmpty()` → property `empty` and the LMF binder
      // would then fail `privateLookupIn(ArrayList.class)` because `java.base` doesn't grant
      // private lookup to application code. The primary gate lives in DeepMap (Collection/Map
      // subtypes don't recurse at all); this scan-time skip is defence-in-depth for any future
      // caller that touches a JDK-derived class directly.
      final var declaringModule = m.getDeclaringClass().getModule();
      if (declaringModule != null && declaringModule.isNamed()) {
        final var moduleName = declaringModule.getName();
        if (moduleName.startsWith("java.") || moduleName.startsWith("jdk.")) continue;
      }
      final var n = m.getName();
      final var afterGet = PropertyNames.afterGet(n);
      final var afterIs = PropertyNames.afterIs(n);
      final String prop;
      if (afterGet != null && m.getReturnType() != void.class) {
        prop = afterGet;
      } else if (afterIs != null && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
        prop = afterIs;
      } else {
        continue;
      }
      if (!"class".equals(prop) && !map.containsKey(prop)) {
        // No setAccessible(true) here — the stored Method is consulted only for metadata
        // (getGenericReturnType, getName, getDeclaringClass); actual invocation goes through the
        // LMF-built Function in GETTER_INVOKERS, which acquires access via privateLookupIn at
        // build time. A raw setAccessible(true) here would also throw InaccessibleObjectException
        // on JPMS-strict modules that `exports` but do not `opens` their packages — false-negative
        // for a path the LMF route succeeds on.
        map.put(prop, m);
      }
    }
    return map;
  }

  private static String capitalize(final String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  /**
   * The property name behind a getter method name: {@code getCity} &rarr; {@code city}, {@code
   * isActive} &rarr; {@code active}. A name without a recognized prefix is returned unchanged. A
   * {@code null} input returns {@code null} — belt-and-suspenders against callers that read a row's
   * source/target field name from a sealed {@code Mapping} variant whose nested-telescope
   * sub-shapes return {@code null} for that field by design (e.g. {@code FromTelescopeTo
   * .sourceField()} returns {@code null} because the source is a nested telescope rather than a
   * flat accessor). The structural fix lives at the {@code DeepMap.populateIso} call site that
   * peels those sub-shapes before normalising; this guard prevents the public {@code
   * Beans.normalize}/{@code propertyOf} surface from NPE-ing if any future caller forgets to peel.
   */
  public static String propertyOf(final String getterName) {
    return PropertyNames.property(getterName);
  }

  /**
   * A strategy for reconstructing a POJO from named values — the reverse (record &rarr; POJO)
   * direction of a bean bridge. Sealed over the three concrete writers; pick one via {@link
   * #settersWriter}, {@link #constructorWriter}, or {@link #builderWriter}.
   *
   * <p>{@code construct(names, valueByName)} contract: the {@code names} array is the source
   * record's component names in component order; for each name the writer pulls its value from
   * {@code valueByName} and feeds it to the chosen mechanism (setter, constructor argument, or
   * builder setter), returning the fully built {@code P}.
   */
  public sealed interface BeanWriter<P> permits ConstructorWriter, BuilderWriter, SettersWriter {
    /** Build a {@code P}, pulling each value in {@code names} from {@code valueByName}. */
    P construct(String[] names, Function<String, Object> valueByName);

    /**
     * Whether {@link #construct} puts the value for property {@code name} into the bean. A getter
     * with no setter, builder method or constructor parameter behind it is readable and not
     * writable: its value is dropped, or never asked for.
     */
    boolean writes(String name);
  }

  /**
   * Strategy: locate the single {@code arity}-parameter constructor and supply its arguments. When
   * the POJO was compiled with {@code -parameters}, arguments are matched by constructor parameter
   * <em>name</em> (order-independent); otherwise they fall back to positional ({@code names[i]} for
   * argument {@code i}). See {@link ConstructorWriter}.
   */
  public static <P> BeanWriter<P> constructorWriter(final Class<P> pojoClass, final int arity) {
    return new ConstructorWriter<>(pojoClass, arity);
  }

  /**
   * Strategy: call the static {@code builder()} factory, invoke a single-arg setter per name, then
   * {@code build()}. See {@link BuilderWriter}.
   */
  public static <P> BeanWriter<P> builderWriter(final Class<P> pojoClass) {
    return new BuilderWriter<>(pojoClass);
  }

  /**
   * Strategy: invoke the no-arg constructor, then call a public {@code setX(value)} setter per
   * name. Name-based and public-member only (no {@code setAccessible} on fields, so no {@code
   * opens} needed) — the natural rebuild strategy for classic JavaBeans.
   */
  public static <P> BeanWriter<P> settersWriter(final Class<P> pojoClass) {
    return new SettersWriter<>(pojoClass);
  }

  /** The bean's property names (getter-derived), in discovery order. */
  public static String[] propertyNames(final Class<?> beanClass) {
    return getters(beanClass).keySet().toArray(String[]::new);
  }

  /**
   * Raw, primitive-typed getter handles for {@code beanClass}, one per property in {@link
   * #propertyNames} order — the read-side input the MethodHandle-combinator assembly path ({@code
   * MhIso}) needs. {@code handles[i]} has type {@code (beanClass) -> propertyType[i]}, unboxed,
   * mirroring {@code Records.RecordInfo.accessorHandles}. The {@link #GETTER_INVOKERS} counterpart
   * is forced to {@code Function<Object, Object>} and boxes; these keep the getter's real return
   * type so a composed conversion stays box-free on same-type slots.
   */
  public static MethodHandle[] beanAccessorHandles(final Class<?> beanClass) {
    return BEAN_MH_INFO.get(beanClass).accessorHandles();
  }

  /**
   * Raw no-arg constructor handle {@code () -> beanClass} for the MethodHandle-combinator setter
   * fold. Present only when {@code beanClass} has a no-arg constructor — {@code MhIso.supports}
   * gates on {@link #isSetterConstructible} first, so callers never reach here for a bean without
   * one.
   */
  public static MethodHandle beanNoArgCtorHandle(final Class<?> beanClass) {
    return BEAN_MH_INFO.get(beanClass).noArgCtorHandle();
  }

  /**
   * Raw setter handle {@code (beanClass, propertyType) -> void} for the property named {@code
   * name}, or {@code null} when the property has no public single-arg {@code setX} setter. Used by
   * the MethodHandle-combinator setter fold on a bean-target conversion; the value keeps the
   * setter's real parameter type so a same-type slot writes unboxed.
   */
  public static MethodHandle beanSetterHandle(final Class<?> beanClass, final String name) {
    return BEAN_MH_INFO.get(beanClass).setterHandle(name);
  }

  /**
   * Whether {@code beanClass} can be constructed by the MethodHandle-combinator setter fold: it has
   * a no-arg constructor and a public single-arg {@code setX} setter for every property in {@code
   * requiredProperties}. Consulted by {@code MhIso.supports} as a build-time shape decision — a
   * bean that needs a builder or a constructor (no no-arg ctor, or a mapped property with no
   * setter) returns {@code false} and routes to the array leaf instead. No runtime fallback.
   */
  public static boolean isSetterConstructible(final Class<?> beanClass, final String[] requiredProperties) {
    if (!hasNoArgConstructor(beanClass)) return false;
    final var info = BEAN_MH_INFO.get(beanClass);
    for (final var name : requiredProperties) {
      if (info.setterHandle(name) == null) return false;
    }
    return true;
  }

  /**
   * Pick a <em>name-based</em> write strategy for {@code cls}, in the order {@link
   * BeanWriteStrategy#AUTO_ORDER} gives: a static {@code builder()}, then a single public all-args
   * constructor (compiled with {@code -parameters} so its arguments can be matched by name without
   * positional ambiguity), then a no-arg constructor with setters. The generated bridges and
   * navigators take the same order, so a bean offering several of these is built the same way on
   * both paths. The result is cached per class.
   *
   * <p>A builder is passed over when it has no member for a property the next available strategy
   * writes — every property when there is a name-matched constructor, else every property a public
   * setter writes — since it skips that property silently and would lose a value the other strategy
   * keeps; and when one of its members answers to a property by name but cannot take a value of the
   * property's type, since every write through it would then fail. {@link BeanWriteStrategy} holds
   * this rule for every path.
   *
   * <p>Used by both the record-less POJO APIs ({@code Telescope.ofBean}) and by the deep mapping
   * path when no explicit {@code writeBean} hint applies. Throws {@link IllegalStateException} if
   * none of the strategies applies. A class whose only write path is its private fields — a no-arg
   * constructor and nothing else — is refused, exactly as the {@code @Bridge} processor refuses it:
   * a private field is not part of the class's write contract. An immutable all-args-only POJO
   * compiled without {@code -parameters} is refused too; its escape is a {@code writeBean(target,
   * CONSTRUCTOR)} hint at the {@code Telescope.map(...)} call site.
   */
  @SuppressWarnings("unchecked")
  public static <P> BeanWriter<P> autoWriter(final Class<P> cls) {
    return (BeanWriter<P>) AUTO_WRITER_CACHE.get(cls);
  }

  /**
   * The writer {@link #autoWriter} picks for {@code cls}, or {@code null} where it refuses the
   * class, so the refusal is raised by the construction that asks for the writer rather than here.
   */
  public static BeanWriter<?> autoWriterOrNull(final Class<?> cls) {
    try {
      return autoWriter(cls);
    } catch (final IllegalStateException e) {
      return null;
    }
  }

  /** An unreflect call, which declares the access failure its lookup has already ruled out. */
  private interface Unreflect {
    MethodHandle get() throws IllegalAccessException;
  }

  /**
   * The handle {@code unreflect} produces for a composed build. Every caller passes a lookup from
   * {@link #privateLookupOrThrow}, which has already refused a member it cannot reach with its own
   * diagnostic, so the declared {@link IllegalAccessException} has nothing left to report.
   */
  private static MethodHandle unreflected(final Unreflect unreflect) {
    try {
      return unreflect.get();
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Throws what {@link ConstructorWriter} throws when its constructor fails: the failure wrapped
   * with the class it was constructing. A composed build calls it from its exception handler so the
   * two report a failing constructor the same way.
   */
  static Object constructorFailed(final Class<?> cls, final Throwable failure) {
    throw new RuntimeException("Failed to construct " + cls.getName() + " via its constructor", failure);
  }

  private static <P> BeanWriter<P> computeAutoWriter(final Class<P> cls) {
    final var props = propertyNames(cls);
    final var sole = solePublicConstructor(cls, props.length);
    // Refuse to silently use positional fallback: getter-iteration order is not guaranteed to match
    // constructor parameter order. Requiring -parameters yields named matching and eliminates the
    // silent-data-shuffle hazard the explicit writeBean(...) hint accepts. Also require every ctor
    // parameter name to appear in the getter-derived property set — otherwise `getURL()` → property
    // `"URL"` mismatched against a ctor parameter named `"url"` would silently pass null into the
    // constructor under the lookup `valueByName("url")`.
    final var nameMatchedConstructor = sole != null && allParameterNamesMatchProperties(sole, props);
    final var builderType = builderTypeOf(cls);
    final var chosen = BeanWriteStrategy.auto(
      new BeanWriteStrategy.Shape() {
        @Override
        public List<String> properties() {
          return List.of(props);
        }

        @Override
        public boolean hasBuilder() {
          return builderType != null;
        }

        @Override
        public BeanWriteStrategy.Member builderMember(final String property) {
          final var member = builderSetterFor(builderType, property);
          if (member == null) return BeanWriteStrategy.Member.ABSENT;
          return builderMemberAccepts(member, cls, property)
            ? BeanWriteStrategy.Member.ACCEPTS
            : BeanWriteStrategy.Member.REJECTS;
        }

        @Override
        public boolean hasConstructor() {
          return nameMatchedConstructor;
        }

        @Override
        public boolean hasSetters() {
          return hasNoArgConstructor(cls) && hasAnySetter(cls);
        }

        @Override
        public boolean setterWrites(final String property) {
          return isSetterConstructible(cls, new String[] { property });
        }

        @Override
        public boolean stored(final String property) {
          return declaresInstanceField(cls, property);
        }
      }
    );
    if (chosen.isPresent()) {
      return switch (chosen.get()) {
        case BUILDER -> builderWriter(cls);
        case CONSTRUCTOR -> constructorWriter(cls, props.length);
        case SETTERS -> settersWriter(cls);
      };
    }
    // A usable builder that was passed over with nothing else to fall to: it cannot carry some
    // property, and building through it anyway would drop that property's value without a word.
    if (builderType != null) throw new IllegalStateException(builderCannotCarryMessage(cls, builderType, props));
    // A class that declares a constructor taking arguments has a constructor write path, so it gets
    // the constructor advice below, which names -parameters and the CONSTRUCTOR hint.
    if (hasNoArgConstructor(cls) && !declaresConstructorWithParameters(cls)) {
      throw new IllegalStateException(onlyPrivateFieldsMessage(cls));
    }
    throw new IllegalStateException(
      "No name-based write strategy for " +
        cls.getName() +
        " — needs a static builder(), a no-arg constructor with setters, or exactly one public" +
        " constructor whose arity matches the" +
        " property count (" +
        props.length +
        "), was compiled with -parameters, and whose parameter names line up with the" +
        " getter-derived properties. Primary fix: recompile the target class with javac" +
        " -parameters so its constructor arguments can be matched by name. The" +
        " writeBean(targetClass, CONSTRUCTOR) hint at the Telescope.map(...) call site is an" +
        " escape hatch, but note it falls back to POSITIONAL argument matching when" +
        " -parameters is absent — argument order is then the getter-discovery order (not a" +
        " stable, user-defined canonical order), so the hint should still be paired with" +
        " -parameters to be safe."
    );
  }

  /** The JLS default of a primitive type, boxed: {@code 0}, {@code false} or {@code '\0'}. */
  private static Object jlsDefault(final Class<?> primitive) {
    return Array.get(Array.newInstance(primitive, 1), 0);
  }

  /** Per parameter, what a null argument becomes: the JLS default for a primitive, else null. */
  private static Object[] nullArguments(final Class<?>[] parameterTypes) {
    final var out = new Object[parameterTypes.length];
    for (var i = 0; i < parameterTypes.length; i++) {
      if (parameterTypes[i].isPrimitive()) out[i] = jlsDefault(parameterTypes[i]);
    }
    return out;
  }

  /**
   * The refusal for a class whose only strategy is a builder that cannot carry every property: one
   * it has no member for, or one whose member cannot take the property's value.
   */
  private static String builderCannotCarryMessage(
    final Class<?> cls,
    final Class<?> builderType,
    final String[] props
  ) {
    final var missing = new ArrayList<String>();
    for (final var property : props) {
      final var member = builderSetterFor(builderType, property);
      final var lost =
        member == null ? declaresInstanceField(cls, property) : !builderMemberAccepts(member, cls, property);
      if (lost) missing.add(property);
    }
    return (
      "Cannot write " +
      cls.getName() +
      ": its builder " +
      builderType.getName() +
      " has no member that takes " +
      missing +
      ", and the class offers no constructor or setters to write them instead. Building through" +
      " the builder would drop those values. Add a builder member for each, a public all-args" +
      " constructor (compiled with -parameters), or setters; or supply an explicit Mapping row" +
      " that produces " +
      cls.getSimpleName() +
      "."
    );
  }

  /**
   * Whether {@code cls} or a superclass declares a non-final instance field named {@code name}. A
   * final field may be given its value where it is declared, which no write strategy can replace
   * and reflection cannot see, so none is counted.
   */
  private static boolean declaresInstanceField(final Class<?> cls, final String name) {
    for (var c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
      for (final var field : c.getDeclaredFields()) {
        if (!field.getName().equals(name) || Modifier.isStatic(field.getModifiers())) continue;
        return !Modifier.isFinal(field.getModifiers());
      }
    }
    return false;
  }

  private static boolean declaresConstructorWithParameters(final Class<?> cls) {
    for (final var c : cls.getDeclaredConstructors()) if (c.getParameterCount() > 0) return true;
    return false;
  }

  /**
   * The refusal for a class whose only write path is its private fields: a no-arg constructor and
   * no other constructor, no public setter, and no static {@code builder()}.
   */
  private static String onlyPrivateFieldsMessage(final Class<?> cls) {
    return (
      "Cannot write " +
      cls.getName() +
      ": it has a no-arg constructor but no public setters, no static builder(), and no all-args" +
      " constructor, and telescope does not write private fields. Add public setters, an" +
      " all-args constructor (compiled with -parameters), or a static builder() to " +
      cls.getSimpleName() +
      ", or supply an explicit Mapping row that produces it."
    );
  }

  /**
   * Probe for the unique public constructor of {@code arity}. Two passes so the probe matches what
   * {@link ConstructorWriter} actually does: that writer scans {@code getDeclaredConstructors()}
   * (any access) and throws on multiple matches. So a class with one public + one non-public ctor
   * of the requested arity would survive a public-only check but blow up later when {@code
   * ConstructorWriter} sees both. We refuse here in both cases — non-public same-arity sibling or
   * multiple publics — so the auto path stays consistent with its delegate.
   */
  private static <P> Constructor<P> solePublicConstructor(final Class<P> cls, final int arity) {
    var declaredCount = 0;
    for (final var c : cls.getDeclaredConstructors()) if (c.getParameterCount() == arity) declaredCount++;
    if (declaredCount != 1) return null; // ambiguous declared-set, or zero matches
    Constructor<P> found = null;
    for (final var c : cls.getConstructors()) {
      if (c.getParameterCount() != arity) continue;
      @SuppressWarnings("unchecked")
      final var cast = (Constructor<P>) c;
      found = cast;
    }
    return found; // non-null only if the unique declared ctor of that arity is public
  }

  private static boolean allParameterNamesMatchProperties(final Constructor<?> ctor, final String[] propertyNames) {
    final var props = new HashSet<>(Arrays.asList(propertyNames));
    for (final var p : ctor.getParameters()) {
      if (!p.isNamePresent()) return false;
      if (!props.contains(p.getName())) return false;
    }
    return true;
  }

  /**
   * A {@link Lens} over a single bean property: {@code get} reads via the getter; {@code
   * set}/{@code modify} rebuild the POJO with that one property replaced (all other getter-exposed
   * properties carried over) via {@code writer}. Immutable — it never mutates {@code source}.
   * Powers {@code Telescope.ofBean(...).field(...)}.
   *
   * <p>The {@code (pojoClass, property)} pair is constant at construction, so the LMF-built {@link
   * Function Function&lt;Object, Object&gt;} reader is resolved <em>once</em> via {@link
   * #GETTER_INVOKERS} and captured by the returned {@code Lens}. Per-call {@code get(source)}
   * dispatches directly through the captured reader — no per-call ClassValue probe, no per-call
   * HashMap lookup. The lens fails fast at build time (rather than at first read) if {@code
   * property} has no getter on {@code pojoClass}.
   *
   * <p>Subclass / proxy polymorphism is preserved through a single-instanceof fast-path: when
   * {@code source.getClass() == pojoClass} the captured reader runs directly; otherwise the call
   * falls through to {@link #readProperty(Object, String)}, which handles both Hibernate-proxy
   * unwrap (via {@link #persistentClassOf}) and ordinary subclass instances by looking up the
   * reader for the runtime class. The common monomorphic case (the lens is applied to instances of
   * exactly {@code pojoClass}) avoids both the ClassValue probe and the HashMap lookup the slow
   * path pays. The reads-via-getter terminal in {@code set}/{@code modify} routes through the same
   * fast-path: each off-path carry-over read picks the cached reader straight from the captured
   * map.
   *
   * <pre>{@code
   * final Lens<UserPojo, String> email =
   *     Beans.lens(UserPojo.class, "email", Beans.autoWriter(UserPojo.class));
   * final var updated = email.modify(user, String::toLowerCase); // new UserPojo
   * }</pre>
   */
  public static <P, A> Lens<P, A> lens(final Class<P> pojoClass, final String property, final BeanWriter<P> writer) {
    return beanLens(pojoClass, property, Objects.requireNonNull(writer, "writer"));
  }

  /**
   * The same {@link Lens} as {@link #lens(Class, String, BeanWriter)}, with the writer left to
   * {@link #autoWriter} and resolved on the first {@code set} / {@code modify}, then kept. A read
   * never resolves it, so a class {@link #autoWriter} refuses — one only its private fields could
   * write — can still be read and navigated through, and only a write raises the refusal.
   */
  public static <P, A> Lens<P, A> lens(final Class<P> pojoClass, final String property) {
    return beanLens(pojoClass, property, null);
  }

  // A null `writer` means autoWriter(pojoClass), resolved on the first write.
  private static <P, A> Lens<P, A> beanLens(
    final Class<P> pojoClass,
    final String property,
    final BeanWriter<P> writer
  ) {
    final var names = propertyNames(pojoClass);
    // Capture the full reader map for pojoClass once at construction. The per-property reader is
    // pulled from the same map for both the focused `get` and the off-path carry-over reads in
    // `set`/`modify`, so all dispatch on the fast path is a direct map.apply call — no per-call
    // ClassValue probe, no per-call HashMap lookup against the JDK's per-Class table.
    final var capturedReaders = GETTER_INVOKERS.get(pojoClass);
    final var capturedReader = capturedReaders.get(property);
    if (capturedReader == null) throw new IllegalArgumentException(
      "No getter for property '" + property + "' on " + pojoClass.getName()
    );
    return new Lens<>() {
      @Override
      @SuppressWarnings("unchecked")
      public A get(final P source) {
        // Fast path: the lens is applied to an instance of exactly pojoClass — dispatch directly
        // through the captured reader. Covers the common monomorphic case (the typical
        // Telescope.ofBean(X.class).field(X::getY) usage) and skips the ClassValue + HashMap probe
        // readProperty would have done.
        if (source != null && source.getClass() == pojoClass) return (A) capturedReader.apply(source);
        // Slow path: subclass instance, or a HibernateProxy whose persistent class differs from
        // the runtime class. readProperty handles both correctly via persistentClassOf + the
        // per-class GETTER_INVOKERS cache, so a Lens<User, X> applied to a SuperUser instance still
        // reads the right value (the SuperUser entry is computed once and cached on first miss).
        return (A) readProperty(source, property);
      }

      // Every BeanWriter keeps its state in final fields, so a racing first write that sees this
      // field unset only resolves the same cached autoWriter entry again.
      private BeanWriter<P> resolved = writer;

      @Override
      public P set(final P source, final A value) {
        var w = resolved;
        if (w == null) {
          w = autoWriter(pojoClass);
          resolved = w;
        }
        return w.construct(names, n -> n.equals(property) ? value : readForRebuild(source, n));
      }

      // modify inherits the Lens default — the writer rebuilds a fresh pojo on null source
      // (readForRebuild + readProperty both short-circuit to null), so the null-tolerant default
      // applies cleanly here without a custom override.

      // Mirrors the get() fast-path for the off-path values the writer needs to copy over.
      // Off-path reads are called once per non-focused property per set/modify, so they benefit
      // from the same capture as the focused read.
      private Object readForRebuild(final P source, final String name) {
        if (source != null && source.getClass() == pojoClass) {
          final var reader = capturedReaders.get(name);
          if (reader == null) throw new IllegalArgumentException(
            "No getter for property '" + name + "' on " + pojoClass.getName()
          );
          return reader.apply(source);
        }
        return readProperty(source, name);
      }
    };
  }

  /**
   * Class-deferred bean lens by property name — the bean-side mirror of {@code
   * Records.fieldLens(String)}. The lens captures only the property name; both the getter and the
   * writer are resolved against the SOURCE's runtime class on each call. Used by {@code
   * Telescope.fieldByName(String)} on a bean Telescope, where the actual POJO class isn't known
   * until call time (the path may have been constructed against a supertype).
   *
   * <p>{@code get} delegates to {@link #readProperty(Object, String)} which already short-circuits
   * on a null source. {@code set} / {@code modify} resolve {@code autoWriter(source.getClass())} at
   * call time and rebuild the source's class with the focused property replaced. Subtype instances
   * are written back as their concrete runtime class, matching the lens-law expectation that {@code
   * set(s, get(s)).equals(s)} round-trips.
   *
   * <p><b>Primitive properties and {@code set(s, null)}:</b> when the focused property is a Java
   * primitive (e.g. {@code int count}), {@code set(s, null)} substitutes the JLS default ({@code 0}
   * / {@code false} / etc.) rather than throwing, whichever writer {@link #autoWriter} picks:
   * {@link SettersWriter} skips a null for a primitive setter, leaving the field at its default,
   * and {@link ConstructorWriter} and {@link BuilderWriter} pass the default to a primitive
   * parameter in place of null. This means the lens-law {@code set(s, null).get == null} does not
   * hold for primitive properties (you get the JLS default back). The same substitution applies to
   * {@code modify(s, f)} when {@code f} returns {@code null}. Use a boxed wrapper type on the
   * property if {@code null} round-trip matters.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public static <P, A> Lens<P, A> fieldLens(final String property) {
    return new Lens<>() {
      @Override
      public A get(final P source) {
        return (A) readProperty(source, property);
      }

      @Override
      public P set(final P source, final A value) {
        if (source == null) return null;
        final Class cls = source.getClass();
        final var names = propertyNames(cls);
        final var writer = (BeanWriter<P>) autoWriter(cls);
        return writer.construct(names, n -> n.equals(property) ? value : readProperty(source, n));
      }

      @Override
      public P modify(final P source, final Function<? super A, ? extends A> f) {
        if (source == null) return null;
        return set(source, f.apply(get(source)));
      }
    };
  }

  private static boolean hasNoArgConstructor(final Class<?> cls) {
    try {
      cls.getDeclaredConstructor();
      return true;
    } catch (final NoSuchMethodException e) {
      return false;
    }
  }

  /**
   * The type a usable static {@code builder()} returns, or {@code null}. Usable means it also has a
   * {@code build()}: a class carrying an unrelated method named {@code builder} answers the name
   * test and then fails when the writer tries to finish with it — and it fails while the path is
   * being built, so even a read throws.
   */
  private static Class<?> builderTypeOf(final Class<?> cls) {
    try {
      final var factory = cls.getMethod("builder");
      if (!Modifier.isStatic(factory.getModifiers())) return null;
      final var builderType = factory.getReturnType();
      final var build = builderType.getMethod("build");
      // Existing is not enough. A static build() binds to nothing the writer can call, and one
      // returning an unrelated type hands back an object of the wrong class without complaint --
      // and because the writer is chosen while the path is built, the first costs even a read that
      // was never going to write anything.
      if (Modifier.isStatic(build.getModifiers())) return null;
      // Unrelated, not merely different. What build() is declared to return says nothing about
      // what it makes: a generic builder's erases to Object, and one on an abstract base names
      // that base while returning something concrete. Demanding the bean's own type refuses both
      // -- and this probe also decides the rung for a bean with no setters at all, so refusing
      // there takes a builder that was working away from it.
      final var returned = build.getReturnType();
      if (!cls.isAssignableFrom(returned) && !returned.isAssignableFrom(cls)) return null;
      return builderType;
    } catch (final NoSuchMethodException e) {
      return null;
    }
  }

  /**
   * Whether the builder member found for {@code property} can actually hold what it reads as.
   *
   * <p>Matching a member by name and arity says a call site can be written, not that the value can
   * make the trip. A builder taking {@code String...} answers to a {@code List} property by name
   * and arity and then rejects the list; so does an overload picked by a first match over a method
   * order the platform does not specify. Deciding to move a bean on that answer turns writes that
   * were landing into cast failures, which is worse than the loss the move is for.
   *
   * <p>Assignability answers the varargs case without naming it — the parameter is an array type,
   * which no other declaration is assignable from — so a property that genuinely is an array still
   * reaches a varargs member that can take it.
   *
   * <p>The getter's return type as a member of {@code cls}, erased, is what is asked about, being
   * the most that can be known before a value exists. A getter inherited from a generic superclass
   * erases to the type variable's bound, while {@code class Node extends Base<Node>} fixes the
   * property to {@code Node}, which is what a builder for {@code Node} takes.
   */
  private static boolean builderMemberAccepts(final Method member, final Class<?> cls, final String property) {
    final var parameter = member.getParameterTypes()[0];
    final var declared = getters(cls).get(property);
    if (declared == null) return false;
    final var propertyType = memberReturnClass(declared, cls);
    if (parameter.isPrimitive() || propertyType.isPrimitive()) return parameter.equals(propertyType);
    return parameter.isAssignableFrom(propertyType);
  }

  // The erasure of `getter`'s return type as a member of `cls`, or its declared erasure when the
  // member type leaves a type variable unresolved.
  private static Class<?> memberReturnClass(final Method getter, final Class<?> cls) {
    final var member = PROPS.memberOf(getter.getGenericReturnType(), getter.getDeclaringClass(), cls);
    if (member instanceof Class<?> c) return c;
    if (member instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) return c;
    return getter.getReturnType();
  }

  /**
   * The single-argument builder method that carries {@code name}, by the rule BuilderWriter binds.
   */
  private static Method builderSetterFor(final Class<?> builderType, final String name) {
    final var set = "set" + capitalize(name);
    final var with = "with" + capitalize(name);
    for (final var m : builderType.getMethods()) {
      if (m.getParameterCount() != 1) continue;
      // Every builder inherits Object's methods, and a property named `wait` or `equals` finds one
      // of the two that take a single argument. Binding it fails on a module that does not open
      // java.lang, so the match has to exclude what every type has rather than what this builder
      // declares -- which skips nothing of the builder's own, since `wait(long)` is final and an
      // overload or an override is declared on the builder itself.
      if (m.getDeclaringClass() == Object.class) continue;
      final var mn = m.getName();
      if (mn.equals(name) || mn.equals(set) || mn.equals(with)) return m;
    }
    return null;
  }

  private static boolean hasAnySetter(final Class<?> cls) {
    for (final var m : cls.getMethods()) {
      // The shared setter-shape rule: setCity qualifies, setup/settle do not. Without it, an
      // unrelated set*-named method flipped the write strategy to SETTERS, whose per-property
      // no-ops then silently lost every field of a getter-only bean.
      if (
        m.getParameterCount() == 1 &&
        !Modifier.isStatic(m.getModifiers()) &&
        PropertyNames.afterSet(m.getName()) != null
      ) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@link BeanWriter} backed by an all-args constructor routed through a cached spread {@link
   * MethodHandle}. At construction it finds the unique declared constructor with the requested
   * arity (throwing if there are zero or more than one), makes it accessible, and binds a {@code
   * Function<Object, Object>} invoker that wraps the {@link MethodHandle#asSpreader(Class, int)
   * asSpreader}-wrapped handle: the raw constructor handle has type {@code (T1, T2, …, Tn) → P} and
   * the spreader converts it to {@code (Object[]) → P}. Primitive args auto-unbox per the same
   * implicit conversions a direct constructor call would apply.
   *
   * <p>The invoker is intentionally <em>not</em> built via {@link LambdaMetafactory} — the spread
   * adapter is a non-direct {@link MethodHandle}, which LMF rejects with {@code "MethodHandle
   * (Object[])P is not direct or cannot be cracked"}. The cached MethodHandle path is the standard
   * JDK alternative — it still skips the per-call access check and the varargs allocation that
   * {@link Constructor#newInstance} pays, and {@code invokeExact} through a {@code final} field
   * inlines under the JIT. The hot path never reaches {@code Constructor.newInstance}.
   *
   * <p>If the POJO was compiled with {@code -parameters}, {@code construct} matches each argument
   * to its source value by the constructor parameter's <em>name</em> — so a reordered constructor
   * is safe; otherwise it falls back to positional, assembling arguments in {@code names} order and
   * relying on the constructor parameters lining up with the components.
   */
  static final class ConstructorWriter<P> implements BeanWriter<P> {

    private final Class<P> beanClass;
    private final Constructor<P> constructor;
    private final Function<Object, Object> ctorFn;
    // Constructor parameter names when the POJO was compiled with -parameters (enables
    // order-independent name matching); null when names are synthetic, so we fall back to
    // positional.
    private final String[] paramNames;
    // What each argument takes in place of null: the JLS default for a primitive parameter, which
    // cannot hold null, and null itself for a reference one.
    private final Object[] nullArguments;

    @SuppressWarnings("unchecked")
    ConstructorWriter(final Class<P> cls, final int arity) {
      Constructor<P> found = null;
      for (final var c : cls.getDeclaredConstructors()) {
        if (c.getParameterCount() != arity) continue;
        if (found != null) throw new IllegalStateException(
          "writeBean(" +
            cls.getName() +
            ", CONSTRUCTOR): more than one " +
            arity +
            "-parameter constructor declared; cannot disambiguate."
        );
        found = (Constructor<P>) c;
      }
      if (found == null) throw new IllegalStateException(
        "writeBean(" +
          cls.getName() +
          ", CONSTRUCTOR) requires a constructor with " +
          arity +
          " parameters (parameters are matched by name when compiled with -parameters," +
          " otherwise positionally)."
      );
      // No raw setAccessible — buildCtorFn acquires private access through privateLookupOrThrow,
      // which routes JPMS failures through the opens-pointing message every writer shares instead
      // of a low-context InaccessibleObjectException.
      this.beanClass = cls;
      this.constructor = found;
      this.paramNames = resolveParamNames(found);
      this.nullArguments = nullArguments(found.getParameterTypes());
      this.ctorFn = buildCtorFn(cls, found, arity);
    }

    /**
     * The names {@link #construct} reads its arguments by, in parameter order: the parameter names
     * when the class kept them, else {@code names} positionally.
     */
    String[] argumentNames(final String[] names) {
      return paramNames != null ? paramNames : names;
    }

    /** What argument {@code index} takes in place of null. */
    Object nullArgument(final int index) {
      return nullArguments[index];
    }

    /**
     * The constructor as a handle {@code (T1, ..., Tn) -> P} for a composed build, with a failure
     * wrapped exactly as {@link #construct} wraps it.
     */
    MethodHandle composableConstructor() {
      final var lookup = privateLookupOrThrow(beanClass, beanClass, "CONSTRUCTOR strategy");
      final var raw = unreflected(() -> lookup.unreflectConstructor(constructor));
      final var failed = MethodHandles.insertArguments(CONSTRUCTOR_FAILED, 0, beanClass).asType(
        MethodType.methodType(beanClass, Throwable.class)
      );
      final var handler = MethodHandles.dropArguments(failed, 1, raw.type().parameterList());
      return MethodHandles.catchException(raw, Throwable.class, handler);
    }

    private static String[] resolveParamNames(final Constructor<?> ctor) {
      final var params = ctor.getParameters();
      final var names = new String[params.length];
      for (var i = 0; i < params.length; i++) {
        if (!params[i].isNamePresent()) return null;
        names[i] = params[i].getName();
      }
      return names;
    }

    private static <P> Function<Object, Object> buildCtorFn(
      final Class<P> cls,
      final Constructor<P> ctor,
      final int arity
    ) {
      // Constructors are not inherited in Java — `cls.getDeclaredConstructors()` returns only the
      // ones declared directly on `cls`, so `ctor.getDeclaringClass() == cls` always. No
      // inherited-accessor concern here (unlike methods); pin the lookup straight to `cls`.
      final var lookup = privateLookupOrThrow(cls, cls, "CONSTRUCTOR strategy");
      final MethodHandle spread;
      try {
        spread = lookup
          .unreflectConstructor(ctor)
          .asSpreader(Object[].class, arity)
          .asType(MethodType.methodType(Object.class, Object[].class));
      } catch (final IllegalAccessException e) {
        throw new RuntimeException("Failed to construct " + cls.getName() + " via its constructor", e);
      }
      return args -> {
        try {
          return (Object) spread.invokeExact((Object[]) args);
        } catch (final Throwable t) {
          throw new RuntimeException("Failed to construct " + cls.getName() + " via its constructor", t);
        }
      };
    }

    @Override
    @SuppressWarnings("unchecked")
    public P construct(final String[] names, final Function<String, Object> valueByName) {
      // Prefer matching by parameter name (order-independent) when names are present; otherwise
      // fall
      // back to positional, assuming the constructor's parameter order matches the components'
      // order.
      final var keys = paramNames != null ? paramNames : names;
      final var args = new Object[keys.length];
      for (var i = 0; i < keys.length; i++) {
        final var value = valueByName.apply(keys[i]);
        // A primitive parameter takes its JLS default for null, the value a setter write leaves
        // the field at when SettersWriter skips that null.
        args[i] = value == null ? nullArguments[i] : value;
      }
      return (P) ctorFn.apply(args);
    }

    /**
     * A property is written when the constructor takes a parameter of that name; without parameter
     * names every property is passed positionally. The automatic writer picks a constructor only
     * when its arity equals the property count and every parameter name is a property, so on that
     * path every property is a parameter and this answers true for each.
     */
    @Override
    public boolean writes(final String name) {
      return paramNames == null || Arrays.asList(paramNames).contains(name);
    }
  }

  /**
   * {@link BeanWriter} backed by the builder pattern. At construction it requires a static {@code
   * builder()} factory and a {@code build()} method on the returned builder type. {@code construct}
   * calls {@code builder()}, then for each name invokes a single-arg setter — matched by exact
   * name, {@code setX}, or {@code withX} (lazily resolved and cached per name) — and finally {@code
   * build()}.
   *
   * <p>Hot-path dispatch is fully de-reflected via {@link LambdaMetafactory}:
   *
   * <ul>
   *   <li>the static {@code builder()} factory is captured once as a {@link Supplier
   *       Supplier&lt;Object&gt;} at construction time;
   *   <li>each setter is captured lazily on first use and cached per name. Two shapes are
   *       supported: fluent setters (return the builder type) are bound as a {@link BiFunction
   *       BiFunction&lt;Object, Object, Object&gt;} — LMF refuses non-direct handles, so a {@code
   *       BiConsumer} via {@code asType}-discard isn't viable; the returned builder is simply
   *       discarded at the call site. Void-returning setters (classic JavaBean style) are bound
   *       directly as a {@link BiConsumer BiConsumer&lt;Object, Object&gt;}, whose SAM return is
   *       {@code void} — a direct MethodHandle match.
   *   <li>{@code build()} is captured once as a {@link Function Function&lt;Object, Object&gt;} at
   *       construction time.
   * </ul>
   *
   * <p>After the first call per setter, dispatch is a single virtual call the JIT inlines — no
   * {@link java.lang.reflect.Method#invoke}, no per-call argument array, no access-check.
   *
   * <p>Building the synthetic SAMs requires a private lookup on both the target class and the
   * builder type via {@link MethodHandles#privateLookupIn}. For fully-public POJOs in the same
   * module this is equivalent to a plain lookup; for closed-package targets under the module path,
   * the POJO's module needs an {@code opens} directive.
   */
  static final class BuilderWriter<P> implements BeanWriter<P> {

    private final Class<P> beanType;
    private final Class<?> builderType;
    private final Method factoryMethod;
    private final Method buildMethod;
    private final Supplier<Object> builderSupplier;
    private final Function<Object, Object> buildFn;
    // Each value is one of: BiConsumer<Object, Object> for a void-returning setter, or
    // BiFunction<Object, Object, Object> for a fluent setter. Per-call dispatch checks the type
    // once; after a few calls the JIT specializes the call site against the observed shape.
    private final Map<String, Object> setterInvokers = new ConcurrentHashMap<>();

    BuilderWriter(final Class<P> cls) {
      Method factory = null;
      try {
        final var candidate = cls.getMethod("builder");
        if (Modifier.isStatic(candidate.getModifiers())) factory = candidate;
      } catch (final NoSuchMethodException ignored) {
        // fall through to the error below
      }
      if (factory == null) throw new IllegalStateException(
        "writeBean(" + cls.getName() + ", BUILDER) requires a static builder() method"
      );
      this.beanType = cls;
      this.builderType = factory.getReturnType();
      final Method buildMethod;
      try {
        buildMethod = builderType.getMethod("build");
      } catch (final NoSuchMethodException e) {
        throw new IllegalStateException(
          "writeBean(" + cls.getName() + ", BUILDER): builder " + builderType.getName() + " has no build() method",
          e
        );
      }
      this.factoryMethod = factory;
      this.buildMethod = buildMethod;
      this.builderSupplier = buildBuilderSupplier(cls, factory);
      this.buildFn = buildBuildFn(builderType, buildMethod);
    }

    /** The static {@code builder()} as a handle {@code () -> Builder} for a composed build. */
    MethodHandle composableFactory() {
      final var lookup = privateLookupOrThrow(factoryMethod.getDeclaringClass(), beanType, "builder factory");
      return unreflected(() -> lookup.unreflect(factoryMethod)).asType(MethodType.methodType(builderType));
    }

    /**
     * The builder member for {@code name} as a handle {@code (Builder, T) -> void}, its return
     * discarded as {@link #construct} discards it, or {@code null} when the builder has none.
     */
    MethodHandle composableMember(final String name) {
      final var member = builderSetterFor(builderType, name);
      if (member == null) return null;
      final var lookup = privateLookupOrThrow(member.getDeclaringClass(), builderType, "builder setter");
      return unreflected(() -> lookup.unreflect(member)).asType(
        MethodType.methodType(void.class, builderType, member.getParameterTypes()[0])
      );
    }

    /** What the member for {@code name} takes in place of null: its JLS default when primitive. */
    Object nullArgument(final String name) {
      final var member = builderSetterFor(builderType, name);
      if (member == null || !member.getParameterTypes()[0].isPrimitive()) return null;
      return jlsDefault(member.getParameterTypes()[0]);
    }

    /**
     * {@code build()} followed by the check {@link #construct} makes on what it returns, as a
     * handle {@code (Builder) -> P} for a composed build.
     */
    MethodHandle composableBuild() {
      final var lookup = privateLookupOrThrow(buildMethod.getDeclaringClass(), builderType, "builder build()");
      final var build = unreflected(() -> lookup.unreflect(buildMethod)).asType(
        MethodType.methodType(Object.class, builderType)
      );
      final var verify = VERIFY_BUILT.bindTo(this);
      return MethodHandles.filterReturnValue(build, verify).asType(MethodType.methodType(beanType, builderType));
    }

    /** The builder type {@code builder()} returns. */
    Class<?> builderType() {
      return builderType;
    }

    @Override
    @SuppressWarnings("unchecked")
    public P construct(final String[] names, final Function<String, Object> valueByName) {
      // Dispatch-time exceptions (from builder() / setter / build() execution, or from auto-unbox
      // bridges in LMF setter dispatch) propagate raw, matching the other writer strategies
      // (SETTERS / CONSTRUCTOR); no `ReflectiveOperationException` arises on the LMF hot path.
      // Build-time LMF failures are wrapped at construction time in the respective build*Fn
      // methods with the class-context message; runtime is consistent with the rest of the
      // writer family.
      final var builder = builderSupplier.get();
      for (final var name : names) {
        final var inv = setterFor(name);
        final var value = valueByName.apply(name);
        // Fluent setters return the builder (typically `this`); discard the return — the previous
        // Method#invoke path did the same. Void-returning setters dispatch through BiConsumer; the
        // builder pattern works either way (build() doesn't depend on setter return type).
        if (inv instanceof BiConsumer<?, ?>) {
          ((BiConsumer<Object, Object>) inv).accept(builder, value);
        } else if (inv instanceof BiFunction<?, ?, ?>) {
          ((BiFunction<Object, Object, Object>) inv).apply(builder, value);
        } else {
          // buildSetterInvoker today returns one of {BiConsumer no-op, BiConsumer void setter,
          // BiFunction fluent setter}. A future SAM shape would silently CCE on the BiFunction
          // cast above — surface the contract violation explicitly so the regression is loud.
          throw new IllegalStateException(
            "BuilderWriter: unknown setter invoker SAM shape " + inv.getClass().getName() + " for '" + name + "'"
          );
        }
      }
      return (P) verifyBuilt(buildFn.apply(builder));
    }

    /**
     * {@code built}, once it is known to be an instance of the bean type.
     *
     * <p>What build() makes is not decided by what it declares: a build() typed as an ancestor
     * satisfies every sibling under that ancestor, and one erasing to Object satisfies anything at
     * all. Both shapes are admitted deliberately, so the object itself is the only place the
     * question can be asked. isInstance accepts a subtype, which is the legitimate case.
     */
    private Object verifyBuilt(final Object built) {
      if (!beanType.isInstance(built)) throw new IllegalStateException(
        "writeBean(" +
          beanType.getName() +
          ", BUILDER): builder " +
          builderType.getName() +
          " build() returned " +
          (built == null ? "null" : built.getClass().getName()) +
          ", which is not a " +
          beanType.getName()
      );
      return built;
    }

    private Object setterFor(final String name) {
      return setterInvokers.computeIfAbsent(name, this::buildNullSafeSetterInvoker);
    }

    /**
     * The invoker for {@code name}, passing a primitive member its JLS default in place of null. A
     * primitive parameter cannot hold null, and the default is the value a setter write leaves the
     * field at when {@code SettersWriter} skips that null, so the three writers agree on it.
     */
    @SuppressWarnings("unchecked")
    private Object buildNullSafeSetterInvoker(final String name) {
      final var invoker = buildSetterInvoker(name);
      final var member = builderSetterFor(builderType, name);
      if (member == null || !member.getParameterTypes()[0].isPrimitive()) return invoker;
      final var fallback = jlsDefault(member.getParameterTypes()[0]);
      if (invoker instanceof BiConsumer<?, ?>) {
        final var consumer = (BiConsumer<Object, Object>) invoker;
        return (BiConsumer<Object, Object>) (builder, value) ->
          consumer.accept(builder, value == null ? fallback : value);
      }
      final var function = (BiFunction<Object, Object, Object>) invoker;
      return (BiFunction<Object, Object, Object>) (builder, value) ->
        function.apply(builder, value == null ? fallback : value);
    }

    /**
     * A property is written when the builder has a {@code name} / {@code setX} / {@code withX}
     * method for it.
     */
    @Override
    public boolean writes(final String name) {
      return builderSetterFor(builderType, name) != null;
    }

    /**
     * Resolve the {@code name} / {@code setX} / {@code withX} single-arg setter on the builder type
     * and bind it via {@link LambdaMetafactory}. Returns one of two SAM shapes depending on the
     * setter's return type:
     *
     * <ul>
     *   <li>{@code void setX(X)} → {@link BiConsumer BiConsumer&lt;Object, Object&gt;} (classic
     *       JavaBean-style builder setters)
     *   <li>fluent {@code Builder setX(X)} returning the builder → {@link BiFunction
     *       BiFunction&lt;Object, Object, Object&gt;} (the return is discarded at the call site)
     * </ul>
     *
     * <p>LMF only accepts direct {@link java.lang.invoke.MethodHandle}s; modeling each shape
     * directly avoids the {@code asType}-discard trick LMF rejects. Primitive setter parameters
     * auto-unbox through a boxed {@code instantiatedMethodType} parameter.
     */
    @SuppressWarnings("unchecked")
    private Object buildSetterInvoker(final String name) {
      final var setter = builderSetterFor(builderType, name);
      // Align with SettersWriter and the captured-writer path — when a target property has no
      // matching builder setter (getter-only on the target POJO, computed-only value, etc.),
      // silently skip rather than throw. The names array passed to
      // {@code construct(...)} is derived from the target's getter set, not from a user-
      // authored builder name list, so an asymmetric writer contract here would diverge from
      // {@code Mapper.forward} (via SettersWriter) on POJOs that happen to expose a static
      // {@code builder()} factory. A BiConsumer no-op matches the dispatch path in
      // {@code construct(...)}.
      if (setter == null) return (BiConsumer<Object, Object>) (builder, value) -> {};
      // Inherited-accessor correctness: a builder type may extend another builder type whose
      // setters live in a different package / module. Pin the lookup, error message, and
      // instantiatedMethodType receiver to the setter's declaring class so a closed inheritor
      // package doesn't mask an open declaring package (or vice-versa).
      final var declaringClass = setter.getDeclaringClass();
      final var lookup = privateLookupOrThrow(declaringClass, builderType, "builder setter");
      final var paramType = setter.getParameterTypes()[0];
      // LMF rejects primitives in instantiatedMethodType parameters when the SAM parameter is
      // erased to Object (e.g. `setScore(int)` against `BiFunction.apply(Object, Object)`). When
      // the setter takes a primitive, pin the instantiatedMethodType parameter to the matching
      // boxed type so LMF inserts a single auto-unbox adapter in the synthesized bridge — the
      // call site still passes a boxed Integer, the bridge unboxes once before invoking the
      // primitive setter.
      final var samParamType = paramType.isPrimitive() ? wrap(paramType) : paramType;
      try {
        final var handle = lookup.unreflect(setter);
        final var voidSetter = setter.getReturnType() == void.class;
        final var spinner = ModuleAccess.spinner(lookup, setter);
        if (spinner == null) {
          // Native-image: MethodHandle closures, no runtime class synthesis (see MhAccessors). A
          // void setter binds as BiConsumer; a fluent setter as BiFunction (the returned builder is
          // discarded at the call site).
          return voidSetter ? MhAccessors.biConsumer(handle) : MhAccessors.biFunction(handle);
        }
        if (voidSetter) {
          // Void-returning setter (classic JavaBean style): bind directly as BiConsumer.
          final var callSite = LambdaMetafactory.metafactory(
            spinner,
            "accept",
            MethodType.methodType(BiConsumer.class),
            MethodType.methodType(void.class, Object.class, Object.class),
            handle,
            MethodType.methodType(void.class, declaringClass, samParamType)
          );
          return (BiConsumer<Object, Object>) callSite.getTarget().invoke();
        }
        // Fluent setter: bind as BiFunction; the returned builder is discarded at the call site.
        final var callSite = LambdaMetafactory.metafactory(
          spinner,
          "apply",
          MethodType.methodType(BiFunction.class),
          MethodType.methodType(Object.class, Object.class, Object.class),
          handle,
          MethodType.methodType(setter.getReturnType(), declaringClass, samParamType)
        );
        return (BiFunction<Object, Object, Object>) callSite.getTarget().invoke();
      } catch (final Throwable t) {
        throw new RuntimeException(
          "Failed to build LambdaMetafactory invoker for builder setter '" + name + "' on " + builderType.getName(),
          t
        );
      }
    }

    /**
     * Capture the zero-arg static {@code builder()} factory as a {@link Supplier
     * Supplier&lt;Object&gt;} via {@link LambdaMetafactory}. The synthesized class' {@code get()}
     * dispatches directly to the static factory — no per-call {@code Method#invoke}.
     */
    @SuppressWarnings("unchecked")
    private static Supplier<Object> buildBuilderSupplier(final Class<?> cls, final Method factory) {
      // Pin to factory.getDeclaringClass() — `builder()` may be inherited from a base type whose
      // package is the one that needs `opens`, not `cls`'s.
      final var lookup = privateLookupOrThrow(factory.getDeclaringClass(), cls, "builder factory");
      try {
        final var handle = lookup.unreflect(factory);
        final var spinner = ModuleAccess.spinner(lookup, factory);
        if (spinner == null) return MhAccessors.supplier(handle);
        final var callSite = LambdaMetafactory.metafactory(
          spinner,
          "get",
          MethodType.methodType(Supplier.class),
          MethodType.methodType(Object.class),
          handle,
          MethodType.methodType(factory.getReturnType())
        );
        return (Supplier<Object>) callSite.getTarget().invoke();
      } catch (final Throwable t) {
        throw new RuntimeException(
          "Failed to build LambdaMetafactory invoker for static builder() factory on " + cls.getName(),
          t
        );
      }
    }

    /**
     * Capture the {@code build()} method on the builder type as a {@link Function
     * Function&lt;Object, Object&gt;} via {@link LambdaMetafactory}. The synthesized class' {@code
     * apply(Object)} dispatches directly to {@code build()} on the builder instance.
     */
    @SuppressWarnings("unchecked")
    private static Function<Object, Object> buildBuildFn(final Class<?> builderType, final Method buildMethod) {
      // Pin the lookup to `build()`'s declaring class — it may be inherited from a base builder
      // type in a different module than the concrete builderType.
      final var declaringClass = buildMethod.getDeclaringClass();
      final var lookup = privateLookupOrThrow(declaringClass, builderType, "builder build()");
      try {
        final var handle = lookup.unreflect(buildMethod);
        final var spinner = ModuleAccess.spinner(lookup, buildMethod);
        if (spinner == null) return MhAccessors.function(handle);
        // Pin the `instantiatedMethodType` return to the build method's actual return type, not
        // `cls`. A covariant `build()` (e.g. on a generic builder hierarchy) returns a subtype of
        // `cls`, and LMF will refuse the binding ("incorrect return type") if we pin to `cls`.
        // Mirrors the pattern in buildGetterInvokers (`method.getReturnType()`).
        final var callSite = LambdaMetafactory.metafactory(
          spinner,
          "apply",
          MethodType.methodType(Function.class),
          MethodType.methodType(Object.class, Object.class),
          handle,
          MethodType.methodType(buildMethod.getReturnType(), declaringClass)
        );
        return (Function<Object, Object>) callSite.getTarget().invoke();
      } catch (final Throwable t) {
        throw new RuntimeException(
          "Failed to build LambdaMetafactory invoker for build() on " + builderType.getName(),
          t
        );
      }
    }
  }

  /**
   * {@link BeanWriter} backed by a no-arg constructor plus public {@code setX(value)} setters,
   * matched by name. Writes only through the setters the class publishes. The natural rebuild
   * strategy for classic JavaBeans / Hibernate entities.
   *
   * <p>Hot-path setter dispatch goes through one {@link BiConsumer BiConsumer&lt;Object,
   * Object&gt;} per property, built once via {@link LambdaMetafactory} over the cached setter
   * {@link Method} and stored in a {@link ConcurrentHashMap}. The metafactory synthesizes a class
   * implementing {@code BiConsumer} whose {@code accept(Object, Object)} directly calls the
   * underlying setter, auto-unboxing any primitive argument (e.g. {@code setAge(int)} consumes a
   * boxed {@link Integer} through {@code BiConsumer<Object, Object>::accept}). After the first call
   * per setter, dispatch is a single virtual call the JIT inlines — no {@link
   * java.lang.reflect.Method#invoke}, no per-call argument array, no access-check.
   *
   * <p>Building the {@code BiConsumer} requires a private lookup on the target class via {@link
   * MethodHandles#privateLookupIn}. For fully-public POJOs in the same module this is equivalent to
   * a plain lookup; for closed-package targets under the module path, the POJO's module needs an
   * {@code opens} directive.
   */
  static final class SettersWriter<P> implements BeanWriter<P> {

    private final Class<P> cls;
    private final Supplier<Object> ctorFn;
    private final Map<String, BiConsumer<Object, Object>> setterInvokers = new ConcurrentHashMap<>();

    SettersWriter(final Class<P> cls) {
      this.cls = cls;
      final Constructor<P> ctor;
      try {
        ctor = cls.getDeclaredConstructor();
      } catch (final NoSuchMethodException e) {
        throw new IllegalStateException("writeBean(" + cls.getName() + ", SETTERS) requires a no-arg constructor", e);
      }
      // Build the no-arg ctor as an LMF-bound Supplier, as BuilderWriter does, so a write never
      // reaches Constructor.newInstance. autoWriter picks SETTERS for a bean with neither a builder
      // nor a name-matched constructor, the plain Lombok @Data shape among them, so this is a
      // common bean-write path.
      final var lookup = privateLookupOrThrow(cls, cls, "SETTERS strategy");
      this.ctorFn = buildCtorSupplier(cls, ctor, lookup);
    }

    @Override
    @SuppressWarnings("unchecked")
    public P construct(final String[] names, final Function<String, Object> valueByName) {
      final P pojo;
      try {
        pojo = (P) ctorFn.get();
      } catch (final RuntimeException e) {
        throw new RuntimeException("Failed to instantiate " + cls.getName(), e);
      }
      for (final var name : names) {
        setterFor(name).accept(pojo, valueByName.apply(name));
      }
      return pojo;
    }

    private BiConsumer<Object, Object> setterFor(final String name) {
      return setterInvokers.computeIfAbsent(name, this::buildSetterInvoker);
    }

    /** A property is written when the bean has a public single-argument {@code setX} for it. */
    @Override
    public boolean writes(final String name) {
      return setterMethod(name) != null;
    }

    /**
     * The public single-argument {@code setX} method for {@code name}, or null when there is none.
     */
    private Method setterMethod(final String name) {
      final var set = "set" + capitalize(name);
      for (final var m : cls.getMethods()) {
        if (m.getParameterCount() == 1 && m.getName().equals(set)) return m;
      }
      return null;
    }

    /**
     * Resolve the {@code setX(value)} {@link Method} for {@code name} and build a {@link
     * BiConsumer} that dispatches directly to it via {@link LambdaMetafactory}. The SAM signature
     * is {@code void accept(Object, Object)}; the {@code instantiatedMethodType} pins the actual
     * {@code (cls, paramType) -> void} signature so the metafactory generates the right bridge —
     * including auto-unboxing for primitive setter parameters (e.g. {@code setAge(int)}). For a
     * primitive {@code int} parameter the instantiated parameter must be the wrapper class {@link
     * Integer} (the metafactory validator rejects a raw {@code int} against the SAM's {@code
     * Object}); the metafactory then synthesizes the {@code Object → Integer → int} unbox bridge
     * automatically.
     */
    @SuppressWarnings("unchecked")
    private BiConsumer<Object, Object> buildSetterInvoker(final String name) {
      final var setter = setterMethod(name);
      // Getter-only properties (computed fields, immutable accessors, etc.) have no matching
      // setX. Throwing here would make writeBean(SETTERS) unusable on any class with a read-
      // only property. MapStruct silently ignores unwritable target fields; match that by
      // returning a no-op BiConsumer so the construct loop skips the property at write time.
      // The property's underlying field stays at its JLS default (null/0/false).
      if (setter == null) return (pojo, value) -> {};
      // Use the SETTER's declaring class — inherited setters live on a superclass / interface
      // whose package may be in a different module than `cls`. Pinning the lookup, the
      // instantiated receiver type, and the opens-error message to the declaring class keeps all
      // three consistent.
      final var declaringClass = setter.getDeclaringClass();
      final var lookup = privateLookupOrThrow(declaringClass, cls, "setter");
      final var paramType = setter.getParameterTypes()[0];
      final var instantiatedParamType = wrap(paramType);
      try {
        final var handle = lookup.unreflect(setter);
        final BiConsumer<Object, Object> baseSetter;
        final var spinner = ModuleAccess.spinner(lookup, setter);
        if (spinner == null) {
          // Native-image: a MethodHandle closure, no runtime class synthesis (see MhAccessors).
          baseSetter = MhAccessors.biConsumer(handle);
        } else {
          final var callSite = LambdaMetafactory.metafactory(
            spinner,
            "accept",
            MethodType.methodType(BiConsumer.class),
            MethodType.methodType(void.class, Object.class, Object.class),
            handle,
            MethodType.methodType(void.class, declaringClass, instantiatedParamType)
          );
          baseSetter = (BiConsumer<Object, Object>) callSite.getTarget().invoke();
        }
        // Defence-in-depth on the primitive-setter null guard. The built setter for a primitive
        // parameter (e.g. setCount(int)) NPEs when invoked with null (Object → Integer → int
        // unbox) — on either the LMF or the native-image MethodHandle path. DeepMap's
        // placeholderIsoFor short-circuits unmatched primitive target fields to their JLS default
        // before the value ever reaches here, so this guard is unreachable on the happy path — it's
        // kept to lock the contract against future code paths that may legitimately deliver null to
        // a primitive setter (e.g. autoboxing-relaxation work). When the parameter type is a
        // primitive, skip on null so the field stays at its JLS default rather than crashing.
        if (paramType.isPrimitive()) {
          return (pojo, value) -> {
            if (value == null) return;
            baseSetter.accept(pojo, value);
          };
        }
        return baseSetter;
      } catch (final Throwable t) {
        throw new RuntimeException("Failed to set '" + name + "' on " + cls.getName(), t);
      }
    }
  }

  /**
   * Cached raw, primitive-typed handles for the MethodHandle-combinator conversion path — the bean
   * mirror of {@code Records.RecordInfo}. {@code accessorHandles[i]} is the getter for property
   * {@code i} (in {@link #propertyNames} order), typed {@code (beanClass) -> propertyType[i]} with
   * no boxing; {@code setterHandles} maps a property name to its {@code (beanClass, propertyType)
   * -> void} setter handle (absent when the property is getter-only); {@code noArgCtorHandle} is
   * the raw {@code () -> beanClass} no-arg constructor handle, or {@code null} when the bean has no
   * no-arg constructor (never reached by the fold — {@code MhIso.supports} gates it out).
   *
   * <p>Getter and setter dispatch each go {@code invokevirtual}, so a subtype instance still reads
   * and writes correctly through a handle bound to the declared {@code beanClass}. The handles keep
   * the member's actual signature, so {@code MhIso} composes them into a mostly-unboxed {@code (S)
   * -> BeanT} fold. This holder carries only the <em>raw</em> handles; the primitive-setter null
   * guard that {@link SettersWriter} applies (skip a null into a primitive setter, leaving the JLS
   * default) is layered on top in {@code MhIso.setterFromSource} for a primitive setter fed by a
   * value-producing Iso — not here.
   */
  record BeanMhInfo(
    String[] names,
    MethodHandle[] accessorHandles,
    Map<String, MethodHandle> setterHandles,
    MethodHandle noArgCtorHandle
  ) {
    static BeanMhInfo of(final Class<?> cls) {
      final var props = propertyNames(cls);
      final var getterMethods = getters(cls);
      final var accessors = new MethodHandle[props.length];
      final var lookupByDeclaringClass = new LinkedHashMap<Class<?>, MethodHandles.Lookup>();
      for (var i = 0; i < props.length; i++) {
        final var getter = getterMethods.get(props[i]);
        final var declaringClass = getter.getDeclaringClass();
        final var lookup = lookupByDeclaringClass.computeIfAbsent(declaringClass, dc ->
          privateLookupOrThrow(dc, cls, "getter")
        );
        try {
          accessors[i] = lookup.unreflect(getter);
        } catch (final IllegalAccessException e) {
          throw new IllegalStateException("Failed to build accessor handle for " + cls.getName() + "." + props[i], e);
        }
      }
      final var setters = new LinkedHashMap<String, MethodHandle>();
      for (final var name : props) {
        final var setter = findSetter(cls, name);
        if (setter == null) continue;
        final var declaringClass = setter.getDeclaringClass();
        final var lookup = lookupByDeclaringClass.computeIfAbsent(declaringClass, dc ->
          privateLookupOrThrow(dc, cls, "setter")
        );
        try {
          setters.put(name, lookup.unreflect(setter));
        } catch (final IllegalAccessException e) {
          throw new IllegalStateException("Failed to build setter handle for " + cls.getName() + "." + name, e);
        }
      }
      MethodHandle ctorHandle = null;
      try {
        final var ctor = cls.getDeclaredConstructor();
        final var lookup = privateLookupOrThrow(cls, cls, "no-arg constructor");
        ctorHandle = lookup.unreflectConstructor(ctor);
      } catch (final NoSuchMethodException | IllegalAccessException ignored) {
        // No accessible no-arg constructor — the setter fold is unavailable for this bean, and
        // MhIso.supports gates it out before the fold runs. Leave the handle null.
      }
      return new BeanMhInfo(props, accessors, setters, ctorHandle);
    }

    private static Method findSetter(final Class<?> cls, final String name) {
      final var set = "set" + capitalize(name);
      for (final var m : cls.getMethods()) {
        if (m.getParameterCount() == 1 && m.getName().equals(set)) return m;
      }
      return null;
    }

    MethodHandle setterHandle(final String name) {
      return setterHandles.get(name);
    }
  }
}
