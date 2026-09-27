package io.github.eschizoid.telescope.spring;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.codegen.BridgeProcessor;
import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import io.github.eschizoid.telescope.codegen.TelescopeMapperProcessor;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelescopeSpringProcessorTest {

  @Test
  void missingDefaultPathFailsAtCompileTime() {
    final var result = ProcessorHarness.compile(
      new TelescopeMapperProcessor(),
      source(
        "demo.BadPathTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        record Person(String name) {}
        @TelescopeTransformer
        interface BadPathTransformer extends TelescopeTransformation<Person, String> {}
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("requires a default path()")).isTrue();
  }

  @Test
  void renamedAccessorFailsAtCompileTime() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.BadNestedPathTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.Telescope;
        record Contact(String email) {}
        record Person(Contact contact) {}
        @TelescopeTransformer
        interface BadNestedPathTransformer extends TelescopeTransformation<Person, String> {
          default Transformation<String> transform() { return new Transformation<>(null, value -> value); }
          default Telescope<Person, String> path() {
            return Telescope.of(Person.class).field(Person::contact).field(Contact::oldEmail);
          }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
  }

  @Test
  void terminalTypeMismatchFailsAtCompileTime() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.WrongTypePathTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.Telescope;
        record Person(String name) {}
        @TelescopeTransformer
        interface WrongTypePathTransformer extends TelescopeTransformation<Person, Integer> {
          default Transformation<Integer> transform() { return new Transformation<>(0, value -> value); }
          default Telescope<Person, Integer> path() {
            return Telescope.of(Person.class).field(Person::name);
          }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
  }

  @Test
  void wrongPathInterfaceFailsAtCompileTime() {
    final var result = ProcessorHarness.compile(
      new TelescopeMapperProcessor(),
      source(
        "demo.NonRecordPathTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.Telescope;
        record Person(String name) {}
        @TelescopeTransformer
        interface NonRecordPathTransformer {
          default Telescope<Person, String> path() {
            return Telescope.of(Person.class).field(Person::name);
          }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("must extend TelescopeTransformation<S, A> with concrete types")).isTrue();
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
    assertThat(result.generated().get("demo.ExternalMapperImpl"))
      .contains("return UserBridge.forward(input);")
      .doesNotContain("transformers");
  }

  @Test
  void inheritedGenericMapperCompiles() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.InheritedMapper",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        interface Mapping<S, T> { T map(S source); }
        @TelescopeMapper(from = User.class, to = UserDto.class)
        interface InheritedMapper extends Mapping<User, UserDto> {}
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
  }

  @Test
  void inheritedExtraAbstractMethodIsRejectedOnTheBlueprint() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.ExtraMapper",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        interface Extra { String other(); }
        @TelescopeMapper(from = User.class, to = UserDto.class)
        interface ExtraMapper extends Extra { UserDto map(User source); }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("requires one method accepting")).isTrue();
    assertThat(result.generated()).doesNotContainKey("demo.ExtraMapperImpl");
  }

  @Test
  void unnamedPackageMapperCompiles() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor(), new BridgeProcessor()),
      List.of(),
      source(
        "PlainMapper",
        """
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(Target.class) record Source(String name) {}
        record Target(String name) {}
        @TelescopeMapper(from = Source.class, to = Target.class)
        interface PlainMapper { Target map(Source source); }
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
  }

  @Test
  void parameterizedPathAndInheritedDefaultCompile() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.NamesTransformer",
        """
        package demo;
        import java.util.List;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        record Person(List<String> names) {}
        interface NamedPath<A> extends TelescopeTransformation<Person, A> {}
        interface NamesBase extends NamedPath<List<String>> {
          default Transformation<List<String>> transform() { return new Transformation<>(List.of(), value -> value); }
          default Telescope<Person, List<String>> path() {
            return Telescope.of(Person.class).field(Person::names);
          }
        }
        interface Marker {}
        @TelescopeTransformer("names")
        interface NamesTransformer extends NamesBase, Marker {}
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
  }

  @Test
  void rawPathIsRejected() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.RawPathTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        @TelescopeTransformer interface RawPathTransformer extends TelescopeTransformation {
          default Telescope path() { return null; }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("must extend TelescopeTransformation<S, A> with concrete types")).isTrue();
  }

  @Test
  void inheritedExtraTransformMethodIsRejected() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.ExtraPathTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        interface Extra { String other(); }
        @TelescopeTransformer interface ExtraPathTransformer extends TelescopeTransformation<String, String>, Extra {
          default Transformation<String> transform() { return new Transformation<>(null, value -> value); }
          default Telescope<String, String> path() { return Telescope.of(String.class); }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("requires no other abstract methods")).isTrue();
  }

  @Test
  void unsupportedBlueprintShapesHaveActionableDiagnostics() {
    for (final var declaration : List.of(
      "class InvalidTransformer {}",
      "interface InvalidTransformer<T> {}",
      "sealed interface InvalidTransformer permits Child {} non-sealed interface Child extends InvalidTransformer {}"
    )) {
      final var result = ProcessorHarness.compileFully(
        List.of(new TelescopeMapperProcessor()),
        List.of(),
        source(
          "demo.InvalidTransformer",
          """
            package demo;
            import io.github.eschizoid.telescope.spring.TelescopeTransformer;
            @TelescopeTransformer
            """ +
            declaration
        )
      );
      assertThat(result.success()).isFalse();
      assertThat(result.hasError("requires a non-")).withFailMessage(result.errorMessages()).isTrue();
    }
  }

  @Test
  void missingTransformIsRejectedBeforeGeneration() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.MissingTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        @TelescopeTransformer interface MissingTransformer extends TelescopeTransformation<String, String> {
          default Telescope<String, String> path() { return Telescope.of(String.class); }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("requires a default transform() returning Transformation<A>")).isTrue();
    assertThat(result.generated()).doesNotContainKey("demo.MissingTransformerImpl");
  }

  @Test
  void rawTransformReturnIsRejectedWithDiagnostic() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.RawTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        @TelescopeTransformer interface RawTransformer extends TelescopeTransformation<String, String> {
          default Telescope<String, String> path() { return Telescope.of(String.class); }
          default Transformation transform() { return new Transformation<>(null, value -> value); }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(
      result.hasError("requires typed Telescope<S, A> path() and Transformation<A> transform() results")
    ).isTrue();
  }

  @Test
  void rawPathReturnIsRejectedWithDiagnostic() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.RawPathReturnTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        @TelescopeTransformer interface RawPathReturnTransformer extends TelescopeTransformation<String, String> {
          default Telescope path() { return Telescope.of(String.class); }
          default Transformation<String> transform() { return new Transformation<>(null, value -> value); }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(
      result.hasError("requires typed Telescope<S, A> path() and Transformation<A> transform() results")
    ).isTrue();
  }

  @Test
  void wrongTransformationFocusTypeFailsCompilation() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.WrongTransformTypeTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        @TelescopeTransformer interface WrongTransformTypeTransformer extends TelescopeTransformation<String, String> {
          default Telescope<String, String> path() { return Telescope.of(String.class); }
          default Transformation<Integer> transform() { return new Transformation<>(0, value -> value + 1); }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
  }

  @Test
  void inheritedGenericTransformAndPathCompile() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.InheritedTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        interface Base<S, A> extends TelescopeTransformation<S, A> {
          default Transformation<A> transform() { return new Transformation<>(null, value -> value); }
        }
        @TelescopeTransformer interface InheritedTransformer extends Base<String, String> {
          default Telescope<String, String> path() { return Telescope.of(String.class); }
        }
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
  }

  @Test
  void generatedTransformCachesBothFactoriesAndBeanName() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.NamedTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        @TelescopeTransformer("normalizer") interface NamedTransformer extends TelescopeTransformation<String, String> {
          default Telescope<String, String> path() { return Telescope.of(String.class); }
          default Transformation<String> transform() { return new Transformation<>("", String::toLowerCase); }
        }
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    final var generated = result.generated().get("demo.NamedTransformerImpl");
    assertThat(generated).contains(
      "@Component(\"normalizer\")",
      "private final Telescope<String, String> path",
      "private final Transformation<String> transformation"
    );
  }

  @Test
  void transformBlueprintNameMustEndWithTransformer() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.CityNormalizer",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.spring.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.spring.TelescopeTransformation;
        @TelescopeTransformer interface CityNormalizer extends TelescopeTransformation<String, String> {
          default Telescope<String, String> path() { return Telescope.of(String.class); }
          default Transformation<String> transform() { return new Transformation<>(null, String::toLowerCase); }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("interface name must end with Transformer")).isTrue();
    assertThat(result.generated()).doesNotContainKey("demo.CityNormalizerImpl");
  }

  @Test
  void mapperRejectsTransformerWithDifferentSourceType() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.WrongSourceProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.spring.blueprint.CounterValueTransformer;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        @TelescopeMapper(from = User.class, to = UserDto.class,
          transformers = { CounterValueTransformer.class })
        interface WrongSourceProjection { UserDto map(User source); }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("must focus on io.github.eschizoid.telescope.spring.blueprint.User")).isTrue();
    assertThat(result.generated()).doesNotContainKey("demo.WrongSourceProjectionImpl");
  }

  @Test
  void projectionTypeArgumentsMustMatchMapperPair() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.WrongPairProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.spring.TelescopeProjection;
        import io.github.eschizoid.telescope.spring.blueprint.Counter;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        @TelescopeMapper(from = User.class, to = UserDto.class)
        interface WrongPairProjection extends TelescopeProjection<Counter, UserDto> {}
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("from/to must match TelescopeProjection<S, T>")).isTrue();
  }

  @Test
  void mapperWithoutProjectionRequiresExplicitPair() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.MissingPairMapper",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        record Source(String name) {}
        record Target(String name) {}
        @TelescopeMapper interface MissingPairMapper { Target map(Source source); }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("requires from/to or TelescopeProjection<S, T>")).isTrue();
  }

  @Test
  void mapperPairAttributesMustBeProvidedTogether() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.PartialPairMapper",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        record Source(String name) {}
        record Target(String name) {}
        @TelescopeMapper(from = Source.class)
        interface PartialPairMapper { Target map(Source source); }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("from and to must be supplied together")).isTrue();
  }
}
