package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.matchedNames;
import static io.github.eschizoid.telescope.Telescope.methodNameOf;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Intermediate of {@link BeanFrom#to(Class)}. Each terminal chooses how the reverse direction
 * (record &rarr; POJO) reconstructs the POJO.
 */
public final class BeanTo<P, R extends Record> {

  private final Class<P> pojoClass;
  private final Class<R> recordClass;
  private final Map<String, Function<Object, Object>> forwardVia = new LinkedHashMap<>();
  private final Map<String, Function<Object, Object>> backwardVia = new LinkedHashMap<>();
  private final Map<String, String> componentToProperty = new LinkedHashMap<>();

  BeanTo(final Class<P> pojoClass, final Class<R> recordClass) {
    this.pojoClass = pojoClass;
    this.recordClass = recordClass;
  }

  /**
   * Map a record component to a differently-named POJO property: the record's {@code component} is
   * read from (and written back to) the POJO's {@code property}. Types must match. Components not
   * named here are matched to a same-named getter/setter as usual.
   *
   * <pre>{@code
   * Telescope.fromBean(LegacyUser.class).to(UserRecord.class)
   *     .rename(UserRecord::fullName, LegacyUser::getName)  // fullName <-> name
   *     .viaConstructor();
   * }</pre>
   */
  public <X> BeanTo<P, R> rename(final Accessor<R, X> component, final Accessor<P, X> property) {
    componentToProperty.put(methodNameOf(component), Beans.propertyOf(methodNameOf(property)));
    return this;
  }

  /**
   * Convert a nested sub-object component through another bridge instead of copying it as-is. Use
   * for a record component whose POJO counterpart is a different (sub-POJO) type that has its own
   * {@code fromBean}/{@code mapBean}/{@code from-to-using} bridge.
   *
   * <pre>{@code
   * final var addr = Telescope.fromBean(AddrPojo.class).to(AddrRecord.class).viaFields();
   * final var user = Telescope.fromBean(UserPojo.class).to(UserRecord.class)
   *     .via(UserRecord::address, addr)   // AddrPojo <-> AddrRecord at the 'address' component
   *     .viaFields();
   * }</pre>
   */
  public <X> BeanTo<P, R> via(final Accessor<R, X> targetAccessor, final Telescope<?, X> subBridge) {
    final var name = methodNameOf(targetAccessor);
    final var iso = isoOf(subBridge);
    forwardVia.put(name, iso::to);
    backwardVia.put(name, iso::from);
    return this;
  }

  /**
   * Convert each element of a collection component through an element bridge — the fix for nested
   * collections (a record's {@code List<SubRecord>} component whose POJO counterpart is a {@code
   * List<SubPojo>}). Without this, the whole-object bridge copies the list reference shallowly and
   * type erasure lets {@code List<SubPojo>} sit in a {@code List<SubRecord>} slot (a latent {@code
   * ClassCastException}); {@code viaEach} maps the element bridge over the list both ways.
   *
   * <pre>{@code
   * final var order = Telescope.fromBean(OrderPojo.class).to(OrderRecord.class).viaConstructor();
   * final var cart  = Telescope.fromBean(CartPojo.class).to(CartRecord.class)
   *     .viaEach(CartRecord::orders, order)  // List<OrderPojo> <-> List<OrderRecord>
   *     .viaFields();
   * }</pre>
   */
  public <X> BeanTo<P, R> viaEach(
    final Accessor<R, ? extends Iterable<X>> targetAccessor,
    final Telescope<?, X> elementBridge
  ) {
    final var name = methodNameOf(targetAccessor);
    final var iso = isoOf(elementBridge);
    forwardVia.put(name, list -> mapList(list, iso::to));
    backwardVia.put(name, list -> mapList(list, iso::from));
    return this;
  }

  /**
   * Reverse via a no-arg constructor + reflective field injection (no setters required). Use this
   * when the POJO has a default constructor but no all-args constructor or builder — the bridge
   * instantiates it and writes each field directly.
   *
   * <p><strong>JPMS caveat:</strong> reflective field injection requires the POJO's package to be
   * open to this module. If the POJO lives in another module, add {@code opens <pkg> to
   * io.github.eschizoid.telescope;} to that module's {@code module-info.java}. The other two
   * strategies, which go through public constructors/builders, don't need this.
   *
   * <pre>{@code
   * final var bridge = Telescope.fromBean(LegacyUser.class).to(UserRecord.class).viaFields();
   * }</pre>
   */
  public Telescope<P, R> viaFields() {
    return bridge(Beans.fieldsWriter(pojoClass));
  }

  /**
   * Reverse via an all-args constructor matched <em>positionally</em>: the constructor must take
   * its parameters in the record's component order. Use this when the POJO is immutable (or exposes
   * a single canonical constructor) and its constructor argument order lines up with the record's
   * components.
   *
   * <pre>{@code
   * final var bridge = Telescope.fromBean(LegacyUser.class).to(UserRecord.class).viaConstructor();
   * }</pre>
   */
  public Telescope<P, R> viaConstructor() {
    return bridge(Beans.constructorWriter(pojoClass, Records.componentNames(recordClass).length));
  }

  /**
   * Reverse via a static {@code builder()} method, a setter per component, then {@code build()}.
   * Use this when the POJO follows the builder pattern (one setter per property named for the
   * component, plus a terminal {@code build()}).
   *
   * <pre>{@code
   * final var bridge = Telescope.fromBean(LegacyUser.class).to(UserRecord.class).viaBuilder();
   * }</pre>
   */
  public Telescope<P, R> viaBuilder() {
    return bridge(Beans.builderWriter(pojoClass));
  }

  private Telescope<P, R> bridge(final Beans.BeanWriter<P> writer) {
    final var names = Records.componentNames(recordClass);
    matchedNames(names, componentToProperty, pojoClass, false, (comp, prop) ->
      new IllegalArgumentException(
        "fromBean: record component '" +
          comp +
          "' on " +
          recordClass.getSimpleName() +
          " has no matching getter '" +
          prop +
          "' on " +
          pojoClass.getSimpleName() +
          " (matched by exact name; rename it with .rename(...) or add a getter)."
      )
    );
    // The POJO property each record component reads from / writes to (identity unless renamed).
    final var beanKeys = new String[names.length];
    for (var i = 0; i < names.length; i++) beanKeys[i] = componentToProperty.getOrDefault(names[i], names[i]);
    final var propertyToComponent = new LinkedHashMap<String, String>();
    componentToProperty.forEach((comp, prop) -> propertyToComponent.put(prop, comp));
    final Function<P, R> forward = pojo ->
      Records.construct(recordClass, comp -> {
        final var raw = Beans.readProperty(pojo, componentToProperty.getOrDefault(comp, comp));
        final var conv = forwardVia.get(comp);
        return conv == null ? raw : conv.apply(raw);
      });
    final Function<R, P> backward = record ->
      writer.construct(beanKeys, prop -> {
        final var comp = propertyToComponent.getOrDefault(prop, prop);
        final var raw = Records.read(record, comp);
        final var conv = backwardVia.get(comp);
        return conv == null ? raw : conv.apply(raw);
      });
    return new Telescope<>(Iso.of(forward, backward));
  }

  @SuppressWarnings("unchecked")
  private static Iso<Object, Object> isoOf(final Telescope<?, ?> bridge) {
    if (bridge.optic instanceof Iso<?, ?> iso) return (Iso<Object, Object>) iso;
    throw new IllegalArgumentException(
      "via/viaEach requires a bidirectional bridge (fromBean / mapBean / from-to-using); got a " +
        bridge.optic.getClass().getSimpleName()
    );
  }

  private static List<Object> mapList(final Object list, final Function<Object, Object> fn) {
    if (!(list instanceof Iterable<?> it)) throw new IllegalArgumentException(
      "viaEach expects a collection component but got " + (list == null ? "null" : list.getClass().getName())
    );
    final var out = new ArrayList<Object>();
    for (final var e : it) out.add(fn.apply(e));
    return List.copyOf(out);
  }
}
