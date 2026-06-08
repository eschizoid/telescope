package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.util.HashMap;
import List;
import Map;
import Optional;
import Set;
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
   * io.github.eschizoid.telescope.mapping.DeepMap} engine can construct entries when building a
   * {@link Mapper} via {@link Mapper#Mapper(Iso, Class, Class, Map)}. The raw {@link Function}
   * field is part of that internal contract; external code must not depend on this record's shape.
   * Treat as private to the module — it may change or disappear without a deprecation cycle.
   */
  public record PatchEntry(String sourceField, Function<Object, Object> backward) {}

  private final Iso<A, B> iso;
  private final Class<A> sourceClass;
  private final Reflective sourceRefl;
  private final Reflective targetRefl;
  private final Map<String, PatchEntry> patchByTargetField;

  /**
   * <b>Module-internal seam — NOT public API.</b> Construct a mapper directly from an {@link Iso},
   * the source/target classes, and a (possibly empty) patch table keyed by target component
   * /property name.
   *
   * <p>Declared {@code public} solely so {@link io.github.eschizoid.telescope.mapping.DeepMap}
   * (different package) can call it; {@link Iso} lives in the unexported {@code internal.optics}
   * package, so module consumers cannot supply a real argument. External code must construct
   * mappers via {@link Telescope#mapper(Class, Class,
   * io.github.eschizoid.telescope.mapping.MapStep...)}. Treat this signature as part of the
   * module's internal contract: it may change or disappear without a deprecation cycle.
   */
  @SuppressWarnings("exports") // Intentional: Iso is module-internal; consumers can't supply one.
  public Mapper(
    final Iso<A, B> iso,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final Map<String, PatchEntry> patchByTargetField
  ) {
    this.iso = iso;
    this.sourceClass = sourceClass;
    this.sourceRefl = Reflective.of(sourceClass);
    this.targetRefl = Reflective.of(targetClass);
    // Defensive copy — patch behavior must not mutate after construction.
    this.patchByTargetField = Map.copyOf(patchByTargetField);
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
    return Telescope.wrap(iso);
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
   * Lift this element-level mapper to a {@code Mapper<List<A>, List<B>>}. Forward maps each
   * element through {@link #forward}; backward maps each element through {@link #backward}.
   * {@code null} lists round-trip to {@code null} (mirrors the null-pass-through convention of
   * {@link io.github.eschizoid.telescope.internal.optics.Iso#liftList}).
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
