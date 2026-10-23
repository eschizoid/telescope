package io.github.eschizoid.telescope.codegen.spike;

import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;

/** Probe: what the tree API resolves for a field initializer during a processing round. */
class SpProbeTest {

  @SupportedAnnotationTypes("*")
  static final class Probe extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment env) {
      if (env.processingOver()) return false;
      final var trees = Trees.instance(processingEnv);
      for (final var root : env.getRootElements()) {
        final var path = trees.getPath(root);
        if (path == null) continue;
        new TreePathScanner<Void, Void>() {
          @Override
          public Void visitMemberReference(final MemberReferenceTree node, final Void v) {
            final var p = getCurrentPath();
            System.out.println(
              "PROBE ref " + node + " element=" + trees.getElement(p) + " type=" + trees.getTypeMirror(p)
            );
            return super.visitMemberReference(node, v);
          }

          @Override
          public Void visitMethodInvocation(final MethodInvocationTree node, final Void v) {
            final var p = new TreePath(getCurrentPath(), node.getMethodSelect());
            System.out.println("PROBE call " + node.getMethodSelect() + " element=" + trees.getElement(p));
            return super.visitMethodInvocation(node, v);
          }
        }.scan(path, null);
      }
      return false;
    }
  }

  @Test
  void inRoundResolution() {
    final var c = ProcessorHarness.compile(
      List.of(new Probe()),
      ProcessorHarness.source("demo.User", "package demo; public record User(long id, String email) {}"),
      ProcessorHarness.source("demo.UserDto", "package demo; public record UserDto(long userId, String contact) {}"),
      ProcessorHarness.source(
        "demo.Mappers",
        "package demo;\n" +
          "import static io.github.eschizoid.telescope.mapping.Mapping.*;\n" +
          "import io.github.eschizoid.telescope.Telescope;\n" +
          "import io.github.eschizoid.telescope.conversion.Mapper;\n" +
          "final class Mappers {\n" +
          "  static final Mapper<User, UserDto> USER = Telescope.mapper(User.class, UserDto.class,\n" +
          "    to(User::id, UserDto::userId),\n" +
          "    to(User::email, UserDto::contact, String::toLowerCase, String::toString));\n" +
          "}\n"
      )
    );
    System.out.println("SUCCESS=" + c.success() + "\n" + c.errorMessages());
  }
}
