package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Getter;
import io.github.eschizoid.telescope.internal.optics.Lens;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * Boolean} — and {@code X} is {@link #decapitalize(String) decapitalized} to a property name. This
 * deliberately avoids {@code java.beans.Introspector} so the library keeps zero dependencies
 * ({@code Introspector} lives in the {@code java.desktop} module). The discovered getter map is
 * cached per class via {@link ClassValue}. Alongside the {@link Method} cache, a sibling {@link
 * ClassValue} caches one {@link Function Function&lt;Object, Object&gt;} per property, built once
 * via {@link LambdaMetafactory} from the resolved accessor — the metafactory synthesizes a {@link
 * Function}-implementing class whose {@code apply(Object)} directly calls the getter and auto-boxes
 * any primitive return, so the hot path never touches {@link Method#invoke}. The lattice-primitive
 * read for one property is {@link #getter(Class, String)} — a {@link Getter Getter&lt;P,
 * Object&gt;} whose body delegates to the cached {@link Function} and allocates a fresh capturing
 * lambda per call (the lattice-shape entry for composing the read with other optics). {@link
 * #readProperty} is the hot-path shortcut that calls the cached {@link Function} directly, skipping
 * the per-call lambda allocation — preferred from inner loops (e.g. {@code
 * Reflective.structuralIso(...).from(...)} reads every property of a target).
 *
 * <p><b>Write direction (Map/record &rarr; POJO).</b> Four strategies behind the sealed {@link
 * BeanWriter} — {@link BuilderWriter}, {@link SettersWriter}, {@link FieldsWriter}, {@link
 * ConstructorWriter} — chosen by {@link #autoWriter} or selected explicitly by a {@code
 * WriteHint.writeBean(target, strategy)} row passed to {@code Telescope.map(...)}.
 */
public final class Beans {

  private Beans() {}

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

  private static final ClassValue<BeanWriter<?>> AUTO_WRITER_CACHE = new ClassValue<>() {
    @Override
    protected BeanWriter<?> computeValue(final Class<?> type) {
      return computeAutoWriter(type);
    }
  };

  /**
   * The {@code org.hibernate.proxy.HibernateProxy} interface, or {@code null} when Hibernate isn't
   * on the classpath. Resolved once via reflection so the {@code :core} module stays free of any
   * Hibernate dependency.
   */
  private static final Class<?> HIBERNATE_PROXY = loadOptionalClass("org.hibernate.proxy.HibernateProxy");

  private static Class<?> loadOptionalClass(final String fqn) {
    try {
      return Class.forName(fqn, false, Beans.class.getClassLoader());
    } catch (final ClassNotFoundException e) {
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
   */
  public static Class<?> persistentClassOf(final Object pojo) {
    if (pojo == null) return null;
    final var raw = pojo.getClass();
    if (HIBERNATE_PROXY == null || !HIBERNATE_PROXY.isInstance(pojo)) return raw;
    try {
      final var getInitializer = HIBERNATE_PROXY.getMethod("getHibernateLazyInitializer");
      final var initializer = getInitializer.invoke(pojo);
      if (initializer == null) return raw;
      final var getPersistentClass = initializer.getClass().getMethod("getPersistentClass");
      final var persistentClass = (Class<?>) getPersistentClass.invoke(initializer);
      return persistentClass != null ? persistentClass : raw;
    } catch (final ReflectiveOperationException e) {
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
    // Unwrap HibernateProxy (when present) so a LAZY-fetched entity routes through the
    // persistent class's cache entry, not a per-proxy-subclass one. See persistentClassOf.
    final var beanClass = persistentClassOf(pojo);
    final var reader = GETTER_INVOKERS.get(beanClass).get(name);
    if (reader == null) throw new IllegalArgumentException(
      "No getter for property '" + name + "' on " + beanClass.getName()
    );
    return reader.apply(pojo);
  }

  /**
   * Whether {@code beanClass} exposes a getter for {@code name} (per the {@code getX()} / {@code
   * isX()} scan). Reuses the same cached getter map as {@link #readProperty(Object, String)}.
   */
  public static boolean hasProperty(final Class<?> beanClass, final String name) {
    return getters(beanClass).containsKey(name);
  }

  /**
   * The generic return type of {@code beanClass}'s getter for {@code name} (used by {@link
   * io.github.eschizoid.telescope.mapping.DeepMap DeepMap} for container shape detection — {@code
   * List<X>}, {@code Map<K, V>}, {@code Optional<X>}).
   *
   * @throws IllegalArgumentException if no getter is found
   */
  public static java.lang.reflect.Type propertyType(final Class<?> beanClass, final String name) {
    final var getter = getters(beanClass).get(name);
    if (getter == null) throw new IllegalArgumentException(
      "No getter for property '" + name + "' on " + beanClass.getName()
    );
    return getter.getGenericReturnType();
  }

  private static Map<String, Method> getters(final Class<?> cls) {
    return GETTERS.get(cls);
  }

  /**
   * Build one {@link Function} per discovered getter via {@link LambdaMetafactory}. The metafactory
   * synthesizes a {@link Function}-implementing class whose {@code apply(Object)} directly calls
   * the getter and auto-boxes any primitive return ({@code int}, {@code boolean}, etc). After the
   * first call per class the dispatch is a single virtual call the JIT inlines — no {@link
   * Method#invoke}, no per-call argument array, no access-check.
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
        // SAM signature is `Object apply(Object)`; the instantiatedMethodType pins the actual
        // (declaringClass) -> returnType signature so the metafactory generates the right bridge
        // — including auto-boxing for primitive returns (`int`, `boolean`, etc).
        final var callSite = LambdaMetafactory.metafactory(
          lookup,
          "apply",
          MethodType.methodType(Function.class),
          MethodType.methodType(Object.class, Object.class),
          handle,
          MethodType.methodType(method.getReturnType(), declaringClass)
        );
        @SuppressWarnings("unchecked")
        final var reader = (Function<Object, Object>) callSite.getTarget().invoke();
        invokers.put(name, reader);
      } catch (final Throwable t) {
        throw new IllegalStateException(
          "Failed to build LambdaMetafactory getter invoker for " + cls.getName() + "." + name,
          t
        );
      }
    }
    return invokers;
  }

  /**
   * Resolve a {@link MethodHandles.Lookup} with private access to {@code declaringClass}, or throw
   * an {@link IllegalStateException} with a JPMS-opens hint. When {@code declaringClass} is
   * inherited (i.e. differs from {@code ownerClass}), the message points to the declaring class's
   * package — that's the one that needs the {@code opens} directive, not the inheritor.
   */
  private static MethodHandles.Lookup privateLookupOrThrow(
    final Class<?> declaringClass,
    final Class<?> ownerClass,
    final String accessorKind
  ) {
    try {
      // `privateLookupIn` is needed when the type's module/package isn't open to the telescope
      // module; for fully-public types in the same module this is equivalent to a plain
      // `MethodHandles.lookup()`. Same JPMS constraint as `setAccessible(true)` — no worse than
      // the previous reflection path.
      return MethodHandles.privateLookupIn(declaringClass, MethodHandles.lookup());
    } catch (final IllegalAccessException e) {
      final var inheritedNote =
        declaringClass == ownerClass
          ? ""
          : " (declaring class of an inherited " + accessorKind + " on " + ownerClass.getName() + ")";
      throw new IllegalStateException(
        "Cannot access " +
          declaringClass.getName() +
          inheritedNote +
          " to build LambdaMetafactory " +
          accessorKind +
          " invokers. Add 'opens " +
          declaringClass.getPackageName() +
          " to io.github.eschizoid.telescope;' to that module's module-info.java.",
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
      final var n = m.getName();
      final String prop;
      if (n.length() > 3 && n.startsWith("get") && m.getReturnType() != void.class) {
        prop = decapitalize(n.substring(3));
      } else if (
        n.length() > 2 &&
        n.startsWith("is") &&
        (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)
      ) {
        prop = decapitalize(n.substring(2));
      } else {
        continue;
      }
      if (!"class".equals(prop) && !map.containsKey(prop)) {
        m.setAccessible(true);
        map.put(prop, m);
      }
    }
    return map;
  }

  // JavaBeans rule: a name whose first two characters are both uppercase is left unchanged (e.g.
  // "URL").
  private static String decapitalize(final String s) {
    if (s.isEmpty()) return s;
    if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) return s;
    return Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  private static String capitalize(final String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  /**
   * The property name behind a getter method name: {@code getCity} &rarr; {@code city}, {@code
   * isActive} &rarr; {@code active}. A name without a recognized prefix is returned unchanged.
   */
  public static String propertyOf(final String getterName) {
    if (getterName.length() > 3 && getterName.startsWith("get")) return decapitalize(getterName.substring(3));
    if (getterName.length() > 2 && getterName.startsWith("is")) return decapitalize(getterName.substring(2));
    return getterName;
  }

  /**
   * A strategy for reconstructing a POJO from named values — the reverse (record &rarr; POJO)
   * direction of a bean bridge. Sealed over the three concrete writers; pick one via {@link
   * #fieldsWriter}, {@link #constructorWriter}, or {@link #builderWriter}.
   *
   * <p>{@code construct(names, valueByName)} contract: the {@code names} array is the source
   * record's component names in component order; for each name the writer pulls its value from
   * {@code valueByName} and feeds it to the chosen mechanism (field, constructor argument, or
   * builder setter), returning the fully built {@code P}.
   */
  public sealed interface BeanWriter<P> permits FieldsWriter, ConstructorWriter, BuilderWriter, SettersWriter {
    /** Build a {@code P}, pulling each value in {@code names} from {@code valueByName}. */
    P construct(String[] names, Function<String, Object> valueByName);
  }

  /**
   * Strategy: invoke the no-arg constructor, then inject each named value into the matching field
   * reflectively (needs no setters). Requires {@code setAccessible} on the fields, so on the module
   * path the target package may need an {@code opens} directive — see {@link FieldsWriter}.
   */
  public static <P> BeanWriter<P> fieldsWriter(final Class<P> pojoClass) {
    return new FieldsWriter<>(pojoClass);
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
   * Pick a <em>name-based</em> write strategy for {@code cls} by probing in priority order: a
   * static {@code builder()}, then a no-arg constructor with setters, then a no-arg constructor
   * with field injection, then — as a last resort — a single public all-args constructor (compiled
   * with {@code -parameters} so its arguments can be matched by name without positional ambiguity).
   * The result is cached per class.
   *
   * <p>Used by both the record-less POJO APIs ({@link
   * io.github.eschizoid.telescope.Telescope#ofBean(Class) Telescope.ofBean}) and by the deep
   * mapping path when no explicit {@code writeBean} hint applies. Throws if none of the strategies
   * applies (e.g., an immutable all-args-only POJO compiled without {@code -parameters}); the
   * recommended escape is to declare a {@code writeBean(target, CONSTRUCTOR)} hint at the {@code
   * Telescope.map(...)} call site.
   */
  @SuppressWarnings("unchecked")
  public static <P> BeanWriter<P> autoWriter(final Class<P> cls) {
    return (BeanWriter<P>) AUTO_WRITER_CACHE.get(cls);
  }

  private static <P> BeanWriter<P> computeAutoWriter(final Class<P> cls) {
    if (hasStaticBuilder(cls)) return builderWriter(cls);
    if (hasNoArgConstructor(cls)) return hasAnySetter(cls) ? settersWriter(cls) : fieldsWriter(cls);
    final var props = propertyNames(cls);
    final var sole = solePublicConstructor(cls, props.length);
    // Refuse to silently use positional fallback: getter-iteration order is not guaranteed to match
    // constructor parameter order. Requiring -parameters yields named matching and eliminates the
    // silent-data-shuffle hazard the explicit writeBean(...) hint accepts. Also require every ctor
    // parameter name to appear in the getter-derived property set — otherwise `getURL()` → property
    // `"URL"` mismatched against a ctor parameter named `"url"` would silently pass null into the
    // constructor under the lookup `valueByName("url")`.
    if (sole != null && allParameterNamesMatchProperties(sole, props)) return constructorWriter(cls, props.length);
    throw new IllegalStateException(
      "No name-based write strategy for " +
        cls.getName() +
        " — needs a static builder(), a no-arg constructor with setters, a no-arg constructor (field injection), " +
        "or exactly one public constructor whose arity matches the property count (" +
        props.length +
        "), was compiled with -parameters, and whose parameter names line up with the getter-derived properties. " +
        "Primary fix: recompile the target class with javac -parameters so its constructor arguments can be " +
        "matched by name. The writeBean(targetClass, CONSTRUCTOR) hint at the Telescope.map(...) call site is " +
        "an escape hatch, but note it falls back to POSITIONAL argument matching when -parameters is absent — " +
        "argument order is then the getter-discovery order (not a stable, user-defined canonical order), so " +
        "the hint should still be paired with -parameters to be safe."
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
    final var props = new java.util.HashSet<>(java.util.Arrays.asList(propertyNames));
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
   * <pre>{@code
   * final Lens<UserPojo, String> email =
   *     Beans.lens(UserPojo.class, "email", Beans.autoWriter(UserPojo.class));
   * final var updated = email.modify(user, String::toLowerCase); // new UserPojo
   * }</pre>
   */
  public static <P, A> Lens<P, A> lens(final Class<P> pojoClass, final String property, final BeanWriter<P> writer) {
    final var names = propertyNames(pojoClass);
    return new Lens<>() {
      @Override
      @SuppressWarnings("unchecked")
      public A get(final P source) {
        return (A) readProperty(source, property);
      }

      @Override
      public P set(final P source, final A value) {
        return writer.construct(names, n -> n.equals(property) ? value : readProperty(source, n));
      }

      @Override
      public P modify(final P source, final Function<? super A, ? extends A> f) {
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

  private static boolean hasStaticBuilder(final Class<?> cls) {
    try {
      return Modifier.isStatic(cls.getMethod("builder").getModifiers());
    } catch (final NoSuchMethodException e) {
      return false;
    }
  }

  private static boolean hasAnySetter(final Class<?> cls) {
    for (final var m : cls.getMethods()) {
      if (m.getParameterCount() == 1 && m.getName().length() > 3 && m.getName().startsWith("set")) return true;
    }
    return false;
  }

  /**
   * {@link BeanWriter} backed by a no-arg constructor plus field injection routed through one
   * cached invoker per member. At construction it resolves the no-arg constructor, walks each
   * non-static / non-synthetic declared field, calls {@code setAccessible(true)} (still needed for
   * the {@link MethodHandles.Lookup#unreflectSetter(Field) unreflectSetter} call on non-public
   * fields, exactly mirroring the previous {@link Field#set} permission model), and binds a {@link
   * Supplier Supplier&lt;Object&gt;} no-arg-constructor invoker built via {@link LambdaMetafactory}
   * plus a {@code BiConsumer<Object, Object>} setter per field. The per-field setters wrap a cached
   * {@link MethodHandle} adapted to {@code (Object, Object) -> void} via {@link MethodHandle#asType
   * asType} — LMF won't accept setter handles ({@code "Unsupported MethodHandle kind: putField"}),
   * so the cached MH is the JDK-standard alternative. It still skips the per-call access check that
   * {@code Field.set} pays; {@code invokeExact} through the captured {@code final} reference is
   * JIT-inlinable.
   *
   * <p>If the JPMS layer forbids access, {@link InaccessibleObjectException} is rethrown as an
   * {@link IllegalStateException} telling the caller to add an {@code opens} directive. Every
   * sibling strategy ({@link ConstructorWriter} / {@link BuilderWriter} / {@link SettersWriter})
   * now reaches the bean through {@link MethodHandles#privateLookupIn} — same JPMS gate — so
   * switching the hint to a sibling only avoids this error when the sibling's target members are
   * already accessible (e.g. the bean's {@code builder()} factory is public and lives in a package
   * the module exports). For a fully closed package, the {@code opens} directive is the real fix
   * regardless of strategy. The hot path — {@link #construct(String[], Function)} — calls {@code
   * ctorFn.get()} once and then {@code setter.accept(pojo, value)} per name; neither call reaches
   * {@link Field#set} or {@link Constructor#newInstance}.
   */
  static final class FieldsWriter<P> implements BeanWriter<P> {

    private final Class<P> cls;
    private final Supplier<Object> ctorFn;
    private final Map<String, BiConsumer<Object, Object>> setters;

    FieldsWriter(final Class<P> cls) {
      this.cls = cls;
      final Constructor<P> ctor;
      try {
        ctor = cls.getDeclaredConstructor();
      } catch (final NoSuchMethodException e) {
        throw new IllegalStateException("writeBean(" + cls.getName() + ", FIELDS) requires a no-arg constructor", e);
      }
      access(ctor);
      final var fs = new LinkedHashMap<String, Field>();
      for (final var f : cls.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
        access(f);
        fs.put(f.getName(), f);
      }
      final var lookup = privateLookupOrThrow(cls, cls, "FIELDS strategy");
      this.ctorFn = buildCtorSupplier(cls, ctor, lookup);
      final var setterMap = new LinkedHashMap<String, BiConsumer<Object, Object>>();
      for (final var entry : fs.entrySet()) {
        setterMap.put(entry.getKey(), buildFieldSetter(cls, entry.getValue(), lookup));
      }
      this.setters = setterMap;
    }

    @Override
    public P construct(final String[] names, final Function<String, Object> valueByName) {
      // Wrap the LMF-built Supplier invocation in the same stable RuntimeException shape the
      // pre-LMF Constructor#newInstance path used. Unlike Constructor#newInstance (which surfaced
      // body failures via InvocationTargetException → checked-exception wrap), the LMF Supplier is
      // signature-polymorphic and can propagate checked exceptions and Errors directly, so we
      // catch the same width here. Errors propagate untouched.
      final P pojo;
      try {
        @SuppressWarnings("unchecked")
        final var built = (P) ctorFn.get();
        pojo = built;
      } catch (final Error error) {
        throw error;
      } catch (final Throwable t) {
        throw new RuntimeException("Failed to instantiate " + cls.getName(), t);
      }
      for (final var name : names) {
        final var setter = setters.get(name);
        if (setter == null) throw new IllegalArgumentException(
          "writeBean(" + cls.getName() + ", FIELDS): no field '" + name + "'"
        );
        setter.accept(pojo, valueByName.apply(name));
      }
      return pojo;
    }

    private void access(final AccessibleObject member) {
      try {
        member.setAccessible(true);
      } catch (final InaccessibleObjectException e) {
        throw new IllegalStateException(
          "Cannot access members of " +
            cls.getName() +
            " for the FIELDS strategy. Add 'opens " +
            cls.getPackageName() +
            " to io.github.eschizoid.telescope;' to that module's module-info.java. " +
            "Switching the writeBean hint to CONSTRUCTOR / BUILDER / SETTERS reaches the bean " +
            "through privateLookupIn rather than raw setAccessible, but the JPMS gate is the same " +
            "— the open directive is the real fix for a fully closed package.",
          e
        );
      }
    }

    @SuppressWarnings("unchecked")
    private static Supplier<Object> buildCtorSupplier(
      final Class<?> cls,
      final Constructor<?> ctor,
      final MethodHandles.Lookup lookup
    ) {
      try {
        final var handle = lookup.unreflectConstructor(ctor);
        final var callSite = LambdaMetafactory.metafactory(
          lookup,
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

    private static BiConsumer<Object, Object> buildFieldSetter(
      final Class<?> cls,
      final Field field,
      final MethodHandles.Lookup lookup
    ) {
      // LambdaMetafactory rejects setter handles ("Unsupported MethodHandle kind: putField"), so
      // the cached MethodHandle path is the JDK-standard alternative — `unreflectSetter` resolves
      // a `(receiver, value) -> void` handle, then `asType` adapts it to the erased
      // `(Object, Object) -> void` so it can be invoked from a `BiConsumer<Object, Object>` shape.
      // Still skips the per-call access check {@link Field#set} pays; the JIT inlines invokeExact
      // through the captured `final` reference.
      final MethodHandle setterHandle;
      try {
        setterHandle = lookup
          .unreflectSetter(field)
          .asType(MethodType.methodType(void.class, Object.class, Object.class));
      } catch (final IllegalAccessException e) {
        throw new RuntimeException("Failed to set field '" + field.getName() + "' on " + cls.getName(), e);
      }
      return (pojo, value) -> {
        try {
          setterHandle.invokeExact(pojo, value);
        } catch (final Throwable t) {
          throw new RuntimeException("Failed to set field '" + field.getName() + "' on " + cls.getName(), t);
        }
      };
    }
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

    private final Function<Object, Object> ctorFn;
    // Constructor parameter names when the POJO was compiled with -parameters (enables
    // order-independent name matching); null when names are synthetic, so we fall back to
    // positional.
    private final String[] paramNames;

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
          " parameters (parameters are matched by name when compiled with -parameters, otherwise positionally)."
      );
      // No raw setAccessible — buildCtorFn acquires private access through privateLookupOrThrow,
      // which is consistent with the FIELDS strategy and routes JPMS failures through the same
      // opens-pointing message instead of a low-context InaccessibleObjectException.
      this.paramNames = resolveParamNames(found);
      this.ctorFn = buildCtorFn(cls, found, arity);
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
      for (var i = 0; i < keys.length; i++) args[i] = valueByName.apply(keys[i]);
      return (P) ctorFn.apply(args);
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
   * the POJO's module needs an {@code opens} directive — the same JPMS constraint as the previous
   * {@code setAccessible(true)} path.
   */
  static final class BuilderWriter<P> implements BeanWriter<P> {

    private final Class<P> cls;
    private final Class<?> builderType;
    private final Supplier<Object> builderSupplier;
    private final Function<Object, Object> buildFn;
    // Each value is one of: BiConsumer<Object, Object> for a void-returning setter, or
    // BiFunction<Object, Object, Object> for a fluent setter. Per-call dispatch checks the type
    // once; after a few calls the JIT specializes the call site against the observed shape.
    private final Map<String, Object> setterInvokers = new ConcurrentHashMap<>();

    BuilderWriter(final Class<P> cls) {
      this.cls = cls;
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
      this.builderSupplier = buildBuilderSupplier(cls, factory);
      this.buildFn = buildBuildFn(builderType, buildMethod);
    }

    @Override
    @SuppressWarnings("unchecked")
    public P construct(final String[] names, final Function<String, Object> valueByName) {
      // Dispatch-time exceptions (from builder() / setter / build() execution, or from auto-unbox
      // bridges in LMF setter dispatch) propagate raw, matching the other writer strategies
      // (FIELDS / SETTERS / CONSTRUCTOR) and the pre-LMF BuilderWriter (which only wrapped
      // `ReflectiveOperationException` — a class that doesn't exist on the LMF hot path).
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
        } else {
          ((BiFunction<Object, Object, Object>) inv).apply(builder, value);
        }
      }
      return (P) buildFn.apply(builder);
    }

    private Object setterFor(final String name) {
      return setterInvokers.computeIfAbsent(name, this::buildSetterInvoker);
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
      final var set = "set" + capitalize(name);
      final var with = "with" + capitalize(name);
      Method setter = null;
      for (final var m : builderType.getMethods()) {
        if (m.getParameterCount() != 1) continue;
        final var mn = m.getName();
        if (mn.equals(name) || mn.equals(set) || mn.equals(with)) {
          setter = m;
          break;
        }
      }
      if (setter == null) throw new IllegalArgumentException(
        "writeBean(" +
          cls.getName() +
          ", BUILDER): no single-argument builder method for '" +
          name +
          "' on " +
          builderType.getName()
      );
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
        if (setter.getReturnType() == void.class) {
          // Void-returning setter (classic JavaBean style): bind directly as BiConsumer.
          final var callSite = LambdaMetafactory.metafactory(
            lookup,
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
          lookup,
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
        final var callSite = LambdaMetafactory.metafactory(
          lookup,
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
        // Pin the `instantiatedMethodType` return to the build method's actual return type, not
        // `cls`. A covariant `build()` (e.g. on a generic builder hierarchy) returns a subtype of
        // `cls`, and LMF will refuse the binding ("incorrect return type") if we pin to `cls`.
        // Mirrors the pattern in buildGetterInvokers (`method.getReturnType()`).
        final var callSite = LambdaMetafactory.metafactory(
          lookup,
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
   * matched by name. Public-member only — unlike {@link FieldsWriter} it needs no {@code opens}
   * directive under JPMS for the setter dispatch itself. The natural rebuild strategy for classic
   * JavaBeans / Hibernate entities.
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
   * {@code opens} directive — the same JPMS constraint as the previous {@code setAccessible(true)}
   * path.
   */
  static final class SettersWriter<P> implements BeanWriter<P> {

    private final Class<P> cls;
    private final Constructor<P> ctor;
    private final Map<String, BiConsumer<Object, Object>> setterInvokers = new ConcurrentHashMap<>();

    SettersWriter(final Class<P> cls) {
      this.cls = cls;
      try {
        this.ctor = cls.getDeclaredConstructor();
      } catch (final NoSuchMethodException e) {
        throw new IllegalStateException("writeBean(" + cls.getName() + ", SETTERS) requires a no-arg constructor", e);
      }
      ctor.setAccessible(true);
    }

    @Override
    public P construct(final String[] names, final Function<String, Object> valueByName) {
      final P pojo;
      try {
        pojo = ctor.newInstance();
      } catch (final ReflectiveOperationException e) {
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
      final var set = "set" + capitalize(name);
      Method setter = null;
      for (final var m : cls.getMethods()) {
        if (m.getParameterCount() == 1 && m.getName().equals(set)) {
          setter = m;
          break;
        }
      }
      if (setter == null) throw new IllegalArgumentException(
        "writeBean(" + cls.getName() + ", SETTERS): no setter '" + set + "'"
      );
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
        final var callSite = LambdaMetafactory.metafactory(
          lookup,
          "accept",
          MethodType.methodType(BiConsumer.class),
          MethodType.methodType(void.class, Object.class, Object.class),
          handle,
          MethodType.methodType(void.class, declaringClass, instantiatedParamType)
        );
        return (BiConsumer<Object, Object>) callSite.getTarget().invoke();
      } catch (final Throwable t) {
        throw new RuntimeException("Failed to set '" + name + "' on " + cls.getName(), t);
      }
    }
  }
}
