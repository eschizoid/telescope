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
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
        import io.github.eschizoid.telescope.inject.Transformation;
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

  // The next two pin the refactor safety the annotations rely on: a path is method references, so a
  // renamed or retyped accessor fails the build in javac itself, with no processor involved.
  @Test
  void renamedAccessorFailsTheBuildWithoutTheProcessor() {
    final var result = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      source(
        "demo.BadNestedPathTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
        import io.github.eschizoid.telescope.inject.Transformation;
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
    assertThat(result.errorMessages()).contains("oldEmail");
  }

  @Test
  void retypedAccessorFailsTheBuildWithoutTheProcessor() {
    final var result = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      source(
        "demo.WrongTypePathTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
        import io.github.eschizoid.telescope.inject.Transformation;
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
    assertThat(result.errorMessages()).contains("incompatible types");
  }

  @Test
  void wrongPathInterfaceFailsAtCompileTime() {
    final var result = ProcessorHarness.compile(
      new TelescopeMapperProcessor(),
      source(
        "demo.NonRecordPathTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
        import io.github.eschizoid.telescope.inject.Transformation;
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
  void projectionOwnsStructuralMappingWithoutABridge() {
    final var result = ProcessorHarness.compile(
      new TelescopeMapperProcessor(),
      source(
        "demo.StructuralProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
        record Source(String name) {}
        record Target(String name) {}
        @TelescopeMapper
        interface StructuralProjection extends TelescopeProjection<Source, Target> {}
        """
      )
    );
    assertThat(result.success()).isTrue();
  }

  @Test
  void projectionCanDeclareTypedTranslationRows() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.RenamedProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
        import io.github.eschizoid.telescope.conversion.MapperBuilder;
        record Source(String displayName, String phoneNumber) {}
        record Target(String name, String phone) {}
        @TelescopeMapper
        interface RenamedProjection extends TelescopeProjection<Source, Target> {
          default void translate(MapperBuilder<Source, Target> mapping) {
            mapping.from(Source::displayName).to(Target::name)
              .from(Source::phoneNumber).to(Target::phone);
          }
        }
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.generated().get("demo.RenamedProjectionImpl")).contains(
      "RenamedProjection.super.translate(builder);"
    );
  }

  @Test
  void translateRowWithMismatchedSidesFailsTheBuild() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.SwappedProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
        import io.github.eschizoid.telescope.conversion.MapperBuilder;
        record Source(String displayName, int age) {}
        record Target(String name, int years) {}
        @TelescopeMapper
        interface SwappedProjection extends TelescopeProjection<Source, Target> {
          default void translate(MapperBuilder<Source, Target> mapping) {
            mapping.from(Source::displayName).to(Target::years);
          }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.errorMessages()).contains("incompatible types");
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
    assertThat(
      result.hasError(
        "@TelescopeMapper requires map to accept io.github.eschizoid.telescope.spring.blueprint.User and return io.github.eschizoid.telescope.spring.blueprint.UserDto"
      )
    )
      .withFailMessage(result.errorMessages())
      .isTrue();
  }

  @Test
  void projectionMayRedeclareItsOwnMethods() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.RedeclaringProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        @TelescopeMapper
        interface RedeclaringProjection extends TelescopeProjection<User, UserDto> {
          @Override User backward(UserDto target);
        }
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
  }

  @Test
  void mapperMethodWithAnotherNameIsToldToBeNamedMap() {
    final var result = ProcessorHarness.compile(
      new TelescopeMapperProcessor(),
      source(
        "demo.ToDtoMapper",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        @TelescopeMapper(from = User.class, to = UserDto.class)
        interface ToDtoMapper { UserDto toDto(User input); }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(
      result.hasError(
        "@TelescopeMapper requires a method named map accepting io.github.eschizoid.telescope.spring.blueprint.User and returning io.github.eschizoid.telescope.spring.blueprint.UserDto; found toDto"
      )
    )
      .withFailMessage(result.errorMessages())
      .isTrue();
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
  void mapperWithTransformersLoopsByIndexOverTheSnapshotWithNoIteratorAllocation() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.LoopShapeMapper",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        import io.github.eschizoid.telescope.spring.blueprint.UserCityTransformer;
        @TelescopeMapper(from = User.class, to = UserDto.class, transformers = { UserCityTransformer.class })
        interface LoopShapeMapper { UserDto map(User user); }
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.generated().get("demo.LoopShapeMapperImpl"))
      .contains("for (int i = 0, n = current.size(); i < n; i++) value = current.get(i).apply(value);")
      .doesNotContain("for (final var transformer : current)");
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
    assertThat(result.hasError("@TelescopeMapper cannot implement demo.Extra.other(); only map may be abstract"))
      .withFailMessage(result.errorMessages())
      .isTrue();
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
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
        import io.github.eschizoid.telescope.inject.Transformation;
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
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
        import io.github.eschizoid.telescope.inject.Transformation;
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
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
        import io.github.eschizoid.telescope.inject.Transformation;
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
    for (final var shape : List.of(
      List.of("class InvalidTransformer {}", "@TelescopeTransformer requires an interface"),
      List.of("interface InvalidTransformer<T> {}", "@TelescopeTransformer requires a non-generic interface"),
      List.of(
        "sealed interface InvalidTransformer permits Child {} non-sealed interface Child extends InvalidTransformer {}",
        "@TelescopeTransformer requires a non-sealed interface"
      )
    )) {
      final var declaration = shape.get(0);
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
      assertThat(result.errorMessages()).as(declaration).contains(shape.get(1));
      assertThat(result.errorMessages()).as(declaration).containsOnlyOnce("@TelescopeTransformer requires");
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
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
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
        import io.github.eschizoid.telescope.inject.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
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
        import io.github.eschizoid.telescope.inject.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
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
        import io.github.eschizoid.telescope.inject.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
        @TelescopeTransformer interface WrongTransformTypeTransformer extends TelescopeTransformation<String, String> {
          default Telescope<String, String> path() { return Telescope.of(String.class); }
          default Transformation<Integer> transform() { return new Transformation<>(0, value -> value + 1); }
        }
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(
      result.hasError(
        "@TelescopeTransformer requires typed Telescope<S, A> path() and Transformation<A> transform() results"
      )
    )
      .withFailMessage(result.errorMessages())
      .isTrue();
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
        import io.github.eschizoid.telescope.inject.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
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
        import io.github.eschizoid.telescope.inject.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
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
  void transformBlueprintNameNeedNotEndWithTransformer() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.CityNormalizer",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.inject.Transformation;
        import io.github.eschizoid.telescope.spring.TelescopeTransformer;
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
        record City(String name) {}
        @TelescopeTransformer interface CityNormalizer extends TelescopeTransformation<City, String> {
          default Telescope<City, String> path() { return Telescope.of(City.class).field(City::name); }
          default Transformation<String> transform() { return new Transformation<>(null, String::toLowerCase); }
        }
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.generated()).containsKey("demo.CityNormalizerImpl");
  }

  @Test
  void inheritedTranslateIsHonouredEvenWhenABridgeExists() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.InheritedTranslateProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.MapperBuilder;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        interface RenamingBase extends TelescopeProjection<User, UserDto> {
          @Override default void translate(MapperBuilder<User, UserDto> mapping) {
            mapping.from(User::name).to(UserDto::name);
          }
        }
        @TelescopeMapper
        interface InheritedTranslateProjection extends RenamingBase {}
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.generated().get("demo.InheritedTranslateProjectionImpl"))
      .contains("InheritedTranslateProjection.super.translate(builder);")
      .doesNotContain("UserBridge.forward");
  }

  @Test
  void unrelatedTranslateMethodDoesNotReplaceTheBridge() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.UnrelatedTranslate",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        interface HasTranslate { default String translate(final String text) { return text; } }
        @TelescopeMapper(from = User.class, to = UserDto.class)
        interface UnrelatedTranslateMapper extends HasTranslate { UserDto map(User source); }
        @TelescopeMapper
        interface UnrelatedTranslate extends TelescopeProjection<User, UserDto>, HasTranslate {}
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.generated().get("demo.UnrelatedTranslateMapperImpl")).contains("UserBridge.forward(input)");
    assertThat(result.generated().get("demo.UnrelatedTranslateImpl")).contains("UserBridge.forward(input)");
  }

  @Test
  void mapperFindsTheBridgeOfASourceWithSeveralTargets() {
    final var result = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor(), new TelescopeMapperProcessor()),
      List.of(),
      source("demo.TgtA", "package demo; public record TgtA(String name) {}"),
      source("demo.TgtB", "package demo; public record TgtB(String name) {}"),
      source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(TgtA.class)
        @Bridge(TgtB.class)
        public record Src(String name) {}
        """
      ),
      source(
        "demo.SrcMapper",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        @TelescopeMapper(from = Src.class, to = TgtB.class)
        public interface SrcMapper { TgtB map(Src source); }
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.generated().get("demo.SrcMapperImpl")).contains("SrcToTgtBBridge.forward(input)");
  }

  @Test
  void mapperAndProjectionFindACarrierBridge() {
    final var result = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor(), new TelescopeMapperProcessor()),
      List.of(),
      source("model.Car", "package model; public record Car(String plate) {}"),
      source("model.CarDto", "package model; public record CarDto(String plate) {}"),
      source(
        "carriers.CarCarrier",
        """
        package carriers;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import model.Car;
        import model.CarDto;
        @Bridge(source = Car.class, target = CarDto.class)
        public final class CarCarrier {}
        """
      ),
      source(
        "demo.CarMapper",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import model.Car;
        import model.CarDto;
        @TelescopeMapper(from = Car.class, to = CarDto.class)
        public interface CarMapper { CarDto map(Car source); }
        """
      ),
      source(
        "demo.CarProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
        import model.Car;
        import model.CarDto;
        @TelescopeMapper
        public interface CarProjection extends TelescopeProjection<Car, CarDto> {}
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.generated().get("demo.CarMapperImpl")).contains("CarCarrierBridge.forward(input)");
    assertThat(result.generated().get("demo.CarProjectionImpl"))
      .contains("CarCarrierBridge.forward(input)")
      .doesNotContain("mapperBuilder");
  }

  @Test
  void genericModelTypesAreRefusedOnTheBlueprint() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source("demo.Box", "package demo; public record Box<T>(T value, String label) {}"),
      source("demo.BoxDto", "package demo; public record BoxDto<T>(T value, String label) {}"),
      source(
        "demo.BoxProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
        @TelescopeMapper
        public interface BoxProjection extends TelescopeProjection<Box<String>, BoxDto<String>> {}
        """
      )
    );
    assertThat(result.success()).isFalse();
    assertThat(result.hasError("@TelescopeMapper does not support generic model types: demo.Box<java.lang.String>"))
      .withFailMessage(result.errorMessages())
      .isTrue();
    assertThat(result.generated()).doesNotContainKey("demo.BoxProjectionImpl");
  }

  @Test
  void projectionWithoutTranslateStillUsesTheBridge() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.PlainProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.spring.TelescopeMapper;
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
        import io.github.eschizoid.telescope.spring.blueprint.User;
        import io.github.eschizoid.telescope.spring.blueprint.UserDto;
        @TelescopeMapper
        interface PlainProjection extends TelescopeProjection<User, UserDto> {}
        """
      )
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.generated().get("demo.PlainProjectionImpl"))
      .contains("UserBridge.forward")
      .contains("ObjectProvider<TelescopeCustomizer<PlainProjection>> customizers")
      .contains("private final List<TelescopeTransformation<User, ?>> transformers;")
      .doesNotContain("volatile")
      .doesNotContain("synchronized");
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
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
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
