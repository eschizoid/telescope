package io.github.eschizoid.telescope.annotations;

import io.github.eschizoid.telescope.conversion.BridgeFn;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A per-field typed conversion attached to a {@link Bridge}. The named source field is read, passed
 * through {@link #using()}'s {@code forward} on the way to the target, and through {@code backward}
 * on the way back. Use this when the two sides of a bridge name compatible fields but the types
 * differ — e.g. a record holds a {@code BigDecimal} price, the entity stores cents as a {@code
 * long}.
 *
 * <pre>{@code
 * public final class CentsConverter implements BridgeFn<BigDecimal, Long> {
 *   public Long forward(BigDecimal x)    { return x.movePointRight(2).longValueExact(); }
 *   public BigDecimal backward(Long c)   { return BigDecimal.valueOf(c).movePointLeft(2); }
 * }
 *
 * @Bridge(value = LineItemEntity.class, transforms = {
 *   @Transform(field = "unitPrice", using = CentsConverter.class)
 * })
 * public record LineItem(String id, BigDecimal unitPrice) {}
 * }</pre>
 *
 * <p>The transform class must:
 *
 * <ul>
 *   <li>implement {@link BridgeFn} parameterised over the source-side and target-side field types
 *   <li>be a top-level type with a public no-arg constructor (the generated bridge instantiates one
 *       static instance per transformed field)
 * </ul>
 *
 * <p>{@code field} names the source side. Pair with a sibling {@link Rename} when the target's
 * matching field has a different name.
 *
 * <h2>Forward-only transforms</h2>
 *
 * <p>Set {@link #forwardOnly()} to {@code true} when the conversion is genuinely one-way and the
 * {@code BridgeFn} should not be required to implement backward. The processor emits a zero-value
 * fallback in the backward direction (mirroring the {@code @Bridge(drops = ...)} backward fill) and
 * skips the {@code __tx_<field>.backward(...)} call-site. The user's {@code BridgeFn} can implement
 * {@code backward} as a stub (e.g. throwing {@link UnsupportedOperationException}); the generated
 * bridge never invokes it.
 *
 * <p>This mirrors the runtime {@code Mapping.forward(srcAcc, tgtAcc, fn)} factory — same forward-
 * only semantics, same "this slot's backward is undefined" contract.
 */
@Retention(RetentionPolicy.SOURCE)
public @interface Transform {
  /**
   * Source field name to route through the {@link #using()} {@link BridgeFn}. Must name a real
   * field on the {@link Bridge} source.
   */
  String field();

  /**
   * A {@link BridgeFn} implementation whose {@code forward}/{@code backward} convert this field's
   * value in each direction, OR — when {@link #method()} is set — any class containing a static
   * method named {@link #method()} taking one argument and returning the converted value. The
   * {@link BridgeFn} shape is the default; the {@code method}-named form unlocks MapStruct's
   * {@code @Named} qualifier-dispatch pattern where a single helper class hosts several conversion
   * methods (different fields point at different methods on the same class).
   *
   * <p>Without {@code method}: must be a top-level {@link BridgeFn} subtype with a public no-arg
   * constructor — the generated bridge instantiates one static instance per transformed field.
   *
   * <p>With {@code method}: may be any top-level class. The generated bridge emits a direct {@code
   * UsingClass.methodName(value)} call (no instantiation, no {@code BridgeFn} requirement). The
   * named method must be static, take exactly one argument, and return the converted value. Always
   * forward-only (qualifier dispatch is inherently asymmetric — the inverse method name isn't
   * recoverable from a forward method name). {@link #forwardOnly()} is implicitly true when {@code
   * method} is set.
   */
  @SuppressWarnings("rawtypes")
  Class<?> using();

  /**
   * Optional named static method on {@link #using()} to invoke for the forward conversion. When
   * non-empty, switches the row from the {@link BridgeFn}-shape to the qualifier-dispatch shape:
   * {@code using} no longer needs to implement {@link BridgeFn}, and the generated bridge emits a
   * direct {@code UsingClass.methodName(value)} call.
   *
   * <pre>{@code
   * public final class DateHelpers {
   *   public static String expiry(Instant i)   { return DateTimeFormatter.ISO_INSTANT.format(i); }
   *   public static String createdAt(Instant i) { return i.atZone(ZoneOffset.UTC).toString(); }
   * }
   *
   * @Bridge(value = UserDto.class, transforms = {
   *   @Transform(field = "expiresAt", using = DateHelpers.class, method = "expiry"),
   *   @Transform(field = "createdAt", using = DateHelpers.class, method = "createdAt")
   * })
   * public record UserEntity(String id, Instant expiresAt, Instant createdAt) {}
   * }</pre>
   *
   * <p>Always implicitly forward-only — see {@link #using()} for the full contract.
   *
   * <p>Default empty ({@code ""}) preserves the existing {@link BridgeFn}-shape semantics.
   */
  String method() default "";

  /**
   * Opt in to forward-only semantics: the generated bridge emits a zero-value fill in the backward
   * direction for this field, the same way {@code @Bridge(drops = ...)} fills dropped sources on
   * backward. The user's {@code BridgeFn#backward} is never invoked; implementations may stub it or
   * throw. Mirrors the runtime {@code Mapping.forward(srcAcc, tgtAcc, fn)} factory.
   *
   * <p>The generated {@code patch(base, partial)} method also skips the backward read for this slot
   * and preserves {@code base.field()} unchanged — a forward-only transform has no defined inverse,
   * so there is no way to overlay a partial value back onto the source.
   *
   * <p>Implicitly {@code true} when {@link #method()} is set — qualifier dispatch is inherently
   * one-direction (the inverse method name isn't recoverable from a forward method name).
   */
  boolean forwardOnly() default false;
}
