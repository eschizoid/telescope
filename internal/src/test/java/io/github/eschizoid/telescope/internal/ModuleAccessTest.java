package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.spinfixtures.SpinTarget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How {@link ModuleAccess} decides, for a lookup into a class and one of its members, whether an
 * accessor is spun by {@code LambdaMetafactory} and with which lookup, on the class path. A lookup
 * into a class of another module lacks module access, which a lookup here is given by dropping it;
 * the named-module cases are in {@link ModuleAccessLayerTest}. A class defined afresh by a loader
 * of its own is the case this module's loader resolves to a different class.
 */
class ModuleAccessTest {

  private static final String FIXTURES = "io.github.eschizoid.telescope.internal.spinfixtures.";

  /** Defines the fixture package afresh, so its classes sit in an unnamed module of their own. */
  private static final class FixtureLoader extends ClassLoader {

    private static final Set<String> NAMES = Set.of(FIXTURES + "SpinTarget", FIXTURES + "Hidden");

    FixtureLoader() {
      super(ModuleAccessTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
      if (!NAMES.contains(name)) return super.loadClass(name, resolve);
      synchronized (getClassLoadingLock(name)) {
        final var loaded = findLoadedClass(name);
        if (loaded != null) return loaded;
        final var resource = name.replace('.', '/') + ".class";
        try (final var in = getParent().getResourceAsStream(resource)) {
          final var bytes = in.readAllBytes();
          return defineClass(name, bytes, 0, bytes.length);
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
  }

  /** A private lookup into {@code target} without module access, as one into another module is. */
  private static MethodHandles.Lookup withoutModuleAccess(final Class<?> target) throws IllegalAccessException {
    final var lookup = ModuleAccess.privateLookupIn(target).dropLookupMode(MethodHandles.Lookup.MODULE);
    assertTrue(!lookup.hasFullPrivilegeAccess(), "a lookup without module access has no full privilege");
    return lookup;
  }

  @Test
  @DisplayName("a full-privilege lookup is the one the accessor is spun with")
  void aFullPrivilegeLookupSpinsItself() throws Exception {
    final var lookup = ModuleAccess.privateLookupIn(SpinTarget.class);
    assertTrue(lookup.hasFullPrivilegeAccess());

    final var spinner = ModuleAccess.spinner(lookup, SpinTarget.class.getMethod("name"));

    if (NativeImage.IN_IMAGE) assertNull(spinner, "an image never spins");
    else assertSame(lookup, spinner);
  }

  @Test
  @DisplayName("without full privilege, a public member naming only public types is spun with this module's lookup")
  void aReachableMemberSpinsInThisModule() throws Exception {
    final var lookup = withoutModuleAccess(SpinTarget.class);

    for (final var member : new String[] { "name", "numbers" }) {
      final var spinner = ModuleAccess.spinner(lookup, SpinTarget.class.getMethod(member));
      if (NativeImage.IN_IMAGE) {
        assertNull(spinner, member);
      } else {
        assertTrue(spinner != null && spinner.hasFullPrivilegeAccess(), member);
        assertSame(ModuleAccess.class.getModule(), spinner.lookupClass().getModule(), member);
      }
    }
    final var ctor = ModuleAccess.spinner(lookup, SpinTarget.class.getConstructor());
    assertEquals(!NativeImage.IN_IMAGE, ctor != null, "a public constructor of a public class spins too");
  }

  @Test
  @DisplayName("without full privilege, a member that is not public or names a non-public type is a closure")
  void anUnreachableMemberIsAClosure() throws Exception {
    final var lookup = withoutModuleAccess(SpinTarget.class);
    final var hidden = Class.forName(FIXTURES + "Hidden");
    final var target = SpinTarget.class;

    final var closures = new ArrayList<String>();
    if (ModuleAccess.spinner(lookup, target.getDeclaredMethod("secret")) == null) closures.add("non-public member");
    if (ModuleAccess.spinner(lookup, target.getMethod("hidden")) == null) closures.add("non-public return type");
    if (ModuleAccess.spinner(lookup, target.getMethod("hiddens")) == null) closures.add("array of a non-public type");
    if (ModuleAccess.spinner(lookup, target.getMethod("accept", hidden)) == null) closures.add("non-public parameter");
    if (ModuleAccess.spinner(withoutModuleAccess(hidden), hidden.getMethod("label")) == null) {
      closures.add("member of a non-public class");
    }

    assertEquals(
      List.of(
        "non-public member",
        "non-public return type",
        "array of a non-public type",
        "non-public parameter",
        "member of a non-public class"
      ),
      closures
    );
  }

  @Test
  @DisplayName("a public member of a class this module's loader resolves to a different class is a closure")
  void aClassResolvedToAnotherIsAClosure() throws Exception {
    final var target = new FixtureLoader().loadClass(FIXTURES + "SpinTarget");
    assertNotSame(SpinTarget.class, target, "the same name, defined twice");
    final var lookup = ModuleAccess.privateLookupIn(target);
    assertTrue(!lookup.hasFullPrivilegeAccess(), "a lookup into another unnamed module keeps no module access");

    assertNull(ModuleAccess.spinner(lookup, target.getMethod("name")));
    assertNull(ModuleAccess.spinner(lookup, target.getConstructor()));
  }

  @Test
  @DisplayName(
    "on the class path, the refusal names the --add-opens flag for the named module that keeps a package closed"
  )
  void theClassPathRemedyIsTheFlag() {
    assertTrue(!ModuleAccess.class.getModule().isNamed(), "this suite runs telescope on the class path");
    assertEquals(
      "Run with '--add-opens java.base/java.util=ALL-UNNAMED', since telescope is on the class path and module" +
        " java.base does not open java.util to it.",
      ModuleAccess.opensRemedy(ArrayList.class)
    );
  }

  @Test
  @DisplayName("reading a module this one already reads changes nothing")
  void readingAReadableModuleIsANoOp() {
    final var self = ModuleAccess.class.getModule();
    assertTrue(self.canRead(String.class.getModule()));
    ModuleAccess.read(String.class);
    assertTrue(self.canRead(String.class.getModule()));
  }
}
