package com.github.eschizoid.telescope;

import com.github.eschizoid.telescope.internal.Beans;
import com.github.eschizoid.telescope.internal.Records;
import com.github.eschizoid.telescope.internal.optics.Iso;
import com.github.eschizoid.telescope.internal.optics.Lens;
import com.github.eschizoid.telescope.internal.optics.Prism;
import com.github.eschizoid.telescope.internal.optics.Traversal;
import com.github.eschizoid.telescope.internal.optics.collections.Traversals;
import com.github.eschizoid.telescope.internal.optics.instances.CompletableFutureK;
import com.github.eschizoid.telescope.internal.optics.instances.EitherK;
import com.github.eschizoid.telescope.internal.optics.instances.OptionalK;
import com.github.eschizoid.telescope.internal.optics.instances.ValidatedK;
import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Deep-copy DSL for Java records.
 *
 * <p>One type. Build a telescope by chaining {@code .field(...)} / {@code .each(...)} / {@code
 * .as(...)} / {@code .filter(...)}; then call {@code read}, {@code find}, {@code toList}, {@code
 * count}, {@code set}, or {@code update}.
 *
 * <pre>{@code
 * Telescope.of(Company.class)
 *     .each(Company::departments)
 *     .each(Department::teams)
 *     .each(Team::users)
 *     .field(User::email)
 *     .update(company, String::toLowerCase);
 * }</pre>
 *
 * <h2>Architecture</h2>
 *
 * <p>Internally, {@code Telescope<S, A>} wraps a {@link Traversal} from {@code
 * com.github.eschizoid.telescope.internal.optics}. Each navigation method builds the appropriate
 * optic ({@link Lens} for fields, {@link Prism} for sealed cases, {@link Traversal} for
 * collections) and composes it via the lattice's {@code .then(...)} rules. Operations ({@code
 * read}, {@code update}, {@code set}, {@code toList}) delegate to the underlying optic. Users never
 * see those types directly.
 */
public final class Telescope<S, A> {

  /**
   * Serializable functional interface for the method references that drive field navigation ({@code
   * User::name}, {@code Team::users}, etc.). Exists because the JVM only generates a {@link
   * Serializable} method reference when the target functional interface declares {@code
   * Serializable} — and we need that to recover the impl method name at runtime via {@link
   * java.lang.invoke.SerializedLambda}.
   *
   * <p>Users rarely type this name; method references implicitly convert. It appears in IDE
   * autocomplete on {@link #field}, {@link #each(Accessor)}, {@link #eachValue}, and {@link
   * #whenPresent}.
   */
  @FunctionalInterface
  public interface Accessor<A, B> extends Function<A, B>, Serializable {}

  private final Traversal<S, A> optic;
  // How accessor-based navigation (field/each/eachValue/whenPresent) turns a method reference into
  // a field Lens: records read/rebuild via the canonical constructor, beans via getters +
  // rebuild-via-strategy (see ofBean). Propagated to derived telescopes.
  private final FieldOptics fieldOptics;

  private Telescope(final Traversal<S, A> optic) {
    this(optic, RecordFieldOptics.INSTANCE);
  }

  private Telescope(final Traversal<S, A> optic, final FieldOptics fieldOptics) {
    this.optic = optic;
    this.fieldOptics = fieldOptics;
  }

  /**
   * Start a telescope at the given root type. Backed by an identity {@link Iso}. The {@code
   * rootType} argument exists purely for type inference; it's not stored or consulted at runtime.
   * This is the usual entry point; from here, chain navigation methods to descend into the
   * structure.
   *
   * <pre>{@code
   * final var emails = Telescope.of(Company.class)
   *     .each(Company::departments)
   *     .each(Department::teams)
   *     .each(Team::users)
   *     .field(User::email);     // Telescope<Company, String>
   * }</pre>
   *
   * <p>Field navigation works on records only; for a JavaBeans-style POJO root, bridge it to a
   * record first with {@link #fromBean(Class)} (or build a hand-rolled focus with {@link #lens}).
   *
   * @see #lens
   * @see #fromBean(Class)
   */
  public static <S> Telescope<S, S> of(final Class<S> rootType) {
    return new Telescope<>(Iso.identity());
  }

  /**
   * Start a <em>native POJO</em> telescope. Unlike {@link #of(Class)} (records only), the resulting
   * telescope navigates JavaBeans-style POJOs directly: {@code .field(Pojo::getX)} reads via the
   * getter, and {@code set}/{@code update} rebuild the POJO immutably with that one property
   * changed — using a write strategy auto-detected per type (static {@code builder()} &rarr; no-arg
   * constructor with setters &rarr; field injection). Deep paths and {@code .each(...)} compose
   * like records, rebuilding the POJO at each level.
   *
   * <pre>{@code
   * Telescope.ofBean(LegacyUser.class)
   *     .field(LegacyUser::getAddress)
   *     .field(Address::getCity)
   *     .update(user, String::toUpperCase);     // new LegacyUser, rebuilt via strategy
   * }</pre>
   *
   * <p>Cost: each level rebuilds the whole POJO via reflection + strategy (slower than the record
   * canonical-constructor copy), and field injection needs an {@code opens} directive under JPMS.
   * For the reflection-free fast path, annotate the POJO with {@link
   * com.github.eschizoid.telescope.annotations.BeanFocus}.
   *
   * <p>It never mutates, but — like all of telescope — it rebuilds only the <em>spine</em> (the
   * path to the changed field) and shares references to untouched off-path subtrees. With records
   * that is always safe; with mutable POJOs the new and old object share those sub-objects, so
   * treat the shared parts as effectively immutable (don't mutate them afterward). For
   * POJO&harr;record or POJO&harr;POJO <em>conversion</em>, use {@link #fromBean(Class)} / {@link
   * #mapBean(Class)}.
   */
  public static <P> Telescope<P, P> ofBean(final Class<P> pojoClass) {
    return new Telescope<>(Iso.identity(), BeanFieldOptics.INSTANCE);
  }

  /**
   * Build a single-focus telescope directly from a getter and a setter, no reflection. This is the
   * factory used by {@link com.github.eschizoid.telescope.annotations.Focus}-generated {@code
   * *Focus} classes; it's also useful when you want a typed accessor for a non-record type or want
   * to skip the reflection cost on a hot path.
   *
   * <p>Caller is responsible for the lens laws: {@code set(s, get(s)).equals(s)} (round-trip),
   * {@code get(set(s, a)).equals(a)} (set-get), and {@code set(set(s, a1), a2).equals(set(s, a2))}
   * (set-set).
   *
   * <pre>{@code
   * // Hand-rolled, no reflection:
   * final var name = Telescope.lens(User::name, (u, n) -> new User(n, u.age()));
   * }</pre>
   *
   * @see com.github.eschizoid.telescope.annotations.Focus
   * @see #of(Class)
   */
  public static <S, A> Telescope<S, A> lens(
    final Function<? super S, ? extends A> getter,
    final BiFunction<? super S, ? super A, ? extends S> setter
  ) {
    return new Telescope<>(Lens.of(getter, setter));
  }

  /**
   * Begin a bidirectional type conversion. The fluent shape {@code from(A.class).to(B.class).using(
   * forward, backward)} produces a {@code Telescope<A, B>} backed by an {@link Iso} from the
   * internal lattice. The {@code Class} arguments exist purely for type inference; they're not
   * stored or consulted at runtime.
   *
   * <pre>{@code
   * final var userIso = Telescope.from(UserEntity.class).to(UserDto.class)
   *     .using(
   *         e -> new UserDto(e.id(), e.email(), e.name()),
   *         d -> new UserEntity(d.id(), d.email(), d.name()));
   *
   * UserDto dto = userIso.read(entity);                    // forward
   * userIso.update(entity, dto -> dto.withEmail(...));     // round-trip modify
   *
   * // composes into a longer telescope path:
   * Telescope.of(EntityPage.class)
   *     .each(EntityPage::items)
   *     .then(userIso)                  // ← Iso participates in the lattice
   *     .field(UserDto::email)
   *     .update(page, String::toLowerCase);
   * }</pre>
   *
   * <p>For a field-by-field declarative mapping between two records (no hand-written conversion
   * functions), use {@link #map(Class)}. To bridge a JavaBeans-style POJO to a record, use {@link
   * #fromBean(Class)}.
   *
   * @see #map(Class)
   * @see #fromBean(Class)
   */
  public static <A> From<A> from(final Class<A> source) {
    return new From<>();
  }

  /** Intermediate of {@link #from(Class)}. */
  public static final class From<A> {

    private From() {}

    public <B> To<A, B> to(final Class<B> target) {
      return new To<>();
    }
  }

  /** Intermediate of {@link From#to(Class)}. */
  public static final class To<A, B> {

    private To() {}

    /**
     * Supply both directions of the conversion. {@code forward} converts {@code A → B}; {@code
     * backward} must satisfy the iso laws ({@code from(to(a)).equals(a)} and {@code
     * to(from(b)).equals(b)} for the components involved). The resulting {@code Telescope<A, B>}
     * composes into longer paths via {@link #then}. See {@link #from(Class)} for a worked example.
     */
    public Telescope<A, B> using(
      final Function<? super A, ? extends B> forward,
      final Function<? super B, ? extends A> backward
    ) {
      return new Telescope<>(Iso.of(forward, backward));
    }
  }

  /**
   * Begin a bidirectional bridge between a JavaBeans-style POJO and a record. The forward direction
   * reads the POJO's properties via its getters and builds the record; the reverse rebuilds the
   * POJO via the strategy you pick at the terminal {@code .via*()} call. Fields are matched by name
   * (record component name &harr; bean property name). Reflection is used at runtime — for a
   * reflection-free, compile-checked equivalent, annotate the record with {@link
   * com.github.eschizoid.telescope.annotations.Bridge}.
   *
   * <pre>{@code
   * // POJO has a no-arg constructor; write each field reflectively (no setters needed):
   * final var bridge = Telescope.fromBean(LegacyUser.class).to(UserRecord.class).viaFields();
   *
   * // POJO has an all-args constructor whose parameters are in record-component order:
   * final var bridge = Telescope.fromBean(LegacyUser.class).to(UserRecord.class).viaConstructor();
   *
   * // POJO exposes a static builder():
   * final var bridge = Telescope.fromBean(LegacyUser.class).to(UserRecord.class).viaBuilder();
   *
   * UserRecord r = bridge.read(legacyUser);          // forward
   * LegacyUser back = bridge.set(legacyUser, r);     // reverse
   *
   * // composes into a longer path, just like from/to/using:
   * Telescope.of(Page.class).each(Page::items).then(bridge).field(UserRecord::email)
   *     .update(page, String::toLowerCase);
   * }</pre>
   *
   * <p>Pick the reverse strategy by how the POJO can be reconstructed: {@link BeanTo#viaFields()}
   * (no-arg constructor, then reflective field injection — no setters needed), {@link
   * BeanTo#viaConstructor()} (one all-args constructor, parameters matched by name when the POJO is
   * compiled with {@code -parameters}, else positionally in record-component order), or {@link
   * BeanTo#viaBuilder()} (static {@code builder()} + setters + {@code build()}).
   *
   * <p>When a record component and its POJO property have different names, map them with {@link
   * BeanTo#rename(Accessor, Accessor)}. Properties the POJO has beyond the record's components are
   * left alone: the forward direction reads only what the record needs and the reverse writes only
   * the mapped properties, so an extra POJO field keeps its constructor/default value (a one-way
   * projection — that part of the POJO doesn't round-trip).
   *
   * <p>The bridge is a whole-object, <em>shallow</em> {@link Iso}: each record component type must
   * match the corresponding bean property type. It does not recurse, so it fits flat POJOs (or
   * POJOs holding records/values as leaves), not nested-POJO graphs. For a nested case, bridge each
   * level and compose.
   *
   * <p>The reflection-free, compile-checked counterpart is {@link
   * com.github.eschizoid.telescope.annotations.Bridge} — annotate the record with it to have the
   * bridge generated and validated at compile time instead of resolved reflectively here.
   *
   * <p>The target type parameter is bounded to {@link Record}; the POJO is unconstrained because
   * the three {@code via*()} strategies cover the shapes a non-record can take.
   *
   * @see com.github.eschizoid.telescope.annotations.Bridge
   */
  public static <P> BeanFrom<P> fromBean(final Class<P> pojoClass) {
    return new BeanFrom<>(pojoClass);
  }

  /** Intermediate of {@link #fromBean(Class)}. */
  public static final class BeanFrom<P> {

    private final Class<P> pojoClass;

    private BeanFrom(final Class<P> pojoClass) {
      this.pojoClass = pojoClass;
    }

    public <R extends Record> BeanTo<P, R> to(final Class<R> recordClass) {
      return new BeanTo<>(pojoClass, recordClass);
    }
  }

  /**
   * Intermediate of {@link BeanFrom#to(Class)}. Each terminal chooses how the reverse direction
   * (record &rarr; POJO) reconstructs the POJO.
   */
  public static final class BeanTo<P, R extends Record> {

    private final Class<P> pojoClass;
    private final Class<R> recordClass;
    private final Map<String, Function<Object, Object>> forwardVia = new java.util.LinkedHashMap<>();
    private final Map<String, Function<Object, Object>> backwardVia = new java.util.LinkedHashMap<>();
    private final Map<String, String> componentToProperty = new java.util.LinkedHashMap<>();

    private BeanTo(final Class<P> pojoClass, final Class<R> recordClass) {
      this.pojoClass = pojoClass;
      this.recordClass = recordClass;
    }

    /**
     * Map a record component to a differently-named POJO property: the record's {@code component}
     * is read from (and written back to) the POJO's {@code property}. Types must match. Components
     * not named here are matched to a same-named getter/setter as usual.
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
     * List<SubPojo>}). Without this, the whole-object bridge copies the list reference shallowly
     * and type erasure lets {@code List<SubPojo>} sit in a {@code List<SubRecord>} slot (a latent
     * {@code ClassCastException}); {@code viaEach} maps the element bridge over the list both ways.
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
     * com.github.eschizoid.telescope;} to that module's {@code module-info.java}. The other two
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
     * its parameters in the record's component order. Use this when the POJO is immutable (or
     * exposes a single canonical constructor) and its constructor argument order lines up with the
     * record's components.
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
      final var propertyToComponent = new java.util.LinkedHashMap<String, String>();
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
      final var out = new java.util.ArrayList<Object>();
      for (final var e : it) out.add(fn.apply(e));
      return List.copyOf(out);
    }
  }

  /**
   * Begin a bidirectional POJO&harr;POJO conversion — the bean analog of {@link #map(Class)}.
   * {@code mapBean(A.class).to(B.class).build()} produces a {@code Telescope<A, B>} (an {@link
   * Iso}) that reads each side via getters and rebuilds the other via an auto-detected write
   * strategy (a static {@code builder()}, a no-arg constructor with setters, or field injection).
   * Properties are matched by name and the conversion is bijective — each property of one side
   * needs a same-named getter on the other.
   *
   * <pre>{@code
   * final var view = Telescope.mapBean(LegacyUser.class).to(UserView.class).build();
   * UserView v = view.read(legacyUser);
   * }</pre>
   *
   * <p>For POJO&harr;record use {@link #fromBean(Class)}; for record&harr;record use {@link
   * #map(Class)} or {@link #from(Class)}.
   */
  public static <A> MapBeanFrom<A> mapBean(final Class<A> source) {
    return new MapBeanFrom<>(source);
  }

  /** Intermediate of {@link #mapBean(Class)}. */
  public static final class MapBeanFrom<A> {

    private final Class<A> source;

    private MapBeanFrom(final Class<A> source) {
      this.source = source;
    }

    public <B> MapBeanTo<A, B> to(final Class<B> target) {
      return new MapBeanTo<>(source, target);
    }
  }

  /** Intermediate of {@link MapBeanFrom#to(Class)}. */
  public static final class MapBeanTo<A, B> {

    private final Class<A> source;
    private final Class<B> target;
    private final Map<String, String> sourceToTarget = new java.util.LinkedHashMap<>();
    private boolean ignoreUnmatched = false;

    private MapBeanTo(final Class<A> source, final Class<B> target) {
      this.source = source;
      this.target = target;
    }

    /**
     * Map a differently-named property across the boundary: {@code A}'s {@code from} property
     * supplies (and is supplied by) {@code B}'s {@code to} property. Types must match. Properties
     * not named here still match by name.
     *
     * <pre>{@code
     * Telescope.mapBean(LegacyUser.class).to(UserView.class)
     *     .rename(LegacyUser::getName, UserView::getFullName)
     *     .build();
     * }</pre>
     */
    public <X> MapBeanTo<A, B> rename(final Accessor<A, X> from, final Accessor<B, X> to) {
      sourceToTarget.put(Beans.propertyOf(methodNameOf(from)), Beans.propertyOf(methodNameOf(to)));
      return this;
    }

    /**
     * Drop the bijection requirement: a property with no counterpart on the other side is simply
     * not transferred (it keeps the rebuilt object's default). The result is lossy — a round-trip
     * won't restore the dropped fields.
     */
    public MapBeanTo<A, B> ignoreUnmatched() {
      this.ignoreUnmatched = true;
      return this;
    }

    /** Build the bidirectional {@code Telescope<A, B>}. */
    public Telescope<A, B> build() {
      final var targetToSource = new java.util.LinkedHashMap<String, String>();
      sourceToTarget.forEach((s, t) -> targetToSource.put(t, s));
      final var bKeys = matchedNames(Beans.propertyNames(target), targetToSource, source, ignoreUnmatched, (name, cp) ->
        mismatch(name, target, cp, source)
      ).toArray(String[]::new);
      final var aKeys = matchedNames(Beans.propertyNames(source), sourceToTarget, target, ignoreUnmatched, (name, cp) ->
        mismatch(name, source, cp, target)
      ).toArray(String[]::new);
      final var writerA = Beans.autoWriter(source);
      final var writerB = Beans.autoWriter(target);
      final Function<A, B> forward = a ->
        writerB.construct(bKeys, bProp -> Beans.readProperty(a, targetToSource.getOrDefault(bProp, bProp)));
      final Function<B, A> backward = b ->
        writerA.construct(aKeys, aProp -> Beans.readProperty(b, sourceToTarget.getOrDefault(aProp, aProp)));
      return new Telescope<>(Iso.of(forward, backward));
    }

    private static RuntimeException mismatch(
      final String name,
      final Class<?> owner,
      final String counterpart,
      final Class<?> other
    ) {
      return new IllegalArgumentException(
        "mapBean: property '" +
          name +
          "' on " +
          owner.getSimpleName() +
          " has no matching getter '" +
          counterpart +
          "' on " +
          other.getSimpleName() +
          " (rename it with .rename(...), or call .ignoreUnmatched() to drop it)."
      );
    }
  }

  /**
   * Begin a declarative field-by-field mapping between two records. Instead of writing the two
   * whole conversion functions by hand ({@link #from}), you declare per-field correspondences and
   * {@code build()} synthesizes the bidirectional conversion via reflection over both records'
   * canonical constructors.
   *
   * <pre>{@code
   * final var userMapper = Telescope.map(UserEntity.class).to(UserDto.class)
   *     .field(UserEntity::id).to(UserDto::id)
   *     .field(UserEntity::email).to(UserDto::email)
   *     .field(UserEntity::name).to(UserDto::fullName)   // rename across the boundary
   *     .build();                                        // Telescope<UserEntity, UserDto>
   *
   * UserDto dto = userMapper.read(entity);
   *
   * // Composes into longer paths — the conversion threads through as an Iso:
   * Telescope.of(EntityPage.class)
   *     .each(EntityPage::items)
   *     .then(userMapper)
   *     .field(UserDto::email)
   *     .update(page, String::toLowerCase);
   * }</pre>
   *
   * <p>Produces a bidirectional {@link Iso}-backed telescope, so every record component on both
   * sides must be mapped (a lossless round-trip needs a value for every constructor parameter in
   * both directions). Field types must match: {@code .field(A::x)} where {@code x} is a {@code
   * String} requires {@code .to(B::y)} where {@code y} is also a {@code String} — enforced at
   * compile time. For lossy or one-directional conversions, use {@link #from} with hand-written
   * functions instead.
   *
   * @see #from(Class)
   * @see #fromBean(Class)
   * @see MapBuilder#buildMapper()
   */
  public static <A> MapTo<A> map(final Class<A> source) {
    return new MapTo<>(source);
  }

  /** Intermediate of {@link #map(Class)}. */
  public static final class MapTo<A> {

    private final Class<A> source;

    private MapTo(final Class<A> source) {
      this.source = source;
    }

    /**
     * Name the target record; returns the {@link MapBuilder} that collects field correspondences.
     */
    public <B> MapBuilder<A, B> to(final Class<B> target) {
      return new MapBuilder<>(source, target);
    }
  }

  /**
   * Accumulates field correspondences for {@link #map(Class)}. {@link #build()} synthesizes a
   * bidirectional {@code Telescope<A, B>}; {@link #buildMapper()} additionally retains the field
   * links so the result supports {@link Mapper#patch}.
   */
  public static final class MapBuilder<A, B> {

    /** One field correspondence with (possibly identity) transforms in each direction. */
    private record Link(
      String sourceField,
      String targetField,
      Function<Object, Object> forward,
      Function<Object, Object> backward
    ) {}

    private static final Function<Object, Object> IDENTITY = x -> x;

    private final Class<A> source;
    private final Class<B> target;
    private final java.util.List<Link> links = new java.util.ArrayList<>();

    private MapBuilder(final Class<A> source, final Class<B> target) {
      this.source = source;
      this.target = target;
    }

    /**
     * Auto-map every target component whose name and type match a source component, leaving any
     * already-declared correspondences untouched. Only exact name matches — no fuzzy heuristics, no
     * nested traversal. Declare {@code .field(...).to(...)} explicitly for renames or transforms
     * (those override the auto-mapped link for the same target).
     *
     * <pre>{@code
     * // id + email map by name; only the renamed field is declared by hand:
     * final var userMapper = Telescope.map(UserEntity.class).to(UserDto.class)
     *     .field(UserEntity::name).to(UserDto::fullName)   // wins over auto for this target
     *     .auto()                                          // id, email
     *     .build();
     * }</pre>
     */
    public MapBuilder<A, B> auto() {
      final var sourceNames = java.util.Set.of(Records.componentNames(source));
      for (final var name : Records.componentNames(target)) {
        final var alreadyLinked = links.stream().anyMatch(l -> l.targetField().equals(name));
        if (!alreadyLinked && sourceNames.contains(name)) {
          links.add(new Link(name, name, IDENTITY, IDENTITY));
        }
      }
      return this;
    }

    /**
     * Declare the source side of a field correspondence; complete it on the returned {@link
     * FieldMapping} with {@link FieldMapping#to(Accessor) .to(...)} (same-typed), {@link
     * FieldMapping#to(Accessor, Function, Function) .to(..., fwd, bwd)} (typed transform), or
     * {@link FieldMapping#via .via(..., mapper)} (nested record).
     */
    public <X> FieldMapping<A, B, X> field(final Accessor<A, X> sourceGetter) {
      return new FieldMapping<>(this, methodNameOf(sourceGetter));
    }

    private MapBuilder<A, B> link(final Link link) {
      links.removeIf(l -> l.targetField().equals(link.targetField()));
      links.add(link);
      return this;
    }

    /**
     * Synthesize the bidirectional {@code Telescope<A, B>}. Throws {@link IllegalStateException} if
     * the mapping isn't a bijection (some component on either side is left unmapped). Use {@link
     * #buildMapper()} instead when you also want {@link Mapper#patch} or to nest the mapping via
     * {@link FieldMapping#via}.
     */
    public Telescope<A, B> build() {
      return new Telescope<>(iso());
    }

    /**
     * Synthesize a {@link Mapper} — the same bidirectional conversion as {@link #build()}, plus the
     * field links retained so it can do {@link Mapper#patch sparse patches}.
     */
    public Mapper<A, B> buildMapper() {
      return new Mapper<>(iso(), java.util.List.copyOf(links), source);
    }

    private Iso<A, B> iso() {
      final var byTarget = new java.util.LinkedHashMap<String, Link>();
      final var bySource = new java.util.LinkedHashMap<String, Link>();
      for (final var l : links) {
        byTarget.put(l.targetField(), l);
        bySource.put(l.sourceField(), l);
      }
      requireAllMapped(Records.componentNames(target), byTarget.keySet(), target, "target");
      requireAllMapped(Records.componentNames(source), bySource.keySet(), source, "source");

      final Function<A, B> forward = a ->
        Records.construct(target, t -> {
          final var l = byTarget.get(t);
          return l.forward().apply(Records.read(a, l.sourceField()));
        });
      final Function<B, A> backward = b ->
        Records.construct(source, s -> {
          final var l = bySource.get(s);
          return l.backward().apply(Records.read(b, l.targetField()));
        });
      return Iso.of(forward, backward);
    }

    private static void requireAllMapped(
      final String[] names,
      final java.util.Set<String> mapped,
      final Class<?> type,
      final String side
    ) {
      for (final var name : names) {
        if (!mapped.contains(name)) throw new IllegalStateException(
          "Mapping is not a bijection: " +
            side +
            " field '" +
            name +
            "' on " +
            type.getSimpleName() +
            " is unmapped. Every component on both sides must be mapped (try .auto() for same-name fields)."
        );
      }
    }
  }

  /**
   * Intermediate of {@link MapBuilder#field(Accessor)} — expects a {@code .to(...)} or {@code
   * .via(...)}.
   */
  public static final class FieldMapping<A, B, X> {

    private final MapBuilder<A, B> builder;
    private final String sourceField;

    private FieldMapping(final MapBuilder<A, B> builder, final String sourceField) {
      this.builder = builder;
      this.sourceField = sourceField;
    }

    /** Complete the correspondence with a same-typed target field. */
    public MapBuilder<A, B> to(final Accessor<B, X> targetGetter) {
      return builder.link(new MapBuilder.Link(sourceField, methodNameOf(targetGetter), x -> x, y -> y));
    }

    /**
     * Complete the correspondence with a target field of a different type, supplying both
     * directions of the conversion. Keeps the overall mapping a bijection (composition-safe).
     *
     * <pre>{@code
     * .field(UserEntity::createdAt).to(UserDto::createdAtIso, Instant::toString, Instant::parse)
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public <Y> MapBuilder<A, B> to(
      final Accessor<B, Y> targetGetter,
      final Function<? super X, ? extends Y> forward,
      final Function<? super Y, ? extends X> backward
    ) {
      return builder.link(
        new MapBuilder.Link(
          sourceField,
          methodNameOf(targetGetter),
          x -> forward.apply((X) x),
          y -> backward.apply((Y) y)
        )
      );
    }

    /**
     * Map a nested record field through another {@link Mapper}. The nested mapper supplies both
     * directions, so the correspondence stays bidirectional.
     *
     * <pre>{@code
     * .field(UserEntity::address).via(UserDto::address, addressMapper)
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public <Y> MapBuilder<A, B> via(final Accessor<B, Y> targetGetter, final Mapper<X, Y> nested) {
      return builder.link(
        new MapBuilder.Link(
          sourceField,
          methodNameOf(targetGetter),
          x -> nested.forward((X) x),
          y -> nested.backward((Y) y)
        )
      );
    }
  }

  /**
   * A bidirectional record mapper produced by {@link MapBuilder#buildMapper()}. Beyond the
   * conversion that {@link MapBuilder#build()} gives you, a {@code Mapper} retains the field links
   * so it can apply a sparse {@link #patch} and be nested inside another mapping via {@link
   * FieldMapping#via}.
   */
  public static final class Mapper<A, B> {

    private final Iso<A, B> iso;
    private final java.util.List<MapBuilder.Link> links;

    private Mapper(final Iso<A, B> iso, final java.util.List<MapBuilder.Link> links, final Class<A> sourceType) {
      this.iso = iso;
      this.links = links;
    }

    /**
     * Convert forward, {@code A → B}.
     *
     * <pre>{@code
     * final var mapper = Telescope.map(UserEntity.class).to(UserDto.class).auto().buildMapper();
     * final UserDto dto = mapper.read(entity);
     * }</pre>
     *
     * For the reverse direction, or to thread the conversion through a longer path, use {@link
     * #asTelescope()} (which exposes {@code set}/{@code update}/{@code then}); for a sparse
     * overlay, use {@link #patch}.
     */
    public B read(final A a) {
      return iso.to(a);
    }

    /**
     * The mapper as a composable {@code Telescope<A, B>}, for threading the conversion through
     * longer paths via {@link #then}.
     *
     * <pre>{@code
     * Telescope.of(EntityPage.class)
     *     .each(EntityPage::items)
     *     .then(userMapper.asTelescope())
     *     .field(UserDto::email)
     *     .update(page, String::toLowerCase);
     * }</pre>
     */
    public Telescope<A, B> asTelescope() {
      return new Telescope<>(iso);
    }

    /**
     * Sparse update: overlay the non-null fields of {@code patch} (a partially-populated target)
     * onto {@code base}, leaving the rest of {@code base} untouched. Each present target field is
     * run back through its link's reverse transform before being written to the source.
     *
     * <pre>{@code
     * // dtoPatch has a new email, null everything else — only the email changes on the entity:
     * UserEntity updated = userMapper.patch(entity, dtoPatch);
     * }</pre>
     */
    public A patch(final A base, final B patch) {
      var result = base;
      for (final var l : links) {
        final var targetValue = Records.read(patch, l.targetField());
        if (targetValue != null) {
          result = Records.with(result, l.sourceField(), l.backward().apply(targetValue));
        }
      }
      return result;
    }

    B forward(final A a) {
      return iso.to(a);
    }

    A backward(final B b) {
      return iso.from(b);
    }
  }

  /**
   * Descend into a record field via method reference. Backed by a {@link Lens}. The argument must
   * be a method reference to a record component accessor ({@code User::email}); a lambda ({@code u
   * -> u.email()}) is rejected at runtime because its synthetic name can't be recovered.
   *
   * <pre>{@code
   * final var email = Telescope.of(User.class).field(User::email);   // Telescope<User, String>
   * final User lower = email.update(user, String::toLowerCase);
   * }</pre>
   *
   * <p>For a field name only known at runtime, use {@link #field(String)} or {@link #field(String,
   * Class)}. Records only — see {@link #fromBean(Class)} for the POJO escape hatch.
   */
  public <B> Telescope<S, B> field(final Accessor<A, B> getter) {
    final Lens<A, B> lens = fieldOptics.lensFor(getter);
    return new Telescope<>(optic.then(lens), fieldOptics);
  }

  /**
   * Descend into a record field by name — for field names only known at runtime. The result type
   * {@code B} is inferred from the call site; {@link #field(String, Class)} lets you pin it with an
   * explicit witness instead. Prefer {@link #field(Accessor)} when the field is statically known.
   */
  public <B> Telescope<S, B> field(final String fieldName) {
    final Lens<A, B> fieldLens = Records.fieldLens(fieldName);
    return new Telescope<>(optic.then(fieldLens));
  }

  /**
   * Descend into a record field by name with an explicit field-type witness — the same shape as
   * {@link #from(Class)} / {@link From#to(Class)}. The {@code fieldType} argument exists purely for
   * type inference; it's not stored or consulted at runtime.
   *
   * <pre>{@code
   * // Now you don't need the leading type witness:
   * final var name = Telescope.of(User.class).field("name", String.class);
   * }</pre>
   */
  public <B> Telescope<S, B> field(final String fieldName, final Class<B> fieldType) {
    return field(fieldName);
  }

  /**
   * Descend into a container ({@code List}/{@code Set}/{@code Map} values/{@code Optional}) when
   * you already hold a {@code Telescope<S, SomeContainer>}. The element container is dispatched at
   * runtime. Prefer {@link #each(Accessor)} for the common case of "descend into a field +
   * iterate."
   *
   * <pre>{@code
   * // Already focused on the List<String> field; broadcast over its elements:
   * final var tags = Telescope.of(Post.class).field(Post::tags).<String>each();
   * final Post lower = tags.update(post, String::toLowerCase);
   * }</pre>
   */
  public <E> Telescope<S, E> each() {
    final Traversal<A, E> elements = Traversals.eachContainer();
    return new Telescope<>(optic.then(elements));
  }

  /**
   * Descend into a record field that holds an {@link Iterable} and broadcast over its elements.
   * Backed by a {@link Lens} composed with a {@link Traversal}.
   *
   * <pre>{@code
   * Telescope.of(Company.class)
   *     .each(Company::departments)   // Telescope<Company, Department>
   *     .each(Department::teams)      // Telescope<Company, Team>
   *     .each(Team::users)            // Telescope<Company, User>
   *     .field(User::email);
   * }</pre>
   */
  public <E> Telescope<S, E> each(final Accessor<A, ? extends Iterable<E>> getter) {
    final Traversal<Iterable<E>, E> elements = Traversals.eachContainer();
    final Lens<A, Iterable<E>> lens = fieldOptics.lensFor(getter);
    return new Telescope<>(optic.then(lens).then(elements), fieldOptics);
  }

  /**
   * Descend into a record field that holds a {@link Map} and broadcast over its values (keys are
   * left untouched). Backed by a {@link Lens} composed with a {@link Traversal} over the map
   * values.
   *
   * <pre>{@code
   * // Map<String, Account> balances — bump every account's balance:
   * Telescope.of(Ledger.class)
   *     .eachValue(Ledger::accounts)
   *     .field(Account::balance)
   *     .update(ledger, b -> b.add(BigDecimal.TEN));
   * }</pre>
   */
  public <K, V> Telescope<S, V> eachValue(final Accessor<A, ? extends Map<K, V>> getter) {
    final Traversal<Map<K, V>, V> values = Traversals.eachMapValue();
    final Lens<A, Map<K, V>> lens = fieldOptics.lensFor(getter);
    return new Telescope<>(optic.then(lens).then(values), fieldOptics);
  }

  /**
   * Descend into a record field that holds an {@link Optional}; no-op when empty. Backed by a
   * {@link Lens} composed with an Affine over Optional. Reads on an empty Optional yield nothing
   * ({@link #find} returns empty, {@link #exists} returns {@code false}); writes are skipped.
   *
   * <pre>{@code
   * // Optional<String> middleName — only touched when present:
   * Telescope.of(Person.class)
   *     .whenPresent(Person::middleName)
   *     .update(person, String::trim);
   * }</pre>
   */
  public <E> Telescope<S, E> whenPresent(final Accessor<A, ? extends Optional<E>> getter) {
    final Traversal<Optional<E>, E> present = Traversals.eachOptional();
    final Lens<A, Optional<E>> lens = fieldOptics.lensFor(getter);
    return new Telescope<>(optic.then(lens).then(present), fieldOptics);
  }

  /**
   * Narrow to a specific subtype (typically a sealed-type case). Backed by a {@link Prism}: the
   * path matches only where the focused value is actually an instance of {@code subType}, and skips
   * the rest (it does not throw on a mismatch).
   *
   * <pre>{@code
   * // shapes is a List<Shape>; bump radius only on the Circle cases:
   * Telescope.of(Canvas.class)
   *     .each(Canvas::shapes)
   *     .as(Circle.class)
   *     .field(Circle::radius)
   *     .update(canvas, r -> r * 2);
   * }</pre>
   */
  public <B extends A> Telescope<S, B> as(final Class<B> subType) {
    final Prism<A, B> prism = Prism.downcast(subType);
    return new Telescope<>(optic.then(prism));
  }

  /**
   * Restrict the path to elements matching the predicate. Delegates to {@link Traversal#filter}.
   * Non-matching elements are skipped by both reads and writes.
   *
   * <pre>{@code
   * // Only normalize emails for active users:
   * Telescope.of(Team.class)
   *     .each(Team::users)
   *     .filter(User::active)
   *     .field(User::email)
   *     .update(team, String::toLowerCase);
   * }</pre>
   */
  public Telescope<S, A> filter(final Predicate<? super A> predicate) {
    return new Telescope<>(optic.filter(predicate));
  }

  /**
   * Compose this telescope with another via the lattice's {@code .then}. Lets you build a path in
   * pieces and stitch them together, and is how reusable conversions ({@link #from}, {@link #map},
   * {@link #fromBean(Class)}) get threaded into a longer path.
   *
   * <pre>{@code
   * final var userEmail = Telescope.of(User.class).field(User::email);   // reusable tail
   * Telescope.of(Team.class).each(Team::users).then(userEmail)
   *     .update(team, String::toLowerCase);
   * }</pre>
   */
  public <B> Telescope<S, B> then(final Telescope<A, B> next) {
    return new Telescope<>(optic.then(next.optic));
  }

  /**
   * First focused value. Throws {@link NoSuchElementException} if the path resolves to nothing
   * (e.g. an empty collection, an absent {@link #whenPresent} Optional, or an {@link #as} narrowing
   * that didn't match).
   *
   * <pre>{@code
   * final String email = Telescope.of(User.class).field(User::email).read(user);
   * }</pre>
   *
   * <p>Use {@link #find} for the {@link Optional}-returning sibling that doesn't throw, {@link
   * #toList} for every focused value, or {@link #exists} / {@link #count} to test presence.
   */
  public A read(final S source) {
    return optic
      .getAll(source)
      .findFirst()
      .orElseThrow(() -> new NoSuchElementException("Telescope has no value in this source"));
  }

  /**
   * First focused value as {@link Optional}, empty when the path resolves to nothing. The
   * non-throwing sibling of {@link #read}.
   */
  public Optional<A> find(final S source) {
    return optic.getAll(source).findFirst();
  }

  /**
   * All focused values, in traversal order.
   *
   * <pre>{@code
   * final List<String> emails = Telescope.of(Company.class)
   *     .each(Company::departments).each(Department::teams).each(Team::users)
   *     .field(User::email)
   *     .toList(company);
   * }</pre>
   *
   * <p>See {@link #toListIndexed} to pair each value with its position.
   */
  public List<A> toList(final S source) {
    return optic.getAll(source).toList();
  }

  /**
   * All focused values, each paired with its 0-based position in traversal order as an {@link
   * Indexed}. Useful when a read cares about where an element sits (e.g. "the third match"). The
   * write-side counterpart is {@link #updateIndexed}.
   *
   * <pre>{@code
   * for (final var e : users.toListIndexed(team)) {
   *   System.out.println(e.index() + ": " + e.value());
   * }
   * }</pre>
   */
  public List<Indexed<A>> toListIndexed(final S source) {
    final var out = new java.util.ArrayList<Indexed<A>>();
    final var i = new int[] { 0 };
    optic.getAll(source).forEach(a -> out.add(new Indexed<>(i[0]++, a)));
    return java.util.List.copyOf(out);
  }

  /**
   * Number of focused values.
   *
   * <pre>{@code
   * final long activeUsers = Telescope.of(Team.class)
   *     .each(Team::users).filter(User::active).count(team);
   * }</pre>
   *
   * <p>{@link #exists} is the cheaper sibling when you only need to know whether the count is
   * nonzero (it stops at the first match).
   */
  public long count(final S source) {
    return optic.getAll(source).count();
  }

  /**
   * Whether the telescope resolves to at least one value. The short-circuiting sibling of {@link
   * #count}.
   */
  public boolean exists(final S source) {
    return optic.getAll(source).findAny().isPresent();
  }

  /**
   * Replace every focused value with a constant, returning a new {@code S} (the input is never
   * mutated). Delegates to {@link Traversal#set}. Use {@link #update} when the new value depends on
   * the old one.
   *
   * <pre>{@code
   * final Team reset = Telescope.of(Team.class).each(Team::users).field(User::active)
   *     .set(team, false);
   * }</pre>
   */
  public S set(final S source, final A value) {
    return optic.set(source, value);
  }

  /**
   * Transform every focused value, returning a new {@code S} (the input is never mutated).
   * Delegates to {@link Traversal#modify}.
   *
   * <pre>{@code
   * final Company normalized = Telescope.of(Company.class)
   *     .each(Company::departments).each(Department::teams).each(Team::users)
   *     .field(User::email)
   *     .update(company, String::toLowerCase);
   * }</pre>
   *
   * <p>See {@link #set} to overwrite with a constant, {@link #updateIndexed} for position-aware
   * edits, and the {@code update*} effect variants ({@link #updateAsync}, {@link #updateOptional},
   * {@link #updateEither}, {@link #updateValidated}) for functions that return wrapped values.
   */
  public S update(final S source, final Function<A, A> fn) {
    return optic.modify(source, fn);
  }

  /**
   * Transform every focused value with access to its 0-based position in traversal order. The
   * lambda receives {@code (index, value)} and returns the new value. Useful for position-dependent
   * edits, e.g. "uppercase only the first match" or "prefix each with its index".
   *
   * <pre>{@code
   * // Number each user's name: "0: alice", "1: bob", ...
   * users.updateIndexed(team, (i, name) -> i + ": " + name);
   * }</pre>
   */
  public S updateIndexed(final S source, final BiFunction<Integer, ? super A, ? extends A> fn) {
    final var i = new int[] { 0 };
    return optic.modify(source, a -> fn.apply(i[0]++, a));
  }

  /**
   * Async update: apply an asynchronous function to every focused value and recover the resulting
   * {@code S} when every future completes. This <em>lifts</em> an effectful function ({@code A ->
   * CompletableFuture<A>}) through the same path that {@link #update} runs synchronously — instead
   * of glueing together {@code stream().map(...)} plus {@code CompletableFuture.allOf}, you reuse
   * the navigation and the library threads the effect for you. The returned {@link
   * CompletableFuture} fails if any of the per-element futures fails.
   *
   * <pre>{@code
   * CompletableFuture<Company> done = emails.updateAsync(company, email -> service.normalize(email));
   * }</pre>
   *
   * <p>The function sees only the focused value; if it also needs a sibling field, close over the
   * {@code source} at the call site. Use the {@link #updateAsync(Object, Function, Executor)}
   * overload to bound where {@code fn} runs.
   *
   * @see #updateOptional
   * @see #updateEither
   * @see #updateValidated
   */
  public CompletableFuture<S> updateAsync(
    final S source,
    final Function<? super A, ? extends CompletableFuture<A>> fn
  ) {
    return CompletableFutureK.unbox(
      optic.modifyF(CompletableFutureK.applicative(), source, a -> CompletableFutureK.box(fn.apply(a)))
    );
  }

  /**
   * Validated update: apply a validation function to every focused value, accumulating every error
   * across every focused element. Returns {@link Validated.Valid} only if every element validated;
   * otherwise {@link Validated.Invalid} with the full list of errors.
   *
   * <p>Like the other {@code update*} effect variants, this lifts a function whose result is
   * wrapped ({@code A -> Validated<E, A>}) through the same path {@link #update} runs purely — the
   * library threads the {@link Validated} applicative so errors collect across the whole traversal
   * rather than at one element.
   *
   * <pre>{@code
   * // Validate every user's email; collect ALL the failures, not just the first:
   * final Validated<String, Team> result = Telescope.of(Team.class)
   *     .each(Team::users)
   *     .field(User::email)
   *     .updateValidated(team, email -> email.contains("@")
   *         ? new Validated.Valid<>(email)
   *         : new Validated.Invalid<>(List.of("bad email: " + email)));
   *
   * switch (result) {
   *   case Validated.Valid<String, Team> v -> save(v.value());
   *   case Validated.Invalid<String, Team> i -> report(i.errors());
   * }
   * }</pre>
   *
   * <p>Different from {@link #updateEither} — Validated accumulates every error, Either
   * short-circuits on the first. As with {@link #updateAsync}, the function sees only the focused
   * value; close over {@code source} for sibling access.
   *
   * @see Validated
   * @see #updateEither
   */
  public <E> Validated<E, S> updateValidated(final S source, final Function<? super A, ? extends Validated<E, A>> fn) {
    return ValidatedK.unbox(optic.modifyF(ValidatedK.<E>forError(), source, a -> ValidatedK.box(fn.apply(a))));
  }

  /**
   * Either update: apply a fallible function to every focused value, short-circuiting on the first
   * {@link Either.Left}. Returns the {@link Either.Right}-wrapped rebuilt {@code S} only if every
   * element succeeded; otherwise the first failure. The short-circuiting counterpart to {@link
   * #updateValidated} (which accumulates) — see that method for a worked effect-lifting example.
   *
   * @see Either
   * @see #updateValidated
   */
  public <E> Either<E, S> updateEither(final S source, final Function<? super A, ? extends Either<E, A>> fn) {
    return EitherK.unbox(optic.modifyF(EitherK.<E>forLeft(), source, a -> EitherK.box(fn.apply(a))));
  }

  /**
   * Optional update: apply a partial function to every focused value. Returns {@link
   * Optional#empty()} if any single element's result is empty; otherwise the rebuilt {@code S}
   * wrapped in {@link Optional}. The all-or-nothing, error-less sibling of {@link #updateEither} —
   * see {@link #updateValidated} for a worked effect-lifting example. (Distinct from {@link
   * #whenPresent}, which navigates into an {@code Optional} <em>field</em>.)
   *
   * @see #updateEither
   */
  public Optional<S> updateOptional(final S source, final Function<? super A, ? extends Optional<A>> fn) {
    return OptionalK.unbox(optic.modifyF(OptionalK.applicative(), source, a -> OptionalK.box(fn.apply(a))));
  }

  /**
   * Async update with explicit executor control. Each per-element {@code fn} invocation runs on
   * {@code executor}; pass {@code Executors.newFixedThreadPool(N)} to cap the number of concurrent
   * invocations at {@code N}.
   *
   * <p>The executor bounds <em>when</em> {@code fn} is called, not the parallelism of the futures
   * returned by {@code fn}. For fully non-blocking functions (e.g. {@code HttpClient.sendAsync})
   * this is the right bound; for blocking work inside {@code fn}, the executor's size is the
   * literal upper bound on in-flight operations.
   *
   * <pre>{@code
   * try (final var pool = Executors.newFixedThreadPool(10)) {
   *   final CompletableFuture<Batch> done = path.updateAsync(batch, this::fetch, pool);
   *   done.join();
   * }
   * }</pre>
   */
  public CompletableFuture<S> updateAsync(
    final S source,
    final Function<? super A, ? extends CompletableFuture<A>> fn,
    final Executor executor
  ) {
    return updateAsync(source, a ->
      CompletableFuture.supplyAsync(() -> fn.apply(a), executor).thenCompose(Function.identity())
    );
  }

  // Shared name-correspondence check for the bean conversions (fromBean / mapBean). Each name in
  // `ownerNames` is mapped to its counterpart via `remap` (identity when absent); names whose
  // counterpart exists on `other` are returned in order. A missing counterpart throws the
  // `onMissing`-built exception unless `dropUnmatched` is set. One place for the matching policy;
  // each caller keeps its own error wording via the factory.
  private static List<String> matchedNames(
    final String[] ownerNames,
    final Map<String, String> remap,
    final Class<?> other,
    final boolean dropUnmatched,
    final BiFunction<String, String, RuntimeException> onMissing
  ) {
    final var keep = new java.util.ArrayList<String>();
    for (final var name : ownerNames) {
      final var counterpart = remap.getOrDefault(name, name);
      if (Beans.hasProperty(other, counterpart)) keep.add(name);
      else if (!dropUnmatched) throw onMissing.apply(name, counterpart);
    }
    return keep;
  }

  private static final Map<Class<?>, String> METHOD_NAME_CACHE = new ConcurrentHashMap<>();

  private static String methodNameOf(final Serializable lambda) {
    return METHOD_NAME_CACHE.computeIfAbsent(lambda.getClass(), cls -> resolveMethodName(lambda));
  }

  private static String resolveMethodName(final Serializable lambda) {
    try {
      final var writeReplace = lambda.getClass().getDeclaredMethod("writeReplace");
      writeReplace.setAccessible(true);
      final var serialized = (SerializedLambda) writeReplace.invoke(lambda);
      final var name = serialized.getImplMethodName();
      if (name.startsWith("lambda$")) throw new IllegalArgumentException(
        "field(...) requires a method reference, not a lambda. Got: " + name
      );
      return name;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalArgumentException("field(...) requires a method reference to a record component accessor", e);
    }
  }

  /**
   * The seam between record-backed and bean-backed telescopes: how accessor-based navigation
   * ({@link #field(Accessor)}, {@link #each(Accessor)}, {@link #eachValue}, {@link #whenPresent})
   * turns a method reference into a field {@link Lens}. {@link #of} installs {@link
   * RecordFieldOptics}; {@link #ofBean} installs {@link BeanFieldOptics}. Both are stateless
   * singletons, so a telescope carries its adapter, not a flag.
   */
  private interface FieldOptics {
    <A, B> Lens<A, B> lensFor(Accessor<A, ?> getter);
  }

  /** Records: read + rebuild via the canonical constructor, keyed by component name. */
  private enum RecordFieldOptics implements FieldOptics {
    INSTANCE;

    @Override
    public <A, B> Lens<A, B> lensFor(final Accessor<A, ?> getter) {
      return Records.fieldLens(methodNameOf(getter));
    }
  }

  /** POJOs: read via the getter, rebuild via the auto-detected write strategy. */
  private enum BeanFieldOptics implements FieldOptics {
    INSTANCE;

    @Override
    public <A, B> Lens<A, B> lensFor(final Accessor<A, ?> getter) {
      final Class<A> implClass = implClassOf(getter);
      final var property = Beans.propertyOf(methodNameOf(getter));
      return Beans.lens(implClass, property, Beans.autoWriter(implClass));
    }

    // The bean's class is recovered from the method reference's SerializedLambda — the navigation
    // hop's owner type (Pojo for Pojo::getX), needed to discover the getter and write strategy.
    @SuppressWarnings("unchecked")
    private static <A> Class<A> implClassOf(final Accessor<A, ?> getter) {
      try {
        final var writeReplace = getter.getClass().getDeclaredMethod("writeReplace");
        writeReplace.setAccessible(true);
        final var serialized = (SerializedLambda) writeReplace.invoke(getter);
        return (Class<A>) Class.forName(serialized.getImplClass().replace('/', '.'));
      } catch (final ReflectiveOperationException e) {
        throw new IllegalArgumentException(
          "ofBean navigation requires a method reference to a getter (e.g. Pojo::getX)",
          e
        );
      }
    }
  }
}
