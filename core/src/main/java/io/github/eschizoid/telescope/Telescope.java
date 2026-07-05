package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.conversion.BridgeRegistry;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.From;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.conversion.MapperBuilder;
import io.github.eschizoid.telescope.conversion.MappingTraces;
import io.github.eschizoid.telescope.effects.Either;
import io.github.eschizoid.telescope.effects.Validated;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.BridgeHolderProbe;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.MetadataHolderProbe;
import io.github.eschizoid.telescope.internal.NullDefaults;
import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Affine;
import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.internal.optics.Lens;
import io.github.eschizoid.telescope.internal.optics.Prism;
import io.github.eschizoid.telescope.internal.optics.Traversal;
import io.github.eschizoid.telescope.internal.optics.collections.Traversals;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.introspection.OpticReport;
import io.github.eschizoid.telescope.introspection.Trace;
import io.github.eschizoid.telescope.introspection.TraceLimits;
import io.github.eschizoid.telescope.mapping.Extract;
import io.github.eschizoid.telescope.mapping.ForwardOnlyTransformTo;
import io.github.eschizoid.telescope.mapping.MapExtractStep;
import io.github.eschizoid.telescope.mapping.MapStep;
import io.github.eschizoid.telescope.mapping.Mapping;
import io.github.eschizoid.telescope.runtime.instances.CompletableFutureK;
import io.github.eschizoid.telescope.runtime.instances.EitherK;
import io.github.eschizoid.telescope.runtime.instances.OptionalK;
import io.github.eschizoid.telescope.runtime.instances.ValidatedK;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
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
> permits
  Telescope.ListTelescope,
  Telescope.SetTelescope,
  Telescope.MapTelescope,
  Telescope.OptionalTelescope,
  Telescope.BridgeTelescope {

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
  // BeanTo's iso-unwrap check, Mapper's asTelescope) AND by the holder-extraction helpers below
  // (singleHolderLens, holderReadersFor) which unwrap codegen-emitted Telescope constants to
  // their underlying Lens. The cast site lives here in :core because Telescope is a :core type;
  // :internal sees the constants only as raw Object — no callback, no global state, no
  // static-init bridge between the modules.
  final Traversal<S, A> optic;
  // How accessor-based navigation (field/each/eachValue/whenPresent) turns a method reference into
  // a field Lens: records read/rebuild via the canonical constructor, beans via getters +
  // rebuild-via-strategy (see ofBean). Propagated to derived telescopes. Package-private (not
  // private) because the typed container subclasses (ListTelescope, SetTelescope, MapTelescope,
  // OptionalTelescope)
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
  // The method name of the FIRST hop accessor used to build this Telescope (e.g. "customer" for
  // Telescope.of(A.class).field(A::getCustomer)…). Stays null for telescopes built without a path
  // (e.g. Iso.identity() from Telescope.of). Used by DeepMap to route Mapping.to(Telescope, ...)
  // rows back to the top-level source/target field — without it, deeply-nested telescope rows
  // can't tell the engine which top-level field they traverse. Package-private; recovered via
  // LambdaIntrospection.methodNameOf(accessor) on the first .field(…) / .each(…) / .eachValue(…)
  // / .whenPresent(…) call that takes an Accessor.
  final String firstHopName;
  // The full navigation trail: one OpticNode per combinator hop that built this Telescope, in
  // order, surfaced by explain(). The generalization of firstHopName from a single slot to the
  // whole path — accumulated immutably (copy-append) at build time, never touched on the
  // read/update hot path. Empty for a bare Telescope.of(...) or an iso-backed conversion.
  final List<OpticNode> trail;

  // Package-private so that the conversion-builder classes (From, To, BeanTo, MapBuilder, Mapper,
  // …) — extracted to sibling files in this same package to keep Telescope.java navigable — can
  // construct Telescope instances without needing us to expose internals through the JPMS export.
  Telescope(final Traversal<S, A> optic) {
    this(optic, RecordFieldOptics.INSTANCE, Function.identity(), null, List.of());
  }

  private Telescope(final Traversal<S, A> optic, final FieldOptics fieldOptics) {
    this(optic, fieldOptics, Function.identity(), null, List.of());
  }

  Telescope(final Traversal<S, A> optic, final FieldOptics fieldOptics, final Function<S, S> chain) {
    this(optic, fieldOptics, chain, null, List.of());
  }

  Telescope(
    final Traversal<S, A> optic,
    final FieldOptics fieldOptics,
    final Function<S, S> chain,
    final String firstHopName
  ) {
    this(optic, fieldOptics, chain, firstHopName, List.of());
  }

  Telescope(
    final Traversal<S, A> optic,
    final FieldOptics fieldOptics,
    final Function<S, S> chain,
    final String firstHopName,
    final List<OpticNode> trail
  ) {
    this.optic = optic;
    this.fieldOptics = fieldOptics;
    this.chain = chain;
    this.firstHopName = firstHopName;
    // Stored as-is. The invariant callers must uphold is immutability, not non-sharing: every
    // internal caller passes an already-immutable list (an empty List.of(), a fresh unmodifiable
    // list from plus(), or a defensively-copied one from mapped()). then() may alias an existing
    // immutable trail when one side is empty — safe precisely because it can never be mutated.
    // Copying here too would copy the trail a second time on every hop.
    this.trail = trail;
  }

  // Append one hop node to this Telescope's trail as a fresh immutable list — one copy of the
  // existing trail, wrapped as an unmodifiable view the constructor stores directly (no second
  // copy). The fresh ArrayList is never shared, so the view cannot be mutated behind our back.
  private List<OpticNode> plus(final OpticNode node) {
    final var extended = new ArrayList<OpticNode>(trail.size() + 1);
    extended.addAll(trail);
    extended.add(node);
    return Collections.unmodifiableList(extended);
  }

  /**
   * Package-private — exposes the method name of the FIRST navigation hop used to build this
   * Telescope, or {@code null} if the Telescope wasn't built via accessor-based navigation. {@link
   * DeepMap} uses this to recover the top-level source/target field a {@code Mapping.to(Telescope,
   * …)} row traverses so it can register a corresponding top-level mapping step.
   */
  String firstHopName() {
    return firstHopName;
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
   * {@link Prism}) as a {@code Telescope<S, A>}. Package-private: only same-package callers in this
   * file construct telescopes from raw lattice optics. Cross-package callers ({@link
   * io.github.eschizoid.telescope.conversion.Mapper#asTelescope}, {@link
   * io.github.eschizoid.telescope.conversion.To#using}, {@link DeepMap}) go through {@link
   * #iso(Function, Function)} instead, which takes only public {@link Function} types so the
   * lattice doesn't appear in any cross-package signature.
   */
  static <S, A> Telescope<S, A> wrap(final Traversal<S, A> optic) {
    return new Telescope<>(optic);
  }

  /**
   * Package-private factory for a deep-conversion telescope that carries the introspection trail
   * the mapping engine resolved, so {@code explain()} works on the {@code Telescope<A, B>} returned
   * by {@link #map(Class, Class, io.github.eschizoid.telescope.mapping.MapStep...)}. Only {@link
   * DeepMap} calls this.
   */
  static <S, A> Telescope<S, A> mapped(final Iso<S, A> iso, final List<OpticNode> trail) {
    // The one boundary that takes a caller-owned list — copy it immutable so the constructor can
    // store every trail as-is.
    return new Telescope<>(iso, RecordFieldOptics.INSTANCE, Function.identity(), null, List.copyOf(trail));
  }

  /**
   * Build a {@code Telescope<A, B>} backed by an {@link Iso} from a forward/backward function pair.
   * The same value as {@link #from(Class)}{@code .to(...).using(forward, backward)} but as a
   * one-shot static factory. Cross-package callers (e.g. {@code conversion.Mapper#asTelescope},
   * {@code conversion.To#using}) use this so the lattice type {@code Iso} never appears in their
   * public signatures — they hand in {@link Function} pairs (which are public JDK types) and this
   * factory builds the {@code Iso} internally and wraps it.
   *
   * <pre>{@code
   * final Telescope<UserEntity, UserDto> bridge =
   *     Telescope.iso(UserDto::fromEntity, UserDto::toEntity);
   * }</pre>
   */
  public static <A, B> Telescope<A, B> iso(
    final Function<? super A, ? extends B> forward,
    final Function<? super B, ? extends A> backward
  ) {
    return wrap(Iso.of(forward, backward));
  }

  /**
   * Build a {@code Telescope<A, B>} backed by an {@link Iso} from a {@link BridgeFn} pair. Same
   * semantics as {@link #iso(Function, Function)}, but the dispatch site sees one concrete {@code
   * BridgeFn} type per bridge instead of two raw {@link Function} captures — codegen emits one
   * implementing class per {@code @Bridge}-annotated type, so the {@code forward(s)} / {@code
   * backward(t)} call inside the wrapped {@code Iso} stays monomorphic at the bridge constant's
   * read site, where {@code iso(Function, Function)}'s {@code Function::apply} hop would go
   * megamorphic across many bridges sharing the same anonymous {@code Iso} class body.
   *
   * <p>The wrapped {@code Iso} calls {@code fn.forward(...)} / {@code fn.backward(...)} directly —
   * no method-reference lambda, no {@code Function::apply} bridge.
   *
   * @param fn the bidirectional conversion pair
   * @param <A> source type
   * @param <B> target type
   * @see BridgeFn
   */
  public static <A, B> Telescope<A, B> bridge(final BridgeFn<A, B> fn) {
    final Iso<A, B> iso = new Iso<>() {
      @Override
      public B to(final A source) {
        return fn.forward(source);
      }

      @Override
      public A from(final B value) {
        return fn.backward(value);
      }
    };
    return new BridgeTelescope<>(iso, fn);
  }

  /**
   * Promote a pre-built {@code Telescope<S, List<X>>} to a typed {@link ListTelescope} so the
   * compile-checked {@link ListTelescope#each()} terminal becomes available. Used by codegen's
   * container-step classes and by power users who hold a list-typed telescope built from
   * composition. Pure lattice — no reflection, no runtime check.
   *
   * <pre>{@code
   * final Telescope<Company, List<Department>> built = ...;            // from elsewhere
   * final Telescope<Company, Department> elements = Telescope.asList(built).each();
   * }</pre>
   */
  public static <S, X> ListTelescope<S, X> asList(final Telescope<S, List<X>> path) {
    return new ListTelescope<>(path.optic, path.fieldOptics, path.chain);
  }

  /** Pre-built-fragment companion to {@link #asList} for {@code Set&lt;X&gt;} paths. */
  public static <S, X> SetTelescope<S, X> asSet(final Telescope<S, Set<X>> path) {
    return new SetTelescope<>(path.optic, path.fieldOptics, path.chain);
  }

  /** Pre-built-fragment companion to {@link #asList} for {@code Map&lt;K, V&gt;} paths. */
  public static <S, K, V> MapTelescope<S, K, V> asMap(final Telescope<S, Map<K, V>> path) {
    return new MapTelescope<>(path.optic, path.fieldOptics, path.chain);
  }

  /** Pre-built-fragment companion to {@link #asList} for {@code Optional&lt;X&gt;} paths. */
  public static <S, X> OptionalTelescope<S, X> asOptional(final Telescope<S, Optional<X>> path) {
    return new OptionalTelescope<>(path.optic, path.fieldOptics, path.chain);
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
   * io.github.eschizoid.telescope.mapping.MapStep...)} — the same deep recursive factory handles
   * both kinds and any cross-paradigm mix.
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
   * Same as {@link #lens(Function, BiFunction)} but with a Serializable {@link Accessor} for the
   * getter — the method name is recovered via {@code SerializedLambda} and stored as the
   * Telescope's first-hop name. Used by codegen-generated {@code <X>Telescope} navigators so the
   * {@code Mapping.to(srcAcc, navigatorMethod())} factory routes through the engine the same way a
   * runtime {@code Telescope.of(B.class).field(B::recipient)…} chain does.
   *
   * <p>Java's overload resolution prefers this overload over {@link #lens(Function, BiFunction)}
   * when the getter is a method reference (method refs implicitly bind to {@link Accessor}'s
   * Serializable contract), so codegen-emitted call sites pick it up automatically.
   */
  public static <S, A> Telescope<S, A> lens(
    final Accessor<S, A> getter,
    final BiFunction<? super S, ? super A, ? extends S> setter
  ) {
    return new Telescope<>(
      Lens.of(getter, setter),
      RecordFieldOptics.INSTANCE,
      Function.identity(),
      LambdaIntrospection.methodNameOf(getter)
    );
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
   * conversion functions), use {@link #map(Class, Class,
   * io.github.eschizoid.telescope.mapping.MapStep...)} — it handles both record↔record, POJO↔POJO,
   * and any cross-paradigm mix at any depth.
   *
   * @see #map(Class, Class, io.github.eschizoid.telescope.mapping.MapStep...)
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
   * import static io.github.eschizoid.telescope.mapping.Mapping.to;
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
   *   <li>{@link io.github.eschizoid.telescope.mapping.Mapping#to(Accessor, Accessor) to(src, tgt)}
   *       — same-typed rename
   *   <li>{@link io.github.eschizoid.telescope.mapping.Mapping#to(Accessor, Accessor,
   *       java.util.function.Function, java.util.function.Function) to(src, tgt, fwd, bwd)} — typed
   *       transform
   *   <li>{@link io.github.eschizoid.telescope.mapping.Mapping#via(Accessor, Accessor, Mapper)
   *       via(src, tgt, mapper)} — nested mapper
   *   <li>{@link io.github.eschizoid.telescope.mapping.WriteHint#writeBean(Class,
   *       io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy) writeBean(target,
   *       strategy)} — per-target write-strategy override (e.g. force {@code CONSTRUCTOR} for an
   *       immutable all-args-only POJO that {@code Beans.autoWriter} refuses)
   * </ul>
   *
   * @param source the source root class — record or POJO (root of the recursion)
   * @param target the target root class — record or POJO (root of the recursion)
   * @param steps {@code Mapping} field overrides and/or {@code WriteHint} construction directives
   * @param <A> the source root type
   * @param <B> the target root type
   * @see #mapper(Class, Class, MapStep...)
   * @see io.github.eschizoid.telescope.mapping.Mapping
   * @see io.github.eschizoid.telescope.mapping.WriteHint
   * @see DeepMap
   */
  // No @SafeVarargs needed: MapStep is reifiable (no type parameter), so this varargs method does
  // not produce heap-pollution warnings for callers.
  public static <A, B> Telescope<A, B> map(final Class<A> source, final Class<B> target, final MapStep... steps) {
    // Mirror the rejection that .mapper(...) does — a forward-only row would silently corrupt the
    // returned Telescope's backward leg (set/update through a path whose constituent Iso has a
    // throwing inverse). Catching it at the factory boundary keeps the diagnostic at the call site.
    rejectForwardOnlyRows(source, target, steps, "Telescope.map");
    return DeepMap.resolveMapped(source, target, steps);
  }

  /**
   * {@link Mapper} sibling of {@link #map(Class, Class, MapStep...)} — same deep recursion, but
   * returns a {@code Mapper<A, B>} (exposes {@link Mapper#patch} for sparse overlays at the top
   * level and is nestable in another mapping via {@link
   * io.github.eschizoid.telescope.mapping.Mapping#via(Accessor, Accessor, Mapper)}).
   *
   * @see #map(Class, Class, MapStep...)
   */
  public static <A, B> Mapper<A, B> mapper(final Class<A> source, final Class<B> target, final MapStep... steps) {
    rejectForwardOnlyRows(source, target, steps, "Telescope.mapper");
    return DeepMap.resolveMapper(source, target, steps);
  }

  /**
   * Fluent builder for assembling a {@link Mapper} from multiple groups of {@link MapStep} rows —
   * closes MapStruct's {@code @InheritConfiguration} for sharing row sets across related mappers.
   * Use when several mappers share a base group (audit columns, tenant pinning, null-handling
   * defaults) and each variant adds its own rows; the builder reads as a sequence of intentional
   * inherit / add steps rather than an opaque array spread.
   *
   * <pre>{@code
   * private static final MapStep[] AUDIT_COLUMNS = {
   *     to(Entity::createdAt, Dto::createdAt),
   *     to(Entity::updatedAt, Dto::updatedAt)};
   *
   * final var userMapper = Telescope.mapperBuilder(User.class, UserDto.class)
   *     .inherit(AUDIT_COLUMNS)
   *     .add(to(User::email, UserDto::emailAddress))
   *     .build();
   * }</pre>
   *
   * <p>Mechanically equivalent to spreading the accumulated steps into {@link #mapper(Class, Class,
   * MapStep...)}; the engine's row-routing / hint-validation / sealed-permit dispatch all run
   * unchanged. See {@link MapperBuilder} for full semantics.
   *
   * @see MapperBuilder
   */
  public static <A, B> MapperBuilder<A, B> mapperBuilder(final Class<A> source, final Class<B> target) {
    return MapperBuilder.create(source, target);
  }

  // Detect forward-only rows (`Mapping.toOneWay(...)`) and steer the caller to mapperForward(...)
  // so the partial-Iso shape doesn't silently corrupt downstream Mapper.backward / Mapper.patch
  // semantics. The check is O(steps.length); rows are typically <20 per mapper. Skips for
  // mapperForward callers which legitimately accept forward-only rows.
  private static void rejectForwardOnlyRows(
    final Class<?> source,
    final Class<?> target,
    final MapStep[] steps,
    final String factoryName
  ) {
    for (final var step : steps) {
      if (step instanceof ForwardOnlyTransformTo<?, ?, ?, ?> r) {
        throw new IllegalArgumentException(
          factoryName +
            "(" +
            source.getSimpleName() +
            ", " +
            target.getSimpleName() +
            ", ...) cannot accept a Mapping.toOneWay(...) row for field '" +
            r.targetField() +
            "' — Mapping.toOneWay(...) is forward-only and would silently corrupt Mapper.backward / Mapper.patch. " +
            "Use Telescope.mapperForward(" +
            source.getSimpleName() +
            ", " +
            target.getSimpleName() +
            ", ...) for a typed forward-only result, or Mapping.to(src, tgt, forward, backward) for " +
            "an explicit bidirectional row."
        );
      }
    }
  }

  /**
   * Forward-only sibling of {@link #mapper(Class, Class, MapStep...)} — returns a {@link
   * ForwardMapper} whose backward direction is not present at the type level. Use when the
   * conversion is genuinely one-way (entity → DTO write-only, audit-log projection, normalisation
   * pipeline) and rows include {@link Mapping#toOneWay toOneWay(...)} / {@link Mapping#constant
   * constant(...)} / {@link Mapping#compute compute(...)} that make the backward direction
   * meaningless.
   *
   * <p>The compiler enforces the one-way contract — there is no {@code backward(...)} method on
   * {@link ForwardMapper} to call. MapStruct cannot express "this mapper is one-way" in its type
   * system; this is the differentiator the type system buys.
   *
   * <pre>{@code
   * ForwardMapper<UserEntity, UserDto> projector = Telescope.mapperForward(
   *     UserEntity.class, UserDto.class,
   *     to(UserEntity::id, UserDto::id),
   *     toOneWay(UserEntity::createdAt, UserDto::createdAtIso, Instant::toString),
   *     constant(UserDto::tenant, "production"));
   *
   * UserDto dto = projector.forward(entity);
   * }</pre>
   *
   * <p><b>Lenient by default.</b> Unmatched target properties take their JLS default ({@code null}
   * for reference, {@code 0} for primitives, {@code false} for {@code boolean}); unmatched source
   * properties are silently ignored. This matches MapStruct's generated-mapper default for every
   * mapper and removes the "many drops + constants for a small rename" friction on the small-DTO →
   * large-entity migration shape. Use the bidirectional {@link #mapper(Class, Class, MapStep...)}
   * instead if you need the strict bijection check (which guards {@code backward()} against
   * silently losing data).
   *
   * <p><b>Auto-discovery from {@code @Bridge}.</b> When {@code steps} is empty AND the {@code
   * source} class has a sibling {@code <Source>Bridge.BRIDGE} (or {@code <Source>To<Target>Bridge.
   * BRIDGE}) constant emitted by the {@code @Bridge} annotation processor, the forward direction
   * routes through that bridge directly. The bridge's full configuration is surfaced in the result
   * — {@code @Rename} (including {@code forwardOnly} fan-out), {@code @Transform},
   * {@code @Constant}, {@code @Compute}, {@code @Default}, {@code @ViaMapper}, {@code drops}, the
   * {@code writeStrategy} ({@code AUTO/CTOR/BUILDER/SETTERS}), and the {@code lenient} flag are all
   * encoded inside the bridge and apply to every {@code mapperForward(source, target)} call. The
   * annotation's defaults are NOT the same as this method's lenient-by-default —
   * {@code @Bridge(lenient = false)} (the annotation's default) produces a strict bijection bridge,
   * so the resulting mapper rejects mappings the row-free fallback would have accepted. To keep the
   * row-free lenient-default-everything path, pass any explicit row (even a no-op {@code
   * nullSourceValues(DEFAULT)}); per-field rows force the {@link DeepMap#resolveForward} path.
   */
  public static <A, B> ForwardMapper<A, B> mapperForward(
    final Class<A> source,
    final Class<B> target,
    final MapStep... steps
  ) {
    // Forward-only: lenient by default. Unmatched target fields silently take JLS defaults,
    // unmatched source fields are silently ignored — no `drop()` / `constant()` rows required
    // for the common "small DTO → large entity" migration shape. Matches MapStruct's default.
    // Bidirectional `mapper(...)` keeps the strict bijection check.

    // No per-field rows: probe for a sibling @Bridge-generated <Source>Bridge.BRIDGE constant
    // and route directly through it when present. See the javadoc's "Auto-discovery from
    // @Bridge" paragraph for the full bridge-baked configuration surface. With rows present,
    // the caller has opted into explicit configuration; skip the probe and run the rows
    // through DeepMap as usual.
    if (steps.length == 0) {
      final var probed = BridgeHolderProbe.probeFor(source, target);
      if (probed.isPresent()) {
        @SuppressWarnings("unchecked")
        final var bridge = (Telescope<A, B>) probed.get().bridge();
        return ForwardMapper.create(bridge::read, source, target);
      }
      // The name-derived probe above only reaches a sibling <Source>Bridge in the source's package.
      // A carrier-form @Bridge lives in the carrier's package, so fall back to the package-agnostic
      // registry before the lenient same-name default — otherwise a declared carrier bridge (and
      // its
      // renames) would be silently dropped.
      final var registered = BridgeRegistry.find(source, target, source.getClassLoader());
      if (registered.isPresent()) {
        @SuppressWarnings("unchecked")
        final var bridge = (Telescope<A, B>) registered.get();
        return ForwardMapper.create(bridge::read, source, target);
      }
    }
    return DeepMap.resolveForwardMapper(source, target, steps);
  }

  /**
   * Forward-only factory for {@code Map<String, Object> → T} mappings — the typed entry point for
   * adopters consuming untyped sources (JDBC {@code ResultSet} maps, framework request-body
   * parsers, message-bus payload decoders). Each row supplies a map key, a target component
   * accessor, and a converter that turns the raw {@code Object} into the typed component value.
   *
   * <pre>{@code
   * import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
   *
   * ForwardMapper<Map<String, Object>, CaseListRequest> m = Telescope.fromMap(
   *     CaseListRequest.class,
   *     extract("bookingType", CaseListRequest::getBookingType, Object::toString),
   *     extract("caseId",      CaseListRequest::getCaseId,      Object::toString),
   *     extract("priority",    CaseListRequest::getPriority,    v -> Integer.parseInt(v.toString())));
   * }</pre>
   *
   * <p><b>Lenient by default.</b> Missing keys (and any target component not named by an {@code
   * extract(...)} row) take the JLS default for the component's declared type — {@code 0} for
   * numeric primitives, {@code false} for {@code boolean}, {@code null} for references, empty
   * singletons for {@code List}/{@code Set}/{@code Map}, {@link java.util.Optional#empty()} for
   * {@code Optional}. Symmetric with {@link #mapperForward(Class, Class, MapStep...)}.
   *
   * <p>The backward direction ({@code T → Map<String, Object>}) is not generated by design — the
   * map shape is the boundary layer, not a typed counterpart, and round-tripping back to a flat
   * {@code String}-keyed map would silently corrupt nested-component identity (the key encoding
   * would have to invent a policy — verbatim names? camelCase → snake_case? configurable?).
   * Adopters who genuinely need that round-trip write it explicitly.
   *
   * @param target the typed target class — record or POJO; rebuild strategy auto-detected via
   *     {@link Records#construct(Class, java.util.function.Function)} / {@link
   *     Beans#autoWriter(Class)}.
   * @param rows one {@link MapExtractStep#extract(String, Accessor, java.util.function.Function)}
   *     per target component to fill.
   * @return a {@link ForwardMapper} from {@code Map<String, Object>} to {@code T}, ready to inject
   *     as a CDI/Spring bean.
   */
  @SuppressWarnings("unchecked")
  public static <T> ForwardMapper<Map<String, Object>, T> fromMap(final Class<T> target, final MapExtractStep... rows) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(rows, "rows");
    final var byField = new LinkedHashMap<String, Extract<?, ?>>();
    for (final var row : rows) {
      if (!(row instanceof Extract<?, ?> e)) throw new IllegalArgumentException(
        "Telescope.fromMap rows must be built via MapExtractStep.extract(...)"
      );
      final var rawName = LambdaIntrospection.methodNameOf(e.targetAccessor());
      final var prop = Beans.propertyOf(rawName);
      final var fieldName = prop == null ? rawName : prop;
      if (byField.put(fieldName, e) != null) throw new IllegalArgumentException(
        "Telescope.fromMap: duplicate extract row for target field '" + fieldName + "'"
      );
    }
    // Per-component JLS-default lookup table — built once at factory time, queried on every
    // forward pass for unmatched target components. Records expose typed generic-type info on each
    // RecordComponent; POJOs query Beans.propertyType per property.
    final var defaultsByName = new HashMap<String, Object>();
    final var isRecord = target.isRecord();
    if (isRecord) {
      for (final var c : target.getRecordComponents()) {
        defaultsByName.put(c.getName(), NullDefaults.defaultFor(c.getGenericType()));
      }
    } else {
      for (final var name : Beans.propertyNames(target)) {
        defaultsByName.put(name, NullDefaults.defaultFor(Beans.propertyType(target, name)));
      }
    }
    // Hoist the reflective writer + property-name array out of the hot path. Recomputing them per
    // forward() would re-walk the bean's accessor methods every call — invariants in the closure.
    final Beans.BeanWriter<T> writer = isRecord ? null : Beans.autoWriter(target);
    final String[] propertyNames = isRecord ? null : Beans.propertyNames(target);
    final Function<Map<String, Object>, T> forward = mapSrc -> {
      if (mapSrc == null) return null;
      final Function<String, Object> valueByName = name -> {
        final var row = byField.get(name);
        if (row == null) return defaultsByName.get(name); // unmatched target → JLS default
        final var raw = mapSrc.get(row.key());
        return ((Extract<?, Object>) row).converter().apply(raw);
      };
      if (isRecord) return Records.construct(target, valueByName);
      return writer.construct(propertyNames, valueByName);
    };
    return ForwardMapper.create(forward, (Class<Map<String, Object>>) (Class<?>) Map.class, target);
  }

  /**
   * Forward-only N-source mapper that reads from any number of source objects (one per distinct
   * runtime class) and assembles a single target. Each {@link
   * io.github.eschizoid.telescope.mapping.MergeStep MergeStep} row identifies its source by the
   * accessor's declaring class (via {@code SerializedLambda} inference) and binds it to a target
   * component by name.
   *
   * <pre>{@code
   * Mapper<Sources, Profile> mapper = Telescope.merge(Profile.class,
   *     from(Customer::id,        Profile::id),
   *     from(Customer::email,     Profile::email),
   *     from(Audit::createdBy,    Profile::createdBy),
   *     from(Audit::createdAt,    Profile::createdAt));
   *
   * Profile p = mapper.forward(Sources.of(customer, audit));
   *
   * // Same factory, more sources — no new overload, no per-arity ceremony:
   * Mapper<Sources, Invoice> bigger = Telescope.merge(Invoice.class,
   *     from(Customer::id,         Invoice::customerId),
   *     from(Audit::createdBy,     Invoice::createdBy),
   *     from(LineItem::totalCents, Invoice::totalCents),
   *     from(Tax::rate,            Invoice::taxRate),
   *     from(Promo::code,          Invoice::promoCode));
   * Invoice inv = bigger.forward(Sources.of(c, a, li, tax, promo));
   * }</pre>
   *
   * <p>Replaces the {@code Edit.over(...)} workaround that loses the typed single-source contract.
   * The recommended path for {@code PLAN.md} item 1.3 — single arity-agnostic factory, no per-arity
   * ceremony.
   *
   * <p><b>Distinct runtime classes.</b> Each source in the {@link Sources} bag must have a distinct
   * runtime class; {@link Sources#of(Object[])} throws on duplicates. If two same-typed sources are
   * needed, pre-aggregate them into a named holder record before the merge.
   *
   * <p><b>Backward is unsupported.</b> The multi-source case has no general inverse (which source
   * gets which fields back?); {@link Mapper#backward(Object)} and {@link Mapper#patch(Object,
   * Object)} both throw on the returned mapper. For bidirectional same-source mapping, use {@link
   * #mapper(Class, Class, MapStep...)} on each source individually.
   *
   * @param <T> the target type
   * @param target the target class
   * @param steps the per-component correspondences
   * @return a {@code Mapper} whose {@code forward} reads from {@link Sources} and assembles the
   *     target; whose {@code backward} throws {@link UnsupportedOperationException}
   * @see io.github.eschizoid.telescope.mapping.MergeStep#from(Accessor, Accessor)
   * @see io.github.eschizoid.telescope.mapping.MergeStep#auto(Class)
   * @see Sources#of(Object[])
   * @see Sources#builder()
   */
  @SafeVarargs
  @SuppressWarnings("varargs")
  public static <T> Mapper<Sources, T> merge(
    final Class<T> target,
    final io.github.eschizoid.telescope.mapping.MergeStep<T>... steps
  ) {
    return Merge.build(target, steps);
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
    return new Telescope<>(
      optic.then(lens),
      fieldOptics,
      chain,
      hopName(getter),
      plus(new OpticNode.Focus(fieldNameOf(getter)))
    );
  }

  /**
   * First-hop name capture: only the first .field/.each/.eachValue/.whenPresent on a path is
   * tracked.
   */
  private String hopName(final Accessor<?, ?> getter) {
    return firstHopName != null ? firstHopName : LambdaIntrospection.methodNameOf(getter);
  }

  /**
   * Typed-container variant of {@link #field(Accessor)} for {@code List<X>} components. Picked by
   * the user when they want the resulting telescope to carry the list type for later {@link
   * ListTelescope#each()} navigation. Returns a {@link ListTelescope} whose {@code each()} terminal
   * descends into list elements via pure lattice composition (no runtime container dispatch).
   *
   * <p>Java's type erasure prevents an overload on {@code field(...)} that returns a narrower
   * subclass when the accessor's return type is {@code List<X>} — both overloads erase to {@code
   * field(Accessor)}. This distinct name is the workaround.
   *
   * <pre>{@code
   * final ListTelescope<Company, Department> deps =
   *     Telescope.of(Company.class).list(Company::departments);
   * deps.each().field(Department::name).update(co, fn);
   * }</pre>
   */
  public <X> ListTelescope<S, X> list(final Accessor<A, List<X>> getter) {
    final Lens<A, List<X>> lens = lensForAccessor(getter);
    return new ListTelescope<>(optic.then(lens), fieldOptics, chain, hopName(getter));
  }

  /**
   * Typed-container variant of {@link #field(Accessor)} for {@code Set<X>} components. Returns a
   * {@link SetTelescope} carrying the set type for later {@link SetTelescope#each()} navigation.
   *
   * <p>Named {@code setField} (rather than {@code set}) to avoid cognitive collision with the write
   * terminal {@link #set(Object, Object)} — they take different argument types, but the shared verb
   * load is real enough that disambiguation pays off at the call site.
   */
  public <X> SetTelescope<S, X> setField(final Accessor<A, Set<X>> getter) {
    final Lens<A, Set<X>> lens = lensForAccessor(getter);
    return new SetTelescope<>(optic.then(lens), fieldOptics, chain, hopName(getter));
  }

  /**
   * Typed-container variant of {@link #field(Accessor)} for {@code Map<K, V>} components. Returns a
   * {@link MapTelescope} carrying the map type for later {@link MapTelescope#values()} navigation.
   *
   * <p>Named {@code mapField} (rather than {@code map}) to disambiguate from the sibling static
   * deep-conversion factory {@link #map(Class, Class,
   * io.github.eschizoid.telescope.mapping.MapStep...)} — those do conceptually different things and
   * share the same verb otherwise.
   */
  public <K, V> MapTelescope<S, K, V> mapField(final Accessor<A, Map<K, V>> getter) {
    final Lens<A, Map<K, V>> lens = lensForAccessor(getter);
    return new MapTelescope<>(optic.then(lens), fieldOptics, chain, hopName(getter));
  }

  /**
   * Typed-container variant of {@link #field(Accessor)} for {@code Optional<X>} components. Returns
   * an {@link OptionalTelescope} carrying the optional type for later {@link
   * OptionalTelescope#present()} navigation.
   */
  public <X> OptionalTelescope<S, X> optional(final Accessor<A, Optional<X>> getter) {
    final Lens<A, Optional<X>> lens = lensForAccessor(getter);
    return new OptionalTelescope<>(optic.then(lens), fieldOptics, chain, hopName(getter));
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
   * processor — it generates a typed {@code <X>Telescope<R>} navigator at build time.
   */
  public <B> Telescope<S, B> fieldByName(final String fieldName) {
    // Dispatch on the carried fieldOptics — the same discriminator that .field(Accessor) uses.
    // A Telescope built via `ofBean(...)` carries BeanFieldOptics; everything else carries
    // RecordFieldOptics. Without this dispatch, fieldByName on a bean Telescope would build a
    // Records.fieldLens that fails at call time because the focus type isn't a record.
    final Lens<A, B> fieldLens =
      fieldOptics == BeanFieldOptics.INSTANCE ? Beans.fieldLens(fieldName) : Records.fieldLens(fieldName);
    return new Telescope<>(optic.then(fieldLens), fieldOptics, chain, null, plus(new OpticNode.Focus(fieldName)));
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
    return new Telescope<>(
      optic.then(lens).then(elements),
      fieldOptics,
      chain,
      hopName(getter),
      plus(new OpticNode.Traverse(fieldNameOf(getter), "collection"))
    );
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
    return new Telescope<>(
      optic.then(lens).then(values),
      fieldOptics,
      chain,
      hopName(getter),
      plus(new OpticNode.Traverse(fieldNameOf(getter), "map values"))
    );
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
    return new Telescope<>(
      optic.then(lens).then(present),
      fieldOptics,
      chain,
      hopName(getter),
      plus(new OpticNode.Traverse(fieldNameOf(getter), "optional"))
    );
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
    return new Telescope<>(
      optic.then(prism),
      fieldOptics,
      chain,
      null,
      plus(new OpticNode.Narrow(subType.getSimpleName()))
    );
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
    return new Telescope<>(optic.filter(predicate), fieldOptics, chain, null, plus(new OpticNode.Filter("predicate")));
  }

  /**
   * Compose a post-read hook onto the path: every value flowing OUT of this telescope (via {@link
   * #read}, {@link #find}, {@link #toList}, {@link #count}, {@link #exists}, the {@link #update}
   * family's pre-hook leaf, or downstream {@link #then} composition) is passed through {@code hook}
   * before reaching the caller / next stage. The lattice-native equivalent of {@link
   * io.github.eschizoid.telescope.conversion.Mapper#afterForward(java.util.function.Function)
   * Mapper.afterForward}, but applied at the path level — composes through {@code .then(...)},
   * {@link Edit#over Edit.over}, {@link #updateAsync}, and codegen-generated navigators. MapStruct
   * cannot reach this; its annotations bind to mapper methods, not paths.
   *
   * <p>Writes pass through unchanged — only the read side gets the hook. For symmetric write
   * transformation, see {@link #before(Function)}.
   *
   * <p><b>Lattice note — one-sided shape.</b> The internal {@link Iso} composed here uses {@code
   * hook} on the read side and identity on the write side, so the produced Iso does <em>not</em>
   * satisfy the round-trip law ({@code from(to(a)) == a} only holds when {@code hook} is identity).
   * Safe under {@code Lens.then(Iso)} composition — which routes reads and writes through separate
   * legs and never round-trips a single value through both — but future contributors must not
   * assume this is a lawful Iso in isolation. The same caveat applies to {@link #before(Function)},
   * {@link Mapping#toOneWay Mapping.toOneWay}, and {@link Mapping#toOrElse Mapping.toOrElse}.
   *
   * <pre>{@code
   * Telescope.of(User.class).field(User::email).after(String::trim)
   *     .read(user);                 // returns the trimmed email
   * }</pre>
   */
  public Telescope<S, A> after(final Function<? super A, ? extends A> hook) {
    return this.then(Telescope.iso(hook, a -> a));
  }

  /**
   * Compose a pre-write hook onto the path: every value flowing IN to this telescope (via {@link
   * #set}, {@link #update}, {@link #updateAsync}, {@link Edit#over Edit.over}'s write-leaf, or
   * downstream {@link #then} composition) is passed through {@code hook} before reaching the
   * underlying writer. The lattice-native equivalent of {@link
   * io.github.eschizoid.telescope.conversion.Mapper#beforeBackward(java.util.function.Function)
   * Mapper.beforeBackward}, applied at the path level.
   *
   * <p>Reads pass through unchanged — only the write side gets the hook. For symmetric read
   * transformation, see {@link #after(Function)}.
   *
   * <p><b>Lattice note — one-sided shape.</b> Same caveat as {@link #after(Function)}: the composed
   * Iso uses identity on the read side and {@code hook} on the write side, so the round-trip law
   * holds only when {@code hook} is identity. Safe under {@code Lens.then(Iso)} composition; not a
   * lawful Iso in isolation.
   *
   * <pre>{@code
   * Telescope.of(User.class).field(User::email).before(String::toLowerCase)
   *     .set(user, "ALICE@X.COM");   // writes "alice@x.com"
   * }</pre>
   */
  public Telescope<S, A> before(final Function<? super A, ? extends A> hook) {
    return this.then(Telescope.iso(a -> a, hook));
  }

  /**
   * Compose this telescope with another via the lattice's {@code .then}. Lets you build a path in
   * pieces and stitch them together, and is how reusable conversions ({@link #from}, {@link
   * #map(Class, Class, io.github.eschizoid.telescope.mapping.MapStep...)}) get threaded into a
   * longer path.
   *
   * <pre>{@code
   * final var userEmail = Telescope.of(User.class).field(User::email);   // reusable tail
   * Telescope.of(Team.class).each(Team::users).then(userEmail)
   *     .update(team, String::toLowerCase);
   * }</pre>
   */
  public <B> Telescope<S, B> then(final Telescope<A, B> next) {
    // Prefer this side's firstHopName — only the FIRST hop on a chain is tracked. If this side has
    // none (e.g. composing a root Telescope.of(...) with a sub-path), inherit from next. The
    // explain() trail concatenates both sides' hops in order, so a composed path describes the
    // whole route.
    // Both sides' trails are already immutable, so when one side is empty (common on codegen paths,
    // where the hop is appended after composition) reuse the other directly — no allocation. Only a
    // genuine two-sided join builds a fresh list.
    final List<OpticNode> joinedTrail;
    if (next.trail.isEmpty()) joinedTrail = trail;
    else if (trail.isEmpty()) joinedTrail = next.trail;
    else {
      final var joined = new ArrayList<OpticNode>(trail.size() + next.trail.size());
      joined.addAll(trail);
      joined.addAll(next.trail);
      joinedTrail = Collections.unmodifiableList(joined);
    }
    return new Telescope<>(
      optic.then(next.optic),
      fieldOptics,
      chain,
      firstHopName != null ? firstHopName : next.firstHopName,
      joinedTrail
    );
  }

  /**
   * Describe what this telescope does, as a queryable {@link OpticReport}. For a navigation path
   * ({@code of(…).each(…).field(…)}) the report is the ordered hop trail; for a conversion built by
   * {@link #map(Class, Class, io.github.eschizoid.telescope.mapping.MapStep...)} it is the field
   * rows the deep-mapping engine resolved. A bare {@code Telescope.of(…)} identity — or any
   * iso-backed telescope with no recorded path — yields the {@link OpticReport#isEmpty() empty}
   * report. Never throws.
   *
   * <p>The trail is captured at build time from the same steps that compose the optic, so the
   * report cannot drift from what the telescope actually does.
   *
   * @return the structure of this telescope; never null
   */
  public OpticReport explain() {
    return new OpticReport(trail);
  }

  /**
   * <b>Codegen-support seam — NOT for hand-written call sites.</b> Return a copy of this telescope
   * with one {@link OpticNode} appended to its introspection trail, so a generated {@code
   * <X>Telescope} navigator — which composes via {@link #lens(Function, BiFunction)} rather than
   * the {@code SerializedLambda}-decoding {@link #field(Accessor)} — still answers {@link
   * #explain()} / {@link #trace(Object)} with what it navigated. The processors emit {@code
   * .hop(new OpticNode.Focus("field"))} after a lens composition, {@code new
   * OpticNode.Traverse(...)} on a container step's {@code each()}, and {@code new
   * OpticNode.Bridge(...)} on an {@code as<Target>()} hop. Hand-written paths use {@link
   * #field(Accessor)} / {@link #each(Accessor)}, which record the hop automatically.
   *
   * @param node the trail node for this hop
   * @return a copy with the hop recorded; the optic and all other state are unchanged
   */
  public Telescope<S, A> hop(final OpticNode node) {
    return new Telescope<>(optic, fieldOptics, chain, firstHopName, plus(node));
  }

  /**
   * Execute this telescope's navigation against {@code input} and describe what each hop did to the
   * data, as a {@link Trace} tree. Where {@link #explain()} is the static path, {@code trace} runs
   * it: single-focus hops ({@code field}) descend linearly; many-focus hops ({@code each} / {@code
   * eachValue} / {@code whenPresent}) expand into one subtree per element. A pure single-focus path
   * stays linear.
   *
   * <p>This is a debugging aid, run off the hot path — it materializes one node per focus. Over a
   * large collection use {@link #trace(Object, TraceLimits)} with explicit caps, or accept the safe
   * {@link TraceLimits#defaults() defaults} this overload applies (10 elements per fan-out, 20
   * deep) which truncate with a {@code … (+K more)} marker.
   *
   * <p><b>Structural fidelity caveat.</b> For a <em>navigation</em> path, {@code trace} re-reads
   * values by field name; it does not execute the built optic. {@code filter}, {@code as} (narrow),
   * and codegen bridge hops are recorded and their value is passed through unchanged — the
   * predicate, subtype check, and bridge conversion are not captured in the trail, so trace cannot
   * apply them. A trace may therefore show a value a real {@code read} would exclude (a
   * filtered-out element, a non-matching subtype), and a field read downstream of an unapplied
   * bridge/narrow that doesn't exist on the un-converted value renders as {@code (n/a)} rather than
   * throwing. (A <em>mapping</em>-built telescope from {@link #map(Class, Class,
   * io.github.eschizoid.telescope.mapping.MapStep...)} is the exception: it runs the conversion
   * forward once to fill the value column, since its rows have no field-by-field structural read.)
   * Use {@code trace} to see the path shape and per-field values; use {@code read} / {@code find}
   * for the exact result.
   *
   * @param input the value to run the path against
   * @return the executed trace tree; never null
   */
  public Trace trace(final S input) {
    return trace(input, TraceLimits.defaults());
  }

  /**
   * {@link #trace(Object)} with explicit caps — use {@link TraceLimits#none()} for the full tree.
   */
  public Trace trace(final S input, final TraceLimits limits) {
    if (trail.isEmpty()) {
      // No instrumented trail (a bare identity, or an iso-backed conversion from from/to/using or a
      // bridge). Render what the telescope actually produces — the executed focus — not the raw
      // input, which would be wrong whenever the output differs from the input; fall back to the
      // input when there is no focus.
      final var focus = find(input);
      return new Trace(List.of(Trace.Node.leaf(renderValue(focus.isPresent() ? focus.get() : input))));
    }
    final var rowCount = trail
      .stream()
      .filter(n -> n instanceof OpticNode.Row)
      .count();
    // A pure mapping telescope (from Telescope.map — all Rows) value-traces the conversion,
    // rendering
    // each row's source value → target value like Mapper.trace. A pure navigation path (all Hops)
    // executes structurally into a tree.
    if (rowCount == trail.size()) return mappingRowsTrace(input);
    if (rowCount == 0) return new Trace(List.of(traceHop(trail, 0, input, limits, 0)));
    // Mixed (a mapping telescope further navigated, e.g. map(A, B).field(B::x)): the Row prefix is
    // a
    // whole conversion the field walk can't execute, and running the full optic then reading
    // mapping
    // rows off the navigated leaf would misread. Fall back to a safe execution-only trace of the
    // final value rather than emit a misleading per-row breakdown.
    return new Trace(List.of(Trace.Node.leaf(renderValue(find(input).orElse(null)))));
  }

  // A mapping-built Telescope (Telescope.map) carries field Rows; its trace shows the same value
  // column as Mapper.trace — run the conversion forward to get the output, then render each row's
  // source value → target value. Shares the renderer so the two surfaces can't drift.
  private Trace mappingRowsTrace(final S input) {
    final var output = find(input).orElse(null);
    return MappingTraces.of(input, output, trail);
  }

  private static Trace.Node traceHop(
    final List<OpticNode> hops,
    final int i,
    final Object value,
    final TraceLimits limits,
    final int depth
  ) {
    final var hop = hops.get(i);
    final var last = i == hops.size() - 1;
    if (hop instanceof OpticNode.Focus f) {
      final var v = readField(value, f.path());
      if (last) return Trace.Node.leaf(f.path() + " → " + renderValue(v));
      return new Trace.Node(f.path(), List.of(traceHop(hops, i + 1, v, limits, depth)), false);
    }
    if (hop instanceof OpticNode.Traverse t) {
      if (depth >= limits.maxDepth()) return Trace.Node.cut("each " + t.path() + " … (depth cap)");
      final var container = readField(value, t.path());
      // An unreadable container (e.g. the field read downstream of an unapplied bridge/narrow) must
      // surface (n/a) like a Focus does — not degrade into an empty fan-out that looks like an
      // empty
      // collection. Honours the trace() javadoc caveat on every hop kind, not just Focus.
      if (container == UNREADABLE) return Trace.Node.leaf("each " + t.path() + " → (n/a)");
      // Materialize only up to maxBreadth elements — a sized container reports its total via size()
      // without copying every element, so the breadth cap bounds memory even for a huge collection.
      final var elements = boundedElementsOf(container, limits.maxBreadth());
      final var shown = elements.shown();
      final var children = new ArrayList<Trace.Node>();
      for (final var elem : shown) {
        if (last) children.add(Trace.Node.leaf(renderValue(elem)));
        else children.add(
          new Trace.Node(renderValue(elem), List.of(traceHop(hops, i + 1, elem, limits, depth + 1)), false)
        );
      }
      if (elements.total() > shown.size()) children.add(
        Trace.Node.cut("… (+" + (elements.total() - shown.size()) + " more)")
      );
      return new Trace.Node("each " + t.path(), children, false);
    }
    // Narrow / Filter / Bridge are structural annotations: the subtype check, predicate, and bridge
    // conversion are NOT captured in the trail, so trace cannot apply them — it records the hop and
    // passes the value through unchanged. See the trace() javadoc caveat.
    if (hop instanceof OpticNode.Narrow n) return passThrough(
      hops,
      i,
      "as " + n.targetType(),
      value,
      limits,
      depth,
      last
    );
    if (hop instanceof OpticNode.Filter) return passThrough(hops, i, "filter", value, limits, depth, last);
    if (hop instanceof OpticNode.Bridge b) return passThrough(
      hops,
      i,
      "as " + b.targetType(),
      value,
      limits,
      depth,
      last
    );
    return Trace.Node.leaf(String.valueOf(hop));
  }

  private static Trace.Node passThrough(
    final List<OpticNode> hops,
    final int i,
    final String label,
    final Object value,
    final TraceLimits limits,
    final int depth,
    final boolean last
  ) {
    if (last) return Trace.Node.leaf(label + " → " + renderValue(value));
    return new Trace.Node(label, List.of(traceHop(hops, i + 1, value, limits, depth)), false);
  }

  // Sentinel for a read that could not apply — e.g. a field read downstream of an unapplied bridge
  // or narrow, where the value is not the type the field belongs to. Surfaced as "(n/a)", never
  // swallowed silently and never thrown (trace is a debug aid, not a load-bearing read).
  private static final Object UNREADABLE = new Object();

  private static Object readField(final Object value, final String name) {
    if (value == null) return null;
    try {
      return Reflective.of(value.getClass()).read(value, name);
    } catch (final RuntimeException e) {
      return UNREADABLE;
    }
  }

  // The first `cap` elements of a container plus its total count — so a fan-out renders the capped
  // slice without materializing a large collection. A Collection/Map reports size() in O(1) and
  // only
  // the shown slice is copied; a bare Iterable is walked once, storing only the cap (bounded
  // memory)
  // while still counting the total so the "(+K more)" marker stays exact.
  private static Elements boundedElementsOf(final Object container, final int cap) {
    if (container == null || container == UNREADABLE) return new Elements(List.of(), 0);
    if (container instanceof Optional<?> o) return o.isPresent()
      ? new Elements(List.of(o.get()), 1)
      : new Elements(List.of(), 0);
    if (container instanceof Collection<?> c) return new Elements(firstN(c, cap), c.size());
    if (container instanceof Map<?, ?> m) return new Elements(firstN(m.values(), cap), m.size());
    if (container instanceof Iterable<?> it) {
      final var shown = new ArrayList<Object>();
      var total = 0;
      for (final var e : it) {
        if (shown.size() < cap) shown.add(e);
        total++;
      }
      return new Elements(shown, total);
    }
    return new Elements(List.of(container), 1);
  }

  private static List<Object> firstN(final Iterable<?> it, final int cap) {
    final var out = new ArrayList<Object>();
    for (final var e : it) {
      if (out.size() >= cap) break;
      out.add(e);
    }
    return out;
  }

  /** The shown (capped) elements of a fan-out plus the container's total element count. */
  private record Elements(List<Object> shown, int total) {}

  private static String renderValue(final Object v) {
    if (v == UNREADABLE) return "(n/a)";
    if (v == null) return "null";
    if (v instanceof String s) return "\"" + s + "\"";
    return String.valueOf(v);
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
    if (optic instanceof final Lens<S, A> lens) {
      if (source == null) throw noValue();
      return lens.get(source);
    }
    if (optic instanceof final Affine<S, A> affine) {
      return affine.getOption(source).orElseThrow(this::noValue);
    }
    // Stream.findFirst() routes through Optional.of(element), which NPEs on null. A Traversal
    // can legitimately surface null elements when an intermediate hop of a multi-hop bean path
    // is null — Beans.readProperty short-circuits to null on a null receiver, but the traversal
    // still produces a one-element [null] stream. Use a stream iterator to grab the head in
    // O(1) without materialising the rest, and preserve nulls explicitly.
    final var it = optic.getAll(source).iterator();
    if (!it.hasNext()) throw noValue();
    return it.next();
  }

  /**
   * Build a {@link NoSuchElementException} for the "no focused value" case, carrying the path's
   * first-hop method name when one was captured. Naming the entry point lets a caller identify
   * which {@code Telescope} produced the empty read; without it every empty-read exception is
   * indistinguishable.
   *
   * <p>Capture is best-effort: {@link #fieldByName(String)} and the no-arg {@code each()} produce a
   * {@code null} first hop, and the message falls back to the generic form. Direct-Iso entry points
   * ({@link #from(Class)}) likewise carry no first hop until the first {@code .field(...)} is
   * appended.
   */
  private NoSuchElementException noValue() {
    return new NoSuchElementException(
      firstHopName != null
        ? "Telescope has no value in this source (path starts at field '" + firstHopName + "')"
        : "Telescope has no value in this source"
    );
  }

  /**
   * First focused value as {@link Optional}, empty when the path resolves to nothing. The
   * non-throwing sibling of {@link #read}.
   */
  public Optional<A> find(final S source) {
    if (optic instanceof final Lens<S, A> lens) {
      if (source == null) return Optional.empty();
      return Optional.ofNullable(lens.get(source));
    }
    if (optic instanceof final Affine<S, A> affine) {
      return affine.getOption(source);
    }
    // Mirror of #read: Stream.findFirst() NPEs on null elements. Use the iterator to keep
    // the lookup O(1) and preserve the null-empty distinction via Optional.ofNullable.
    final var it = optic.getAll(source).iterator();
    return it.hasNext() ? Optional.ofNullable(it.next()) : Optional.empty();
  }

  /**
   * Project this Telescope as a {@link ForwardMapper} from the root type {@code S} to the focused
   * type {@code A}, exposing the read direction. Useful when a {@code @Bridge}-generated Telescope
   * constant needs to surface as a forward-only mapper bean (CDI, Spring controller, audit
   * projection) without manually wrapping with {@link ForwardMapper#create}.
   *
   * <pre>{@code
   * // @Bridge emits a Telescope<UserEntity, UserDto> constant
   * Telescope<UserEntity, UserDto> bridge = UserEntityBridge.BRIDGE;
   * ForwardMapper<UserEntity, UserDto> mapper = bridge.asForwardMapper(UserEntity.class, UserDto.class);
   * }</pre>
   *
   * <p>The source / target classes are required because {@code Telescope<S, A>}'s type parameters
   * are erased at runtime. They're stored on the produced {@link ForwardMapper} so downstream
   * machinery (e.g. {@code TelescopeMapperRegistry} in the Quarkus / Spring Boot starter modules)
   * can key the mapper by the {@code (source, target)} pair.
   *
   * <p><b>Read semantics inherited from {@link #read}.</b> On a {@link Lens}-rooted Telescope the
   * forward returns the single focused value. On an {@link Affine}-rooted Telescope (e.g. after
   * {@code .as(...)} or {@code .whenPresent(...)}) the forward throws {@link
   * NoSuchElementException} when the focused value is absent. On a {@link Traversal}-rooted
   * Telescope (e.g. after {@code .each(...)} or {@code .filter(...)}) the forward returns the FIRST
   * focused element and throws {@link NoSuchElementException} on an empty traversal. If you need
   * null-safe semantics for an absent / empty case, prefer {@link #find} at the call site instead
   * of going through this projection.
   */
  public ForwardMapper<S, A> asForwardMapper(final Class<S> sourceClass, final Class<A> targetClass) {
    return ForwardMapper.create(this::read, sourceClass, targetClass);
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
    if (optic instanceof final Lens<S, A> lens) {
      return List.of(lens.get(source));
    }
    if (optic instanceof final Affine<S, A> affine) {
      return affine.getOption(source).map(List::of).orElseGet(List::of);
    }
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
    if (optic instanceof Lens<S, A>) {
      return 1L;
    }
    if (optic instanceof final Affine<S, A> affine) {
      return affine.getOption(source).isPresent() ? 1L : 0L;
    }
    return optic.getAll(source).count();
  }

  /**
   * Whether the telescope resolves to at least one value. The short-circuiting sibling of {@link
   * #count}.
   */
  public boolean exists(final S source) {
    if (optic instanceof Lens<S, A>) {
      return true;
    }
    if (optic instanceof final Affine<S, A> affine) {
      return affine.getOption(source).isPresent();
    }
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
   * final var pool = Executors.newFixedThreadPool(10);
   * try {
   *   final CompletableFuture<Batch> done = path.updateAsync(batch, this::fetch, pool);
   *   done.join();
   * } finally {
   *   pool.shutdown();
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

  // The logical field name an introspection node carries for an accessor. A record component's
  // accessor name IS the field name, so it passes through; a bean getter (getX / isX) is normalized
  // to its JavaBeans property name so the node matches the codegen-emitted Focus/Traverse AND reads
  // back through the same bean reflection path trace() uses. Record vs bean is decided by the
  // accessor's declaring class (both lookups are per-lambda cached, so this is off the hot path).
  private static String fieldNameOf(final Accessor<?, ?> getter) {
    final var raw = LambdaIntrospection.methodNameOf(getter);
    if (LambdaIntrospection.implClassOf(getter).isRecord()) return raw;
    if (raw.length() > 3 && raw.startsWith("get") && Character.isUpperCase(raw.charAt(3))) return beanDecapitalize(
      raw.substring(3)
    );
    if (raw.length() > 2 && raw.startsWith("is") && Character.isUpperCase(raw.charAt(2))) return beanDecapitalize(
      raw.substring(2)
    );
    return raw;
  }

  // JavaBeans Introspector.decapitalize: an acronym whose first two chars are both uppercase (e.g.
  // "URL") is left as-is; otherwise the first char is lowercased. Mirrors the codegen processor's
  // property-name derivation so a runtime bean node's name agrees with the generated one.
  private static String beanDecapitalize(final String s) {
    if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) return s;
    return Character.toLowerCase(s.charAt(0)) + s.substring(1);
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

  /**
   * Single-name holder lens lookup used by {@link RecordFieldOptics} / {@link BeanFieldOptics}.
   * When {@code cls} has a sibling {@code <X>FieldOptics} holder on the classpath, the codegen-
   * emitted {@code Telescope} constant for {@code name} is unwrapped to its underlying {@code
   * Lens}. Returns {@code null} when no holder is present (caller falls back to the reflective
   * {@link Records#fieldLens} / {@link Beans#lens} path). Throws when the holder IS present but the
   * requested name is missing — silent fallback would mask stale codegen.
   *
   * <p>The cast {@code (Telescope<?, ?>) constant} lives here in {@code :core} so {@code :internal}
   * sees the holder constants only as raw {@code Object}. No callback, no global state, no
   * static-init bridge between the modules.
   */
  @SuppressWarnings("unchecked")
  static <S, A> Lens<S, A> singleHolderLens(final Class<S> cls, final String name) {
    if (cls == null) return null;
    final var maybeHolder = MetadataHolderProbe.probeFor(cls);
    if (maybeHolder.isEmpty()) return null;
    final var holder = maybeHolder.get();
    final var constant = holder.constantsByName().get(name);
    if (constant == null) throw new IllegalStateException(
      "Component '" +
        name +
        "' not found in " +
        cls.getName() +
        "'s metadata holder (" +
        holder.holderClass().getName() +
        "). Re-run the @Focus / @BeanFocus processor."
    );
    return (Lens<S, A>) ((Telescope<?, ?>) constant).optic;
  }

  /**
   * Holder-readers table for the structural-iso backward branch: a {@code name → Lens} map covering
   * every name in {@code componentNames}, or {@code null} when {@code cls} has no sibling holder OR
   * the holder is missing any one of the named constants (all-or-nothing semantics that match the
   * original {@code Reflective#structuralIso} dispatch shape — one branch outside the Iso's hot
   * loop, not {@code N} branches inside).
   *
   * <p>Passed into {@link io.github.eschizoid.telescope.internal.Reflective#structuralIso(Class,
   * Map, java.util.function.Function)} by {@link DeepMap} so {@code :internal} can short-circuit
   * the reflective {@code Reflective#read} path without importing {@code Telescope}.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Lens<Object, Object>> holderReadersFor(final Class<?> cls, final String[] componentNames) {
    final var maybeHolder = MetadataHolderProbe.probeFor(cls);
    if (maybeHolder.isEmpty()) return null;
    final var holder = maybeHolder.get();
    final var readers = new LinkedHashMap<String, Lens<Object, Object>>();
    for (final var name : componentNames) {
      final var constant = holder.constantsByName().get(name);
      if (constant == null) return null;
      readers.put(name, (Lens<Object, Object>) ((Telescope<?, ?>) constant).optic);
    }
    return readers;
  }

  /**
   * Holder-constructor accessor for the structural-iso forward branch: the bound {@code
   * construct(Function<String, Object>)} the codegen-emitted holder exposes, or {@code null} when
   * no holder is on the classpath. Doesn't need any cast — the constructor is stored as {@code
   * Function<Function<String, Object>, Object>} in the {@link MetadataHolderProbe.HolderRef},
   * untyped at the {@code Telescope} level.
   */
  static Function<Function<String, Object>, Object> holderConstructorFor(final Class<?> cls) {
    return MetadataHolderProbe.probeFor(cls).map(MetadataHolderProbe.HolderRef::constructor).orElse(null);
  }

  /** Records: read + rebuild via the canonical constructor, keyed by component name. */
  private enum RecordFieldOptics implements FieldOptics {
    INSTANCE;

    @Override
    public <A, B> Lens<A, B> lensFor(final Accessor<A, ?> getter) {
      final var name = methodNameOf(getter);
      final Class<A> implClass = Telescope.implClassOf(getter);
      final var holderLens = Telescope.<A, B>singleHolderLens(implClass, name);
      if (holderLens != null) return holderLens;
      // Pass the declaring class so the lens captures (info, idx, reader) at construction —
      // eliminates the per-call (class, name) → idx scan that the string-only fieldLens(name)
      // overload pays. The string-only overload remains the fallback for fieldByName(String),
      // where the source class isn't known until call time.
      return implClass != null ? Records.fieldLens(implClass, name) : Records.fieldLens(name);
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
      // codegen would have stripped when naming the per-property method on <X>Telescope.
      final var property = Beans.propertyOf(rawName);
      final var holderLens = Telescope.<A, B>singleHolderLens(implClass, property);
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
  public static final class ListTelescope<S, X> extends Telescope<S, List<X>> {

    ListTelescope(final Traversal<S, List<X>> optic, final FieldOptics fieldOptics, final Function<S, S> chain) {
      super(optic, fieldOptics, chain);
    }

    ListTelescope(
      final Traversal<S, List<X>> optic,
      final FieldOptics fieldOptics,
      final Function<S, S> chain,
      final String firstHopName
    ) {
      super(optic, fieldOptics, chain, firstHopName);
    }

    /** Step into list elements. Pure lattice composition; no reflection. */
    public Telescope<S, X> each() {
      return new Telescope<>(this.optic.then(Traversals.eachList()), this.fieldOptics, this.chain, this.firstHopName);
    }
  }

  /**
   * A {@link Telescope} whose focus is a {@code Set&lt;X&gt;}. Adds a compile-checked {@link
   * #each()} terminal that descends into set elements via {@link Traversals#eachSet()}. Returned by
   * the {@code .field(Accessor<A, Set<X>>)} overload on the parent.
   */
  public static final class SetTelescope<S, X> extends Telescope<S, Set<X>> {

    SetTelescope(final Traversal<S, Set<X>> optic, final FieldOptics fieldOptics, final Function<S, S> chain) {
      super(optic, fieldOptics, chain);
    }

    SetTelescope(
      final Traversal<S, Set<X>> optic,
      final FieldOptics fieldOptics,
      final Function<S, S> chain,
      final String firstHopName
    ) {
      super(optic, fieldOptics, chain, firstHopName);
    }

    /** Step into set elements. Output is iteration-order-preserving (LinkedHashSet). */
    public Telescope<S, X> each() {
      return new Telescope<>(this.optic.then(Traversals.eachSet()), this.fieldOptics, this.chain, this.firstHopName);
    }
  }

  /**
   * A {@link Telescope} whose focus is a {@code Map&lt;K, V&gt;}. Adds a compile-checked {@link
   * #values()} terminal that descends into the map's values via {@link Traversals#eachMapValue()},
   * preserving keys. Returned by the {@code .field(Accessor<A, Map<K, V>>)} overload.
   */
  public static final class MapTelescope<S, K, V> extends Telescope<S, Map<K, V>> {

    MapTelescope(final Traversal<S, Map<K, V>> optic, final FieldOptics fieldOptics, final Function<S, S> chain) {
      super(optic, fieldOptics, chain);
    }

    MapTelescope(
      final Traversal<S, Map<K, V>> optic,
      final FieldOptics fieldOptics,
      final Function<S, S> chain,
      final String firstHopName
    ) {
      super(optic, fieldOptics, chain, firstHopName);
    }

    /** Step into map values; keys remain on the map. Pure lattice composition. */
    public Telescope<S, V> values() {
      return new Telescope<>(
        this.optic.then(Traversals.eachMapValue()),
        this.fieldOptics,
        this.chain,
        this.firstHopName
      );
    }
  }

  /**
   * A {@link Telescope} whose focus is an {@code Optional&lt;X&gt;}. Adds a compile-checked {@link
   * #present()} terminal that descends into the payload when present (no-op when empty) via {@link
   * Traversals#eachOptional()}. Returned by the {@code .field(Accessor<A, Optional<X>>)} overload.
   */
  public static final class OptionalTelescope<S, X> extends Telescope<S, Optional<X>> {

    OptionalTelescope(
      final Traversal<S, Optional<X>> optic,
      final FieldOptics fieldOptics,
      final Function<S, S> chain
    ) {
      super(optic, fieldOptics, chain);
    }

    OptionalTelescope(
      final Traversal<S, Optional<X>> optic,
      final FieldOptics fieldOptics,
      final Function<S, S> chain,
      final String firstHopName
    ) {
      super(optic, fieldOptics, chain, firstHopName);
    }

    /** Step into the Optional's payload. Empty Optional is a write no-op (Affine semantics). */
    public Telescope<S, X> present() {
      return new Telescope<>(
        this.optic.then(Traversals.eachOptional()),
        this.fieldOptics,
        this.chain,
        this.firstHopName
      );
    }
  }

  // Specialised Telescope for @Bridge-emitted constants. The parent's read/set terminals walk
  // optic instanceof Lens → Iso.get default → anonymous Iso.to → BridgeFn.forward — three virtual
  // hops to reach a static call. Holding the BridgeFn directly lets read/set fire fn.forward /
  // fn.backward in one virtual hop. The Iso is still the underlying optic — composition (.then,
  // .field, .each, .as, etc.) returns a regular Telescope and keeps lattice semantics intact; the
  // fast path applies only when a terminal is invoked directly on the bridge constant.
  static final class BridgeTelescope<S, T> extends Telescope<S, T> {

    private final BridgeFn<S, T> fn;

    BridgeTelescope(final Iso<S, T> iso, final BridgeFn<S, T> fn) {
      super(iso);
      this.fn = fn;
    }

    @Override
    public T read(final S source) {
      return fn.forward(source);
    }

    @Override
    public S set(final S source, final T value) {
      return fn.backward(value);
    }
  }
}
