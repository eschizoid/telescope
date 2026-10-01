package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.optics.Iso;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The composed builds of a bean written through a constructor or a builder, against the writer they
 * stand in for. Each composed result is compared with what the writer's own {@code construct}
 * produces from the same values, failures included, so a composed build that called a different
 * member, in a different order, or wrapped a failure differently shows up here.
 */
class MhIsoComposedBuildTest {

  @AfterEach
  void restore() {
    System.clearProperty(MhIso.DISABLE_PROPERTY);
  }

  public record Src(String name, int age) {}

  /** Parameters in the opposite order to the properties; the constructor trims. */
  public static final class CtorBean {

    private final String name;
    private final int age;

    public CtorBean(final int age, final String name) {
      if (age < 0) throw new IllegalArgumentException("negative age");
      this.age = age;
      this.name = name == null ? null : name.trim();
    }

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }
  }

  /** A constructor whose parameters are not named after the properties. */
  public static final class UnnamedCtorBean {

    private final String name;
    private final int age;

    public UnnamedCtorBean(final String first, final int second) {
      this.name = first;
      this.age = second;
    }

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }
  }

  /** Its builder upper-cases and logs each member it is called with, in order. */
  public static final class BuiltBean {

    static final List<String> CALLS = new ArrayList<>();

    private String name;
    private int age;

    private BuiltBean() {}

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private final BuiltBean built = new BuiltBean();

      public Builder name(final String name) {
        CALLS.add("name");
        built.name = name == null ? null : name.toUpperCase(Locale.ROOT);
        return this;
      }

      public void setAge(final int age) {
        CALLS.add("age");
        built.age = age;
      }

      public BuiltBean build() {
        if (built.age < 0) throw new IllegalStateException("build refused");
        return built;
      }
    }
  }

  /** Its build() is declared to return Object and returns something that is not the bean. */
  public static final class WrongBuild {

    private String name;
    private int age;

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      public Builder name(final String name) {
        return this;
      }

      public Builder age(final int age) {
        return this;
      }

      public Object build() {
        return "not the bean";
      }
    }
  }

  /** Read-only: no writer builds it, so a pair from it composes the forward direction alone. */
  public static final class ReadOnly {

    private final String name;
    private final int age;

    public ReadOnly(final String a, final int b) {
      this.name = a;
      this.age = b;
    }

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }
  }

  private static List<String> names(final Class<?> type) {
    return type.isRecord()
      ? Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList()
      : List.of(Beans.propertyNames(type));
  }

  /**
   * The composed pair of {@code source} and {@code target}, every slot matched by name, through
   * {@code forwardIso} where one is given. A direction composes when {@link MhIso#composesBuild}
   * accepts the writer for the side it builds; otherwise it runs through {@code other}.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <S, T> Iso<S, T> composed(
    final Class<S> source,
    final Class<T> target,
    final Beans.BeanWriter<?> targetWriter,
    final Beans.BeanWriter<?> sourceWriter,
    final Map<String, Iso<Object, Object>> forwardIso,
    final Iso<Object, Object> other
  ) {
    final Iso<Object, Object> identity = Iso.identity();
    final var srcNames = names(source);
    final var tgtNames = names(target);
    final int[] fwd = new int[tgtNames.size()];
    final Iso<Object, Object>[] fi = new Iso[tgtNames.size()];
    for (var i = 0; i < tgtNames.size(); i++) {
      fwd[i] = srcNames.indexOf(tgtNames.get(i));
      fi[i] = forwardIso.getOrDefault(tgtNames.get(i), identity);
    }
    final int[] bwd = new int[srcNames.size()];
    final Iso<Object, Object>[] bi = new Iso[srcNames.size()];
    for (var i = 0; i < srcNames.size(); i++) {
      bwd[i] = tgtNames.indexOf(srcNames.get(i));
      bi[i] = identity;
    }
    return MhIso.pair(
      source,
      target,
      fwd,
      fi,
      bwd,
      bi,
      identity,
      other,
      MhIso.composesBuild(source, target, targetWriter),
      MhIso.composesBuild(target, source, sourceWriter),
      targetWriter,
      sourceWriter
    );
  }

  private static <T> Iso<Src, T> forward(final Class<T> target, final Beans.BeanWriter<?> writer) {
    return composed(Src.class, target, writer, null, Map.of(), null);
  }

  @Nested
  @DisplayName("composesBuild — which writer a composed build reproduces")
  class ComposesBuild {

    @Test
    @DisplayName("a constructor writer whose arguments are properties, and a builder writer, compose")
    void constructorAndBuilderCompose() {
      assertTrue(MhIso.composesBuild(Src.class, CtorBean.class, Beans.constructorWriter(CtorBean.class, 2)));
      assertTrue(MhIso.composesBuild(Src.class, BuiltBean.class, Beans.builderWriter(BuiltBean.class)));
    }

    @Test
    @DisplayName("a constructor writer reading an argument by a name that is no property does not")
    void unnamedConstructorDoesNot() {
      assertFalse(
        MhIso.composesBuild(Src.class, UnnamedCtorBean.class, Beans.constructorWriter(UnnamedCtorBean.class, 2))
      );
    }

    @Test
    @DisplayName("the setters-only gate refuses a bean the setter writer cannot be built for")
    void settersGateRefusesWithoutANoArgConstructor() {
      assertFalse(MhIso.supports(Src.class, CtorBean.class), "CtorBean has no no-arg constructor");
    }

    @Test
    @DisplayName("a bean with no writer does not")
    void noWriterDoesNot() {
      assertFalse(MhIso.composesBuild(Src.class, CtorBean.class, null));
    }

    @Test
    @DisplayName("a record target composes whatever writer is given; the disable property turns everything off")
    void recordTargetAndDisableProperty() {
      assertTrue(MhIso.composesBuild(CtorBean.class, Src.class, null));
      System.setProperty(MhIso.DISABLE_PROPERTY, "true");
      assertFalse(MhIso.composesBuild(Src.class, BuiltBean.class, Beans.builderWriter(BuiltBean.class)));
    }
  }

  @Nested
  @DisplayName("a composed constructor build")
  class ConstructorBuild {

    @Test
    @DisplayName("passes each argument the property its parameter is named after, as the writer does")
    void argumentsByName() {
      final var writer = Beans.constructorWriter(CtorBean.class, 2);
      final var built = forward(CtorBean.class, writer).to(new Src("  a  ", 3));
      final var direct = writer.construct(Beans.propertyNames(CtorBean.class), n -> n.equals("age") ? 3 : "  a  ");

      assertEquals("a", built.getName(), "the constructor ran and trimmed");
      assertEquals(3, built.getAge());
      assertEquals(direct.getName(), built.getName());
      assertEquals(direct.getAge(), built.getAge());
    }

    @Test
    @DisplayName("a null for a primitive parameter becomes its default, as the writer makes it")
    void nullIntoPrimitive() {
      final Iso<Object, Object> nulling = Iso.of(v -> null, v -> v);
      final var writer = Beans.constructorWriter(CtorBean.class, 2);
      final var built = composed(Src.class, CtorBean.class, writer, null, Map.of("age", nulling), null).to(
        new Src("a", 3)
      );
      assertEquals(0, built.getAge());
      assertEquals(
        0,
        writer.construct(Beans.propertyNames(CtorBean.class), n -> n.equals("name") ? "a" : null).getAge()
      );
    }

    @Test
    @DisplayName("a failing constructor is wrapped as the writer wraps it")
    void failureWrapped() {
      final var writer = Beans.constructorWriter(CtorBean.class, 2);
      final var composedFailure = assertThrows(RuntimeException.class, () ->
        forward(CtorBean.class, writer).to(new Src("a", -1))
      );
      final var directFailure = assertThrows(RuntimeException.class, () ->
        writer.construct(Beans.propertyNames(CtorBean.class), n -> n.equals("age") ? -1 : "a")
      );
      assertEquals(directFailure.getMessage(), composedFailure.getMessage());
      assertInstanceOf(IllegalArgumentException.class, composedFailure.getCause());
      assertEquals("negative age", composedFailure.getCause().getMessage());
    }
  }

  @Nested
  @DisplayName("a composed builder build")
  class BuilderBuild {

    @Test
    @DisplayName("calls each member once, in property order, as the writer does")
    void membersInPropertyOrder() {
      final var writer = Beans.builderWriter(BuiltBean.class);
      BuiltBean.CALLS.clear();
      final var built = forward(BuiltBean.class, writer).to(new Src("a", 3));
      final var composedCalls = List.copyOf(BuiltBean.CALLS);
      BuiltBean.CALLS.clear();
      writer.construct(Beans.propertyNames(BuiltBean.class), n -> n.equals("age") ? 3 : "a");

      assertEquals("A", built.getName());
      assertEquals(3, built.getAge());
      assertEquals(BuiltBean.CALLS, composedCalls);
      assertEquals(2, composedCalls.size());
    }

    @Test
    @DisplayName("a null for a primitive member becomes its default")
    void nullIntoPrimitive() {
      final Iso<Object, Object> nulling = Iso.of(v -> null, v -> v);
      final var built = composed(
        Src.class,
        BuiltBean.class,
        Beans.builderWriter(BuiltBean.class),
        null,
        Map.of("age", nulling),
        null
      ).to(new Src("a", 3));
      assertEquals(0, built.getAge());
    }

    @Test
    @DisplayName("a failing build() surfaces as it does through the writer")
    void failingBuild() {
      final var writer = Beans.builderWriter(BuiltBean.class);
      final var composedFailure = assertThrows(IllegalStateException.class, () ->
        forward(BuiltBean.class, writer).to(new Src("a", -1))
      );
      final var directFailure = assertThrows(IllegalStateException.class, () ->
        writer.construct(Beans.propertyNames(BuiltBean.class), n -> n.equals("age") ? -1 : "a")
      );
      assertEquals(directFailure.getMessage(), composedFailure.getMessage());
    }

    @Test
    @DisplayName("a build() returning something that is not the bean is refused, in the writer's words")
    void instanceCheck() {
      final var writer = Beans.builderWriter(WrongBuild.class);
      final var composedFailure = assertThrows(IllegalStateException.class, () ->
        forward(WrongBuild.class, writer).to(new Src("a", 1))
      );
      final var directFailure = assertThrows(IllegalStateException.class, () ->
        writer.construct(Beans.propertyNames(WrongBuild.class), n -> null)
      );
      assertEquals(directFailure.getMessage(), composedFailure.getMessage());
      assertTrue(composedFailure.getMessage().contains("returned java.lang.String"), composedFailure.getMessage());
    }
  }

  @Nested
  @DisplayName("a pair composed in one direction")
  class OneDirection {

    @Test
    @DisplayName("runs the other direction through the iso it is given, and is still a composed leaf")
    void otherDirectionThroughTheGivenIso() {
      final var sentinel = new ReadOnly("other", 9);
      final Iso<Object, Object> other = Iso.of(s -> null, t -> sentinel);
      @SuppressWarnings("unchecked")
      final Iso<ReadOnly, CtorBean> iso = composed(
        ReadOnly.class,
        CtorBean.class,
        Beans.constructorWriter(CtorBean.class, 2),
        null,
        Map.of(),
        other
      );

      assertTrue(MhIso.isComposedLeaf(iso));
      assertEquals("x", iso.to(new ReadOnly(" x ", 1)).getName(), "forward composed through the constructor");
      assertSame(sentinel, iso.from(new CtorBean(1, "x")), "backward ran through the given iso");
    }
  }
}
