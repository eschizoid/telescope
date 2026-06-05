package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.internal.optics.Lens;
import io.github.eschizoid.telescope.internal.optics.Prism;
import io.github.eschizoid.telescope.internal.optics.Traversal;
import io.github.eschizoid.telescope.internal.optics.collections.Traversals;
import io.github.eschizoid.telescope.internal.optics.instances.CompletableFutureK;
import io.github.eschizoid.telescope.internal.optics.instances.EitherK;
import io.github.eschizoid.telescope.internal.optics.instances.OptionalK;
import io.github.eschizoid.telescope.internal.optics.instances.ValidatedK;
import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.util.ArrayList;
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
 * io.github.eschizoid.telescope.internal.optics}. Each navigation method builds the appropriate
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

  // Package-private — read by the conversion-builder classes in this same package (e.g.
  // BeanTo's iso-unwrap check, Mapper's asTelescope).
  final Traversal<S, A> optic;
  // How accessor-based navigation (field/each/eachValue/whenPresent) turns a method reference into
  // a field Lens: records read/rebuild via the canonical constructor, beans via getters +
  // rebuild-via-strategy (see ofBean). Propagated to derived telescopes.
  private final FieldOptics fieldOptics;
  // Accumulated pending edits — appended to by {@link #with(Function)}, reset to identity by the
  // static factories, run by {@link #apply(Object)}. Threaded through every navigation method so a
  // chain like {@code .each(...).field(...).with(fn1).each(...).field(...).with(fn2).apply(s)}
  // accumulates both edits and runs them in order against {@code s}.
  private final Function<S, S> chain;

  // Package-private so that the conversion-builder classes (From, To, BeanTo, MapBuilder, Mapper,
  // …) — extracted to sibling files in this same package to keep Telescope.java navigable — can
  // construct Telescope instances without needing us to expose internals through the JPMS export.
  Telescope(final Traversal<S, A> optic) {
    this(optic, RecordFieldOptics.INSTANCE, Function.identity());
  }

  private Telescope(final Traversal<S, A> optic, final FieldOptics fieldOptics) {
    this(optic, fieldOptics, Function.identity());
  }

  private Telescope(final Traversal<S, A> optic, final FieldOptics fieldOptics, final Function<S, S> chain) {
    this.optic = optic;
    this.fieldOptics = fieldOptics;
    this.chain = chain;
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
   * io.github.eschizoid.telescope.annotations.BeanFocus}.
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
   * factory used by {@link io.github.eschizoid.telescope.annotations.Focus}-generated {@code
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
   * @see io.github.eschizoid.telescope.annotations.Focus
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

  /**
   * Begin a bidirectional bridge between a JavaBeans-style POJO and a record. The forward direction
   * reads the POJO's properties via its getters and builds the record; the reverse rebuilds the
   * POJO via the strategy you pick at the terminal {@code .via*()} call. Fields are matched by name
   * (record component name &harr; bean property name). Reflection is used at runtime — for a
   * reflection-free, compile-checked equivalent, annotate the record with {@link
   * io.github.eschizoid.telescope.annotations.Bridge}.
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
   * io.github.eschizoid.telescope.annotations.Bridge} — annotate the record with it to have the
   * bridge generated and validated at compile time instead of resolved reflectively here.
   *
   * <p>The target type parameter is bounded to {@link Record}; the POJO is unconstrained because
   * the three {@code via*()} strategies cover the shapes a non-record can take.
   *
   * @see io.github.eschizoid.telescope.annotations.Bridge
   */
  public static <P> BeanFrom<P> fromBean(final Class<P> pojoClass) {
    return new BeanFrom<>(pojoClass);
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
   * <p>For a field name only known at runtime, use {@link #fieldByName(String)} (a documented
   * runtime escape hatch — no compile-time check on the name or the resulting type). Records only —
   * see {@link #fromBean(Class)} for the POJO escape hatch.
   */
  public <B> Telescope<S, B> field(final Accessor<A, B> getter) {
    final Lens<A, B> lens = fieldOptics.lensFor(getter);
    return new Telescope<>(optic.then(lens), fieldOptics, chain);
  }

  /**
   * Descend into a record field by string name — the runtime escape hatch for cases where the field
   * name is only known at runtime (config-driven paths, late-binding tools). <b>Not
   * compile-checked:</b> {@code javac} cannot verify that {@code fieldName} matches a real
   * component of {@code A}, nor can it check that the inferred return type {@code B} matches the
   * component's actual type. A wrong name or type surfaces as a runtime {@link
   * IllegalArgumentException} at use site.
   *
   * <p>Prefer {@link #field(Accessor)} (e.g. {@code .field(User::email)}) when the field is
   * statically known — that form is fully type-checked at compile time.
   *
   * <pre>{@code
   * // Runtime-only path; the name comes from config.
   * final String fieldName = config.getString("updatePath");
   * final var path = Telescope.of(User.class).<String>fieldByName(fieldName);
   * }</pre>
   *
   * <p>The compile-time-safe alternative for paths-as-data is the {@code @Focus} annotation
   * processor — it generates a typed {@code <X>Path<R>} navigator at build time.
   */
  public <B> Telescope<S, B> fieldByName(final String fieldName) {
    final Lens<A, B> fieldLens = Records.fieldLens(fieldName);
    return new Telescope<>(optic.then(fieldLens), fieldOptics, chain);
  }

  /**
   * Same as {@link #fieldByName(String)}, with an explicit type witness so call sites can use
   * {@code var} without a leading {@code <B>} generic.
   *
   * <p><b>The {@code fieldType} argument is purely for type inference</b> — it lets you write
   * {@code var name = ...fieldByName("name", String.class)} cleanly. <em>It is not validated
   * against the field's actual type at compile or runtime.</em> A wrong witness surfaces the same
   * way a wrong field name surfaces — as a runtime error at use site, same as {@link
   * #fieldByName(String)} would. The Class argument follows the same pattern as {@link #of(Class)}
   * and {@link #from(Class)}: it's there for inference, not enforcement.
   *
   * <pre>{@code
   * final var name = Telescope.of(User.class).fieldByName("name", String.class);
   * }</pre>
   *
   * <p>For a fully compile-checked path, use {@link #field(Accessor)} (e.g. {@code
   * .field(User::email)}) or the {@code @Focus} annotation processor.
   */
  public <B> Telescope<S, B> fieldByName(final String fieldName, final Class<B> fieldType) {
    return fieldByName(fieldName);
  }

  /**
   * Descend into a container ({@code List}/{@code Set}/{@code Map} values/{@code Optional}) when
   * you already hold a {@code Telescope<S, SomeContainer>}. The element type {@code E} and
   * container shape are dispatched at runtime. <b>Not compile-checked:</b> {@code javac} cannot
   * verify that the current focus {@code A} is actually a container, nor that the inferred element
   * type {@code E} matches the container's actual element type.
   *
   * <p>Prefer {@link #each(Accessor)} / {@link #eachValue(Accessor)} / {@link
   * #whenPresent(Accessor)} for the common case of "descend into a field + iterate" — those forms
   * are fully type-checked at compile time. Use the no-arg form only when you already hold a
   * container telescope built elsewhere (e.g. a pre-built path composed from multiple pieces).
   *
   * <pre>{@code
   * // Already focused on the List<String> field; broadcast over its elements:
   * final var tags = Telescope.of(Post.class).field(Post::tags).<String>each();
   * final Post lower = tags.update(post, String::toLowerCase);
   * }</pre>
   *
   * <p>The compile-time-safe alternative for paths-as-data is the {@code @Focus} annotation
   * processor — it generates a typed {@code <X>Path<R>} navigator at build time.
   */
  public <E> Telescope<S, E> each() {
    final Traversal<A, E> elements = Traversals.eachContainer();
    return new Telescope<>(optic.then(elements), fieldOptics, chain);
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
    return new Telescope<>(optic.then(lens).then(elements), fieldOptics, chain);
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
    return new Telescope<>(optic.then(lens).then(values), fieldOptics, chain);
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
    return new Telescope<>(optic.then(lens).then(present), fieldOptics, chain);
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
    return new Telescope<>(optic.then(prism), fieldOptics, chain);
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
    return new Telescope<>(optic.filter(predicate), fieldOptics, chain);
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
    return new Telescope<>(optic.then(next.optic), fieldOptics, chain);
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
    final var out = new ArrayList<Indexed<A>>();
    final var i = new int[] { 0 };
    optic.getAll(source).forEach(a -> out.add(new Indexed<>(i[0]++, a)));
    return List.copyOf(out);
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
   * Accumulate an edit through a <em>pre-built</em> telescope path and return a fresh identity
   * {@code Telescope<S, S>} carrying the running chain — the multi-edit form that pairs with {@link
   * #with(Function)} for inline paths and {@link #apply(Object)} for the terminal.
   *
   * <p>The natural shape when paths are reusable: declare each as a static {@code final
   * Telescope<S, X>} value once, then list the operations cleanly without re-walking the navigation
   * tree per edit:
   *
   * <pre>{@code
   * static final Telescope<Company, String> EMAILS    = Telescope.of(Company.class)
   *     .each(Company::departments).each(Department::teams).each(Team::users).field(User::email);
   * static final Telescope<Company, String> DEPT_NAMES = Telescope.of(Company.class)
   *     .each(Company::departments).field(Department::name);
   *
   * final Company done = Telescope.of(Company.class)
   *     .update(EMAILS,     String::toLowerCase)
   *     .update(DEPT_NAMES, String::trim)
   *     .apply(company);
   * }</pre>
   *
   * <p><b>Fully compile-checked.</b> {@code path} carries its leaf type {@code X}; {@code fn} must
   * be {@code Function<X, X>} to match. A wrong-type function → compile error.
   *
   * <p>Java overload-resolves this against {@link #update(Object, Function)} by the first
   * argument's type — a {@code Telescope} starts (or continues) a multi-edit chain; anything else
   * is the single-shot terminal that returns {@code S} immediately. Mixes cleanly with {@link
   * #with(Function)} in the same chain when some edits use inline paths and others use pre-built
   * ones.
   *
   * @param path the pre-built telescope to navigate when this edit runs
   * @param fn the per-leaf transformation
   * @param <X> the focused leaf type for this edit (independent of any other edit's leaf type)
   * @see #with(Function)
   * @see #apply(Object)
   */
  public <X> Telescope<S, S> update(final Telescope<S, X> path, final Function<X, X> fn) {
    return new Telescope<>(Iso.identity(), fieldOptics, chain.andThen(s -> path.update(s, fn)));
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
   * Switch this telescope into <em>index-aware</em> mode and return a chainable view whose terminal
   * operations expose the 0-based position of each focused value in traversal order. The same path
   * is reused — {@code withIndex()} only changes how subsequent operations report results; it does
   * not alter the shape of the underlying traversal or its cost.
   *
   * <p>The chain form pairs with the existing terminal {@link #updateIndexed} / {@link
   * #toListIndexed} methods on {@code Telescope} — when a path is built once and reused with
   * different operations, chaining {@code .withIndex()} reads more naturally than threading the
   * {@code Indexed*} terminal names through call sites.
   *
   * <pre>{@code
   * final Telescope<Company, String> emails = Telescope.of(Company.class)
   *     .each(Company::departments).each(Department::teams).each(Team::users)
   *     .field(User::email);
   *
   * // Lower-case only the email of the third focused user in the whole tree.
   * final Company stamped = emails.withIndex().update(company, (i, e) -> i == 2 ? e.toLowerCase() : e);
   *
   * // Same path, indexed read.
   * final List<Indexed<String>> tagged = emails.withIndex().toList(company);
   * }</pre>
   *
   * <p>The returned {@link WithIndex} exposes only terminal operations (no further composition).
   * Indices are flat positions in {@code getAll} enumeration order; for multi-level paths they
   * count across the entire flattened focus, not per inner collection — matching {@link
   * #toListIndexed} and {@link #updateIndexed}.
   *
   * @see WithIndex
   * @see #updateIndexed
   * @see #toListIndexed
   */
  public WithIndex<S, A> withIndex() {
    return new WithIndex<>(this);
  }

  /**
   * Index-aware terminal view of a {@link Telescope}, produced by {@link Telescope#withIndex()}.
   * Holds no extra state — every method delegates to the parent telescope's existing indexed
   * machinery, so the chain form costs nothing beyond the wrapping allocation. Each terminal call
   * starts a fresh counter; the same {@code WithIndex} instance can be applied to many sources.
   *
   * <p>Mirrors the operation subset that benefits from positional info ({@link #update}, {@link
   * #toList}, {@link #find}) plus the index-agnostic siblings ({@link #count}, {@link #exists}) for
   * symmetry with the parent's terminal surface.
   *
   * @param <S> root type
   * @param <A> focused value type
   */
  public static final class WithIndex<S, A> {

    private final Telescope<S, A> parent;

    WithIndex(final Telescope<S, A> parent) {
      this.parent = parent;
    }

    /**
     * Transform every focused value with access to its 0-based position. The chainable counterpart
     * of {@link Telescope#updateIndexed} — same engine, fluent shape.
     *
     * <pre>{@code
     * users.withIndex().update(team, (i, name) -> i + ": " + name);
     * }</pre>
     */
    public S update(final S source, final BiFunction<Integer, ? super A, ? extends A> fn) {
      return parent.updateIndexed(source, fn);
    }

    /**
     * Read every focused value paired with its 0-based position. The chainable counterpart of
     * {@link Telescope#toListIndexed}.
     *
     * <pre>{@code
     * for (final var e : users.withIndex().toList(team)) {
     *   System.out.println(e.index() + ": " + e.value());
     * }
     * }</pre>
     */
    public List<Indexed<A>> toList(final S source) {
      return parent.toListIndexed(source);
    }

    /**
     * The first focused value paired with its position ({@code 0}), or {@link Optional#empty()} if
     * the path resolves to no values. Short-circuiting: stops at the first match.
     */
    public Optional<Indexed<A>> find(final S source) {
      return parent.find(source).map(a -> new Indexed<>(0, a));
    }

    /**
     * Number of focused values. Identical to {@link Telescope#count}; exposed here so callers can
     * stay in the {@code WithIndex} chain without bouncing back through the parent.
     */
    public long count(final S source) {
      return parent.count(source);
    }

    /**
     * Whether the telescope resolves to at least one value. Identical to {@link Telescope#exists};
     * exposed here for symmetry with {@link #count}.
     */
    public boolean exists(final S source) {
      return parent.exists(source);
    }
  }

  /**
   * Accumulate an edit at this telescope's current focus and return a fresh {@code Telescope<S, S>}
   * (identity at the root) carrying the running chain — ready for the next path. Chain multiple
   * edits by navigating again from the returned telescope; terminate with {@link #apply(Object)} on
   * the final result.
   *
   * <p>The whole multi-edit story is just typed navigation methods you already know, separated by
   * {@code .with(...)} markers. No new types, no static fields needed, every step compile-checked:
   *
   * <pre>{@code
   * final Company done = Telescope.of(Company.class)
   *     .each(Company::departments).each(Department::teams).each(Team::users).field(User::email)
   *         .with(String::toLowerCase)
   *     .each(Company::departments).field(Department::name)
   *         .with(String::trim)
   *     .apply(company);
   * }</pre>
   *
   * <p>Reusable form — hold onto the {@code Telescope<S, S>} instead of calling {@code apply}:
   *
   * <pre>{@code
   * final Telescope<Company, Company> normalize = Telescope.of(Company.class)
   *     .each(Company::departments).each(Department::teams).each(Team::users).field(User::email)
   *         .with(String::toLowerCase)
   *     .each(Company::departments).field(Department::name)
   *         .with(String::trim);
   *
   * final Company a = normalize.apply(companyA);
   * final Company b = normalize.apply(companyB);
   * }</pre>
   *
   * <p><b>Compile-time check.</b> The function's input type {@code A} must match the current focus
   * exactly — {@code javac} rejects e.g. {@code Function<String, Integer>} against a {@code String}
   * leaf.
   *
   * <p><b>Honest semantics.</b> Each accumulated edit runs sequentially in insertion order when
   * {@code apply} is called. The chain does not fuse edits into a single structural walk; cost is
   * the sum of the individual updates. The win is at the call site.
   *
   * @param fn the per-leaf transformation at the current focus
   * @see #apply(Object)
   */
  public Telescope<S, S> with(final Function<A, A> fn) {
    return new Telescope<>(Iso.identity(), fieldOptics, chain.andThen(s -> update(s, fn)));
  }

  /**
   * Run every accumulated {@link #with} edit against {@code source} in insertion order, threading
   * each result into the next. Returns a new {@code S}; the input is never mutated.
   *
   * <p>When called on a telescope that has no accumulated edits (e.g. fresh from {@link
   * #of(Class)}), this returns {@code source} unchanged — the chain defaults to the identity
   * function.
   */
  public S apply(final S source) {
    return chain.apply(source);
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
  // Package-private — shared with the conversion-builder classes (BeanTo, MapBuilder) which live
  // in sibling files in this same package.
  static List<String> matchedNames(
    final String[] ownerNames,
    final Map<String, String> remap,
    final Class<?> other,
    final boolean dropUnmatched,
    final BiFunction<String, String, RuntimeException> onMissing
  ) {
    final var keep = new ArrayList<String>();
    for (final var name : ownerNames) {
      final var counterpart = remap.getOrDefault(name, name);
      if (Beans.hasProperty(other, counterpart)) keep.add(name);
      else if (!dropUnmatched) throw onMissing.apply(name, counterpart);
    }
    return keep;
  }

  private static final Map<Class<?>, String> METHOD_NAME_CACHE = new ConcurrentHashMap<>();

  // Package-private — shared with the conversion-builder classes (BeanTo, MapBuilder,
  // FieldMapping, Mapper) which live in sibling files in this same package.
  static String methodNameOf(final Serializable lambda) {
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
