package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.mapping.MapStep;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Fluent builder for assembling a {@link Mapper} (or a deep-mapping {@link Telescope}) from
 * multiple groups of {@link MapStep} rows. Closes MapStruct's {@code @InheritConfiguration} for the
 * case where several mappers share a base set of {@link
 * io.github.eschizoid.telescope.mapping.Mapping} rows and each adds its own variant.
 *
 * <pre>{@code
 * private static final MapStep[] AUDIT_COLUMNS = {
 *     to(UserEntity::createdAt, UserDto::createdAt),
 *     to(UserEntity::createdBy, UserDto::createdBy),
 *     to(UserEntity::updatedAt, UserDto::updatedAt),
 *     to(UserEntity::updatedBy, UserDto::updatedBy)};
 *
 * private static final MapStep[] DEFAULTS = {
 *     nullSourceValues(DEFAULT),
 *     writeBeans(SETTERS)};
 *
 * final Mapper<UserEntity, UserDto> userMapper = Telescope.mapperBuilder(UserEntity.class, UserDto.class)
 *     .inherit(AUDIT_COLUMNS)
 *     .inherit(DEFAULTS)
 *     .add(to(UserEntity::email, UserDto::emailAddress))
 *     .add(constant(UserDto::tenant, "us-east"))
 *     .build();
 *
 * final Mapper<UserEntity, UserDto> projectionMapper = Telescope.mapperBuilder(UserEntity.class, UserDto.class)
 *     .inherit(AUDIT_COLUMNS)                                 // same audit rules, second mapper
 *     .add(constant(UserDto::tenant, "eu-west"))
 *     .build();
 * }</pre>
 *
 * <p><b>Row groups bind to their type pair.</b> Each {@code Mapping} row is keyed by its accessors'
 * declaring classes, so a group declared against {@code (UserEntity, UserDto)} reuses across any
 * number of mappers of that same pair (or of pairs whose recursion visits it). Inheriting it into a
 * mapper of a different pair — say {@code (UserEntity, AdminUserDto)} — cannot apply, and {@code
 * build()} fails fast with an error naming the unreachable pair and its rows; declare a per-variant
 * group typed against the variant instead.
 *
 * <p><b>Equivalent to varargs spread, more declarative.</b> The builder is mechanically equivalent
 * to spreading a {@code MapStep[]} constant into {@link Telescope#mapper(Class, Class,
 * MapStep...)}, but the call-site reads as a sequence of intentional inherit / add steps rather
 * than an opaque array literal — which scales better when a mapper inherits from two or three
 * different groups (the common pattern in larger enterprise codebases with many DTO variants
 * sharing audit columns, tenant pinning, null-handling defaults, and writer-strategy hints).
 *
 * <p><b>Semantic distinction between {@link #inherit(MapStep...)} and {@link #add(MapStep...)}.
 * </b> Both append rows to the internal list; the distinction is purely intent at the call site.
 * Use {@code inherit(...)} for shared groups defined elsewhere ({@code static final MapStep[]}
 * constants, returned arrays, etc.), and {@code add(...)} for individual rows declared inline. The
 * engine treats both identically.
 *
 * <p><b>Insertion order = precedence order.</b> Rows added later override rows added earlier — a
 * later {@code add(to(srcAcc, tgtAcc))} would supersede an inherited {@code to(srcAcc, tgtAcc)} on
 * the same target field. The engine's existing duplicate-target check fires on conflicting rows for
 * the same target field; the builder does not pre-dedup. Order your inherits so the most specific
 * groups land last.
 *
 * <p><b>Lattice-honest.</b> The builder accumulates {@link MapStep}s and delegates the actual
 * assembly to the existing {@link Telescope#mapper(Class, Class, MapStep...)} / {@link
 * Telescope#map(Class, Class, MapStep...)} engines — no parallel resolution path, no duplicated
 * recursion. The engine's row-routing, hint-validation, sealed-permit dispatch all run unchanged.
 *
 * <p><b>Not thread-safe.</b> The internal step list is a plain {@link java.util.ArrayList}; two
 * threads concurrently calling {@link #add(MapStep...)} / {@link #inherit(MapStep...)} / {@link
 * #build()} on the same builder can throw {@link java.util.ConcurrentModificationException} or
 * produce a torn snapshot. Construct one builder per call site, or guard the builder externally if
 * shared. Snapshot semantics ("rows added after build() affect only future builds") hold for the
 * single-threaded case.
 *
 * <p>Constructed via {@link Telescope#mapperBuilder(Class, Class)} — package-private constructor.
 */
public final class MapperBuilder<A, B> {

  private final Class<A> sourceClass;
  private final Class<B> targetClass;
  private final List<MapStep> steps;

  private MapperBuilder(final Class<A> sourceClass, final Class<B> targetClass) {
    this.sourceClass = Objects.requireNonNull(sourceClass, "sourceClass");
    this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
    this.steps = new ArrayList<>();
  }

  /**
   * Cross-package factory used by {@link Telescope#mapperBuilder(Class, Class)} — the only entry
   * point users should call. Public so {@code :core}'s {@code io.github.eschizoid.telescope}
   * package can construct the builder; users static-import {@code Telescope.mapperBuilder(...)} and
   * never name this method directly.
   */
  public static <A, B> MapperBuilder<A, B> create(final Class<A> sourceClass, final Class<B> targetClass) {
    return new MapperBuilder<>(sourceClass, targetClass);
  }

  /**
   * Inherit a group of {@link MapStep} rows from a shared / external source. Semantically the same
   * as {@link #add(MapStep...)} but the call site reads as "these come from a shared config." Use
   * for {@code static final MapStep[]} constants and any pre-built row arrays.
   *
   * <p>Equivalent to calling {@link #add(MapStep...)} with the same arguments — the engine applies
   * all rows in insertion order regardless of which method added them.
   *
   * @param rows the rows to inherit; must not be null (individual rows must not be null either)
   * @return this builder for chaining
   */
  public MapperBuilder<A, B> inherit(final MapStep... rows) {
    addAllChecked(rows);
    return this;
  }

  /**
   * Add one or more {@link MapStep} rows declared inline at the call site. Semantically the same as
   * {@link #inherit(MapStep...)} but the call site reads as "these are specific to this mapper."
   *
   * @param rows the rows to add; must not be null (individual rows must not be null either)
   * @return this builder for chaining
   */
  public MapperBuilder<A, B> add(final MapStep... rows) {
    addAllChecked(rows);
    return this;
  }

  /**
   * Append {@code rows} to {@link #steps} after validating that neither the array nor any element
   * is {@code null}. A {@code null} element silently slipping through would land in the engine's
   * partitioning loop and be discarded with no diagnostic — six months later the user debugs why
   * one field "didn't map." The named-index NPE message points the user at the offending slot in
   * their {@code static final MapStep[]} constant.
   */
  private void addAllChecked(final MapStep[] rows) {
    Objects.requireNonNull(rows, "rows");
    for (int i = 0; i < rows.length; i++) {
      final var idx = i;
      Objects.requireNonNull(rows[i], () -> "rows[" + idx + "]");
    }
    Collections.addAll(steps, rows);
  }

  /**
   * Build the {@link Mapper}. Delegates to {@link Telescope#mapper(Class, Class, MapStep...)} with
   * the accumulated steps in insertion order — every validation, routing, and hint check the engine
   * does for a direct {@code Telescope.mapper(...)} call applies identically.
   *
   * <p>Calling {@code build()} multiple times is supported and returns independent Mappers.
   * Subsequent {@link #inherit} / {@link #add} calls after a {@code build()} affect only future
   * builds — the returned Mappers are independent.
   *
   * @return a freshly-assembled {@link Mapper} for the configured source / target pair
   */
  public Mapper<A, B> build() {
    return Telescope.mapper(sourceClass, targetClass, steps.toArray(MapStep[]::new));
  }

  /**
   * Build the deep-mapping {@link Telescope} rather than a {@link Mapper}. Delegates to {@link
   * Telescope#map(Class, Class, MapStep...)} with the accumulated steps. Use when the call site
   * needs the composable {@link Telescope} shape (for {@code .then(...)} chains, single-direction
   * reads, etc.) rather than the {@link Mapper}'s patch / hook / into surface.
   */
  public Telescope<A, B> buildTelescope() {
    return Telescope.map(sourceClass, targetClass, steps.toArray(MapStep[]::new));
  }
}
