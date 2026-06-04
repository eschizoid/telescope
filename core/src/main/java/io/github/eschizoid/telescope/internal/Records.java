package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Lens;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Reflection-based machinery for building {@link Lens}es over record components by name. Backs the
 * field navigation of {@link io.github.eschizoid.telescope.Telescope}.
 *
 * <p>State is a single per-record-class {@code RecordInfo} cache: the record's {@link
 * RecordComponent} array (in canonical-constructor order) and the canonical {@link Constructor},
 * resolved once via reflection and stored in a {@link ConcurrentHashMap}. On the first lookup for a
 * class, accessors and the constructor are {@code setAccessible(true)} so later reads and rebuilds
 * skip access checks; every subsequent operation is constant-time.
 *
 * <p>Records only. Mutating helpers reject any non-record argument with {@code "Not a record"}, and
 * {@code RecordInfo.of} does the same when a class is first cached.
 */
public final class Records {

  private Records() {}

  private static final Map<Class<?>, RecordInfo> CACHE = new ConcurrentHashMap<>();

  /**
   * A {@link Lens} over a record component, identified by name. The {@code get} reads the
   * component; {@code set}/{@code modify} return a copy of the record with that one component
   * replaced. Backs {@link io.github.eschizoid.telescope.Telescope}'s {@code .field(String)}
   * overload.
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
   * Return a copy of {@code source} with one component replaced by {@code value}; all other
   * components carry over. Returns {@code null} for a {@code null} source; throws "Not a record"
   * for a non-record, "No field" for an unknown name.
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
    try {
      return (R) info.ctor().newInstance(args);
    } catch (final ReflectiveOperationException e) {
      throw new RuntimeException("Failed to construct " + recordClass.getSimpleName(), e);
    }
  }

  private static Object readField(final Object source, final String fieldName) {
    if (source == null) return null;
    final var info = info(source.getClass());
    final var idx = info.indexOf(fieldName);
    if (idx < 0) throw noField(fieldName, source.getClass());
    try {
      return info.components()[idx].getAccessor().invoke(source);
    } catch (final ReflectiveOperationException e) {
      throw new RuntimeException("Failed to read field " + fieldName, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static <S> S updateField(final S source, final String fieldName, final Function<Object, Object> fn) {
    if (source == null) return null;
    final var cls = source.getClass();
    if (!cls.isRecord()) throw new IllegalArgumentException("Not a record: " + cls.getName());
    final var info = info(cls);
    final var idx = info.indexOf(fieldName);
    if (idx < 0) throw noField(fieldName, cls);
    final var args = new Object[info.components().length];
    try {
      for (var i = 0; i < args.length; i++) {
        final var current = info.components()[i].getAccessor().invoke(source);
        args[i] = (i == idx) ? fn.apply(current) : current;
      }
      return (S) info.ctor().newInstance(args);
    } catch (final ReflectiveOperationException e) {
      throw new RuntimeException("Failed to rebuild " + cls.getSimpleName(), e);
    }
  }

  private static RecordInfo info(final Class<?> cls) {
    return CACHE.computeIfAbsent(cls, RecordInfo::of);
  }

  private static IllegalArgumentException noField(final String fieldName, final Class<?> cls) {
    return new IllegalArgumentException("No field '" + fieldName + "' on " + cls.getName());
  }

  /**
   * Cached per-class reflection: the component array (canonical order) and the canonical
   * constructor, both made accessible once at {@link #of(Class)} time. {@code indexOf} maps a
   * component name to its position; the cache lives in {@link #CACHE}.
   */
  private record RecordInfo(RecordComponent[] components, Constructor<?> ctor) {
    static RecordInfo of(final Class<?> cls) {
      if (!cls.isRecord()) throw new IllegalArgumentException("Not a record: " + cls.getName());
      final var comps = cls.getRecordComponents();
      final var paramTypes = Arrays.stream(comps).map(RecordComponent::getType).toArray(Class<?>[]::new);
      try {
        final var ctor = cls.getDeclaredConstructor(paramTypes);
        ctor.setAccessible(true);
        for (final var c : comps) c.getAccessor().setAccessible(true);
        return new RecordInfo(comps, ctor);
      } catch (final NoSuchMethodException e) {
        throw new IllegalStateException("Cannot find canonical constructor for " + cls.getName(), e);
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
