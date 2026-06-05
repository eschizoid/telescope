package io.github.eschizoid.telescope.internal;

import java.lang.reflect.Type;
import java.util.function.Function;

/**
 * The uniform reflective interface that {@link io.github.eschizoid.telescope.mapping.DeepMap
 * DeepMap} drives — abstracts over "this side is a record" vs "this side is a bean" so the
 * recursive resolver doesn't have to know. Per-side dispatch via {@link #of(Class)} lets a single
 * deep-mapping call mix and match records and POJOs at any depth: the source side of a given pair
 * uses one {@code Reflective}, the target side another, chosen independently from the pair's
 * classes.
 *
 * <p>Two implementations:
 *
 * <ul>
 *   <li>{@link #RECORDS} — backed by {@link Records}. Canonical-constructor rebuild, identity name
 *       normalization (record components are already named the user-visible way).
 *   <li>{@link #BEANS} — backed by {@link Beans}. Auto-detected write strategy ({@code builder} →
 *       all-args ctor → no-arg + setters → fields), {@code getX/isX} stripped to a property name.
 * </ul>
 *
 * <p>The lattice-first principle holds: {@link io.github.eschizoid.telescope.mapping.DeepMap
 * DeepMap} composes {@link io.github.eschizoid.telescope.internal.optics.Iso Iso}s; this interface
 * only handles "how do I read one component value" and "how do I rebuild one object from a
 * name-keyed function." No bidirectional plumbing lives here.
 */
public interface Reflective {
  /** Component / property names in declaration order. */
  String[] names(Class<?> cls);

  /** Generic type of the named component / property (for container shape detection). */
  Type genericType(Class<?> cls, String name);

  /** Read a value by name. */
  Object read(Object value, String name);

  /** Construct a fresh instance by reading each component's value from the function. */
  Object construct(Class<?> cls, Function<String, Object> valueByName);

  /**
   * Translate a raw method name from an {@code Accessor} (recovered via {@code SerializedLambda})
   * into the component / property name DeepMap uses for lookups. For records: identity. For beans:
   * strip {@code get} / {@code is} prefix and decapitalize.
   */
  String normalize(String rawMethodName);

  /**
   * Pick the right reflective for {@code cls}: records → {@link #RECORDS}; everything else → {@link
   * #BEANS}.
   */
  static Reflective of(final Class<?> cls) {
    return cls.isRecord() ? RECORDS : BEANS;
  }

  Reflective RECORDS = new Reflective() {
    @Override
    public String[] names(final Class<?> cls) {
      return Records.componentNames(cls);
    }

    @Override
    public Type genericType(final Class<?> cls, final String name) {
      return Records.componentType(cls, name);
    }

    @Override
    public Object read(final Object value, final String name) {
      return Records.read(value, name);
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Object construct(final Class<?> cls, final Function<String, Object> valueByName) {
      return Records.construct((Class) cls, valueByName);
    }

    @Override
    public String normalize(final String rawMethodName) {
      return rawMethodName;
    }
  };

  Reflective BEANS = new Reflective() {
    @Override
    public String[] names(final Class<?> cls) {
      return Beans.propertyNames(cls);
    }

    @Override
    public Type genericType(final Class<?> cls, final String name) {
      return Beans.propertyType(cls, name);
    }

    @Override
    public Object read(final Object value, final String name) {
      return Beans.readProperty(value, name);
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Object construct(final Class<?> cls, final Function<String, Object> valueByName) {
      final var writer = Beans.autoWriter((Class) cls);
      return writer.construct(Beans.propertyNames(cls), valueByName);
    }

    @Override
    public String normalize(final String rawMethodName) {
      return Beans.propertyOf(rawMethodName);
    }
  };
}
