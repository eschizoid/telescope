package io.github.eschizoid.telescope.spring;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import io.github.eschizoid.telescope.codegen.TelescopeMapperProcessor;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelescopeSpringProcessorTest {

  @Test
  void unknownPathSegmentFailsAtCompileTime() {
    final var result = ProcessorHarness.compile(
      new TelescopeMapperProcessor(),
      source(
        "demo.BadPath",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopePath;
        import io.github.eschizoid.telescope.spring.TelescopeTransform;
        record Person(String name) {}
        @TelescopeTransform(from = Person.class, to = String.class, path = "missing")
        interface BadPath extends TelescopePath<Person, String> {}
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("path segment 'missing' is not a field")).isTrue();
  }

  @Test
  void nestedPathWithAnEmptySegmentFailsAtCompileTime() {
    final var result = ProcessorHarness.compile(
      new TelescopeMapperProcessor(),
      source(
        "demo.BadNestedPath",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopePath;
        import io.github.eschizoid.telescope.spring.TelescopeTransform;
        record Contact(String email) {}
        record Person(Contact contact) {}
        @TelescopeTransform(from = Person.class, to = String.class, path = "contact..email")
        interface BadNestedPath extends TelescopePath<Person, String> {}
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("path segment '' is not a field")).isTrue();
  }

  @Test
  void terminalTypeMismatchFailsAtCompileTime() {
    final var result = ProcessorHarness.compile(
      new TelescopeMapperProcessor(),
      source(
        "demo.WrongTypePath",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopePath;
        import io.github.eschizoid.telescope.spring.TelescopeTransform;
        record Person(String name) {}
        @TelescopeTransform(from = Person.class, to = Integer.class, path = "name")
        interface WrongTypePath extends TelescopePath<Person, Integer> {}
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("path ends at java.lang.String, expected java.lang.Integer")).isTrue();
  }

  @Test
  void nonRecordIntermediateFailsAtCompileTime() {
    final var result = ProcessorHarness.compile(
      new TelescopeMapperProcessor(),
      source(
        "demo.NonRecordPath",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopePath;
        import io.github.eschizoid.telescope.spring.TelescopeTransform;
        import java.util.List;
        record Person(List<String> names) {}
        @TelescopeTransform(from = Person.class, to = String.class, path = "names.value")
        interface NonRecordPath extends TelescopePath<Person, String> {}
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("supports record fields only")).isTrue();
  }

  @Test
  void mapperRequiresAnExistingBridge() {
    final var result = ProcessorHarness.compile(
      new TelescopeMapperProcessor(),
      source(
        "demo.BadMapper",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        record Source(String name) {}
        record Target(String name) {}
        @TelescopeMapper(from = Source.class, to = Target.class)
        interface BadMapper { Target map(Source source); }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("requires a generated @Bridge")).isTrue();
  }

  @Test
  void mapperMethodMustMatchItsDeclaredTypePair() {
    final var result = ProcessorHarness.compile(
      new TelescopeMapperProcessor(),
      source(
        "demo.WrongSignatureMapper",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        @TelescopeMapper(from = User.class, to = UserDto.class)
        interface WrongSignatureMapper { User map(User input); }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("requires one method accepting")).isTrue();
  }

  @Test
  void mapperCanUseABridgeCompiledInAnotherModule() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.ExternalMapper",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        @TelescopeMapper(from = User.class, to = UserDto.class)
        interface ExternalMapper { UserDto map(User user); }
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
  }
}
