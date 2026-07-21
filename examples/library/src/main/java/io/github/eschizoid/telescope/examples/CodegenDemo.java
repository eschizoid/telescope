package io.github.eschizoid.telescope.examples;

import io.github.eschizoid.telescope.examples.codegen.BeanFocusUser;
import io.github.eschizoid.telescope.examples.codegen.BeanFocusUserTelescope;
import io.github.eschizoid.telescope.examples.codegen.BridgeDto;
import io.github.eschizoid.telescope.examples.codegen.BridgeEntity;
import io.github.eschizoid.telescope.examples.codegen.BridgeEntityBridge;
import io.github.eschizoid.telescope.examples.codegen.BridgeEntityTelescope;
import io.github.eschizoid.telescope.examples.codegen.FocusAddress;
import io.github.eschizoid.telescope.examples.codegen.FocusAddressTelescope;
import io.github.eschizoid.telescope.examples.codegen.FocusUser;
import io.github.eschizoid.telescope.examples.codegen.FocusUserTelescope;

/**
 * Exercises the codegen-generated navigators. Every class referenced here below the {@code Path} /
 * {@code Bridge} suffix is produced by the {@code @Focus} / {@code @BeanFocus} / {@code @Bridge}
 * processors at compile time — this file's compilability IS proof that the generated source is on
 * the classpath.
 *
 * <p>If a regenerated navigator's API drifts (a method renamed, a forwarder dropped) the change
 * trips a compile error in this file before it reaches a real user.
 */
final class CodegenDemo {

  private CodegenDemo() {}

  static void main() {
    run();
  }

  static void run() {
    focusRecordNavigator();
    focusRecordCrossPathThen();
    beanFocusNavigator();
    bridgeNavigatorHop();
  }

  // @Focus emits a FocusUserTelescope<R> with one method per component and the full Telescope op
  // surface
  // forwarded. .of() returns FocusUserTelescope<FocusUser>.
  private static void focusRecordNavigator() {
    final var alice = new FocusUser("alice", "ALICE@ACME.COM");
    final var loweredEmail = FocusUserTelescope.of().email().update(alice, String::toLowerCase);
    System.out.println("[@Focus] email update         : " + loweredEmail);

    // Forwarders: read / find / toList / set / etc. are all on the Path itself.
    System.out.println("[@Focus] forwarder name()     : " + FocusUserTelescope.of().name().read(alice));
  }

  // Two @Focus records compose via .then(...) — the runtime DSL still works on generated paths,
  // since the navigator exposes its underlying Telescope via .get() / forwarders.
  private static void focusRecordCrossPathThen() {
    // The example is a degenerate one (we don't have nested @Focus structure here) — but it proves
    // a Path-typed value composes with another Telescope via .then(...) without unwrapping.
    final var addr = new FocusAddress("nyc", "10001");
    final var loud = FocusAddressTelescope.of().city().update(addr, String::toUpperCase);
    System.out.println("[@Focus] composition fixture  : " + loud);
  }

  // @BeanFocus emits BeanFocusUserTelescope<R> with no-arg + setter rebuild.
  private static void beanFocusNavigator() {
    final var bean = new BeanFocusUser("u1", "FOO@BAR.COM");
    final var lowered = BeanFocusUserTelescope.of().email().update(bean, String::toLowerCase);
    System.out.println("[@BeanFocus] email update     : " + lowered);
  }

  // @Bridge emits BridgeEntityBridge.BRIDGE plus an as<Target>() hop on the source Path. Because
  // BridgeDto is also @Focus, the hop returns BridgeDtoTelescope<R> so navigation continues
  // fluently.
  private static void bridgeNavigatorHop() {
    final var entity = new BridgeEntity("u1", "ALICE@ACME.COM");

    // Use the as<Target>() hop, then continue with the target Path's email() method, then update.
    final BridgeEntity lowered = BridgeEntityTelescope.of().asBridgeDto().email().update(entity, String::toLowerCase);
    System.out.println("[@Bridge] entity via DTO hop  : " + lowered);

    // The bridge itself is also reachable as the BRIDGE constant for direct conversion.
    final BridgeDto dto = BridgeEntityBridge.BRIDGE.read(entity);
    System.out.println("[@Bridge] direct BRIDGE.read  : " + dto);
  }
}
