package io.github.eschizoid.telescope.codegen;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * SPIKE: reads the rows of a {@code @Bridge} field's {@code Telescope.mapper(...)} or {@code
 * Telescope.mapperForward(...)} initializer into the configuration the bridge emitter already
 * takes. A row the generated bridge cannot express is refused by name, anchored on the row.
 *
 * <p>The rows are read in a processing round. The tree API resolves a method reference's target
 * there by attributing the expression on demand, which is what lets the reader name the accessor a
 * row passes rather than guess it from text.
 */
final class FieldRows {

  private static final String TELESCOPE_FQN = "io.github.eschizoid.telescope.Telescope";
  private static final String MAPPING_FQN = "io.github.eschizoid.telescope.mapping.Mapping";

  /** What the reader found: the parsed configuration, or the refusal and where to anchor it. */
  sealed interface Result permits Parsed, Refused {}

  record Parsed(
    TypeElement source,
    TypeElement target,
    boolean strict,
    Set<String> drops,
    Map<String, String> renames,
    Map<String, String> transformClasses,
    Map<String, String> transformMethods,
    Map<String, String> viaMappers,
    Map<String, String> constants
  ) implements Result {}

  record Refused(Tree at, String message) implements Result {}

  private final ProcessingEnvironment env;
  private final Trees trees;

  FieldRows(final ProcessingEnvironment env) {
    this.env = env;
    this.trees = Trees.instance(env);
  }

  /** The name of the bridge generated for {@code field}: its class's name, the field's, Bridge. */
  static String bridgeSimpleName(final VariableElement field) {
    final var owner = (TypeElement) field.getEnclosingElement();
    return owner.getSimpleName() + camel(field.getSimpleName().toString()) + "Bridge";
  }

  static String camel(final String constant) {
    final var out = new StringBuilder();
    for (final var part : constant.toLowerCase().split("_")) {
      if (part.isEmpty()) continue;
      out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return out.toString();
  }

  Result read(final VariableElement field) {
    final var fieldTree = trees.getTree(field);
    if (!(fieldTree instanceof VariableTree variable)) {
      return new Refused(null, "@Bridge field " + field.getSimpleName() + ": its source is not in this compilation");
    }
    if (!field.getModifiers().contains(Modifier.STATIC) || !field.getModifiers().contains(Modifier.FINAL)) {
      return new Refused(variable, "a @Bridge field must be static final, so one mapper stands for the declaration");
    }
    final var unit = trees.getPath(field).getCompilationUnit();
    if (!(variable.getInitializer() instanceof MethodInvocationTree call)) {
      return new Refused(variable, "a @Bridge field must be initialised by a Telescope.mapper(...) call written in place");
    }
    final var factory = elementAt(unit, call.getMethodSelect());
    if (
      !(factory instanceof ExecutableElement method) ||
      !((TypeElement) method.getEnclosingElement()).getQualifiedName().contentEquals(TELESCOPE_FQN) ||
      !(method.getSimpleName().contentEquals("mapper") || method.getSimpleName().contentEquals("mapperForward"))
    ) {
      return new Refused(call, "a @Bridge field must be initialised by Telescope.mapper(...) or Telescope.mapperForward(...)");
    }
    final var strict = method.getSimpleName().contentEquals("mapper");
    final var args = call.getArguments();
    if (args.size() < 2) return new Refused(call, "a @Bridge mapper names its source and target classes");
    final var source = classLiteral(unit, args.get(0));
    final var target = classLiteral(unit, args.get(1));
    if (source == null || target == null) {
      return new Refused(call, "a @Bridge mapper names its source and target as class literals");
    }
    final var drops = new LinkedHashSet<String>();
    final var renames = new LinkedHashMap<String, String>();
    final var transformClasses = new LinkedHashMap<String, String>();
    final var transformMethods = new LinkedHashMap<String, String>();
    final var viaMappers = new LinkedHashMap<String, String>();
    final var constants = new LinkedHashMap<String, String>();
    for (var i = 2; i < args.size(); i++) {
      final var arg = args.get(i);
      if (!(arg instanceof MethodInvocationTree row)) {
        return new Refused(arg, "row " + (i - 1) + " is not a row factory call written in place; a @Bridge mapper " +
          "compiles only rows written inline, not rows built by a helper, a loop or a variable");
      }
      final var rowElement = elementAt(unit, row.getMethodSelect());
      if (
        !(rowElement instanceof ExecutableElement rowExec) ||
        !((TypeElement) rowExec.getEnclosingElement()).getQualifiedName().contentEquals(MAPPING_FQN)
      ) {
        return new Refused(row, "row " + row.getMethodSelect() + "(...) is not a Mapping row factory");
      }
      final var rowArgs = row.getArguments();
      for (final var rowArg : rowArgs) {
        if (rowArg instanceof LambdaExpressionTree) {
          return new Refused(rowArg, "a @Bridge mapper cannot compile an inline lambda; name a method instead " +
            "(Conversions::lower), so the generated bridge calls the same code");
        }
      }
      final var kind = rowExec.getSimpleName().toString();
      switch (kind) {
        case "to" -> {
          if (rowArgs.size() != 2) {
            return new Refused(row, "to(...) with conversion functions needs a target attribute on the emitter's " +
              "transform; the spike compiles toOneWay(src, tgt, Owner::method) only");
          }
          final var src = property(unit, rowArgs.get(0), source);
          final var tgt = property(unit, rowArgs.get(1), target);
          if (src == null || tgt == null) return new Refused(row, "to(...) must name an accessor of each side");
          if (!src.equals(tgt)) renames.put(src, tgt);
        }
        case "toOneWay" -> {
          final var src = property(unit, rowArgs.get(0), source);
          final var tgt = property(unit, rowArgs.get(1), target);
          if (src == null || tgt == null) return new Refused(row, "toOneWay(...) must name an accessor of each side");
          if (!src.equals(tgt)) {
            return new Refused(row, "a renamed and converted row needs the emitter's rename-plus-transform support");
          }
          final var fn = elementAt(unit, rowArgs.get(2));
          if (
            !(rowArgs.get(2) instanceof MemberReferenceTree) ||
            !(fn instanceof ExecutableElement fnExec) ||
            !fnExec.getModifiers().contains(Modifier.STATIC) ||
            fnExec.getParameters().size() != 1
          ) {
            return new Refused(rowArgs.get(2), "the spike compiles a converter written as a reference to a static " +
              "one-argument method; an unbound instance method (String::toLowerCase) or a constructor " +
              "reference needs its own plan kind");
          }
          final var owner = (TypeElement) fnExec.getEnclosingElement();
          transformClasses.put(src, owner.getQualifiedName().toString());
          transformMethods.put(src, fnExec.getSimpleName().toString());
        }
        case "via" -> {
          final var src = property(unit, rowArgs.get(0), source);
          final var tgt = property(unit, rowArgs.get(1), target);
          if (src == null || tgt == null) return new Refused(row, "via(...) must name an accessor of each side");
          if (!src.equals(tgt)) return new Refused(row, "a renamed via row needs the emitter's rename-plus-via support");
          final var mapperRef = elementAt(unit, rowArgs.get(2));
          if (
            !(mapperRef instanceof VariableElement mapperField) ||
            mapperField.getKind() != ElementKind.FIELD ||
            mapperField.getAnnotationMirrors().stream()
              .noneMatch(a -> a.getAnnotationType().toString().equals("io.github.eschizoid.telescope.annotations.Bridge"))
          ) {
            return new Refused(rowArgs.get(2), "via(...) in a @Bridge mapper names another @Bridge mapper field, so " +
              "the generated bridge can call that field's generated bridge");
          }
          final var owner = (TypeElement) mapperField.getEnclosingElement();
          final var pkg = env.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
          viaMappers.put(src, (pkg.isEmpty() ? "" : pkg + ".") + bridgeSimpleName(mapperField));
        }
        case "drop" -> {
          if (rowArgs.size() != 1) return new Refused(row, "drop(src, Target.class) is not compiled by the spike");
          final var src = property(unit, rowArgs.get(0), source);
          if (src == null) return new Refused(row, "drop(...) must name an accessor of the source");
          drops.add(src);
        }
        case "constant" -> {
          final var tgt = property(unit, rowArgs.get(0), target);
          if (tgt == null) return new Refused(row, "constant(...) must name an accessor of the target");
          if (!(rowArgs.get(1) instanceof LiteralTree literal)) {
            return new Refused(rowArgs.get(1), "a constant in a @Bridge mapper must be a literal, which the " +
              "generated bridge writes in place");
          }
          constants.put(tgt, String.valueOf(literal.getValue()));
        }
        default -> {
          return new Refused(row, kind + "(...) cannot be compiled into a generated bridge yet");
        }
      }
    }
    return new Parsed(source, target, strict, drops, renames, transformClasses, transformMethods, viaMappers, constants);
  }

  private Element elementAt(final com.sun.source.tree.CompilationUnitTree unit, final Tree tree) {
    final var path = TreePath.getPath(unit, tree);
    return path == null ? null : trees.getElement(path);
  }

  private TypeElement classLiteral(final com.sun.source.tree.CompilationUnitTree unit, final ExpressionTree arg) {
    if (!(arg instanceof MemberSelectTree select) || !select.getIdentifier().contentEquals("class")) return null;
    final var expr = select.getExpression();
    if (!(expr instanceof IdentifierTree) && !(expr instanceof MemberSelectTree)) return null;
    return elementAt(unit, expr) instanceof TypeElement type ? type : null;
  }

  /** The property an accessor reference reads, when it is declared on {@code side} or above it. */
  private String property(final com.sun.source.tree.CompilationUnitTree unit, final ExpressionTree arg, final TypeElement side) {
    if (!(arg instanceof MemberReferenceTree)) return null;
    if (!(elementAt(unit, arg) instanceof ExecutableElement accessor)) return null;
    if (!accessor.getParameters().isEmpty() || accessor.getModifiers().contains(Modifier.STATIC)) return null;
    final var declaring = (TypeElement) accessor.getEnclosingElement();
    final var types = env.getTypeUtils();
    if (!types.isSubtype(types.erasure(side.asType()), types.erasure(declaring.asType()))) return null;
    final var name = accessor.getSimpleName().toString();
    if (side.getKind() == ElementKind.RECORD) return name;
    if (name.startsWith("get") && name.length() > 3) return Character.toLowerCase(name.charAt(3)) + name.substring(4);
    if (name.startsWith("is") && name.length() > 2) return Character.toLowerCase(name.charAt(2)) + name.substring(3);
    return name;
  }
}
