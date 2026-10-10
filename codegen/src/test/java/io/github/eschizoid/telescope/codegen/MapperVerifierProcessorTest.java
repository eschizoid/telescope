package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.compileFully;
import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.List;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link MapperVerifierProcessor} through the in-memory harness and asserts the compile-time
 * diagnostics replay the construction-time pairing rejections — same message text, error severity
 * by default, silence wherever the site isn't statically analyzable.
 */
class MapperVerifierProcessorTest {

  @Test
  void containerSubclassArgumentsAreResolvedThroughTheGenericSupertype() {
    final var compilation = verify(
      """
      package demo;
      import java.util.*;
      import io.github.eschizoid.telescope.Telescope;
      class StringMap<V> extends HashMap<String,V> {}
      class Reordered<V,K> extends HashMap<K,V> {}
      class Tagged<Tag,E> extends ArrayList<E> {}
      class Nested<E> extends Tagged<String,List<E>> {}
      record Value(int n) {}
      record ValueDto(int n) {}
      record Src(StringMap<Value> fixed, Reordered<Value,String> reordered, Nested<Value> nested) {}
      record Tgt(HashMap<String,ValueDto> fixed, Map<String,ValueDto> reordered, List<List<ValueDto>> nested) {}
      class Holder { static final Object M = Telescope.mapper(Src.class,Tgt.class); }
      """
    );
    assertTrue(compilation.success(), compilation::errorMessages);
  }

  @Test
  void inheritedMapKeyMismatchHasAFieldDiagnostic() {
    final var compilation = verify(
      """
      package demo;
      import java.util.*;
      import io.github.eschizoid.telescope.Telescope;
      class StringMap<V> extends HashMap<String,V> {}
      record Src(StringMap<Integer> values) {}
      record Tgt(HashMap<Long,Integer> values) {}
      class Holder { static final Object M = Telescope.mapper(Src.class,Tgt.class); }
      """
    );
    assertFalse(compilation.success());
    assertTrue(compilation.hasError("values"), compilation::errorMessages);
    assertTrue(compilation.hasError("key types"), compilation::errorMessages);
  }

  @Nested
  @DisplayName("Rows through a getter inherited from an abstract base")
  class InheritedAccessorRows {

    // Sub inherits name and code from an abstract base; it declares nothing of its own.
    private static final String BEANS = """
      abstract class Base {
        private String name;
        private String code;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
      }
      class Sub extends Base { public Sub() {} }
      """;

    private ProcessorHarness.Compilation verifyWithBeans(final String rest) {
      return verify(
        """
          package demo;
          import static io.github.eschizoid.telescope.mapping.Mapping.*;
          import io.github.eschizoid.telescope.Telescope;
          import io.github.eschizoid.telescope.mapping.Mapping;
          """ +
          BEANS +
          rest
      );
    }

    @Test
    @DisplayName("a rename row onto an inherited target getter claims the subclass's property")
    void renameOntoAnInheritedTargetGetter() {
      final var compilation = verifyWithBeans(
        """
        record Renamed(String label, String code) {}
        class Holder { static final Object M = Telescope.mapper(Renamed.class, Sub.class, to(Renamed::label, Sub::getName)); }
        """
      );
      assertTrue(compilation.success(), compilation::errorMessages);
    }

    @Test
    @DisplayName("a rename row from an inherited source getter claims the subclass's property")
    void renameFromAnInheritedSourceGetter() {
      final var compilation = verifyWithBeans(
        """
        record Renamed(String label, String code) {}
        class Holder { static final Object M = Telescope.mapper(Sub.class, Renamed.class, to(Sub::getName, Renamed::label)); }
        """
      );
      assertTrue(compilation.success(), compilation::errorMessages);
    }

    @Test
    @DisplayName("a drop through an inherited source getter leaves the subclass's property out")
    void dropThroughAnInheritedSourceGetter() {
      final var compilation = verifyWithBeans(
        """
        record Uncoded(String name) {}
        class Holder { static final Object M = Telescope.mapper(Sub.class, Uncoded.class, drop(Sub::getCode)); }
        """
      );
      assertTrue(compilation.success(), compilation::errorMessages);
    }

    @Test
    @DisplayName("a row typed by a variable whose bound is another variable claims the innermost bound's property")
    void rowTypedByAChainedTypeVariableBound() {
      final var compilation = verifyWithBeans(
        """
        record Renamed(String label, String code) {}
        class Holder {
          static <U extends Sub, T extends U> Object m() {
            return Telescope.mapper(
              Renamed.class, Sub.class, Mapping.<Renamed, T, String>to(Renamed::label, Base::getName));
          }
        }
        """
      );
      assertTrue(compilation.success(), compilation::errorMessages);
    }

    @Test
    @DisplayName("a second row onto the same inherited target property is still a duplicate")
    void duplicateRowsOntoAnInheritedTargetPropertyStillError() {
      final var compilation = verifyWithBeans(
        """
        record Renamed(String label, String alias, String code) {}
        class Holder {
          static final Object M = Telescope.mapper(
            Renamed.class, Sub.class, to(Renamed::label, Sub::getName), to(Renamed::alias, Sub::getName));
        }
        """
      );
      assertFalse(compilation.success());
      assertTrue(compilation.hasError("duplicate override row for target field 'name'"), compilation::errorMessages);
    }

    @Test
    @DisplayName("a constant row through the abstract base's getter writes a property the target has")
    void constantThroughTheBaseGetterIsAccepted() {
      final var compilation = verifyWithBeans(
        """
        record Coded(String code) {}
        class Holder { static final Object M = Telescope.mapper(Coded.class, Sub.class, constant(Base::getName, "fixed")); }
        """
      );
      assertTrue(compilation.success(), compilation::errorMessages);
    }

    @Test
    @DisplayName("a compute row through a generic base's getter writes a property the target has")
    void computeThroughAGenericBaseGetterIsAccepted() {
      final var compilation = verifyWithBeans(
        """
        abstract class Named<T> {
          private T label;
          public T getLabel() { return label; }
          public void setLabel(T label) { this.label = label; }
        }
        class Labelled extends Named<String> { public Labelled() {} }
        record Plain(int n) {}
        class Holder {
          static final Object M = Telescope.mapper(Plain.class, Labelled.class, compute(Named<String>::getLabel, () -> "made"));
        }
        """
      );
      assertTrue(compilation.success(), compilation::errorMessages);
    }

    @Test
    @DisplayName("a constant row through a class the target does not extend is refused as construction refuses it")
    void constantThroughAnUnrelatedClassIsRefused() {
      final var compilation = verifyWithBeans(
        """
        class Other { private String name; public String getName() { return name; } public void setName(String n) { name = n; } }
        record Coded(String code) {}
        class Holder { static final Object M = Telescope.mapper(Coded.class, Sub.class, constant(Other::getName, "fixed")); }
        """
      );
      assertFalse(compilation.success());
      assertTrue(
        compilation.hasError(PairingMessages.targetRowOffTarget("Coded", "Sub", "constant", "Other", "name")),
        compilation::errorMessages
      );
    }
  }

  private static ProcessorHarness.Compilation verify(final String code) {
    return verify(List.of(), code);
  }

  private static ProcessorHarness.Compilation verify(final List<String> options, final String code) {
    // Full pipeline (not -proc:only): the verifier scans from a post-ANALYZE task listener, which
    // never fires when compilation stops after the processing rounds.
    return compileFully(List.of(new MapperVerifierProcessor()), options, source("demo.Holder", code));
  }

  @Nested
  @DisplayName("Strict factories — completeness")
  class Completeness {

    @Test
    @DisplayName("an unmatched target field is a compile error with the construction-time message")
    void unmatchedTargetErrors() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        record Src(String a) {}
        record Tgt(String a, String b) {}
        class Holder {
          static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
        }
        """
      );
      assertFalse(compilation.success(), () -> compilation.errorMessages());
      assertTrue(compilation.hasError("target field 'b' has no same-name source"), () -> compilation.errorMessages());
    }

    @Test
    @DisplayName("an unmatched source field is a compile error with the construction-time message")
    void unmatchedSourceErrors() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        record Src(String a, String extra) {}
        record Tgt(String a) {}
        class Holder {
          static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
        }
        """
      );
      assertFalse(compilation.success(), () -> compilation.errorMessages());
      assertTrue(compilation.hasError("source field 'extra' has no same-name target"), () ->
        compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("a complete same-name mapper compiles clean")
    void cleanMapperPasses() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        record Src(String a, Integer n) {}
        record Tgt(String a, Integer n) {}
        class Holder {
          static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
        }
        """
      );
      assertTrue(compilation.success(), () -> compilation.errorMessages());
    }

    @Test
    @DisplayName("a rename row claims both sides — the renamed pair is not reported unmatched")
    void renameRowClaims() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        import io.github.eschizoid.telescope.mapping.Mapping;
        record Src(String email) {}
        record Tgt(String contactEmail) {}
        class Holder {
          static final Mapper<Src, Tgt> M = Telescope.mapper(
            Src.class, Tgt.class, Mapping.to(Src::email, Tgt::contactEmail));
        }
        """
      );
      assertTrue(compilation.success(), () -> compilation.errorMessages());
    }

    @Test
    @DisplayName("mapperForward is lenient by contract — unmatched fields are not reported")
    void mapperForwardLenient() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.ForwardMapper;
        record Src(String a) {}
        record Tgt(String a, String b) {}
        class Holder {
          static final ForwardMapper<Src, Tgt> M = Telescope.mapperForward(Src.class, Tgt.class);
        }
        """
      );
      assertTrue(compilation.success(), () -> compilation.errorMessages());
    }
  }

  @Nested
  @DisplayName("Enum pairs — constant names checked in each direction the mapper converts")
  class EnumPairs {

    @Test
    @DisplayName("two enums with the same constants compile clean under a strict mapper")
    void sameConstantsPass() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        enum Ea { A, B }
        enum Eb { A, B }
        record Src(Ea status, java.util.List<Ea> history) {}
        record Tgt(Eb status, java.util.List<Eb> history) {}
        class Holder { static final Object M = Telescope.mapper(Src.class, Tgt.class); }
        """
      );
      assertTrue(compilation.success(), compilation::errorMessages);
    }

    @Test
    @DisplayName("a target constant the source lacks is an error under a strict mapper, naming the constant")
    void supersetTargetErrorsWhenStrict() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        enum Ea { A, B }
        enum Eb { A, B, C }
        record Src(Ea status) {}
        record Tgt(Eb status) {}
        class Holder { static final Object M = Telescope.mapper(Src.class, Tgt.class); }
        """
      );
      assertFalse(compilation.success(), compilation::errorMessages);
      assertTrue(
        compilation.hasError("demo.Ea has no constant named C, which the backward direction needs"),
        compilation::errorMessages
      );
    }

    @Test
    @DisplayName("enums in an EnumSet are an error, since no rebuild can build an EnumSet")
    void enumSetIsRefused() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        enum Ea { A, B }
        enum Eb { A, B }
        record Src(java.util.EnumSet<Ea> items) {}
        record Tgt(java.util.EnumSet<Eb> items) {}
        class Holder { static final Object M = Telescope.mapper(Src.class, Tgt.class); }
        """
      );
      assertFalse(compilation.success(), compilation::errorMessages);
      assertTrue(compilation.hasError("java.util.EnumSet has no instance of its own"), compilation::errorMessages);
    }

    @Test
    @DisplayName(
      "a container with no reachable constructor is an error unless it offers the builder construction falls back to"
    )
    void anUnbuildableContainerIsAnErrorWithoutAPublicNoArgBuilder() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        enum Ea { A, B }
        enum Eb { A, B }
        class Bag<E> extends java.util.ArrayList<E> {
          private Bag() {}
          static String builder(final int n) { return ""; }
        }
        record Src(Bag<Ea> items) {}
        record Tgt(Bag<Eb> items) {}
        class Holder { static final Object M = Telescope.mapper(Src.class, Tgt.class); }
        """
      );
      assertFalse(compilation.success(), compilation::errorMessages);
      assertTrue(
        compilation.hasError("demo.Bag has no no-argument constructor a rebuild can call"),
        compilation::errorMessages
      );
      final var withBuilder = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        enum Ea { A, B }
        enum Eb { A, B }
        class Bag<E> extends java.util.ArrayList<E> {
          private Bag() {}
          public static Builder builder() { return new Builder(); }
          public static final class Builder {
            public Bag<Object> build() { return new Bag<>(); }
          }
        }
        record Src(Bag<Ea> items) {}
        record Tgt(Bag<Eb> items) {}
        class Holder { static final Object M = Telescope.mapper(Src.class, Tgt.class); }
        """
      );
      assertTrue(withBuilder.success(), withBuilder::errorMessages);
    }

    @Test
    @DisplayName("a target constant the source lacks compiles clean on a mapperForward row")
    void supersetTargetPassesForwardOnly() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.mapping.Mapping;
        enum Ea { A, B }
        enum Eb { A, B, C }
        record Src(Ea status) {}
        record Tgt(Eb status) {}
        class Holder {
          static final Object M = Telescope.mapperForward(Src.class, Tgt.class, Mapping.to(Src::status, Tgt::status));
        }
        """
      );
      assertTrue(compilation.success(), compilation::errorMessages);
    }

    @Test
    @DisplayName("a source constant the target lacks is an error on a mapperForward row, naming the constant")
    void missingTargetConstantErrorsForwardOnly() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.mapping.Mapping;
        enum Ea { A, B, C }
        enum Eb { A }
        record Src(Ea status) {}
        record Tgt(Eb status) {}
        class Holder {
          static final Object M = Telescope.mapperForward(Src.class, Tgt.class, Mapping.to(Src::status, Tgt::status));
        }
        """
      );
      assertFalse(compilation.success(), compilation::errorMessages);
      assertTrue(compilation.hasError("demo.Eb has no constant named B, C."), compilation::errorMessages);
    }
  }

  @Nested
  @DisplayName("Shape checks — the shared pairing lattice at compile time")
  class ShapeChecks {

    @Test
    @DisplayName("a same-typed to(...) rename over incompatible scalars errors with the 4-arg pointer")
    void incompatibleRenameErrors() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        import io.github.eschizoid.telescope.mapping.Mapping;
        record Src(Integer count) {}
        record Tgt(String label) {}
        class Holder {
          static final Mapper<Src, Tgt> M = Telescope.mapper(
            Src.class, Tgt.class, Mapping.to(Src::count, Tgt::label));
        }
        """
      );
      assertFalse(compilation.success(), () -> compilation.errorMessages());
      assertTrue(compilation.hasError("incompatible source/target shapes"), () -> compilation.errorMessages());
      assertTrue(compilation.hasError("to(src, tgt, forward, backward)"), () -> compilation.errorMessages());
    }

    @Test
    @DisplayName("an incompatible same-name field inside a NESTED auto-recursed pair errors")
    void nestedIncompatibleErrors() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        record SrcIn(Integer v) {}
        record TgtIn(String v) {}
        record Src(SrcIn in) {}
        record Tgt(TgtIn in) {}
        class Holder {
          static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
        }
        """
      );
      assertFalse(compilation.success(), () -> compilation.errorMessages());
      assertTrue(compilation.hasError("incompatible source/target shapes"), () -> compilation.errorMessages());
    }

    @Test
    @DisplayName("container elements are shape-checked through the lift — List<Integer> vs List<String>")
    void liftedElementIncompatibleErrors() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        import java.util.List;
        record Src(List<Integer> xs) {}
        record Tgt(List<String> xs) {}
        class Holder {
          static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
        }
        """
      );
      assertFalse(compilation.success(), () -> compilation.errorMessages());
      assertTrue(compilation.hasError("incompatible source/target shapes"), () -> compilation.errorMessages());
    }

    @Test
    @DisplayName("a 4-arg to(...) carries the user's conversion — no shape error")
    void fourArgTransformPasses() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        import io.github.eschizoid.telescope.mapping.Mapping;
        record Src(Integer count) {}
        record Tgt(String label) {}
        class Holder {
          static final Mapper<Src, Tgt> M = Telescope.mapper(
            Src.class, Tgt.class,
            Mapping.to(Src::count, Tgt::label, i -> i == null ? null : String.valueOf(i), s -> s == null ? null : Integer.parseInt(s)));
        }
        """
      );
      assertTrue(compilation.success(), () -> compilation.errorMessages());
    }
  }

  @Nested
  @DisplayName("Degradation — never guess, never false-positive")
  class Degradation {

    @Test
    @DisplayName("a dynamic row disables completeness but visible rows are still shape-checked")
    void dynamicRowKeepsVisibleChecks() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        import io.github.eschizoid.telescope.mapping.Mapping;
        record Src(Integer count, String orphan) {}
        record Tgt(String label) {}
        class Holder {
          static Mapping<Src, Tgt> helperRow() { return null; }
          static final Mapper<Src, Tgt> M = Telescope.mapper(
            Src.class, Tgt.class, Mapping.to(Src::count, Tgt::label), helperRow());
        }
        """
      );
      assertFalse(compilation.success(), () -> compilation.errorMessages());
      assertTrue(compilation.hasError("incompatible source/target shapes"), () -> compilation.errorMessages());
      assertFalse(compilation.hasError("has no same-name"), () -> compilation.errorMessages());
    }

    @Test
    @DisplayName("a non-literal class argument skips the site entirely")
    void nonLiteralClassSkips() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        record Src(String a) {}
        record Tgt(String a, String b) {}
        class Holder {
          static final Class<Src> SRC = Src.class;
          static final Mapper<Src, Tgt> M = Telescope.mapper(SRC, Tgt.class);
        }
        """
      );
      assertTrue(compilation.success(), () -> compilation.errorMessages());
    }

    @Test
    @DisplayName("@UncheckedMapping on the enclosing field exempts the site")
    void uncheckedMappingSuppresses() {
      final var compilation = verify(
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.annotations.UncheckedMapping;
        import io.github.eschizoid.telescope.conversion.Mapper;
        record Src(String a) {}
        record Tgt(String a, String b) {}
        class Holder {
          @UncheckedMapping("intentionally partial in this test")
          static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
        }
        """
      );
      assertTrue(compilation.success(), () -> compilation.errorMessages());
    }

    @Test
    @DisplayName("-Atelescope.verify=off disables the verifier")
    void offFlagDisables() {
      final var compilation = verify(
        List.of("-Atelescope.verify=off"),
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        record Src(String a) {}
        record Tgt(String a, String b) {}
        class Holder {
          static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
        }
        """
      );
      assertTrue(compilation.success(), () -> compilation.errorMessages());
    }

    @Test
    @DisplayName("-Atelescope.verify=warn reports a WARNING and the build stays green")
    void warnModeWarns() {
      final var compilation = verify(
        List.of("-Atelescope.verify=warn"),
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        record Src(String a) {}
        record Tgt(String a, String b) {}
        class Holder {
          static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
        }
        """
      );
      assertTrue(compilation.success(), () -> compilation.errorMessages());
      final var warned = compilation
        .diagnostics()
        .stream()
        .anyMatch(
          d ->
            d.getKind() == Diagnostic.Kind.WARNING &&
            d.getMessage(null) != null &&
            d.getMessage(null).contains("has no same-name source")
        );
      assertTrue(warned, "expected the completeness diagnostic at WARNING severity");
    }
  }

  @Nested
  @DisplayName("WriteHint validation")
  class WriteHints {

    @Test
    @DisplayName("writeBean targeting a record errors with the construction-time message")
    void writeBeanOnRecordErrors() {
      final var compilation = verify(
        """
        package demo;
        import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
        import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.conversion.Mapper;
        record Src(String a) {}
        record Tgt(String a) {}
        class Holder {
          static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class, writeBean(Tgt.class, SETTERS));
        }
        """
      );
      assertFalse(compilation.success(), () -> compilation.errorMessages());
      assertTrue(compilation.hasError("writeBean hint targets a record class"), () -> compilation.errorMessages());
    }
  }

  @Test
  @DisplayName("a when-wrapped constant is permissive exactly like a bare one — no completeness error")
  void whenWrappedConstantIsPermissive() {
    final var compilation = verify(
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.conversion.Mapper;
      import io.github.eschizoid.telescope.mapping.Mapping;
      record Src(String a) {}
      record Tgt(String a, String extra) {}
      class Holder {
        static final Mapper<Src, Tgt> M = Telescope.mapper(
          Src.class, Tgt.class, Mapping.when(s -> true, Mapping.constant(Tgt::extra, "x")));
      }
      """
    );
    assertTrue(compilation.success(), () -> compilation.errorMessages());
  }

  @Test
  @DisplayName("a toOneWay row claims its fields — the renamed pair is not reported unmatched")
  void toOneWayRowClaims() {
    final var compilation = verify(
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.conversion.ForwardMapper;
      import io.github.eschizoid.telescope.mapping.Mapping;
      record Src(Integer count) {}
      record Tgt(String label) {}
      class Holder {
        static final ForwardMapper<Src, Tgt> M = Telescope.mapperForward(
          Src.class, Tgt.class, Mapping.toOneWay(Src::count, Tgt::label, i -> String.valueOf(i)));
      }
      """
    );
    assertTrue(compilation.success(), () -> compilation.errorMessages());
  }

  @Test
  @DisplayName("an identical wildcard-bearing field pair is accepted, as the runtime accepts it")
  void wildcardIdentityAccepted() {
    final var compilation = verify(
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.conversion.Mapper;
      import java.util.List;
      record Src(List<? extends CharSequence> xs) {}
      record Tgt(List<? extends CharSequence> xs) {}
      class Holder {
        static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
      }
      """
    );
    assertTrue(compilation.success(), () -> compilation.errorMessages());
  }

  @Test
  @DisplayName("a sorted set of a converted element that is not Comparable is reported at compile time")
  void unorderableSortedElementReported() {
    final var compilation = verify(
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.conversion.Mapper;
      import java.util.SortedSet;
      record Leaf(String v) implements Comparable<Leaf> { public int compareTo(Leaf o) { return v.compareTo(o.v()); } }
      record LeafDto(String v) {}
      record Src(SortedSet<Leaf> xs) {}
      record Tgt(SortedSet<LeafDto> xs) {}
      class Holder {
        static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
      }
      """
    );
    assertFalse(compilation.success(), "the verifier should report the pair");
    assertTrue(compilation.errorMessages().contains("does not implement Comparable"), () ->
      compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("wildcards with different bounds are reported at compile time, as the runtime refuses them")
  void wildcardBoundsMismatchReported() {
    final var compilation = verify(
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.conversion.Mapper;
      import java.util.List;
      record Src(List<? super String> xs) {}
      record Tgt(List<? super Integer> xs) {}
      class Holder {
        static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
      }
      """
    );
    assertFalse(compilation.success(), "the verifier should report the pair");
    assertTrue(compilation.errorMessages().contains("xs"), () -> compilation.errorMessages());
  }

  @Test
  @DisplayName("rows on a generic source class still claim their fields (erasure-matched owners)")
  void genericOwnerRowsClaim() {
    final var compilation = verify(
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.conversion.Mapper;
      import io.github.eschizoid.telescope.mapping.Mapping;
      record Box<T>(String payload) {}
      record BoxDto(String data) {}
      class Holder {
        @SuppressWarnings("rawtypes")
        static final Mapper<Box, BoxDto> M = Telescope.mapper(
          Box.class, BoxDto.class, Mapping.<Box, BoxDto, String>to(Box::payload, BoxDto::data));
      }
      """
    );
    assertTrue(compilation.success(), () -> compilation.errorMessages());
  }

  @Test
  @DisplayName("duplicate rows for the same target field error with the construction-time message")
  void duplicateTargetRowErrors() {
    final var compilation = verify(
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.conversion.Mapper;
      import io.github.eschizoid.telescope.mapping.Mapping;
      record Src(String a, String b) {}
      record Tgt(String x) {}
      class Holder {
        static final Mapper<Src, Tgt> M = Telescope.mapper(
          Src.class, Tgt.class, Mapping.to(Src::a, Tgt::x), Mapping.to(Src::b, Tgt::x));
      }
      """
    );
    assertFalse(compilation.success(), () -> compilation.errorMessages());
    assertTrue(compilation.hasError("duplicate override row for target field 'x'"), () -> compilation.errorMessages());
    // The duplicate-target error is the only diagnostic — 'b' must not cascade as unmatched-source
    // because the duplicate row still consumes 'b' from the source side.
    final var errors = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
      .count();
    assertEquals(
      1,
      errors,
      () -> "the duplicate must not cascade into unmatched-field errors:\n" + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a call site inside a NESTED class is diagnosed exactly once, not once per enclosing type")
  void nestedClassCallSiteDiagnosedOnce() {
    final var compilation = verify(
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.conversion.Mapper;
      record Src(String a) {}
      record Tgt(String a, String b) {}
      class Holder {
        static class Inner {
          static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
        }
      }
      """
    );
    assertFalse(compilation.success(), () -> compilation.errorMessages());
    final var errors = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
      .count();
    assertEquals(
      1,
      errors,
      () ->
        "ANALYZE fires per type declaration; the nested subtree must be scanned once:\n" + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("exactly one diagnostic per problem — no cascade from a single unmatched field")
  void singleDiagnosticPerProblem() {
    final var compilation = verify(
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.conversion.Mapper;
      record Src(String a) {}
      record Tgt(String a, String b) {}
      class Holder {
        static final Mapper<Src, Tgt> M = Telescope.mapper(Src.class, Tgt.class);
      }
      """
    );
    final var errors = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
      .count();
    assertEquals(1, errors, () -> compilation.errorMessages());
  }
}
