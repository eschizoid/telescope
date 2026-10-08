package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.NullDefaults;
import java.util.Objects;

/**
 * Per-mapper null-handling strategy. Closes MapStruct's {@code nullValueMappingStrategy} / {@code
 * nullValuePropertyMappingStrategy} for the most common case — what should happen at field level
 * when the source's value is {@code null}?
 *
 * <pre>{@code
 * import static io.github.eschizoid.telescope.mapping.NullHint.NullStrategy.DEFAULT;
 * import static io.github.eschizoid.telescope.mapping.NullHint.nullSourceValues;
 *
 * final Mapper<UserEntity, UserDto> userMapper = Telescope.mapper(
 *     UserEntity.class, UserDto.class,
 *     nullSourceValues(DEFAULT),                        // null auto-matched field → type default
 *     to(UserEntity::id, UserDto::userId));
 * }</pre>
 *
 * <p><b>Which rows the strategy applies to.</b> The null-substitution gate wraps the fields that
 * carry no conversion of their own: auto-matched fields and same-typed {@link SameTypedTo} rows
 * ({@code to(src, tgt)}), which resolve the same way an auto-matched field does. Rows that carry
 * their own functions — {@link TypedTransformTo} ({@code to(src, tgt, forward, backward)}), {@link
 * ForwardOnlyTransformTo} ({@code toOneWay}) and {@link Via} ({@code via}) — are not wrapped. Their
 * forward function, or the {@code via} mapper, receives a {@code null} source value unchanged, and
 * whatever it returns lands on the target, even when the target's leaf type has a table default.
 * Telescope-based rows ({@link TelescopeTo}, {@link FromTelescopeTo}, {@link TelescopeToTelescope},
 * {@link Constant}, {@link Compute}, {@link Conditional}) are not wrapped either.
 *
 * <p><b>Strategy applies on forward direction only.</b> Backward direction is unchanged. Same
 * retraction posture as the existing {@link Constant} / {@link Compute} rows, which are also
 * forward-only — the engine has a well-tested precedent for "this row contributes on forward but
 * skips backward." If you need symmetric null handling, supply defaults on both sides via {@link
 * Mapping#toOrElse(Telescope.Accessor, Telescope.Accessor, Object)} which is a fully bidirectional
 * per-row construct.
 *
 * <p><b>Substitution does NOT cascade into recursive default allocation.</b> The wrap consults only
 * the per-leaf-type table in {@link NullDefaults#defaultFor}. For record / bean / enum /
 * custom-type leaves where that table returns {@code null}, the wrap is skipped and the engine's
 * separate recursive-default allocation path runs as it always does. If you want a populated
 * nested-record default, supply one explicitly via {@link Mapping#toOrElse} on the row that targets
 * that leaf.
 *
 * <p><b>Per-row precedence.</b> {@link Mapping#toOrElse} rows are {@link TypedTransformTo} rows
 * whose forward function substitutes the row's own default, and the strategy never wraps a row that
 * carries its own function. The row's default is therefore the one that lands, so a single {@code
 * DEFAULT}-strategy mapper can still pin a specific field's null handling via {@code toOrElse(src,
 * tgt, mySpecialDefault)} on that row without restating the global.
 *
 * <p><b>Codegen 1:1 via composition.</b> The hint is consumed at {@link Telescope#mapper}-build
 * time, so a codegen-emitted {@code @Bridge} mapper used inside a hinted outer mapper inherits the
 * null strategy at the outer level. No processor change needed — the strategy is a runtime assembly
 * concern, not a codegen-time concern.
 *
 * <p><b>Default defaults.</b> When the strategy is {@link NullStrategy#DEFAULT}, the substitution
 * value for a target field's leaf type follows {@link NullDefaults#defaultFor} — primitives and
 * primitive wrappers get JLS-style zero/false, {@code String} gets {@code ""}, containers ({@code
 * List}/{@code Set}/{@code Map}) get the JDK empty singleton, {@code Optional} gets {@link
 * java.util.Optional#empty()}, anything else (records, beans, enums, custom types) gets {@code
 * null}.
 */
public sealed interface NullHint extends MapStep permits NullHint.NullSourceValuesHint {
  /**
   * The strategies. Order matches MapStruct's {@code NullValuePropertyMappingStrategy} enum —
   * {@link #PROPAGATE} is the current (and only) v0.x behavior; {@link #DEFAULT} unlocks the
   * "always return a usable target" idiom that bean-heavy enterprise codebases lean on.
   */
  enum NullStrategy {
    /**
     * Default. Null source values land on the target as null. Matches MapStruct's {@code
     * SET_TO_NULL}.
     */
    PROPAGATE,
    /**
     * Null source values are replaced with a JLS-style or empty-collection default of the target
     * field's leaf type. Matches MapStruct's {@code SET_TO_DEFAULT}.
     */
    DEFAULT,
  }

  /** Declare a per-mapper null-source-value strategy. */
  static NullHint nullSourceValues(final NullStrategy strategy) {
    return new NullSourceValuesHint(Objects.requireNonNull(strategy, "strategy"));
  }

  /** The selected strategy. */
  NullStrategy strategy();

  /** Package-private record impl; users construct via {@link #nullSourceValues(NullStrategy)}. */
  record NullSourceValuesHint(NullStrategy strategy) implements NullHint {}
}
