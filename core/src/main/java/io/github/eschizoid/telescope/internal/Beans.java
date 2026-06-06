package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Lens;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Reflection-based machinery for bridging JavaBeans-style POJOs to records. Powers {@code
 * io.github.eschizoid.telescope.Telescope.fromBean(...)}.
 *
 * <p>Read direction (POJO &rarr; record): getters are discovered by convention — a no-arg {@code
 * getX()} with a non-void return, or {@code isX()} returning {@code boolean}/{@code Boolean} — and
 * the {@code X} is {@link #decapitalize(String) decapitalized} to a property name. This
 * deliberately avoids {@code java.beans.Introspector} so the library keeps zero dependencies
 * (Introspector lives in the {@code java.desktop} module). The discovered getter map is cached per
 * class.
 *
 * <p>Write direction (record &rarr; POJO): three strategies behind the sealed {@link BeanWriter} —
 * {@link FieldsWriter}, {@link ConstructorWriter}, {@link BuilderWriter}.
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

  private static final ClassValue<BeanWriter<?>> AUTO_WRITER_CACHE = new ClassValue<>() {
    @Override
    protected BeanWriter<?> computeValue(final Class<?> type) {
      return computeAutoWriter(type);
    }
  };

  /**
   * Read a bean property by name via its {@code getX()} / {@code isX()} accessor. Throws if no
   * getter matches {@code name}.
   *
   * <pre>{@code
   * final var name = (String) Beans.readProperty(userPojo, "name"); // userPojo.getName()
   * }</pre>
   */
  public static Object readProperty(final Object pojo, final String name) {
    final var getter = getters(pojo.getClass()).get(name);
    if (getter == null) throw new IllegalArgumentException(
      "No getter for property '" + name + "' on " + pojo.getClass().getName()
    );
    try {
      return getter.invoke(pojo);
    } catch (final ReflectiveOperationException e) {
      throw new RuntimeException("Failed to read property '" + name + "' on " + pojo.getClass().getName(), e);
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
    // Each *Writer constructor still throws IllegalStateException with messages referencing the
    // demolished fromBean(...).viaX() APIs (kept for source compatibility inside the writer
    // classes). When autoWriter surfaces those at the public Telescope.map(...) layer, rewrite the
    // message to point at the current API. The original exception becomes the cause.
    try {
      return computeAutoWriterUnsafe(cls);
    } catch (final IllegalStateException e) {
      final var msg = e.getMessage();
      if (msg != null && msg.contains("fromBean")) throw new IllegalStateException(
        "Auto write-strategy detection for " +
          cls.getName() +
          " selected a strategy whose underlying writer rejected the class: " +
          msg.replaceAll("fromBean\\(\\.\\.\\.\\)\\.via(\\w+)\\(\\)", "writeBean(targetClass, $1)") +
          " Either fix the class shape (add a builder() / no-arg ctor / etc.) or declare an explicit " +
          "writeBean(targetClass, strategy) hint at the Telescope.map(...) call site.",
        e
      );
      throw e;
    }
  }

  private static <P> BeanWriter<P> computeAutoWriterUnsafe(final Class<P> cls) {
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
   * {@link BeanWriter} backed by a no-arg constructor plus reflective field injection. At
   * construction it resolves the no-arg constructor and maps each non-static, non-synthetic
   * declared field by name, calling {@code setAccessible(true)} on all of them. If the JPMS layer
   * forbids that, {@link InaccessibleObjectException} is rethrown as an {@link
   * IllegalStateException} telling the caller to add an {@code opens} directive or switch to {@link
   * ConstructorWriter} / {@link BuilderWriter} (which touch public members only).
   */
  static final class FieldsWriter<P> implements BeanWriter<P> {

    private final Class<P> cls;
    private final Constructor<P> ctor;
    private final Map<String, Field> fields;

    FieldsWriter(final Class<P> cls) {
      this.cls = cls;
      try {
        this.ctor = cls.getDeclaredConstructor();
      } catch (final NoSuchMethodException e) {
        throw new IllegalStateException(
          "fromBean(...).viaFields() requires a no-arg constructor on " + cls.getName(),
          e
        );
      }
      access(ctor);
      final var fs = new LinkedHashMap<String, Field>();
      for (final var f : cls.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
        access(f);
        fs.put(f.getName(), f);
      }
      this.fields = fs;
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
        final var f = fields.get(name);
        if (f == null) throw new IllegalArgumentException("viaFields: no field '" + name + "' on " + cls.getName());
        try {
          f.set(pojo, valueByName.apply(name));
        } catch (final IllegalAccessException e) {
          throw new RuntimeException("Failed to set field '" + name + "' on " + cls.getName(), e);
        }
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
            " for viaFields(). Add 'opens " +
            cls.getPackageName() +
            " to io.github.eschizoid.telescope;' to that module's module-info.java, or use viaConstructor() / viaBuilder() " +
            "(which use public members only).",
          e
        );
      }
    }
  }

  /**
   * {@link BeanWriter} backed by an all-args constructor. At construction it finds the unique
   * declared constructor with the requested arity (throwing if there are zero or more than one) and
   * makes it accessible. If the POJO was compiled with {@code -parameters}, {@code construct}
   * matches each argument to its source value by the constructor parameter's <em>name</em> — so a
   * reordered constructor is safe; otherwise it falls back to positional, assembling arguments in
   * {@code names} order and relying on the constructor parameters lining up with the components.
   */
  static final class ConstructorWriter<P> implements BeanWriter<P> {

    private final Class<P> cls;
    private final Constructor<P> ctor;
    // Constructor parameter names when the POJO was compiled with -parameters (enables
    // order-independent name matching); null when names are synthetic, so we fall back to
    // positional.
    private final String[] paramNames;

    @SuppressWarnings("unchecked")
    ConstructorWriter(final Class<P> cls, final int arity) {
      this.cls = cls;
      Constructor<P> found = null;
      for (final var c : cls.getDeclaredConstructors()) {
        if (c.getParameterCount() != arity) continue;
        if (found != null) throw new IllegalStateException(
          "fromBean(...).viaConstructor(): " +
            cls.getName() +
            " has more than one " +
            arity +
            "-parameter constructor; cannot disambiguate."
        );
        found = (Constructor<P>) c;
      }
      if (found == null) throw new IllegalStateException(
        "fromBean(...).viaConstructor() requires a constructor with " +
          arity +
          " parameters on " +
          cls.getName() +
          " (parameters are matched positionally to record components, in component order)."
      );
      found.setAccessible(true);
      this.ctor = found;
      this.paramNames = resolveParamNames(found);
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

    @Override
    public P construct(final String[] names, final Function<String, Object> valueByName) {
      // Prefer matching by parameter name (order-independent) when names are present; otherwise
      // fall
      // back to positional, assuming the constructor's parameter order matches the components'
      // order.
      final var keys = paramNames != null ? paramNames : names;
      final var args = new Object[keys.length];
      for (var i = 0; i < keys.length; i++) args[i] = valueByName.apply(keys[i]);
      try {
        return ctor.newInstance(args);
      } catch (final ReflectiveOperationException e) {
        throw new RuntimeException("Failed to construct " + cls.getName() + " via its constructor", e);
      }
    }
  }

  /**
   * {@link BeanWriter} backed by the builder pattern. At construction it requires a static {@code
   * builder()} factory and a {@code build()} method on the returned builder type. {@code construct}
   * calls {@code builder()}, then for each name invokes a single-arg setter — matched by exact
   * name, {@code setX}, or {@code withX} (lazily resolved and cached per name) — and finally {@code
   * build()}.
   */
  static final class BuilderWriter<P> implements BeanWriter<P> {

    private final Class<P> cls;
    private final Method builderFactory;
    private final Class<?> builderType;
    private final Method buildMethod;
    private final Map<String, Method> setters = new ConcurrentHashMap<>();

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
        "fromBean(...).viaBuilder() requires a static builder() method on " + cls.getName()
      );
      this.builderFactory = factory;
      this.builderFactory.setAccessible(true);
      this.builderType = factory.getReturnType();
      try {
        this.buildMethod = builderType.getMethod("build");
      } catch (final NoSuchMethodException e) {
        throw new IllegalStateException(
          "fromBean(...).viaBuilder(): builder " + builderType.getName() + " has no build() method",
          e
        );
      }
      this.buildMethod.setAccessible(true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public P construct(final String[] names, final Function<String, Object> valueByName) {
      try {
        final var builder = builderFactory.invoke(null);
        for (final var name : names) setterFor(name).invoke(builder, valueByName.apply(name));
        return (P) buildMethod.invoke(builder);
      } catch (final ReflectiveOperationException e) {
        throw new RuntimeException("Failed to build " + cls.getName() + " via its builder", e);
      }
    }

    private Method setterFor(final String name) {
      return setters.computeIfAbsent(name, n -> {
        final var set = "set" + capitalize(n);
        final var with = "with" + capitalize(n);
        for (final var m : builderType.getMethods()) {
          if (m.getParameterCount() != 1) continue;
          final var mn = m.getName();
          if (mn.equals(n) || mn.equals(set) || mn.equals(with)) {
            m.setAccessible(true);
            return m;
          }
        }
        throw new IllegalArgumentException(
          "viaBuilder: no single-argument builder method for '" + n + "' on " + builderType.getName()
        );
      });
    }
  }

  /**
   * {@link BeanWriter} backed by a no-arg constructor plus public {@code setX(value)} setters,
   * matched by name. Public-member only — unlike {@link FieldsWriter} it needs no {@code opens}
   * directive under JPMS. The natural rebuild strategy for classic JavaBeans / Hibernate entities.
   */
  static final class SettersWriter<P> implements BeanWriter<P> {

    private final Class<P> cls;
    private final Constructor<P> ctor;
    private final Map<String, Method> setters = new ConcurrentHashMap<>();

    SettersWriter(final Class<P> cls) {
      this.cls = cls;
      try {
        this.ctor = cls.getDeclaredConstructor();
      } catch (final NoSuchMethodException e) {
        throw new IllegalStateException("viaSetters() requires a no-arg constructor on " + cls.getName(), e);
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
        try {
          setterFor(name).invoke(pojo, valueByName.apply(name));
        } catch (final ReflectiveOperationException e) {
          throw new RuntimeException("Failed to set '" + name + "' on " + cls.getName(), e);
        }
      }
      return pojo;
    }

    private Method setterFor(final String name) {
      return setters.computeIfAbsent(name, n -> {
        final var set = "set" + capitalize(n);
        for (final var m : cls.getMethods()) {
          if (m.getParameterCount() == 1 && m.getName().equals(set)) {
            m.setAccessible(true);
            return m;
          }
        }
        throw new IllegalArgumentException("viaSetters: no setter '" + set + "' on " + cls.getName());
      });
    }
  }
}
