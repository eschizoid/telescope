package io.github.eschizoid.telescope.codegen.lombok;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A navigator's bridge hop descends into the target's navigator only when that navigator is written
 * in time to be named from the source's. A target carrying a Lombok bean annotation gets its
 * navigator early from telescope-lombok's processor; without that processor it gets one in the
 * final round from the {@code @BeanFocus} processor, or none.
 *
 * <p>Each source ends in a class naming the hop by its type, so a hop of the other shape, or one
 * naming a navigator written too late or never, fails the compilation.
 */
class BridgeHopInTimeTest {

  private static final String TELESCOPE_FIRST =
    "io.github.eschizoid.telescope.codegen.FocusProcessor," +
    "io.github.eschizoid.telescope.codegen.BeanFocusProcessor," +
    "io.github.eschizoid.telescope.codegen.BridgeProcessor," +
    "io.github.eschizoid.telescope.codegen.lombok.LombokFocusProcessor," +
    "lombok.launch.AnnotationProcessorHider$AnnotationProcessor," +
    "lombok.launch.AnnotationProcessorHider$ClaimingProcessor";

  private static final String BEAN_SOURCE = """
    @BeanFocus
    @Bridge(Tgt.class)
    public class Src {
      private String name;
      public Src() {}
      public String getName() { return name; }
      public void setName(final String name) { this.name = name; }
    }
    """;

  private static final String RECORD_SOURCE = """
    @Focus
    @Bridge(Tgt.class)
    public record Src(String name) {}
    """;

  private static String source(final String src, final String targetAnnotations, final String hopType) {
    return """
    package demo;
    import io.github.eschizoid.telescope.Telescope;
    import io.github.eschizoid.telescope.annotations.BeanFocus;
    import io.github.eschizoid.telescope.annotations.Bridge;
    import io.github.eschizoid.telescope.annotations.Focus;
    %s
    %s
    class Tgt {
      private String name;
    }
    class Use {
      final %s hop = SrcTelescope.of().asTgt();
    }
    """.formatted(src, targetAnnotations, hopType);
  }

  @Test
  @DisplayName("with telescope-lombok, a bean's bridge hop to a @Data target descends, the telescope processors first")
  void beanHopDescendsIntoALombokTarget(@TempDir final Path dir) throws IOException {
    LombokLastCompiler.compile(
      dir,
      "Src",
      source(BEAN_SOURCE, "@lombok.Data", "TgtTelescope<Src>"),
      true,
      List.of("-processor", TELESCOPE_FIRST)
    );
  }

  @Test
  @DisplayName(
    "with telescope-lombok, a record's bridge hop to a @Data target descends, the telescope processors first"
  )
  void recordHopDescendsIntoALombokTarget(@TempDir final Path dir) throws IOException {
    LombokLastCompiler.compile(
      dir,
      "Src",
      source(RECORD_SOURCE, "@lombok.Data", "TgtTelescope<Src>"),
      true,
      List.of("-processor", TELESCOPE_FIRST)
    );
  }

  @Test
  @DisplayName("without telescope-lombok, a bridge hop to a target carrying only @Data ends at the target")
  void hopToADataOnlyTargetEnds(@TempDir final Path dir) throws IOException {
    LombokLastCompiler.compile(dir, "Src", source(BEAN_SOURCE, "@lombok.Data", "Telescope<Src, Tgt>"), false);
  }

  @Test
  @DisplayName("without telescope-lombok, a bridge hop to a @BeanFocus target carrying @Data ends at the target")
  void hopToABeanFocusDataTargetEnds(@TempDir final Path dir) throws IOException {
    LombokLastCompiler.compile(
      dir,
      "Src",
      source(BEAN_SOURCE, "@BeanFocus\n@lombok.Data", "Telescope<Src, Tgt>"),
      false
    );
  }
}
