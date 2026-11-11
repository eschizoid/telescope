package io.github.eschizoid.telescope.declaredbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.eschizoid.telescope.declaredbridge.PathFixtures.Declared;
import io.github.eschizoid.telescope.declaredbridge.PathFixtures.Mid;
import io.github.eschizoid.telescope.declaredbridge.PathFixtures.Src;
import io.github.eschizoid.telescope.declaredbridge.PathFixtures.Tgt;
import io.github.eschizoid.telescope.declaredbridge.PathFixtures.Zipped;
import io.github.eschizoid.telescope.introspection.OpticNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A field whose rows are paths is matched to its generated bridge by the paths each row steps
 * through: a row with a path on each side by both paths, so a zip of the same two paths, which
 * names neither, is a different row.
 */
class DeclaredPathTest {

  @Test
  @DisplayName("a field of path rows runs the bridge generated from the same paths")
  void aFieldOfPathRowsRunsItsBridge() {
    assertEquals(OpticNode.Runs.GENERATED, Declared.PATHS.explain().declared().orElseThrow().runs());
    assertEquals(new Tgt(null, "generated"), Declared.PATHS.forward(new Src(new Mid("n", 1), "k")));
  }

  @Test
  @DisplayName("a zip of the paths a bridge's row wrote one through the other is passed over, and explain says why")
  void aZipOfTheSamePathsIsPassedOver() {
    // An image compares row kinds only; the accessors are read on the JVM.
    assumeTrue(System.getProperty("org.graalvm.nativeimage.imagecode") == null);
    final var declared = Zipped.PATHS.explain().declared().orElseThrow();
    assertEquals(OpticNode.Runs.RUNTIME, declared.runs());
    assertTrue(
      declared
        .why()
        .contains(
          "rows naming the accessors [customer.name>ship.label, nick>ship.nick, customer.name>name], but the mapper" +
            " is built from rows naming [>, nick>ship.nick, customer.name>name]"
        ),
      declared.why()
    );
  }
}
