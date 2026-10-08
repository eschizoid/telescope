package io.github.eschizoid.telescope.internal;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Access to an application's classes from this module, which builds every accessor the runtime path
 * uses.
 *
 * <p>A method handle lookup into a class needs two things of its module: that this module reads it,
 * and, for private access, that it opens the class's package to this module. An application module
 * grants the second with {@code opens}. The first it cannot grant, since readability belongs to the
 * reading module, and this module does not require the application, which it has never heard of. So
 * this module adds the read edge itself, which a module may always do for its own reads, before it
 * looks anything up. On the class path every module reads every unnamed module, and the edge is
 * already there.
 */
final class ModuleAccess {

  private static final Module SELF = ModuleAccess.class.getModule();

  private ModuleAccess() {}

  /** Makes {@code target}'s module readable from this one, so a lookup into it can proceed. */
  static void read(final Class<?> target) {
    final var module = target.getModule();
    if (!SELF.canRead(module)) SELF.addReads(module);
  }

  /** A lookup with private access to {@code target}, after making its module readable. */
  static MethodHandles.Lookup privateLookupIn(final Class<?> target) throws IllegalAccessException {
    read(target);
    return MethodHandles.privateLookupIn(target, MethodHandles.lookup());
  }

  /**
   * The lookup to spin a {@code LambdaMetafactory} accessor for {@code member} in, or null when the
   * accessor has to be a {@link MhAccessors} closure over the same handle.
   *
   * <p>Spinning needs a caller lookup with full privilege access, and it reaches the member from
   * the class it spins. A private lookup into a class of this module, or of the unnamed module on
   * the class path, has full privilege and is used as it is. A private lookup into another named
   * module keeps private access but loses module access, so it cannot spin. This module's own
   * lookup can, provided the class it spins may reach the member as ordinary code would: the member
   * and every type it names are public, and their packages are exported (or opened, which counts as
   * exported at run time) to this module. Anything else, and everything in a native image, which
   * cannot spin a class at all, is a closure.
   *
   * <p>The spun class is defined in the spinning lookup's class loader and names the member's
   * declaring class, parameter types and return type, which that loader resolves by name when the
   * class links. A loader that resolves one of those names to nothing, or to a different class, as
   * this module's loader does for a class a child loader holds, would leave the accessor failing
   * with {@link NoClassDefFoundError} on its first call, so such a member is a closure too.
   */
  static MethodHandles.Lookup spinner(final MethodHandles.Lookup lookup, final Executable member) {
    if (NativeImage.IN_IMAGE) return null;
    final MethodHandles.Lookup candidate;
    if (lookup.hasFullPrivilegeAccess()) {
      candidate = lookup;
    } else {
      if (!Modifier.isPublic(member.getModifiers()) || !reachable(member.getDeclaringClass())) return null;
      for (final var parameter : member.getParameterTypes()) if (!reachable(parameter)) return null;
      if (member instanceof Method method && !reachable(method.getReturnType())) return null;
      candidate = OWN;
    }
    return resolvable(candidate, member) ? candidate : null;
  }

  private static final MethodHandles.Lookup OWN = MethodHandles.lookup();

  /**
   * Whether {@code spinner}'s loader resolves every type {@code member} names to that same type.
   */
  private static boolean resolvable(final MethodHandles.Lookup spinner, final Executable member) {
    final var loader = spinner.lookupClass().getClassLoader();
    if (!resolves(loader, member.getDeclaringClass())) return false;
    for (final var parameter : member.getParameterTypes()) if (!resolves(loader, parameter)) return false;
    return !(member instanceof Method method) || resolves(loader, method.getReturnType());
  }

  /**
   * Whether {@code loader} resolves {@code type}'s name to {@code type}. A type held by the boot
   * loader or by {@code loader} itself always is; any other is asked of {@code loader}, since
   * delegation, not parentage, decides what a loader can see.
   */
  private static boolean resolves(final ClassLoader loader, final Class<?> type) {
    if (type.isArray()) return resolves(loader, type.getComponentType());
    if (type.isPrimitive()) return true;
    final var holder = type.getClassLoader();
    if (holder == null || holder == loader) return true;
    try {
      return Class.forName(type.getName(), false, loader) == type;
    } catch (final ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** Whether code in this module may name {@code type}: public, in a package exported to it. */
  private static boolean reachable(final Class<?> type) {
    if (type.isArray()) return reachable(type.getComponentType());
    if (type.isPrimitive()) return true;
    return Modifier.isPublic(type.getModifiers()) && type.getModule().isExported(type.getPackageName(), SELF);
  }

  /**
   * What the application has to change when a private lookup into {@code target} is refused once
   * its module is readable: the package is not open to this module.
   */
  static String opensRemedy(final Class<?> target) {
    final var module = target.getModule();
    final var pkg = target.getPackageName();
    if (!SELF.isNamed()) {
      return (
        "Run with '--add-opens " +
        module.getName() +
        "/" +
        pkg +
        "=ALL-UNNAMED', since telescope is on the class path and module " +
        module.getName() +
        " does not open " +
        pkg +
        " to it."
      );
    }
    return (
      "Add 'opens " +
      pkg +
      " to " +
      SELF.getName() +
      ";' (or an unqualified 'opens " +
      pkg +
      ";') to the module-info.java of module " +
      module.getName() +
      ", which telescope's accessors are built in."
    );
  }
}
