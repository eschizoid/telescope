package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.MetadataHolderProbe;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
public sealed class Telescope<
  S,
  A
> permits Telescope.ListPath, Telescope.SetPath, Telescope.MapPath, Telescope.OptionalPath {

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
  // rebuild-via-strategy (see ofBean). Propagated to derived telescopes. Package-private (not
  // private) because the typed container subclasses (ListPath, SetPath, MapPath, OptionalPath)
  // at the bottom of this file access this as an INHERITED field via `this.fieldOptics` — Java's
  // nested-class private-access rule lets a nested class read a private field through an
  // enclosing instance reference, but NOT through inheritance, so private here would break the
  // subclass bodies. Package-private is the narrowest visibility that compiles.
  final FieldOptics fieldOptics;
  // Accumulated pending edits — appended to by {@link #with(Function)}, reset to identity by the
  // static factories, run by {@link #apply(Object)}. Threaded through every navigation method so a
  // chain like {@code .each(...).field(...).with(fn1).each(...).field(...).with(fn2).apply(s)}
  // accumulates both edits and runs them in order against {@code s}. Package-private for the same
  // reason as `fieldOptics`.
  final Function<S, S> chain;

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
   * <p>For a POJO root, use {@link #ofBean(Class)} instead — it navigates beans natively (getter
   * read + auto-detected write strategy). The hand-rolled {@link #lens} factory works for either.
   *
   * @see #lens
   * @see #ofBean(Class)
   */
  public static <S> Telescope<S, S> of(final Class<S> rootType) {
    return new Telescope<>(Iso.identity());
  }

  /**
   * Wrap an internal optic ({@link Traversal} or any of its subtypes — {@link Iso}, {@link Lens},
   * {@link Prism}) as a {@code Telescope<S, A>}. Package-private: same-package callers ({@link
   * Mapper#asTelescope}, {@link To#using}, {@link DeepMap}) construct telescopes from internally-
   * composed optics. External code uses the documented entry points ({@link #of(Class)}, {@link
   * #ofBean(Class)}, {@link #lens}, {@link #from(Class)}).
   */
  static <S, A> Telescope<S, A> wrap(final Traversal<S, A> optic) {
    return new Telescope<>(optic);
  }

  /**
   * Promote a pre-built {@code Telescope<S, List<X>>} to a typed {@link ListPath} so the
   * compile-checked {@link ListPath#each()} terminal becomes available. Used by codegen's
   * container-step classes and by power users who hold a list-typed telescope built from
   * composition. Pure lattice — no reflection, no runtime check.
   *
   * <pre>{@code
   * final Telescope<Company, List<Department>> built = ...;            // from elsewhere
   * final Telescope<Company, Department> elements = Telescope.asList(built).each();
   * }</pre>
   */
  public static <S, X> ListPath<S, X> asList(final Telescope<S, List<X>> path) {
    return new ListPath<>(path.optic, path.fieldOptics, path.chain);
  }

  /** Pre-built-fragment companion to {@link #asList} for {@code Set&lt;X&gt;} paths. */
  public static <S, X> SetPath<S, X> asSet(final Telescope<S, Set<X>> path) {
    return new SetPath<>(path.optic, path.fieldOptics, path.chain);
  }

  /** Pre-built-fragment companion to {@link #asList} for {@code Map&lt;K, V&gt;} paths. */
  public static <S, K, V> MapPath<S, K, V> asMap(final Telescope<S, Map<K, V>> path) {
    return new MapPath<>(path.optic, path.fieldOptics, path.chain);
  }

  /** Pre-built-fragment companion to {@link #asList} for {@code Optional&lt;X&gt;} paths. */
  public static <S, X> OptionalPath<S, X> asOptional(final Telescope<S, Optional<X>> path) {
    return new OptionalPath<>(path.optic, path.fieldOptics, path.chain);
  }

  /**
   * Expose the underlying optic as an opaque {@code Object}. Internal-only seam — {@code
   * internal.MetadataHolderProbe} casts the result to {@code Lens} when recovering a codegen-
   * emitted holder constant, and the same-package bridge code casts to {@code Iso} when unwrapping
   * a bidirectional conversion. The {@code Object} return type keeps the {@code internal.optics}
   * lattice types out of this class's public signature — external callers get an opaque value with
   * no usable shape since the cast targets ({@code Lens} / {@code Iso} / {@code Traversal}) are not
   * exported by the module.
   */
  public static Object opticOf(final Telescope<?, ?> t) {
    return t.optic;
  }

  /**
   * Combine several {@link Edit edits} into one reusable {@code Telescope<S, S>} normalizer. Each
   * {@link Edit#over(Telescope, Function) over(PATH, fn)} pairs a pre-built telescope with its
   * per-leaf transformation; {@code Telescope.all(...)} folds them into a single {@link
   * #apply(Object)}-able value that runs each edit in argument order.
   *
   * <pre>{@code
   * import static io.github.eschizoid.telescope.Edit.over;
   *
   * static final Telescope<Company, String> EMAILS     = ...;
   * static final Telescope<Company, String> DEPT_NAMES = ...;
   * static final Telescope<Company, String> USER_NAMES = ...;
   *
   * final Telescope<Company, Company> normalize = Telescope.all(
   *     over(EMAILS,     String::toLowerCase),
   *     over(DEPT_NAMES, String::trim),
   *     over(USER_NAMES, titleCase));
   *
   * final Company a = normalize.apply(companyA);
   * normalize.apply(companyB);   // reusable across sources
   * }</pre>
   *
   * <p><b>Preferred multi-edit shape.</b> For two or more distinct paths this is the recommended
   * form over the chained {@link #update(Telescope, Function)} / {@link #with(Function)}
   * accumulator: each edit lives on its own line, the count is visible at a glance, and
   * back-to-back navigation segments cannot visually blur into one chain. The accumulator stays for
   * single edits and for the fluent inline form.
   *
   * <p><b>Sequential semantics.</b> Edits run in argument order; the second sees the first edit's
   * result, not the original source. An empty argument list returns an identity telescope (apply
   * returns the source unchanged).
   *
   * <p><b>Cost.</b> One structural pass per edit — no fusion across shared prefixes in this
   * version. Costs the same as the equivalent chain accumulator.
   *
   * @param edits the edits to apply, in order
   * @param <S> the shared root type — all edits must target the same {@code S}
   * @see Edit#over(Telescope, Function)
   * @see #apply(Object)
   */
  @SafeVarargs
  public static <S> Telescope<S, S> all(final Edit<S>... edits) {
    Function<S, S> fused = Function.identity();
    for (final var e : edits) fused = fused.andThen(e::apply);
    return new Telescope<>(Iso.identity(), RecordFieldOptics.INSTANCE, fused);
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
   * POJO&harr;record or POJO&harr;POJO <em>conversion</em>, use {@link #map(Class, Class,
   * io.github.eschizoid.telescope.MapStep...)} — the same deep recursive factory handles both kinds
   * and any cross-paradigm mix.
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
   * <p>For a field-by-field declarative mapping between two records or POJOs (no hand-written
   * conversion functions), use {@link #map(Class, Class, io.github.eschizoid.telescope.MapStep...)}
   * — it handles both record↔record, POJO↔POJO, and any cross-paradigm mix at any depth.
   *
   * @see #map(Class, Class, io.github.eschizoid.telescope.MapStep...)
   */
  public static <A> From<A> from(final Class<A> source) {
    return new From<>();
  }

  /**
   * Deep recursive mapping: pass the source/target root classes up front (either side may be a
   * record or a POJO — {@link io.github.eschizoid.telescope.internal.Reflective} dispatches per
   * side), then varargs of {@link MapStep} rows. Recursion does the rest — same-name components
   * identity-map, nested records/POJOs recurse, {@code List<X>↔List<Y>} / {@code Set<X>↔Set<Y>} /
   * {@code Map<K, X>↔Map<K, Y>} / {@code Optional<X>↔Optional<Y>} lift the inner Iso through the
   * container automatically. Containers nest to any depth ({@code List<Map<K, Set<X>>>} resolves by
   * construction). Override rows are typed by their accessors and apply <em>wherever</em> recursion
   * lands on the matching {@code (sourceClass, targetClass)} pair — a single {@code
   * to(UserEntity::name, UserDto::fullName)} at the top of a multi-level mapping affects every
   * User↔UserDto encounter in the tree.
   *
   * <pre>{@code
   * import static io.github.eschizoid.telescope.Mapping.to;
   *
   * final Telescope<CompanyEntity, CompanyDto> companyMapper = Telescope.map(
   *     CompanyEntity.class, CompanyDto.class,
   *     to(CompanyEntity::founded, CompanyDto::since),   // top-level rename
   *     to(UserEntity::name,       UserDto::fullName));  // applies wherever User↔UserDto recurses
   * // Everything else — Address, nested Lists, Map values, Optional<User> — figures itself out.
   * }</pre>
   *
   * <p><b>Same-name 1-liner.</b> No rows means pure deep auto-recursion:
   *
   * <pre>{@code
   * Telescope.map(UserEntity.class, UserDto.class);   // recurses; every component lines up by name
   * }</pre>
   *
   * <p><b>Cycle handling.</b> Self-referencing structures (a {@code User} that contains {@code
   * Optional<User>}) terminate naturally — the recursion caches the in-progress type pair and
   * re-uses it instead of descending infinitely.
   *
   * <p><b>Row kinds accepted.</b>
   *
   * <ul>
   *   <li>{@link io.github.eschizoid.telescope.Mapping#to(Accessor, Accessor) to(src, tgt)} —
   *       same-typed rename
   *   <li>{@link io.github.eschizoid.telescope.Mapping#to(Accessor, Accessor,
   *       java.util.function.Function, java.util.function.Function) to(src, tgt, fwd, bwd)} — typed
   *       transform
   *   <li>{@link io.github.eschizoid.telescope.Mapping#via(Accessor, Accessor, Mapper) via(src,
   *       tgt, mapper)} — nested mapper
   *   <li>{@link io.github.eschizoid.telescope.WriteHint#writeBean(Class,
   *       io.github.eschizoid.telescope.WriteHint.WriteStrategy) writeBean(target, strategy)} —
   *       per-target write-strategy override (e.g. force {@code CONSTRUCTOR} for an immutable
   *       all-args-only POJO that {@code Beans.autoWriter} refuses)
   * </ul>
   *
   * @param source the source root class — record or POJO (root of the recursion)
   * @param target the target root class — record or POJO (root of the recursion)
   * @param steps {@code Mapping} field overrides and/or {@code WriteHint} construction directives
   * @param <A> the source root type
   * @param <B> the target root type
   * @see #mapper(Class, Class, MapStep...)
   * @see io.github.eschizoid.telescope.Mapping
   * @see io.github.eschizoid.telescope.WriteHint
   * @see DeepMap
   */
  // No @SafeVarargs needed: MapStep is reifiable (no type parameter), so this varargs method does
  // not produce heap-pollution warnings for callers.
  public static <A, B> Telescope<A, B> map(final Class<A> source, final Class<B> target, final MapStep... steps) {
    return new Telescope<>(DeepMap.resolve(source, target, steps));
  }

  /**
   * {@link Mapper} sibling of {@link #map(Class, Class, MapStep...)} — same deep recursion, but
   * returns a {@code Mapper<A, B>} (exposes {@link Mapper#patch} for sparse overlays at the top
   * level and is nestable in another mapping via {@link
   * io.github.eschizoid.telescope.Mapping#via(Accessor, Accessor, Mapper)}).
   *
   * @see #map(Class, Class, MapStep...)
   */
  public static <A, B> Mapper<A, B> mapper(final Class<A> source, final Class<B> target, final MapStep... steps) {
    return DeepMap.resolveMapper(source, target, steps);
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
   * runtime escape hatch — no compile-time check on the name or the resulting type). For POJOs, use
   * {@link #ofBean(Class)} as the root — same {@code .field(...)} navigation, bean semantics.
   */
  public <B> Telescope<S, B> field(final Accessor<A, B> getter) {
    final Lens<A, B> lens = lensForAccessor(getter);
    return new Telescope<>(optic.then(lens), fieldOptics, chain);
  }

  /**
   * Typed-container variant of {@link #field(Accessor)} for {@code List<X>} components. Picked by
   * the user when they want the resulting telescope to carry the list type for later {@link
   * ListPath#each()} navigation. Returns a {@link ListPath} whose {@code each()} terminal descends
   * into list elements via pure lattice composition (no runtime container dispatch).
   *
   * <p>Java's type erasure prevents an overload on {@code field(...)} that returns a narrower
   * subclass when the accessor's return type is {@code List<X>} — both overloads erase to {@code
   * field(Accessor)}. This distinct name is the workaround.
   *
   * <pre>{@code
   * final ListPath<Company, Department> deps =
   *     Telescope.of(Company.class).list(Company::departments);
   * deps.each().field(Department::name).update(co, fn);
   * }</pre>
   */
  public <X> ListPath<S, X> list(final Accessor<A, List<X>> getter) {
    final Lens<A, List<X>> lens = lensForAccessor(getter);
    return new ListPath<>(optic.then(lens), fieldOptics, chain);
  }

  /**
   * Typed-container variant of {@link #field(Accessor)} for {@code Set<X>} components. Returns a
   * {@link SetPath} carrying the set type for later {@link SetPath#each()} navigation.
   *
   * <p>Named {@code setField} (rather than {@code set}) to avoid cognitive collision with the write
   * terminal {@link #set(Object, Object)} — they take different argument types, but the shared verb
   * load is real enough that disambiguation pays off at the call site.
   */
  public <X> SetPath<S, X> setField(final Accessor<A, Set<X>> getter) {
    final Lens<A, Set<X>> lens = lensForAccessor(getter);
    return new SetPath<>(optic.then(lens), fieldOptics, chain);
  }

  /**
   * Typed-container variant of {@link #field(Accessor)} for {@code Map<K, V>} components. Returns a
   * {@link MapPath} carrying the map type for later {@link MapPath#values()} navigation.
   *
   * <p>Named {@code mapField} (rather than {@code map}) to disambiguate from the sibling static
   * deep-conversion factory {@link #map(Class, Class, io.github.eschizoid.telescope.MapStep...)} —
   * those do conceptually different things and share the same verb otherwise.
   */
  public <K, V> MapPath<S, K, V> mapField(final Accessor<A, Map<K, V>> getter) {
    final Lens<A, Map<K, V>> lens = lensForAccessor(getter);
    return new MapPath<>(optic.then(lens), fieldOptics, chain);
  }

  /**
   * Typed-container variant of {@link #field(Accessor)} for {@code Optional<X>} components. Returns
   * an {@link OptionalPath} carrying the optional type for later {@link OptionalPath#present()}
   * navigation.
   */
  public <X> OptionalPath<S, X> optional(final Accessor<A, Optional<X>> getter) {
    final Lens<A, Optional<X>> lens = lensForAccessor(getter);
    return new OptionalPath<>(optic.then(lens), fieldOptics, chain);
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
    final Traversal<Iterable<E>, E> elements = Traversals.eachIterable();
    final Lens<A, Iterable<E>> lens = lensForAccessor(getter);
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
    final Lens<A, Map<K, V>> lens = lensForAccessor(getter);
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
    final Lens<A, Optional<E>> lens = lensForAccessor(getter);
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
   * pieces and stitch them together, and is how reusable conversions ({@link #from}, {@link
   * #map(Class, Class, io.github.eschizoid.telescope.MapStep...)}) get threaded into a longer path.
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
    return ValidatedK.unbox(optic.modifyF(ValidatedK.forError(), source, a -> ValidatedK.box(fn.apply(a))));
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
    return EitherK.unbox(optic.modifyF(EitherK.forLeft(), source, a -> EitherK.box(fn.apply(a))));
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

  // methodNameOf + implClassOf live in internal/LambdaIntrospection.java so the new
  // mapping/conversion sub-packages can reach them without re-implementing the SerializedLambda
  // decode. These shims keep the existing callsites in this file unchanged.
  static String methodNameOf(final Serializable lambda) {
    return LambdaIntrospection.methodNameOf(lambda);
  }

  static <A> Class<A> implClassOf(final Serializable lambda) {
    return LambdaIntrospection.implClassOf(lambda);
  }

  /**
   * The seam between record-backed and bean-backed telescopes: how accessor-based navigation
   * ({@link #field(Accessor)}, {@link #each(Accessor)}, {@link #eachValue}, {@link #whenPresent})
   * turns a method reference into a field {@link Lens}. {@link #of} installs {@link
   * RecordFieldOptics}; {@link #ofBean} installs {@link BeanFieldOptics}. Both are stateless
   * singletons, so a telescope carries its adapter, not a flag.
   *
   * <p><b>Per-accessor dispatch.</b> The {@code fieldOptics} field is the <em>fallback</em> used by
   * methods that have no accessor (e.g. {@link #fieldByName(String)}). Accessor-based navigation
   * methods route through {@link #lensForAccessor(Accessor)}, which re-picks the adapter on every
   * call based on the accessor's declaring class (recovered via {@code SerializedLambda}). This
   * matters across paradigm hops: a chain like {@code Telescope.of(Record.class).field(...).then(
   * mapper.asTelescope()).field(BeanType::getX)} crosses from a record root into a bean focus; the
   * stored {@code fieldOptics} stays {@code RecordFieldOptics} but the trailing {@code .field()}
   * needs {@code BeanFieldOptics} to resolve the bean accessor. The same applies to {@code .as()}
   * narrowing into a sealed-type subtype that's a bean.
   */
  private interface FieldOptics {
    <A, B> Lens<A, B> lensFor(Accessor<A, ?> getter);
  }

  /**
   * Pick the right {@link FieldOptics} for {@code getter}'s declaring class. Records route through
   * {@link RecordFieldOptics}; everything else through {@link BeanFieldOptics}. Used by every
   * accessor-based navigation method so the dispatch survives paradigm hops via {@link
   * #then(Telescope)} and sealed-type narrowing via {@link #as(Class)}.
   */
  private <X, B> Lens<X, B> lensForAccessor(final Accessor<X, ?> getter) {
    final Class<?> declaringClass = LambdaIntrospection.implClassOf(getter);
    final FieldOptics dispatch =
      declaringClass != null && declaringClass.isRecord() ? RecordFieldOptics.INSTANCE : BeanFieldOptics.INSTANCE;
    return dispatch.lensFor(getter);
  }

  /** Records: read + rebuild via the canonical constructor, keyed by component name. */
  private enum RecordFieldOptics implements FieldOptics {
    INSTANCE;

    @Override
    public <A, B> Lens<A, B> lensFor(final Accessor<A, ?> getter) {
      final var name = methodNameOf(getter);
      final Class<A> implClass = Telescope.implClassOf(getter);
      final var holderLens = MetadataHolderProbe.<A, B>lensFromHolder(implClass, name);
      if (holderLens != null) return holderLens;
      return Records.fieldLens(name);
    }
  }

  /** POJOs: read via the getter, rebuild via the auto-detected write strategy. */
  private enum BeanFieldOptics implements FieldOptics {
    INSTANCE;

    @Override
    public <A, B> Lens<A, B> lensFor(final Accessor<A, ?> getter) {
      final Class<A> implClass = Telescope.implClassOf(getter);
      final var rawName = methodNameOf(getter);
      // The holder names its constants by the property name (lowerCamel, no getX/isX prefix), to
      // match how @BeanFocus codegen emits them — Beans.propertyOf strips the same prefixes the
      // codegen would have stripped when naming the per-property method on <X>Path.
      final var property = Beans.propertyOf(rawName);
      final var holderLens = MetadataHolderProbe.<A, B>lensFromHolder(implClass, property);
      if (holderLens != null) return holderLens;
      return Beans.lens(implClass, property, Beans.autoWriter(implClass));
    }
  }

  // ----- Typed container subclasses -----
  // Each subclass narrows A to a known container shape and exposes a typed terminal step
  // (each / values / present) that's compile-checked instead of runtime-dispatched. The narrower
  // type is picked at .field(...) call time via overload resolution (see the .field overloads
  // above). These subclasses replace the runtime-dispatched Telescope.each() no-arg form, which
  // used to walk an instanceof chain over List/Set/Map/Optional/array via reflection.

  /**
   * A {@link Telescope} whose focus is a {@code List&lt;X&gt;}. Adds a compile-checked {@link
   * #each()} terminal that descends into list elements via the lattice's {@link
   * Traversals#eachList()}, with no runtime container dispatch.
   *
   * <p>Returned by the {@code .field(Accessor<A, List<X>>)} overload on the parent. Existing {@code
   * .each(Accessor)} chains land here transparently — the typed terminal is available whenever a
   * path ends at a list-typed focus.
   */
  public static final class ListPath<S, X> extends Telescope<S, List<X>> {

    ListPath(final Traversal<S, List<X>> optic, final FieldOptics fieldOptics, final Function<S, S> chain) {
      super(optic, fieldOptics, chain);
    }

    /** Step into list elements. Pure lattice composition; no reflection. */
    public Telescope<S, X> each() {
      return new Telescope<>(this.optic.then(Traversals.eachList()), this.fieldOptics, this.chain);
    }
  }

  /**
   * A {@link Telescope} whose focus is a {@code Set&lt;X&gt;}. Adds a compile-checked {@link
   * #each()} terminal that descends into set elements via {@link Traversals#eachSet()}. Returned by
   * the {@code .field(Accessor<A, Set<X>>)} overload on the parent.
   */
  public static final class SetPath<S, X> extends Telescope<S, Set<X>> {

    SetPath(final Traversal<S, Set<X>> optic, final FieldOptics fieldOptics, final Function<S, S> chain) {
      super(optic, fieldOptics, chain);
    }

    /** Step into set elements. Output is iteration-order-preserving (LinkedHashSet). */
    public Telescope<S, X> each() {
      return new Telescope<>(this.optic.then(Traversals.eachSet()), this.fieldOptics, this.chain);
    }
  }

  /**
   * A {@link Telescope} whose focus is a {@code Map&lt;K, V&gt;}. Adds a compile-checked {@link
   * #values()} terminal that descends into the map's values via {@link Traversals#eachMapValue()},
   * preserving keys. Returned by the {@code .field(Accessor<A, Map<K, V>>)} overload.
   */
  public static final class MapPath<S, K, V> extends Telescope<S, Map<K, V>> {

    MapPath(final Traversal<S, Map<K, V>> optic, final FieldOptics fieldOptics, final Function<S, S> chain) {
      super(optic, fieldOptics, chain);
    }

    /** Step into map values; keys remain on the map. Pure lattice composition. */
    public Telescope<S, V> values() {
      return new Telescope<>(this.optic.then(Traversals.eachMapValue()), this.fieldOptics, this.chain);
    }
  }

  /**
   * A {@link Telescope} whose focus is an {@code Optional&lt;X&gt;}. Adds a compile-checked {@link
   * #present()} terminal that descends into the payload when present (no-op when empty) via {@link
   * Traversals#eachOptional()}. Returned by the {@code .field(Accessor<A, Optional<X>>)} overload.
   */
  public static final class OptionalPath<S, X> extends Telescope<S, Optional<X>> {

    OptionalPath(final Traversal<S, Optional<X>> optic, final FieldOptics fieldOptics, final Function<S, S> chain) {
      super(optic, fieldOptics, chain);
    }

    /** Step into the Optional's payload. Empty Optional is a write no-op (Affine semantics). */
    public Telescope<S, X> present() {
      return new Telescope<>(this.optic.then(Traversals.eachOptional()), this.fieldOptics, this.chain);
    }
  }
}
