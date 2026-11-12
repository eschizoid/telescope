package io.github.eschizoid.telescope.demo.nativeimage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the day at the shop on the JVM, so the build checks every step on each run. The native image
 * runs the same steps in the native-image workflow, which is where the image itself is checked.
 */
class OrderRunTest {

  @Test
  @DisplayName("every step of the day at the shop passes on the JVM")
  void everyStepPasses() {
    assertAll(
      OrderRun.run()
        .stream()
        .map(result -> () -> assertTrue(result.passed(), () -> result.step() + ": " + result.detail()))
    );
  }
}
