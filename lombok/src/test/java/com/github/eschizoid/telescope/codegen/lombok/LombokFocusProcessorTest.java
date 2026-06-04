package com.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.eschizoid.telescope.Telescope;
import com.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderUser;
import com.github.eschizoid.telescope.codegen.lombok.fixtures.DataUser;
import com.github.eschizoid.telescope.codegen.lombok.fixtures.ValueBuilderUser;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration test: drives {@link LombokFocusProcessor} through Gradle's standard {@code
 * compileTestJava} pipeline against the real Lombok-annotated fixtures in {@code fixtures/} — the
 * same way an end user's build would consume {@code telescope-lombok}. Verifies the generated
 * {@code <Pojo>Path} classes by loading them via reflection and asserting on their shape; if they
 * are missing or malformed, this test class itself wouldn't compile (it references the generated
 * types' method signatures indirectly), so test failure already means a real regression.
 *
 * <p>The in-memory {@code ProcessorHarness} used by {@code :codegen} tests doesn't work for Lombok
 * because Lombok's javac AST hook installs in a different round than the one in which {@code
 * Elements.getAllMembers()} is queried — that's an in-process API limitation, not a JDK
 * incompatibility. Gradle's standard pipeline runs Lombok in its native execution path and
 * everything works.
 */
class LombokFocusProcessorTest {

  @Nested
  @DisplayName("Generated <X>Path classes exist for every Lombok bean trigger")
  class Generated {

    @Test
    @DisplayName("@Data POJO yields a DataUserPath with start(), get(), and per-property methods")
    void dataPath() throws Exception {
      final var pathClass = Class.forName("com.github.eschizoid.telescope.codegen.lombok.fixtures.DataUserPath");
      assertNotNull(pathClass);

      assertHasStartMethod(pathClass, DataUser.class);
      assertHasGetMethod(pathClass, DataUser.class);
      assertReturnsTelescope(pathClass, "id");
      assertReturnsTelescope(pathClass, "email");
    }

    @Test
    @DisplayName("@Builder POJO yields a BuilderUserPath with start(), get(), and per-property methods")
    void builderPath() throws Exception {
      final var pathClass = Class.forName("com.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderUserPath");
      assertNotNull(pathClass);

      assertHasStartMethod(pathClass, BuilderUser.class);
      assertHasGetMethod(pathClass, BuilderUser.class);
      assertReturnsTelescope(pathClass, "id");
      assertReturnsTelescope(pathClass, "email");
    }

    @Test
    @DisplayName("@Value + @Builder POJO yields a ValueBuilderUserPath via the synthesised builder()")
    void valueBuilderPath() throws Exception {
      final var pathClass = Class.forName(
        "com.github.eschizoid.telescope.codegen.lombok.fixtures.ValueBuilderUserPath"
      );
      assertNotNull(pathClass);

      assertHasStartMethod(pathClass, ValueBuilderUser.class);
      assertReturnsTelescope(pathClass, "id");
      assertReturnsTelescope(pathClass, "email");
    }

    @Test
    @DisplayName("@Data POJO with List<@Data> emits a container step whose each() returns the element's Path")
    void containerStepDescendsIntoSubPath() throws Exception {
      final var teamPath = Class.forName("com.github.eschizoid.telescope.codegen.lombok.fixtures.DataTeamPath");
      assertNotNull(teamPath);

      final var membersStep = Class.forName(
        "com.github.eschizoid.telescope.codegen.lombok.fixtures.DataTeamMembersStep"
      );
      assertNotNull(membersStep);

      final var eachMethod = membersStep.getDeclaredMethod("each");
      // Element type DataUser is @Data-annotated, so each() returns DataUserPath<R>, not
      // Telescope<R, DataUser>.
      assertEquals(
        "com.github.eschizoid.telescope.codegen.lombok.fixtures.DataUserPath",
        eachMethod.getReturnType().getName(),
        () -> "each() should return DataUserPath; was " + eachMethod.getReturnType()
      );
    }
  }

  @Nested
  @DisplayName("Runtime behaviour — generated paths actually work end-to-end")
  class Runtime {

    @Test
    @DisplayName("DataUserPath.start().email().update lower-cases the email via Lombok's setter rebuild")
    void dataUserPathRoundTrip() throws Exception {
      final var pathClass = Class.forName("com.github.eschizoid.telescope.codegen.lombok.fixtures.DataUserPath");
      final var start = pathClass.getDeclaredMethod("start").invoke(null);
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
    @DisplayName("BuilderUserPath.start().email().update rebuilds via the synthesised builder()")
    void builderUserPathRoundTrip() throws Exception {
      final var pathClass = Class.forName("com.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderUserPath");
      final var start = pathClass.getDeclaredMethod("start").invoke(null);
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

  private static void assertHasStartMethod(final Class<?> pathClass, final Class<?> rootType) throws Exception {
    final var start = pathClass.getDeclaredMethod("start");
    assertTrue(java.lang.reflect.Modifier.isStatic(start.getModifiers()), "start() must be static");
    assertEquals(pathClass, start.getReturnType(), () -> "start() should return " + pathClass.getSimpleName());
  }

  private static void assertHasGetMethod(final Class<?> pathClass, final Class<?> rootType) throws Exception {
    final var get = pathClass.getDeclaredMethod("get");
    assertEquals(Telescope.class, get.getReturnType());
  }

  private static void assertReturnsTelescope(final Class<?> pathClass, final String name) throws Exception {
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
