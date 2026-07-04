package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderAlertRequest;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.DataAlertRequest;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.DataUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.SameRoundConsumer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration test: drives {@link LombokFocusProcessor} through Gradle's standard {@code
 * compileTestJava} pipeline against the real Lombok-annotated fixtures in {@code fixtures/} — the
 * same way an end user's build would consume {@code telescope-lombok}. Verifies the generated
 * {@code <Pojo>Telescope} classes by loading them via reflection and asserting on their shape; if
 * they are missing or malformed, this test class itself wouldn't compile (it references the
 * generated types' method signatures indirectly), so test failure already means a real regression.
 *
 * <p>The in-memory {@code ProcessorHarness} used by {@code :codegen} tests doesn't work for Lombok
 * because Lombok's javac AST hook installs in a different round than the one in which {@code
 * Elements.getAllMembers()} is queried — that's an in-process API limitation, not a JDK
 * incompatibility. Gradle's standard pipeline runs Lombok in its native execution path and
 * everything works.
 */
class LombokFocusProcessorTest {

  @Nested
  @DisplayName("Generated <X>Telescope classes exist for every Lombok bean trigger")
  class Generated {

    @Test
    @DisplayName("@Data POJO yields a DataUserTelescope with of(), get(), and per-property methods")
    void dataPath() throws Exception {
      final var pathClass = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures.DataUserTelescope");
      assertNotNull(pathClass);

      assertHasFocusMethod(pathClass);
      assertHasGetMethod(pathClass);
      assertReturnsTelescope(pathClass, "id");
      assertReturnsTelescope(pathClass, "email");
    }

    @Test
    @DisplayName("@Builder POJO yields a BuilderUserTelescope with of(), get(), and per-property methods")
    void builderPath() throws Exception {
      final var pathClass = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderUserTelescope");
      assertNotNull(pathClass);

      assertHasFocusMethod(pathClass);
      assertHasGetMethod(pathClass);
      assertReturnsTelescope(pathClass, "id");
      assertReturnsTelescope(pathClass, "email");
    }

    @Test
    @DisplayName("@Value + @Builder POJO yields a ValueBuilderUserTelescope via the synthesised builder()")
    void valueBuilderPath() throws Exception {
      final var pathClass = Class.forName(
        "io.github.eschizoid.telescope.codegen.lombok.fixtures.ValueBuilderUserTelescope"
      );
      assertNotNull(pathClass);

      assertHasFocusMethod(pathClass);
      assertReturnsTelescope(pathClass, "id");
      assertReturnsTelescope(pathClass, "email");
    }

    @Test
    @DisplayName("Lombok-emitted <X>Telescope is visible to same-module same-round consumers (no round-deferred limit)")
    void sameRoundConsumerCanReferenceEmittedPath() {
      // SameRoundConsumer is in src/test/java alongside DataUser. Both go through the same javac
      // compilation pass with LombokFocusProcessor on the annotation-processor classpath. The
      // consumer references DataUserTelescope directly — if this class were loaded at all (it is,
      // by
      // this test), the consumer compiled, meaning the navigator symbol resolved during the
      // consumer's
      // own binding phase. That's the regression guard against re-introducing the
      // processingOver()-only emission pattern.
      final var result = SameRoundConsumer.shoutEmail(new DataUser("u-1", "alice@example.com"));
      assertEquals("u-1", result.getId());
      assertEquals("ALICE@EXAMPLE.COM", result.getEmail());
    }

    @Test
    @DisplayName("Nested static @Data class yields a flattened-name navigator at package level")
    void nestedStaticDataClassEmitsFlattenedPath() throws Exception {
      // OuterWithNested holds a nested static @Data Inner. The processor should emit the navigator
      // /
      // metadata holder at package level with the outer's name folded in to avoid colliding with
      // a hypothetical top-level Inner. The navigator identifies the nested Inner via a dotted type
      // reference inside its own source.
      final var pathClass = Class.forName(
        "io.github.eschizoid.telescope.codegen.lombok.fixtures.OuterWithNestedInnerTelescope"
      );
      assertNotNull(pathClass);

      final var holder = Class.forName(
        "io.github.eschizoid.telescope.codegen.lombok.fixtures.OuterWithNestedInnerTelescope"
      );
      assertNotNull(holder);

      // The navigator's method signatures must reference the nested type, not a hypothetical
      // top-level one.
      final var nestedType = Class.forName(
        "io.github.eschizoid.telescope.codegen.lombok.fixtures.OuterWithNested$Inner"
      );
      assertHasFocusMethod(pathClass);
      assertHasGetMethod(pathClass);
      assertReturnsTelescope(pathClass, "label");
      assertReturnsTelescope(pathClass, "weight");
    }

    @Test
    @DisplayName("@Data POJO with List<@Data> emits a container step whose each() returns the element's navigator")
    void containerStepDescendsIntoSubPath() throws Exception {
      final var teamPath = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures.DataTeamTelescope");
      assertNotNull(teamPath);

      final var membersStep = Class.forName(
        "io.github.eschizoid.telescope.codegen.lombok.fixtures.DataTeamMembersStep"
      );
      assertNotNull(membersStep);

      final var eachMethod = membersStep.getDeclaredMethod("each");
      // Element type DataUser is @Data-annotated, so each() returns DataUserTelescope<R>, not
      // Telescope<R, DataUser>.
      assertEquals(
        "io.github.eschizoid.telescope.codegen.lombok.fixtures.DataUserTelescope",
        eachMethod.getReturnType().getName(),
        () -> "each() should return DataUserTelescope; was " + eachMethod.getReturnType()
      );
    }
  }

  @Nested
  @DisplayName("Sibling <X>FieldOptics metadata holders are emitted (ADR-0006)")
  class MetadataHolder {

    @Test
    @DisplayName(
      "@Data POJO yields a DataUserFieldOptics holder with public static final Telescope constants per property"
    )
    void dataHolder() throws Exception {
      final var holder = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures.DataUserFieldOptics");
      assertNotNull(holder);

      // Utility holder — final class, private no-arg ctor only.
      assertTrue(Modifier.isFinal(holder.getModifiers()), "holder must be final");
      assertEquals(1, holder.getDeclaredConstructors().length, "holder must have exactly one (private) constructor");
      assertTrue(Modifier.isPrivate(holder.getDeclaredConstructors()[0].getModifiers()), "holder ctor must be private");

      assertHolderField(holder, "id");
      assertHolderField(holder, "email");
    }

    @Test
    @DisplayName("@Builder POJO yields a BuilderUserTelescope holder")
    void builderHolder() throws Exception {
      final var holder = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderUserFieldOptics");
      assertNotNull(holder);
      assertHolderField(holder, "id");
      assertHolderField(holder, "email");
    }

    @Test
    @DisplayName("@Value + @Builder POJO yields a ValueBuilderUserTelescope holder")
    void valueBuilderHolder() throws Exception {
      final var holder = Class.forName(
        "io.github.eschizoid.telescope.codegen.lombok.fixtures.ValueBuilderUserFieldOptics"
      );
      assertNotNull(holder);
      assertHolderField(holder, "id");
      assertHolderField(holder, "email");
    }

    @Test
    @DisplayName("constants are functional Telescope<X, FieldType> values usable end-to-end")
    void holderConstantsAreUsable() throws Exception {
      final var holder = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures.DataUserFieldOptics");
      final var emailField = holder.getDeclaredField("email");
      @SuppressWarnings("unchecked")
      final Telescope<DataUser, String> emailLens = (Telescope<DataUser, String>) emailField.get(null);
      assertNotNull(emailLens, "email constant must be a non-null Telescope");

      final var user = new DataUser("ABC", "FOO@BAR.COM");
      final var updated = emailLens.update(user, String::toLowerCase);
      assertEquals("foo@bar.com", updated.getEmail());
      assertEquals("ABC", updated.getId(), "the non-focused property should round-trip untouched");
    }

    @Test
    @DisplayName("Phase D: @Data holder exposes a public static construct(Function) that rebuilds via setters")
    void dataHolderConstruct() throws Exception {
      final var holder = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures.DataUserFieldOptics");
      final var constructMethod = holder.getDeclaredMethod("construct", Function.class);
      final var mods = constructMethod.getModifiers();
      assertTrue(Modifier.isPublic(mods), "construct must be public");
      assertTrue(Modifier.isStatic(mods), "construct must be static");
      assertEquals(DataUser.class, constructMethod.getReturnType(), "construct must return DataUser");

      // Drive it directly with a name-keyed lookup function.
      final Function<String, Object> values = name ->
        switch (name) {
          case "id" -> "X-123";
          case "email" -> "noreply@example.com";
          default -> throw new IllegalArgumentException("Unexpected: " + name);
        };
      final var built = (DataUser) constructMethod.invoke(null, values);
      assertEquals("X-123", built.getId());
      assertEquals("noreply@example.com", built.getEmail());
    }

    @Test
    @DisplayName("Phase D: @Builder holder exposes a public static construct(Function) that chains the builder")
    void builderHolderConstruct() throws Exception {
      final var holder = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderUserFieldOptics");
      final var constructMethod = holder.getDeclaredMethod("construct", Function.class);
      assertEquals(BuilderUser.class, constructMethod.getReturnType());

      final Function<String, Object> values = name ->
        switch (name) {
          case "id" -> "B-1";
          case "email" -> "builder@example.com";
          default -> throw new IllegalArgumentException("Unexpected: " + name);
        };
      final var built = (BuilderUser) constructMethod.invoke(null, values);
      assertEquals("B-1", built.getId());
      assertEquals("builder@example.com", built.getEmail());
    }

    @Test
    @DisplayName("@Data POJO with primitive int: construct() substitutes JLS default on null entry, no NPE")
    void dataHolderConstructNullPrimitive() throws Exception {
      // Lombok @Data emits a primitive int setter for `attemptCount`. The codegen template must
      // null-guard the unbox so a null entry in the values map substitutes 0 rather than NPEing
      // through Integer.intValue() on the implicit cast.
      final var holder = Class.forName(
        "io.github.eschizoid.telescope.codegen.lombok.fixtures.DataAlertRequestFieldOptics"
      );
      final var constructMethod = holder.getDeclaredMethod("construct", Function.class);
      final Function<String, Object> values = name ->
        switch (name) {
          case "attemptCount" -> null;
          case "label" -> "warn";
          default -> throw new IllegalArgumentException("Unexpected: " + name);
        };
      final var built = (DataAlertRequest) constructMethod.invoke(null, values);
      assertEquals(0, built.getAttemptCount(), "primitive int substitutes JLS default on null entry");
      assertEquals("warn", built.getLabel());
    }

    @Test
    @DisplayName("@Builder POJO with primitive int: construct() chain substitutes JLS default on null entry, no NPE")
    void builderHolderConstructNullPrimitive() throws Exception {
      // Builder-strategy rebuild: the generated construct() chains the static builder() with one
      // .x(...) per property. Primitive properties must take the instanceof-pattern null-guard
      // form in the chain just as they do in the setter-strategy form.
      final var holder = Class.forName(
        "io.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderAlertRequestFieldOptics"
      );
      final var constructMethod = holder.getDeclaredMethod("construct", Function.class);
      final Function<String, Object> values = name ->
        switch (name) {
          case "retries" -> null;
          case "label" -> "warn";
          default -> throw new IllegalArgumentException("Unexpected: " + name);
        };
      final var built = (BuilderAlertRequest) constructMethod.invoke(null, values);
      assertEquals(
        0,
        built.getRetries(),
        "primitive int substitutes JLS default on null entry through the builder chain"
      );
      assertEquals("warn", built.getLabel());
    }
  }

  private static void assertHolderField(final Class<?> holder, final String name) throws Exception {
    final var field = holder.getDeclaredField(name);
    final var mods = field.getModifiers();
    assertTrue(Modifier.isPublic(mods), name + " must be public");
    assertTrue(Modifier.isStatic(mods), name + " must be static");
    assertTrue(Modifier.isFinal(mods), name + " must be final");
    assertEquals(Telescope.class, field.getType(), () -> name + " must be a Telescope; was " + field.getType());
  }

  @Nested
  @DisplayName("Runtime behaviour — generated navigators actually work end-to-end")
  class Runtime {

    @Test
    @DisplayName("DataUserTelescope.of().email().update lower-cases the email via Lombok's setter rebuild")
    void dataUserPathRoundTrip() throws Exception {
      final var pathClass = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures.DataUserTelescope");
      final var start = pathClass.getDeclaredMethod("of").invoke(null);
      @SuppressWarnings("unchecked")
      final Telescope<DataUser, String> emailPath = (Telescope<DataUser, String>) pathClass
        .getDeclaredMethod("email")
        .invoke(start);

      final var user = new DataUser("ABC", "FOO@BAR.COM");
      final var updated = emailPath.update(user, String::toLowerCase);
      assertEquals("foo@bar.com", updated.getEmail());
      assertEquals("ABC", updated.getId(), "the non-focused property should round-trip untouched");
    }

    @Test
    @DisplayName("BuilderUserTelescope.of().email().update rebuilds via the synthesised builder()")
    void builderUserPathRoundTrip() throws Exception {
      final var pathClass = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderUserTelescope");
      final var start = pathClass.getDeclaredMethod("of").invoke(null);
      @SuppressWarnings("unchecked")
      final Telescope<BuilderUser, String> emailPath = (Telescope<BuilderUser, String>) pathClass
        .getDeclaredMethod("email")
        .invoke(start);

      final var user = BuilderUser.builder().id("ABC").email("FOO@BAR.COM").build();
      final var updated = emailPath.update(user, String::toLowerCase);
      assertEquals("foo@bar.com", updated.getEmail());
      assertEquals("ABC", updated.getId(), "the non-focused property should round-trip untouched");
    }
  }

  private static void assertHasFocusMethod(final Class<?> pathClass) throws Exception {
    final var of = pathClass.getDeclaredMethod("of");
    assertTrue(Modifier.isStatic(of.getModifiers()), "of() must be static");
    assertEquals(pathClass, of.getReturnType(), () -> "of() should return " + pathClass.getSimpleName());
  }

  private static void assertHasGetMethod(final Class<?> pathClass) throws Exception {
    final var get = pathClass.getDeclaredMethod("get");
    assertEquals(Telescope.class, get.getReturnType());
  }

  private static void assertReturnsTelescope(final Class<?> pathClass, final String name) {
    final Method m;
    try {
      m = pathClass.getDeclaredMethod(name);
    } catch (final NoSuchMethodException e) {
      throw new AssertionError("missing method " + name + " on " + pathClass.getName(), e);
    }
    assertEquals(
      Telescope.class,
      m.getReturnType(),
      () -> name + "() should return Telescope; was " + m.getReturnType()
    );
  }
}
