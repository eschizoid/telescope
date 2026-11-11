package io.github.eschizoid.telescope.declaredbridge;

import static io.github.eschizoid.telescope.mapping.MergeStep.auto;
import static io.github.eschizoid.telescope.mapping.MergeStep.from;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.eschizoid.telescope.Sources;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.declaredbridge.MergeFixtures.Audit;
import io.github.eschizoid.telescope.declaredbridge.MergeFixtures.Customer;
import io.github.eschizoid.telescope.declaredbridge.MergeFixtures.Declared;
import io.github.eschizoid.telescope.declaredbridge.MergeFixtures.OtherAccessors;
import io.github.eschizoid.telescope.declaredbridge.MergeFixtures.OtherKinds;
import io.github.eschizoid.telescope.declaredbridge.MergeFixtures.Profile;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.mapping.Mapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code Telescope.merge} call from the class whose {@code @Bridge} field declares it returns a
 * mapper over the generated bridge, which converts forward only and fails in the runtime merge's
 * words; a bridge generated from other rows is passed over for the runtime merge.
 */
class DeclaredMergeTest {

  private static final Customer CUSTOMER = new Customer("c-1", "Ada");
  private static final Audit AUDIT = new Audit("sys");

  /** The runtime merge of the declaring class's rows, built where no field declares it. */
  private static Mapper<Sources, Profile> runtime() {
    return Telescope.merge(Profile.class, auto(Customer.class), from(Audit::by, Profile::by));
  }

  @Test
  @DisplayName("the declaring class's merge runs the generated bridge and builds what the runtime merge builds")
  void theDeclaringClassRunsTheBridge() {
    final var sources = Sources.of(CUSTOMER, AUDIT);
    final var before = MergeFixtures.CALLS.get();
    final var built = Declared.PROFILE.forward(sources);
    assertEquals(before + 1, MergeFixtures.CALLS.get());
    assertEquals(runtime().forward(sources), built);
    assertEquals(new Profile("c-1", "Ada", "sys"), built);
    assertEquals(OpticNode.Runs.GENERATED, Declared.PROFILE.explain().declared().orElseThrow().runs());
    assertTrue(runtime().explain().declared().isEmpty());
  }

  @Test
  @DisplayName("the declaring class's merge refuses backward, patch and into in the runtime merge's words")
  void theDeclaringClassRefusesTheOtherDirections() {
    final var profile = new Profile("c-1", "Ada", "sys");
    final var bag = Sources.of(CUSTOMER, AUDIT);
    final var runtime = runtime();
    assertEquals(
      assertThrows(UnsupportedOperationException.class, () -> runtime.backward(profile)).getMessage(),
      assertThrows(UnsupportedOperationException.class, () -> Declared.PROFILE.backward(profile)).getMessage()
    );
    assertEquals(
      assertThrows(UnsupportedOperationException.class, () -> runtime.patch(bag, profile)).getMessage(),
      assertThrows(UnsupportedOperationException.class, () -> Declared.PROFILE.patch(bag, profile)).getMessage()
    );
    assertEquals(
      assertThrows(UnsupportedOperationException.class, () -> runtime.into(profile, bag)).getMessage(),
      assertThrows(UnsupportedOperationException.class, () -> Declared.PROFILE.into(profile, bag)).getMessage()
    );
  }

  @Test
  @DisplayName("a bag missing a source fails the declaring class's merge in the runtime merge's words")
  void aMissingSourceFailsInTheRuntimesWords() {
    final var bag = Sources.of(CUSTOMER);
    assertEquals(
      assertThrows(IllegalStateException.class, () -> runtime().forward(bag)).getMessage(),
      assertThrows(IllegalStateException.class, () -> Declared.PROFILE.forward(bag)).getMessage()
    );
  }

  @Test
  @DisplayName("a merge bridge generated from rows of other kinds is passed over, and explain says why")
  void aBridgeFromOtherKindsIsPassedOver() {
    final var before = MergeFixtures.CALLS.get();
    assertEquals(new Profile("c-1", "Ada", "sys"), OtherKinds.PROFILE.forward(Sources.of(CUSTOMER, AUDIT)));
    assertEquals(before, MergeFixtures.CALLS.get());
    final var declared = OtherKinds.PROFILE.explain().declared().orElseThrow();
    assertEquals(OpticNode.Runs.RUNTIME, declared.runs());
    assertTrue(
      declared
        .why()
        .contains("the rows [AutoSameName, FromInferred], but the mapper is built from [AutoSameName, AutoSameName]"),
      declared.why()
    );
  }

  @Test
  @DisplayName("a merge bridge generated from rows naming other accessors is passed over on the JVM")
  void aBridgeFromOtherAccessorsIsPassedOver() {
    // An image compares row kinds only; the accessors are read on the JVM.
    assumeTrue(System.getProperty("org.graalvm.nativeimage.imagecode") == null);
    final var before = MergeFixtures.CALLS.get();
    assertEquals(new Profile("c-1", "Ada", "Ada"), OtherAccessors.PROFILE.forward(Sources.of(CUSTOMER, AUDIT)));
    assertEquals(before, MergeFixtures.CALLS.get());
    final var declared = OtherAccessors.PROFILE.explain().declared().orElseThrow();
    assertEquals(OpticNode.Runs.RUNTIME, declared.runs());
    assertTrue(declared.why().contains("name>by"), declared.why());
  }

  /** A predicate that always throws, which a when(...) row reports. */
  private static boolean refuses(final Object o) {
    throw new IllegalArgumentException("refused " + o);
  }

  @Test
  @DisplayName("the failure a generated bridge throws for a when predicate is the runtime's, carrying the cause")
  void theWhenSeamThrowsTheRuntimesFailure() {
    record Src(String a) {}
    record Tgt(String a, String c) {}
    final var mapper = Telescope.mapperForward(
      Src.class,
      Tgt.class,
      Mapping.when(DeclaredMergeTest::refuses, Mapping.constant(Tgt::c, "k"))
    );
    final var runtime = assertThrows(IllegalStateException.class, () -> mapper.forward(new Src("x")));
    final var cause = new IllegalArgumentException("refused " + new Src("x"));
    final var generated = Telescope.whenPredicateThrew("Constant", null, cause);
    assertEquals(runtime.getMessage(), generated.getMessage());
    assertSame(cause, generated.getCause());
    assertEquals(runtime.getCause().getClass(), generated.getCause().getClass());
  }
}
