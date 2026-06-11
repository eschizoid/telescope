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
    this.iso = iso;
    this.sourceClass = sourceClass;
    this.targetClass = targetClass;
    this.sourceRefl = Reflective.of(sourceClass);
    this.targetRefl = Reflective.of(targetClass);
    // Defensive copy — patch behavior must not mutate after construction.
    this.patchByTargetField = Map.copyOf(patchByTargetField);
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
    return iso.to(a);
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
    return Telescope.iso(iso::to, iso::from);
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
    return iso.to(a);
  }

  /** Backward conversion {@code B → A}. See {@link #forward(Object)}. */
  public A backward(final B b) {
    return iso.from(b);
  }

  /**
   * Add a nested-path correspondence on top of this mapper. After {@link #forward}/{@link
   * #backward} produces the base value, the path-pair is applied: forward reads the source value
   * from {@code srcPath} and writes it through {@code tgtPath}; backward does the mirror. Closes
   * the gap with MapStruct's {@code @Mapping(source = "a.b.c", target = "x.y.z")} for nested
   * fields.
   *
   * <pre>{@code
   * record A(String code, Inner inner) {}
   * record Inner(String name) {}
   * record B(String code, BInner inner) {}
   * record BInner(String code) {}
   *
   * final var mapper = Telescope.mapper(A.class, B.class, Mapping.auto())
   *     .withPath(
   *         Telescope.of(A.class).field(A::code),                              // src path
   *         Telescope.of(B.class).field(B::inner).field(BInner::code));        // tgt path
   *
   * mapper.forward(new A("X", new Inner("n"))).inner().code();  // "X"
   * }</pre>
   *
   * <p><b>Phase 1 — single-focus paths.</b> Both paths must terminate at a single value (built from
   * {@code .field(...)} navigation only; no {@code .each()} / {@code .eachValue()} / {@code
   * .whenPresent()} in the chain). Many-focus paths fall through to broadcast/zip semantics with
   * caveats documented at {@link #withBroadcastPath} (Phase 2).
   *
   * <p><b>Composition.</b> Returns a fresh mapper; the receiver is unchanged. Chain multiple {@code
   * withPath} calls to layer N nested-path rules.
   *
   * <p><b>Patch table.</b> Carried through unchanged. Path-based rules don't participate in the
   * sparse-overlay patch protocol — they fire on every forward/backward, not on {@link #patch}.
   */
  public <X> Mapper<A, B> withPath(final Telescope<A, X> srcPath, final Telescope<B, X> tgtPath) {
    final Mapper<A, B> self = this;
    return new Mapper<>(
      Iso.of(a -> tgtPath.set(self.forward(a), srcPath.read(a)), b -> srcPath.set(self.backward(b), tgtPath.read(b))),
      sourceClass,
      targetClass,
      patchByTargetField
    );
  }

  /**
   * Convenience: like {@link #withPath(Telescope, Telescope)} but with a flat source accessor —
   * mirrors the common shape "pull a top-level field from {@code A} and stamp it into a nested
   * location on {@code B}." Equivalent to {@code withPath(Telescope.of(A.class).field(srcAcc),
   * tgtPath)} — the explicit form lets you compose multi-hop source paths when the source side is
   * also nested.
   */
  public <X> Mapper<A, B> withPath(final Telescope.Accessor<A, X> srcAcc, final Telescope<B, X> tgtPath) {
    return withPath(Telescope.of(sourceClass).field(srcAcc), tgtPath);
  }

  /** Mirror of {@link #withPath(Telescope.Accessor, Telescope)} — nested source, flat target. */
  public <X> Mapper<A, B> withPath(final Telescope<A, X> srcPath, final Telescope.Accessor<B, X> tgtAcc) {
    return withPath(srcPath, Telescope.of(targetClass).field(tgtAcc));
  }

  /**
   * Phase-2 sibling of {@link #withPath(Telescope, Telescope)} — when the target path is many-focus
   * (uses {@code .each()} / {@code .eachValue()} / {@code .whenPresent()}), forward broadcasts the
   * single source value to every target focus, and backward reverse-maps the (assumed-uniform)
   * value at the first focus back to the source. The "all foci hold the same value" precondition is
   * the caveat to the iso laws — see the README for the round-trip semantics.
   *
   * <p>Implementation is identical to the scalar form because {@link Telescope#set} on a {@code
   * Traversal} already broadcasts and {@link Telescope#read} returns the first focus. This method
   * exists as a named documentation seam — call it when you intentionally want broadcast semantics
   * so the call site signals the choice to a reader.
   */
  public <X> Mapper<A, B> withBroadcastPath(final Telescope<A, X> srcPath, final Telescope<B, X> tgtPath) {
    return withPath(srcPath, tgtPath);
  }

  /**
   * Phase-2 sibling of {@link #withPath(Telescope, Telescope)} for the symmetric many-focus case —
   * both paths traverse collections of matching cardinality. Forward writes positionally: source's
   * Nth value lands at target's Nth focus. A cardinality mismatch throws at apply time (silent
   * truncation would be a footgun). Backward is the mirror.
   *
   * <p><b>Caveat.</b> {@link Telescope#set} on a {@code Traversal} broadcasts a single value to
   * every focus — it does not natively support positional zip. This method enforces positional
   * semantics by reading the source values as a list via {@link Telescope#toList} and using {@link
   * Telescope#updateIndexed} on the target to write per position. See {@link #withPath} for the
   * single-focus scalar case.
   */
  public <X> Mapper<A, B> withZipPath(final Telescope<A, X> srcPath, final Telescope<B, X> tgtPath) {
    final Mapper<A, B> self = this;
    return new Mapper<>(
      Iso.of(
        a -> {
          final var baseB = self.forward(a);
          final var srcValues = srcPath.toList(a);
          final var targetCount = tgtPath.count(baseB);
          if (srcValues.size() != targetCount) throw new IllegalStateException(
            "withZipPath: source has " +
              srcValues.size() +
              " value(s), target has " +
              targetCount +
              " focus(es) — cardinality must match for positional zip."
          );
          return tgtPath.updateIndexed(baseB, (i, _ignored) -> srcValues.get(i));
        },
        b -> {
          final var baseA = self.backward(b);
          final var tgtValues = tgtPath.toList(b);
          final var sourceCount = srcPath.count(baseA);
          if (tgtValues.size() != sourceCount) throw new IllegalStateException(
            "withZipPath: target has " +
              tgtValues.size() +
              " value(s), source has " +
              sourceCount +
              " focus(es) — cardinality must match for positional zip."
          );
          return srcPath.updateIndexed(baseA, (i, _ignored) -> tgtValues.get(i));
        }
      ),
      sourceClass,
      targetClass,
      patchByTargetField
    );
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
