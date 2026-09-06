package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.compileFully;
import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  @DisplayName("an identical wildcard-bearing field pair is skipped, not mis-decided")
  void wildcardIdentitySkips() {
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
