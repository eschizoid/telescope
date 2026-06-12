package io.github.eschizoid.telescope.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record or class as the <em>source</em> of a compile-time, reflection-free bidirectional
 * bridge to the type named by {@link #value()}. The {@code telescope-codegen} processor emits a
 * sibling class {@code <Source>Bridge} holding a {@code public static final Telescope<Source,
 * Target> BRIDGE}.
 *
 * <p>Both sides may be records or JavaBeans-style POJOs, in any combination (record&harr;record,
 * record&harr;POJO, POJO&harr;POJO). Fields are matched by name and the mapping is a
 * <strong>bijection</strong> — each side must expose the same set of field names (so a round-trip
 * is lossless). The generated forward/backward lambdas call public members directly: there is no
 * runtime reflection, no {@code java.desktop}, and no {@code setAccessible}; a name mismatch or a
 * missing construction strategy is a compile error rather than a runtime failure.
 *
 * <p>Each direction reads the other side's fields (a record component {@code x()}, or a POJO getter
 * {@code getX()} / {@code isX()}) and rebuilds, auto-detecting a construction strategy at compile
 * time: a record uses its canonical constructor; a POJO uses, in priority order, a public
 * constructor whose parameter names match the fields, then a static {@code builder()}, then a
 * public no-arg constructor plus {@code setX} setters.
 *
 * <p>Annotate whichever side you own. Records and classes only, and only top-level types on both
 * sides — the generated top-level {@code *Bridge} class cannot name a nested type's constructor.
 *
 * <p>This is the reflection-free, compile-checked counterpart to the runtime {@link
 * io.github.eschizoid.telescope.Telescope#map(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)} — that single factory handles record↔record,
 * POJO↔POJO, and POJO↔record at any depth. For renames or per-field transforms (which can't be
 * expressed in an annotation), use the runtime form instead.
 *
 * <pre>{@code
 * // Source — the entity points at the DTO it bridges to:
 * @Bridge(UserDto.class)
 * record UserEntity(String id, String email) {}
 *
 * // Generated alongside (UserEntityBridge.java):
 * // public final class UserEntityBridge {
 * //   public static final Telescope<UserEntity, UserDto> BRIDGE =
 * //     Telescope.from(UserEntity.class).to(UserDto.class).using(
 * //       s -> new UserDto(s.id(), s.email()),
 * //       t -> new UserEntity(t.id(), t.email()));   // strategy auto-detected per side
 * // }
 *
 * UserDto dto = UserEntityBridge.BRIDGE.read(entity);
 * }</pre>
 *
 * @see io.github.eschizoid.telescope.Telescope#map(Class, Class,
 *     io.github.eschizoid.telescope.mapping.MapStep...)
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(Bridges.class)
public @interface Bridge {
  /**
   * The target type to bridge to and from. Must be a top-level record or class exposing the same
   * field names as the annotated source, with a usable construction strategy.
   */
  Class<?> value();

  /**
   * Source field names that exist on the annotated source but not on the target. Forward conversion
   * skips them; backward conversion fills the dropped slot with {@code null} for reference types,
   * {@code 0} for numeric primitives, {@code false} for {@code boolean}, and the NUL character
   * ({@code '\u0000'}) for {@code char}. Use this when the source carries fields the target shape
   * legitimately doesn't (e.g. an internal-only field that the partner DTO does not carry).
   *
   * <p>Drops do not need a counterpart on the other side — they relax the strict-bijection check by
   * exactly the names listed. A drop name that is not actually present on the source is a compile
   * error.
   */
  String[] drops() default {};
}
