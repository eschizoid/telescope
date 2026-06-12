package io.github.eschizoid.telescope.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Supplier;

/**
 * A computed value injected into a target field at {@link Bridge} codegen time. Mirrors MapStruct's
 * {@code @Mapping(target = "x", expression = "java(...)")}. Forward-only by design — the target
 * field is filled by calling {@code Supplier#get()} on each forward conversion; backward conversion
 * silently drops the slot since the source has no counterpart.
 *
 * <pre>{@code
 * public final class NowSupplier implements Supplier<java.time.Instant> {
 *   public NowSupplier() {}
 *   public java.time.Instant get() { return java.time.Instant.now(); }
 * }
 *
 * @Bridge(value = LineItemEntity.class, computes = {
 *   @Compute(field = "createdAt", using = NowSupplier.class)
 * })
 * public record LineItem(String id, java.math.BigDecimal unitPrice) {}
 * }</pre>
 *
 * <p>The {@link #using()} class must:
 *
 * <ul>
 *   <li>implement {@link Supplier} parameterised over the target field's type
 *   <li>be a top-level class with a public no-arg constructor (the emitted bridge instantiates one
 *       static instance per computed field)
 * </ul>
 *
 * <p>The target field cannot also appear in {@link Bridge#renames()}, {@link Bridge#drops()}, or in
 * a {@link Constant} for this pair — picking one of constant / compute / rename / drop per field is
 * enforced at compile time.
 */
@Retention(RetentionPolicy.SOURCE)
public @interface Compute {
  /**
   * Target field name receiving the computed value. Must name a real field on the {@link Bridge}
   * target.
   */
  String field();

  /**
   * A {@link Supplier} implementation whose {@code get()} method produces the value to inject on
   * each forward conversion. Must be a top-level class with a public no-arg constructor.
   */
  @SuppressWarnings("rawtypes")
  Class<? extends Supplier> using();
}
