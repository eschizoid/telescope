package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Lens;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes through a mapping row's path that steps through properties alone ({@link
 * Telescope#stepsThroughProperties}), the shape the generated bridge compiles too, so both paths
 * give one result.
 *
 * <p>A read gives the leaf's value, or null when any object the path steps through is null. A write
 * rebuilds each object the path steps through, root first, with the next one replaced, through the
 * class each property declares. An object that is null is built when the value written through it
 * is not null, and left null when it is, so a null read back writes nothing: every property the
 * path does not name takes its null value, which is null for a reference and the type's default
 * for a primitive member of a record, while a bean's write strategy passes the default to a
 * constructor or a builder and leaves a primitive setter unwritten.
 *
 * <p>A null written into a primitive member follows the same rule: a record and a bean's
 * constructor or builder take the default, a setter is not called.
 */
final class PathWrites {

  private PathWrites() {}

  /**
   * A path of {@code hops} names, read from the class at each level: {@code levels[i]} is the class
   * the {@code i}-th property is read on, the root class first, and each later one the class the
   * property before it declares.
   */
  record Plan(String[] names, Class<?>[] levels, List<Lens<Object, Object>> reads) {}

  /**
   * The plan of {@code path} started at {@code root}, or null when the path does not step through
   * properties alone.
   */
  static Plan plan(final Telescope<?, ?> path, final Class<?> root) {
    if (!path.stepsThroughProperties()) return null;
    final List<Fusion.Hop> hops = path.hops;
    final var names = new String[hops.size()];
    final var levels = new Class<?>[hops.size()];
    final var reads = new ArrayList<Lens<Object, Object>>(hops.size());
    var at = root;
    for (var i = 0; i < hops.size(); i++) {
      names[i] = hops.get(i).component();
      levels[i] = at;
      // A field hop reads through the lens its accessor built, which reads a method that names no
      // bean property, such as an interface's label(), as the accessor does.
      if (!(hops.get(i).segment() instanceof Lens<Object, Object> lens)) return null;
      reads.add(lens);
      if (i + 1 < hops.size()) at = declaredClass(Reflective.of(at).genericType(at, names[i]));
    }
    return new Plan(names, levels, List.copyOf(reads));
  }

  /**
   * The refusal of a write through {@code plan} that would have to build an object no write
   * strategy builds, named for the pair of {@code source} and {@code target}; or null.
   */
  static String unbuildable(final Plan plan, final String source, final String target) {
    for (var i = 1; i < plan.levels.length; i++) {
      final var level = plan.levels[i];
      if (level.isRecord()) continue;
      final var builds =
        !level.isPrimitive() && !level.isArray() && !level.isEnum() && Reflective.BEANS.writerFor(level) != null;
      if (!builds) return PairingMessages.pathIntermediateUnbuildable(
        source,
        target,
        String.join(".", plan.names),
        PairingMessages.className(level)
      );
    }
    return null;
  }

  /** The leaf {@code plan} reads on {@code root}, or null when an object on the way is null. */
  static Object read(final Plan plan, final Object root) {
    var at = root;
    for (final var read : plan.reads) {
      if (at == null) return null;
      at = read.get(at);
    }
    return at;
  }

  /**
   * {@code root}, rebuilt through {@code rootRefl}, with {@code value} written at the leaf of {@code
   * plan}.
   */
  static Object write(final Plan plan, final Object root, final Object value, final Reflective rootRefl) {
    return writeAt(plan, 0, root, value, rootRefl);
  }

  private static Object writeAt(
    final Plan plan,
    final int level,
    final Object current,
    final Object value,
    final Reflective refl
  ) {
    if (current == null && value == null) return null;
    final var cls = plan.levels[level];
    final var name = plan.names[level];
    final Object next;
    if (level == plan.names.length - 1) {
      next = value;
    } else {
      final var child = current == null ? null : plan.reads.get(level).get(current);
      next = writeAt(plan, level + 1, child, value, Reflective.of(plan.levels[level + 1]));
    }
    return refl.construct(cls, property -> {
      if (property.equals(name)) return next == null ? nullValue(cls, property) : next;
      return current == null ? nullValue(cls, property) : refl.read(current, property);
    });
  }

  /**
   * The value a member of {@code cls} takes where nothing is written into it: the default of a
   * primitive record component, and null otherwise, which a bean's write strategy turns into the
   * default for a constructor or a builder and into no call for a setter.
   */
  static Object nullValue(final Class<?> cls, final String property) {
    if (!cls.isRecord()) return null;
    return Reflective.RECORDS.genericType(cls, property) instanceof Class<?> type && type.isPrimitive()
      ? Placeholders.primitiveDefault(type)
      : null;
  }

  /** The class {@code type} names, its raw class or the erasure of a variable's bound. */
  private static Class<?> declaredClass(final Type type) {
    if (type instanceof Class<?> c) return c;
    if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) return c;
    if (type instanceof TypeVariable<?> v && v.getBounds().length > 0) return declaredClass(v.getBounds()[0]);
    return Object.class;
  }
}
