package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.mapping.MapStep;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A bidirectional mapper produced by the deep recursive factory {@link Telescope#mapper(Class,
 * Class, io.github.eschizoid.telescope.mapping.MapStep...)}. Wraps an {@link Iso} for the
 * forward/backward conversion and (optionally) a per-target-component patch table for sparse {@link
 * #patch} overlays.
 *
 * <p>Works for any combination of record and bean classes — the source and target side each pick
 * their own {@link Reflective} dispatch at construction, so {@code patch(...)} reads the partial
 * value through the right reflective and rebuilds the source through its writer (canonical
 * constructor for records, auto-detected write strategy for beans).
 */
public final class Mapper<A, B> {

  /**
   * <b>Module-internal seam — NOT public API.</b> One entry of the patch table — when overlaying a
   * partially-populated target onto a source, each non-null target component value is fed to {@link
   * #backward} and written to {@link #sourceField} on the source.
   *
   * <p>Declared {@code public} solely so the cross-package {@link
   * io.github.eschizoid.telescope.DeepMap} engine can construct entries when building a {@link
   * Mapper} via {@link Mapper#Mapper(Iso, Class, Class, Map)}. The raw {@link Function} field is
   * part of that internal contract; external code must not depend on this record's shape. Treat as
   * private to the module — it may change or disappear without a deprecation cycle.
   */
  public record PatchEntry(String sourceField, Function<Object, Object> backward) {}

  private final Iso<A, B> iso;
  private final Class<A> sourceClass;
  private final Class<B> targetClass;
  private final Reflective sourceRefl;
  private final Reflective targetRefl;
  private final Map<String, PatchEntry> patchByTargetField;
  // Folded hook chains — null = no hook. Composed by repeated calls to before*/after*. Each side
  // is a single Function/BiFunction reference at call time so HotSpot stays monomorphic regardless
  // of chain depth (avoids the megamorphic Iso.of cliff that the per-hook-Iso composition shape
  // produced at 3+ hooks).
  //
  // Lattice mandate carve-out — these are NOT routed through the optic lattice on purpose. The
  // lattice's bidirectional substrate (`Iso<A, B>`) requires the round-trip law `from(to(a)) == a`.
  // A user-supplied hook like `e -> e.normalised()` has no inverse — wrapping it as
  // `Iso.of(hook, identity)` would construct a fake Iso that silently violates the laws
  // `OpticLawsTest` pins. That's worse than admitting the hook isn't lattice-shaped. The Telescope-
  // level equivalents (`Telescope.after/before`, PR #90) get away with the partial-Iso shape
  // because Telescope is read-or-write through a Traversal — there's no single-value round-trip
  // through both legs. `Mapper.forward(a)` and `Mapper.backward(b)` ARE both surfaces of a single
  // contract, so the lattice-routed shape would break composition. The four hook fields below own
  // that responsibility instead, and the engine `forward` / `backward` methods (just above)
  // compose them around the underlying iso explicitly.
  private final Function<A, A> preForward;
  private final BiFunction<A, B, B> postForward;
  private final Function<B, B> preBackward;
  private final BiFunction<B, A, A> postBackward;

  /**
   * <b>Module-internal seam — NOT public API.</b> Construct a mapper directly from an {@link Iso},
   * the source/target classes, and a (possibly empty) patch table keyed by target component
   * /property name.
   *
   * <p>Package-private: only same-package callers ({@code DeepMap}, {@link Telescope}, and the
   * {@code lift*} helpers below) construct mappers. External code uses {@link Telescope#mapper(
   * Class, Class, MapStep...)}.
   */
  Mapper(
    final Iso<A, B> iso,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final Map<String, PatchEntry> patchByTargetField
  ) {
    this(iso, sourceClass, targetClass, patchByTargetField, null, null, null, null);
  }

  private Mapper(
    final Iso<A, B> iso,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final Map<String, PatchEntry> patchByTargetField,
    final Function<A, A> preForward,
    final BiFunction<A, B, B> postForward,
    final Function<B, B> preBackward,
    final BiFunction<B, A, A> postBackward
  ) {
    this.iso = iso;
    this.sourceClass = sourceClass;
    this.targetClass = targetClass;
    this.sourceRefl = Reflective.of(sourceClass);
    this.targetRefl = Reflective.of(targetClass);
    // Defensive copy — patch behavior must not mutate after construction.
    this.patchByTargetField = Map.copyOf(patchByTargetField);
    this.preForward = preForward;
    this.postForward = postForward;
    this.preBackward = preBackward;
    this.postBackward = postBackward;
  }

  /**
   * Build a {@code Mapper<A, B>} from a forward/backward function pair and the source/target
   * classes. Cross-package factory used by {@code DeepMap#resolveMapper(Class, Class,
   * io.github.eschizoid.telescope.mapping.MapStep[])} — takes only public {@link Function} types so
   * the lattice {@code Iso} never appears in this class's public signatures even after {@code
   * Mapper} moves to its own sub-package.
   *
   * <p>The {@code patchByTargetField} map is keyed by target-component name; an empty map means no
   * sparse-overlay semantics for {@link #patch}.
   */
  public static <A, B> Mapper<A, B> create(
    final Function<? super A, ? extends B> forward,
    final Function<? super B, ? extends A> backward,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final Map<String, PatchEntry> patchByTargetField
  ) {
    return new Mapper<>(Iso.of(forward, backward), sourceClass, targetClass, patchByTargetField);
  }

  /**
   * Convert forward, {@code A → B}.
   *
   * <pre>{@code
   * final var mapper = Telescope.mapper(UserEntity.class, UserDto.class, to(UserEntity::name, UserDto::fullName));
   * final UserDto dto = mapper.read(entity);
   * }</pre>
   *
   * For the reverse direction, or to thread the conversion through a longer path, use {@link
   * #asTelescope()} (which exposes {@code set}/{@code update}/{@code then}); for a sparse overlay,
   * use {@link #patch}.
   */
  public B read(final A a) {
    return forward(a);
  }

  /**
   * The mapper as a composable {@code Telescope<A, B>}, for threading the conversion through longer
   * paths via {@link Telescope#then}.
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
    // Route through this::forward / this::backward (NOT iso::to / iso::from) so the four hook
    // fields (beforeForward / afterForward / beforeBackward / afterBackward) compose into the
    // returned Telescope. Calling iso::to directly bypasses the hook chain — a configured mapper
    // would silently drop its hooks when handed to longer .then(...) chains via this method.
    return Telescope.iso(this::forward, this::backward);
  }

  /**
   * The mapper's source class — exposed so the deep-mapping engine can decide whether to lift this
   * mapper through a container shape ({@code List} / {@code Set} / {@code Optional} / {@code Map})
   * when the user passed it as the {@code via(...)} element mapper.
   */
  public Class<A> sourceClass() {
    return sourceClass;
  }

  /** The mapper's target class. See {@link #sourceClass()}. */
  public Class<B> targetClass() {
    return targetClass;
  }

  /**
   * Sparse update: overlay the non-null fields of {@code partial} (a partially-populated target)
   * onto {@code base}, leaving the rest of {@code base} untouched. Each present target field is run
   * back through its component {@link Iso}'s backward direction and used to override the matching
   * component when {@code base} is reconstructed via the source {@link Reflective}.
   *
   * <pre>{@code
   * // dtoPatch has a new email, null everything else — only the email changes on the entity:
   * UserEntity updated = userMapper.patch(entity, dtoPatch);
   * }</pre>
   *
   * <p>Records: rebuilds via the canonical constructor with the patched values substituted in.
   * Beans: rebuilds via the auto-detected write strategy (builder / no-arg + setters / fields). The
   * patch table is populated by the deep factory at the top level of the source/target type pair
   * only — patches that target nested components write the <em>whole</em> nested value (since
   * that's what the top-level component holds), not individual sub-component overlays.
   */
  @SuppressWarnings("unchecked")
  public A patch(final A base, final B partial) {
    if (base == null || partial == null) return base;
    if (patchByTargetField.isEmpty()) return base;
    final var patched = new HashMap<String, Object>();
    for (final var entry : patchByTargetField.entrySet()) {
      final var partialValue = targetRefl.read(partial, entry.getKey());
      if (partialValue != null) patched.put(
        entry.getValue().sourceField(),
        entry.getValue().backward().apply(partialValue)
      );
    }
    if (patched.isEmpty()) return base;
    return (A) sourceRefl.construct(sourceClass, name ->
      patched.containsKey(name) ? patched.get(name) : sourceRefl.read(base, name)
    );
  }

  /**
   * Forward conversion {@code A → B}. Public so the {@code mapping} sub-package can wrap a {@link
   * Mapper} as an {@link Iso} when stitching deep recursive mappings together (the {@link
   * io.github.eschizoid.telescope.mapping.Mapping#via via(...)} row).
   */
  public B forward(final A a) {
    final A a1 = preForward == null ? a : preForward.apply(a);
    final B b = iso.to(a1);
    return postForward == null ? b : postForward.apply(a1, b);
  }

  /** Backward conversion {@code B → A}. See {@link #forward(Object)}. */
  public A backward(final B b) {
    final B b1 = preBackward == null ? b : preBackward.apply(b);
    final A a = iso.from(b1);
    return postBackward == null ? a : postBackward.apply(b1, a);
  }

  /**
   * Compose a pre-forward hook: before the structural forward direction reads from {@code A}, run
   * {@code hook} on the source and feed the result into the conversion. MapStruct's
   * {@code @BeforeMapping void prep(Source src)} equivalent — the canonical place for input
   * normalisation, validation, or canonicalisation.
   *
   * <p>The hook is a {@link Function}, not a {@link java.util.function.Consumer}, so it works for
   * both immutable record sources (return a new {@code A} with the hook's changes) and mutable bean
   * sources (mutate {@code a} in place and {@code return a} the same instance).
   *
   * <p>Returns a new {@code Mapper} — chains compose left-to-right.
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .beforeForward(e -> e.normalised());
   * }</pre>
   *
   * @see io.github.eschizoid.telescope.Telescope#before(Function) for the path-level (single-
   *     direction, lattice-native) sibling that composes through {@code .then(...)} chains.
   */
  public Mapper<A, B> beforeForward(final Function<? super A, ? extends A> hook) {
    final Function<A, A> prev = this.preForward;
    final Function<A, A> next = prev == null ? hook::apply : a -> hook.apply(prev.apply(a));
    return new Mapper<>(
      iso,
      sourceClass,
      targetClass,
      patchByTargetField,
      next,
      postForward,
      preBackward,
      postBackward
    );
  }

  /**
   * Compose a post-forward hook: after the structural forward direction produces a {@code B}, run
   * {@code hook} on it and return whatever the hook returns. Closes MapStruct's
   * {@code @AfterMapping} gap for the common "stamp a derived/computed value after the structural
   * mapping completes" case ({@code entity.setUpdatedAt(Instant.now())} after the row data is in
   * place).
   *
   * <p>The hook is a {@link Function}, not a {@link java.util.function.Consumer}, so it works for
   * both immutable record targets (return a new {@code B} with the hook's changes) and mutable bean
   * targets (mutate {@code b} in place and {@code return b} the same instance).
   *
   * <p>Returns a new {@code Mapper} — chains compose left-to-right (the first {@code afterForward}
   * call's hook runs first, the second runs second, etc.). The backward direction is unchanged.
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .afterForward(dto -> dto.withUpdatedAt(Instant.now().toString()));
   * }</pre>
   *
   * @see io.github.eschizoid.telescope.Telescope#after(Function) for the path-level (single-
   *     direction, lattice-native) sibling that composes through {@code .then(...)} chains.
   */
  public Mapper<A, B> afterForward(final Function<? super B, ? extends B> hook) {
    return afterForward((a, b) -> hook.apply(b));
  }

  /**
   * Source-aware post-forward hook: same as {@link #afterForward(Function)} but the hook receives
   * BOTH the source {@code A} (as seen by the forward direction, after any {@link
   * #beforeForward(Function)} normalisation) AND the structural result {@code B}. MapStruct's
   * {@code @AfterMapping void enrich(Source src, @MappingTarget Dto dto)} equivalent — typed, not
   * reflective.
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .afterForward((src, dto) -> dto.withDisplayName(src.firstName() + " " + src.lastName()));
   * }</pre>
   */
  public Mapper<A, B> afterForward(final BiFunction<? super A, ? super B, ? extends B> hook) {
    final BiFunction<A, B, B> prev = this.postForward;
    final BiFunction<A, B, B> next = prev == null ? hook::apply : (a, b) -> hook.apply(a, prev.apply(a, b));
    return new Mapper<>(iso, sourceClass, targetClass, patchByTargetField, preForward, next, preBackward, postBackward);
  }

  /**
   * Compose a pre-backward hook: before the structural backward direction consumes a {@code B}, run
   * {@code hook} on it and feed the result into {@code backward}. The MapStruct
   * {@code @BeforeMapping} equivalent for the backward direction.
   *
   * <p>Returns a new {@code Mapper} — chains compose left-to-right (the first {@code
   * beforeBackward} call's hook runs first on a backward invocation). The forward direction is
   * unchanged.
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .beforeBackward(dto -> dto.normalised());
   * }</pre>
   */
  public Mapper<A, B> beforeBackward(final Function<? super B, ? extends B> hook) {
    final Function<B, B> prev = this.preBackward;
    final Function<B, B> next = prev == null ? hook::apply : b -> hook.apply(prev.apply(b));
    return new Mapper<>(iso, sourceClass, targetClass, patchByTargetField, preForward, postForward, next, postBackward);
  }

  /**
   * Compose a post-backward hook: after the structural backward direction produces a rebuilt {@code
   * A}, run {@code hook} on it and return whatever the hook returns. The symmetric mirror of {@link
   * #afterForward(Function)} for the backward direction.
   *
   * <p>The canonical place to stamp source-side derived fields after a target → source rebuild
   * (e.g. {@code entity.setLastModifiedBy(currentUser())} during a backward write).
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .afterBackward(e -> e.withLastModifiedAt(Instant.now()));
   * }</pre>
   */
  public Mapper<A, B> afterBackward(final Function<? super A, ? extends A> hook) {
    return afterBackward((b, a) -> hook.apply(a));
  }

  /**
   * Target-aware post-backward hook: same as {@link #afterBackward(Function)} but the hook receives
   * BOTH the target {@code B} (as seen by the backward direction, after any {@link
   * #beforeBackward(Function)} normalisation) AND the rebuilt source {@code A}. Use when the stamp
   * depends on a target-side field that the source doesn't carry.
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .afterBackward((dto, entity) -> entity.withAuditTrail(dto.actor() + "@" + Instant.now()));
   * }</pre>
   */
  public Mapper<A, B> afterBackward(final BiFunction<? super B, ? super A, ? extends A> hook) {
    final BiFunction<B, A, A> prev = this.postBackward;
    final BiFunction<B, A, A> next = prev == null ? hook::apply : (b, a) -> hook.apply(b, prev.apply(b, a));
    return new Mapper<>(iso, sourceClass, targetClass, patchByTargetField, preForward, postForward, preBackward, next);
  }

  /**
   * Lift this element-level mapper to a {@code Mapper<List<A>, List<B>>}. Forward maps each element
   * through {@link #forward}; backward maps each element through {@link #backward}. {@code null}
   * lists round-trip to {@code null} (mirrors the null-pass-through convention of {@link
   * io.github.eschizoid.telescope.internal.optics.Iso#liftList}).
   *
   * <p>The lifted mapper has an empty patch table — sparse-overlay semantics aren't well-defined
   * for list-shaped roots. Use it as a building block in {@link
   * io.github.eschizoid.telescope.mapping.Mapping#via(io.github.eschizoid.telescope.Telescope.Accessor,
   * io.github.eschizoid.telescope.Telescope.Accessor, Mapper) Mapping.via} or hand-roll the
   * forward/backward calls at a {@code List} call site.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public Mapper<List<A>, List<B>> liftList() {
    final var lifted = Iso.liftList(iso);
    return new Mapper<>(lifted, (Class) List.class, (Class) List.class, Map.of());
  }

  /** Same as {@link #liftList()} but produces a {@code Mapper<Set<A>, Set<B>>}. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public Mapper<Set<A>, Set<B>> liftSet() {
    final var lifted = Iso.liftSet(iso);
    return new Mapper<>(lifted, (Class) Set.class, (Class) Set.class, Map.of());
  }

  /** Same as {@link #liftList()} but produces a {@code Mapper<Optional<A>, Optional<B>>}. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public Mapper<Optional<A>, Optional<B>> liftOptional() {
    final var lifted = Iso.liftOptional(iso);
    return new Mapper<>(lifted, (Class) Optional.class, (Class) Optional.class, Map.of());
  }

  /**
   * Same as {@link #liftList()} but produces a {@code Mapper<Map<K, A>, Map<K, B>>}. Keys are
   * preserved; only the values flow through {@link #forward}/{@link #backward}.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public <K> Mapper<Map<K, A>, Map<K, B>> liftMapValues() {
    final var lifted = Iso.<K, A, B>liftMapValues(iso);
    return new Mapper<>(lifted, (Class) Map.class, (Class) Map.class, Map.of());
  }
}
