package org.telescope.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JavaBeans-style POJO for reflection-free lens codegen — the bean analog of {@link Focus}
 * (which targets records). For each readable property the {@code telescope-codegen} processor emits
 * a {@code public static final Telescope<Pojo, PropType>} constant on a generated {@code
 * <Pojo>Focus} class, built from {@code Telescope.lens(getter, rebuild)} using only public members.
 *
 * <p>This is the fast path for {@link org.telescope.Telescope#ofBean(Class)}: where {@code ofBean}
 * navigates a POJO reflectively (rebuilding the whole bean and re-reading every getter at each
 * level), the generated constants are reflection-free and compose at roughly the speed of a
 * hand-written copy.
 *
 * <p>The lens setter rebuilds the POJO with one property changed via a strategy auto-detected at
 * compile time: a static {@code builder()}, or a no-arg constructor plus {@code setX} setters.
 * (Field injection isn't available to generated code, so a POJO that exposes neither a builder nor
 * setters can't be {@code @BeanFocus}'d — bridge it to a record with {@link Bridge}, or navigate it
 * reflectively with {@code ofBean}.) A missing setter/builder method is a compile error.
 *
 * <pre>{@code
 * @BeanFocus
 * class User { // getName()/setName(), getEmail()/setEmail(), no-arg ctor
 * }
 *
 * // Generated alongside:
 * // public final class UserFocus {
 * //   public static final Telescope<User, String> name  = Telescope.lens(User::getName, ...);
 * //   public static final Telescope<User, String> email = Telescope.lens(User::getEmail, ...);
 * // }
 *
 * // Usage — no reflection:
 * UserFocus.email.update(user, String::toLowerCase);   // new User
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface BeanFocus {}
