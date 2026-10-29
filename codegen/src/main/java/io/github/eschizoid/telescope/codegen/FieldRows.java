package io.github.eschizoid.telescope.codegen;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;

/**
 * Reads the rows of a {@code @Bridge} field's {@code Telescope.mapper(...)} or {@code
 * Telescope.mapperForward(...)} initializer into the configuration the bridge emitter takes for a
 * pair. A row the generated bridge cannot express is refused by name, on the row.
 *
 * <p>The rows are read during a processing round, when the bridge can still be written. The tree
 * API resolves a method reference there by attributing the expression on demand, so a row names the
 * accessor or method its reference resolves to rather than one guessed from its text. Every
 * initializer the reader accepts holds only class literals, method references, literals and other
 * fields, so nothing it attributes is a lambda or class body the compiler attributes again later.
 *
 * <p>A converter, a supplier, a default and a constant are each compiled as a small class the
 * reader writes beside the bridge: a {@code BridgeFn} for a conversion, a {@code Supplier} for a
 * value the target is filled with. The bridge converts through such a class as it converts through
 * a class a row names, so every check the emitter makes on a conversion or a supplier applies to
 * the row unchanged.
 */
final class FieldRows {

  static final String TELESCOPE_FQN = "io.github.eschizoid.telescope.Telescope";
  private static final String MAPPING_FQN = "io.github.eschizoid.telescope.mapping.Mapping";
  private static final String WRITE_HINT_FQN = "io.github.eschizoid.telescope.mapping.WriteHint";
  private static final String BRIDGE_FQN = "io.github.eschizoid.telescope.annotations.Bridge";
  private static final Set<String> FACTORIES = Set.of("map", "mapper", "mapperForward");
  private static final Set<String> DISPATCHING = Set.of("mapper", "mapperForward");
  private static final Set<String> ROW_FACTORIES = Set.of(
    "to",
    "toOneWay",
    "toOrElse",
    "toOrElseGet",
    "enumTo",
    "via",
    "drop",
    "constant",
    "compute",
    "zip",
    "when",
    "writeBean",
    "writeBeans"
  );

  /** What the reader found for a field. */
  sealed interface Result permits Parsed, Refused {}

  /**
   * The configuration a field's rows declare. {@code classes} are the converter and supplier
   * classes the rows need written; {@code awaits} the classes, written by this compilation, that
   * must exist before the bridge is planned. {@code rowKinds} are the classes of the rows the
   * runtime builds from the same call, in order, and {@code rowAccessors} the accessor methods each
   * names, as {@code source>target}; the generated provider records both. {@code beanStrategies}
   * are the write strategies {@code writeBean} rows name, by the qualified name of the class each
   * is keyed to, and {@code defaultStrategy} the one a {@code writeBeans} row names, or null.
   */
  record Parsed(
    TypeElement source,
    TypeElement target,
    boolean strict,
    Set<String> drops,
    Map<String, String> renames,
    Map<String, List<String>> fanouts,
    Map<String, String> transforms,
    Map<String, String> transformMethods,
    Set<String> forwardOnly,
    Map<String, String> viaMappers,
    Map<String, String> computes,
    List<String> rowKinds,
    List<String> rowAccessors,
    List<GeneratedClass> classes,
    Set<String> awaits,
    Map<String, String> beanStrategies,
    String defaultStrategy
  ) implements Result {
    /**
     * How {@code side} is rebuilt: the strategy a {@code writeBean} row keyed to it names, else the
     * {@code writeBeans} default, else {@code AUTO}.
     */
    String writeStrategyOf(final TypeElement side) {
      final var keyed = beanStrategies.get(side.getQualifiedName().toString());
      if (keyed != null) return keyed;
      return defaultStrategy != null ? defaultStrategy : "AUTO";
    }
  }

  /** A class the reader writes beside the bridge, by qualified name, with its source. */
  record GeneratedClass(String qualifiedName, String source) {}

  /**
   * A row, or the field, the reader refuses, with the tree to report it on (null for the field). A
   * null message is a call javac cannot resolve, which javac reports itself.
   */
  record Refused(Tree at, String message) implements Result {
    static Refused javacReports(final Tree at) {
      return new Refused(at, null);
    }
  }

  private final ProcessingEnvironment env;
  private final Trees trees;

  FieldRows(final ProcessingEnvironment env, final Trees trees) {
    this.env = env;
    this.trees = trees;
  }

  /**
   * The simple name of the bridge a {@code @Bridge} field declares for {@code source} and {@code
   * target}, written in the package of the field's class: named by the pair, which one declaration
   * per pair keeps unique, so renaming or moving the field leaves it as it is.
   */
  static String bridgeSimpleName(final TypeElement source, final TypeElement target) {
    return source.getSimpleName() + "To" + target.getSimpleName() + "Bridge";
  }

  /**
   * The simple name of the bridge {@code field} declares, read from the pair its {@code Mapper<A,
   * B>} or {@code ForwardMapper<A, B>} type names, else from the class literals of its initializer,
   * or null when neither names the pair.
   */
  String bridgeSimpleName(final VariableElement field) {
    if (field.asType() instanceof DeclaredType declared && declared.getTypeArguments().size() == 2) {
      final var source = declared.getTypeArguments().get(0);
      final var target = declared.getTypeArguments().get(1);
      if (
        source instanceof DeclaredType s &&
        s.asElement() instanceof TypeElement sourceType &&
        target instanceof DeclaredType t &&
        t.asElement() instanceof TypeElement targetType
      ) {
        return bridgeSimpleName(sourceType, targetType);
      }
    }
    final var call = initializerCall(field);
    final var path = trees.getPath(field);
    if (call == null || path == null) return null;
    final var pair = literalPair(path.getCompilationUnit(), call, (TypeElement) field.getEnclosingElement());
    return pair == null ? null : bridgeSimpleName(pair.get(0), pair.get(1));
  }

  /** {@code USER_DTO} as {@code UserDto}, and {@code userDto} as {@code UserDto}. */
  static String camel(final String name) {
    if (!name.equals(name.toUpperCase())) return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    final var out = new StringBuilder();
    for (final var part : name.toLowerCase().split("_")) {
      if (part.isEmpty()) continue;
      out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return out.toString();
  }

  /** Whether {@code element} carries {@code @Bridge}. */
  static boolean annotatedBridge(final Element element) {
    for (final var mirror : element.getAnnotationMirrors()) {
      final var type = (TypeElement) mirror.getAnnotationType().asElement();
      if (type.getQualifiedName().contentEquals(BRIDGE_FQN)) return true;
    }
    return false;
  }

  /** The mapper factory {@code call} invokes, by name, or null when it invokes none. */
  String factoryOf(final CompilationUnitTree unit, final MethodInvocationTree call) {
    if (
      !(elementAt(unit, call.getMethodSelect()) instanceof ExecutableElement method) ||
      !(method.getEnclosingElement() instanceof TypeElement owner) ||
      !owner.getQualifiedName().contentEquals(TELESCOPE_FQN)
    ) {
      return null;
    }
    final var name = method.getSimpleName().toString();
    return FACTORIES.contains(name) ? name : null;
  }

  /** The class a class-literal argument names, or null when the argument is not one. */
  TypeElement classLiteral(final CompilationUnitTree unit, final ExpressionTree arg) {
    if (!(arg instanceof MemberSelectTree select) || !select.getIdentifier().contentEquals("class")) return null;
    final var expr = select.getExpression();
    if (!(expr instanceof IdentifierTree) && !(expr instanceof MemberSelectTree)) return null;
    return elementAt(unit, expr) instanceof TypeElement type ? type : null;
  }

  /**
   * The class a class-literal argument names, resolved by its name as the compilation unit's
   * package, imports and enclosing classes resolve it, for a tree nothing has attributed yet; or
   * null when the argument is not a class literal or the name resolves to no class.
   */
  TypeElement classLiteralByName(
    final CompilationUnitTree unit,
    final ExpressionTree arg,
    final TypeElement enclosing
  ) {
    if (!(arg instanceof MemberSelectTree select) || !select.getIdentifier().contentEquals("class")) return null;
    final var name = select.getExpression().toString();
    final var elements = env.getElementUtils();
    final var candidates = new ArrayList<String>();
    for (Element at = enclosing; at instanceof TypeElement type; at = type.getEnclosingElement()) {
      candidates.add(type.getQualifiedName() + "." + name);
    }
    final var dot = name.indexOf('.');
    final var head = dot < 0 ? name : name.substring(0, dot);
    final var tail = dot < 0 ? "" : name.substring(dot);
    for (final var imported : unit.getImports()) {
      if (imported.isStatic()) continue;
      final var text = imported.getQualifiedIdentifier().toString();
      if (text.endsWith("." + head)) candidates.add(text + tail);
      else if (text.endsWith(".*")) candidates.add(text.substring(0, text.length() - 1) + name);
    }
    final var pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
    candidates.add(pkg.isEmpty() ? name : pkg + "." + name);
    candidates.add("java.lang." + name);
    candidates.add(name);
    for (final var candidate : candidates) {
      final var type = elements.getTypeElement(candidate);
      if (type != null) return type;
    }
    return null;
  }

  /**
   * The {@code Telescope.mapper} and {@code mapperForward} calls in {@code type}, a class that
   * declares a {@code @Bridge} field, that the class may not make. The runtime hands a field's
   * bridge to a call from the class's static initialiser for the field's pair, so the class makes
   * only calls it can be read to make safely: each one the whole initializer of a static field,
   * naming its classes as literals, for the pair of a {@code @Bridge} field when it is that field,
   * and for a pair no {@code @Bridge} field of the class declares otherwise. Every other call is
   * returned, whatever its arguments: one in a method, a lambda or a block, one passed through a
   * helper, one nested in another call, one naming its classes by a variable. A nested class is its
   * own caller, with a static initialiser of its own, and is left out.
   *
   * <p>The calls are matched by their names and their class literals resolved by name, so nothing
   * outside a field's own initializer is attributed during the round: attributing a class body
   * there, a lambda's or an anonymous class's, leaves state the compiler trips over when it
   * attributes the body again.
   */
  List<MethodInvocationTree> unsanctionedCalls(final TypeElement type) {
    final var path = trees.getPath(type);
    if (path == null || !(path.getLeaf() instanceof ClassTree declaration)) return List.of();
    final var unit = path.getCompilationUnit();
    final var sanctioned = sanctionedCalls(type);
    final var found = new ArrayList<MethodInvocationTree>();
    final var scanner = new TreeScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
        if (namesFactory(unit, node) && !sanctioned.contains(node)) found.add(node);
        return super.visitMethodInvocation(node, unused);
      }

      @Override
      public Void visitClass(final ClassTree node, final Void unused) {
        return null;
      }
    };
    for (final var member : declaration.getMembers()) {
      if (!(member instanceof ClassTree)) scanner.scan(member, null);
    }
    return found;
  }

  /**
   * The mapper-factory calls {@code type} may make: each one the whole initializer of a static
   * field, naming its classes as literals, for the pair of a {@code @Bridge} field when it is that
   * field, and for a pair no {@code @Bridge} field of the class declares otherwise. A field of an
   * interface is static whether or not it says so.
   */
  Set<Tree> sanctionedCalls(final TypeElement type) {
    final var path = trees.getPath(type);
    if (path == null || !(path.getLeaf() instanceof ClassTree declaration)) return Set.of();
    final var unit = path.getCompilationUnit();
    final var implicitlyStatic = type.getKind().isInterface();
    final var declared = new LinkedHashSet<List<TypeElement>>();
    for (final var member : declaration.getMembers()) {
      if (
        member instanceof VariableTree field &&
        bridgeAnnotated(field) &&
        field.getInitializer() instanceof MethodInvocationTree call
      ) {
        final var pair = literalPair(unit, call, type);
        if (pair != null) declared.add(pair);
      }
    }
    final var sanctioned = new HashSet<Tree>();
    for (final var member : declaration.getMembers()) {
      if (
        !(member instanceof VariableTree field) ||
        !(implicitlyStatic || field.getModifiers().getFlags().contains(Modifier.STATIC)) ||
        !(field.getInitializer() instanceof MethodInvocationTree call) ||
        !namesFactory(unit, call)
      ) {
        continue;
      }
      final var pair = literalPair(unit, call, type);
      if (pair != null && (bridgeAnnotated(field) || !declared.contains(pair))) sanctioned.add(call);
    }
    return sanctioned;
  }

  /**
   * The calls to {@code Telescope.mapper} or {@code mapperForward}, and the method references to
   * either, in {@code type} outside {@link #sanctionedCalls}, found by the method each resolves to.
   * Read once javac has attributed {@code type}, so each tree already carries its symbol and
   * nothing is attributed again; this finds what {@link #unsanctionedCalls} cannot see by name: a
   * factory reached through any receiver expression, or a reference to it called later.
   */
  List<Tree> unsanctionedResolved(final TypeElement type) {
    final var path = trees.getPath(type);
    if (path == null || !(path.getLeaf() instanceof ClassTree declaration)) return List.of();
    final var sanctioned = sanctionedCalls(type);
    final var found = new ArrayList<Tree>();
    final var scanner = new TreePathScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
        if (!sanctioned.contains(node) && dispatching(trees.getElement(getCurrentPath()))) found.add(node);
        return super.visitMethodInvocation(node, unused);
      }

      @Override
      public Void visitMemberReference(final MemberReferenceTree node, final Void unused) {
        if (dispatching(trees.getElement(getCurrentPath()))) found.add(node);
        return super.visitMemberReference(node, unused);
      }

      @Override
      public Void visitClass(final ClassTree node, final Void unused) {
        return null;
      }
    };
    for (final var member : declaration.getMembers()) {
      if (!(member instanceof ClassTree)) scanner.scan(new TreePath(path, member), null);
    }
    return found;
  }

  /** Whether {@code element} is {@code Telescope.mapper} or {@code Telescope.mapperForward}. */
  private static boolean dispatching(final Element element) {
    return (
      element instanceof ExecutableElement method &&
      DISPATCHING.contains(method.getSimpleName().toString()) &&
      method.getEnclosingElement() instanceof TypeElement owner &&
      owner.getQualifiedName().contentEquals(TELESCOPE_FQN)
    );
  }

  /** The two classes {@code call} names as class literals, resolved by name, or null. */
  private List<TypeElement> literalPair(
    final CompilationUnitTree unit,
    final MethodInvocationTree call,
    final TypeElement enclosing
  ) {
    if (call.getArguments().size() < 2) return null;
    final var source = classLiteralByName(unit, call.getArguments().get(0), enclosing);
    final var target = classLiteralByName(unit, call.getArguments().get(1), enclosing);
    return source == null || target == null ? null : List.of(source, target);
  }

  /** Whether {@code field} carries {@code @Bridge}, read from its annotation's name. */
  private static boolean bridgeAnnotated(final VariableTree field) {
    for (final var annotation : field.getModifiers().getAnnotations()) {
      final var name = annotation.getAnnotationType().toString();
      if (name.equals("Bridge") || name.equals(BRIDGE_FQN)) return true;
    }
    return false;
  }

  /**
   * Whether {@code call} is written as {@code Telescope.mapper(...)} or {@code mapperForward(...)}:
   * qualified by {@code Telescope}, or by its simple name where the unit imports it statically.
   */
  private static boolean namesFactory(final CompilationUnitTree unit, final MethodInvocationTree call) {
    final var select = call.getMethodSelect();
    if (select instanceof MemberSelectTree member) {
      final var qualifier = member.getExpression().toString();
      return (
        DISPATCHING.contains(member.getIdentifier().toString()) &&
        (qualifier.equals("Telescope") || qualifier.equals(TELESCOPE_FQN))
      );
    }
    if (!(select instanceof IdentifierTree name) || !DISPATCHING.contains(name.getName().toString())) return false;
    for (final var imported : unit.getImports()) {
      if (!imported.isStatic()) continue;
      final var text = imported.getQualifiedIdentifier().toString();
      if (text.equals(TELESCOPE_FQN + ".*") || text.equals(TELESCOPE_FQN + "." + name.getName())) return true;
    }
    return false;
  }

  /** The initializer of {@code field} when it is a mapper-factory call, else null. */
  MethodInvocationTree initializerCall(final VariableElement field) {
    return trees.getTree(field) instanceof VariableTree variable &&
      variable.getInitializer() instanceof MethodInvocationTree call
      ? call
      : null;
  }

  Result read(final VariableElement field) {
    final var fieldTree = trees.getTree(field);
    if (!(fieldTree instanceof VariableTree variable)) {
      return new Refused(null, "@Bridge field " + field.getSimpleName() + ": its source is not in this compilation");
    }
    final var unit = trees.getPath(field).getCompilationUnit();
    final var fieldName = field.getEnclosingElement().getSimpleName() + "." + field.getSimpleName();
    if (!field.getModifiers().contains(Modifier.STATIC) || !field.getModifiers().contains(Modifier.FINAL)) {
      return new Refused(
        variable,
        "@Bridge field " +
          fieldName +
          " must be static final: the generated bridge stands for the one mapper the field holds"
      );
    }
    final var factory = variable.getInitializer() instanceof MethodInvocationTree call ? factoryOf(unit, call) : null;
    if (
      factory == null &&
      variable.getInitializer() instanceof MethodInvocationTree unresolved &&
      namesFactory(unit, unresolved) &&
      elementAt(unit, unresolved.getMethodSelect()) == null
    ) {
      return Refused.javacReports(unresolved);
    }
    if (factory == null || factory.equals("map")) {
      return new Refused(
        variable.getInitializer() == null ? variable : variable.getInitializer(),
        "@Bridge field " +
          fieldName +
          " must be initialised by a Telescope.mapper(...) or Telescope.mapperForward(...) call written in" +
          " place, whose rows the processor compiles into the bridge"
      );
    }
    final var call = (MethodInvocationTree) variable.getInitializer();
    final var strict = factory.equals("mapper");
    final var args = call.getArguments();
    final var source = args.size() < 2 ? null : classLiteral(unit, args.get(0));
    final var target = args.size() < 2 ? null : classLiteral(unit, args.get(1));
    if (source == null || target == null) {
      return new Refused(
        call,
        "@Bridge field " + fieldName + " must name its source and target classes as class literals"
      );
    }
    final var pkg = env.getElementUtils().getPackageOf(field.getEnclosingElement()).getQualifiedName().toString();
    final var bridgeName = bridgeSimpleName(source, target);
    final var rows = new Rows(unit, source, target, strict, pkg, bridgeName, fieldName);
    for (var i = 2; i < args.size(); i++) {
      final var refused = rows.read(args.get(i));
      if (refused != null) return refused;
    }
    return rows.parsed();
  }

  /** The rows of one call, accumulated into the pair's configuration. */
  private final class Rows {

    private final CompilationUnitTree unit;
    private final TypeElement source;
    private final TypeElement target;
    private final boolean strict;
    private final String pkg;
    private final String bridgeName;
    private final Set<String> drops = new LinkedHashSet<>();
    private final Map<String, String> renames = new LinkedHashMap<>();
    private final Map<String, List<String>> fanouts = new LinkedHashMap<>();
    private final Map<String, String> transforms = new LinkedHashMap<>();
    private final Map<String, String> transformMethods = new LinkedHashMap<>();
    private final Set<String> forwardOnly = new LinkedHashSet<>();
    private final Map<String, String> viaMappers = new LinkedHashMap<>();
    private final Map<String, String> computes = new LinkedHashMap<>();
    private final Set<String> targetsWritten = new LinkedHashSet<>();
    private final List<String> rowKinds = new ArrayList<>();
    private final List<String> rowAccessors = new ArrayList<>();
    private final List<GeneratedClass> classes = new ArrayList<>();
    private final Set<String> awaits = new LinkedHashSet<>();
    private final Map<String, String> beanStrategies = new LinkedHashMap<>();
    private String defaultStrategy;

    /** The field the rows are read from, as {@code Carrier.FIELD}, which a refusal names. */
    private final String fieldName;

    Rows(
      final CompilationUnitTree unit,
      final TypeElement source,
      final TypeElement target,
      final boolean strict,
      final String pkg,
      final String bridgeName,
      final String fieldName
    ) {
      this.unit = unit;
      this.source = source;
      this.target = target;
      this.strict = strict;
      this.pkg = pkg;
      this.bridgeName = bridgeName;
      this.fieldName = fieldName;
    }

    Parsed parsed() {
      return new Parsed(
        source,
        target,
        strict,
        drops,
        renames,
        fanouts,
        transforms,
        transformMethods,
        forwardOnly,
        viaMappers,
        computes,
        rowKinds,
        rowAccessors,
        classes,
        awaits,
        beanStrategies,
        defaultStrategy
      );
    }

    /** Reads one row, or answers why it cannot be compiled. */
    Refused read(final ExpressionTree arg) {
      if (!(arg instanceof MethodInvocationTree row)) {
        return new Refused(
          arg,
          "a @Bridge mapper compiles only rows written in place as a Mapping factory call; a row held in" +
            " a variable or built by a helper method or a loop has no rows the processor can read"
        );
      }
      final var rowElement = elementAt(unit, row.getMethodSelect());
      if (ROW_FACTORIES.contains(selectedName(row))) {
        // A row javac cannot type, an argument it cannot infer included, is javac's own error.
        final var typed = trees.getTypeMirror(TreePath.getPath(unit, row));
        if (rowElement == null || typed == null || typed.getKind() == TypeKind.ERROR) return Refused.javacReports(row);
      }
      if (
        rowElement instanceof ExecutableElement hintExec &&
        hintExec.getEnclosingElement() instanceof TypeElement hintOwner &&
        hintOwner.getQualifiedName().contentEquals(WRITE_HINT_FQN)
      ) {
        return hint(row, hintExec.getSimpleName().toString());
      }
      if (
        !(rowElement instanceof ExecutableElement rowExec) ||
        !(rowExec.getEnclosingElement() instanceof TypeElement rowOwner) ||
        !rowOwner.getQualifiedName().contentEquals(MAPPING_FQN)
      ) {
        return new Refused(
          row,
          row.getMethodSelect() +
            "(...) is not a row a @Bridge mapper compiles; the generated bridge takes the Mapping rows" +
            " to, toOneWay, toOrElse, toOrElseGet, via, drop, constant and compute, and the hints writeBean and" +
            " writeBeans"
        );
      }
      final var rowArgs = row.getArguments();
      for (final var rowArg : rowArgs) {
        if (rowArg instanceof LambdaExpressionTree) {
          return new Refused(
            rowArg,
            "an inline lambda can't be compiled into the generated bridge; write a method reference" +
              " instead, such as String::trim or a static method like Conversions::normalise, so both" +
              " paths call the same code"
          );
        }
      }
      final var kind = rowExec.getSimpleName().toString();
      return switch (kind) {
        case "to" -> switch (rowArgs.size()) {
          case 2 -> passThrough(row);
          case 3 -> twoWay(row);
          default -> converting(row, true);
        };
        case "toOneWay" -> converting(row, false);
        case "toOrElse", "toOrElseGet" -> orElse(row, kind);
        case "via" -> via(row);
        case "drop" -> drop(row);
        case "constant" -> filled(row, true);
        case "compute" -> filled(row, false);
        default -> new Refused(
          row,
          kind +
            "(...) has no generated form yet, so a @Bridge mapper cannot compile it; build this mapper" +
            " without @Bridge to run it on the runtime path"
        );
      };
    }

    private Refused passThrough(final MethodInvocationTree row) {
      final var src = accessor(row.getArguments().get(0), source);
      if (src instanceof Refused refused) return refused;
      final var tgt = accessor(row.getArguments().get(1), target);
      if (tgt instanceof Refused refused) return refused;
      final var refusal = write(row, ((Accessor) src).property(), ((Accessor) tgt).property(), null);
      if (refusal != null) return refusal;
      rowKinds.add("SameTypedTo");
      rowAccessors.add(((Accessor) src).method() + ">" + ((Accessor) tgt).method());
      return null;
    }

    private Refused converting(final MethodInvocationTree row, final boolean twoWay) {
      final var args = row.getArguments();
      if (args.size() != (twoWay ? 4 : 3)) return new Refused(row, "this form of the row has no generated form yet");
      if (!twoWay && strict) {
        return new Refused(
          row,
          "Telescope.mapper(...) cannot accept a Mapping.toOneWay(...) row: it is forward-only and the" +
            " mapper converts both ways. Use Telescope.mapperForward(...)"
        );
      }
      final var src = accessor(args.get(0), source);
      if (src instanceof Refused refused) return refused;
      final var tgt = accessor(args.get(1), target);
      if (tgt instanceof Refused refused) return refused;
      final var s = (Accessor) src;
      final var t = (Accessor) tgt;
      final var forward = call(args.get(2), "v", 1);
      if (forward instanceof Refused refused) return refused;
      final String backward;
      if (twoWay) {
        final var back = call(args.get(3), "v", 1);
        if (back instanceof Refused refused) return refused;
        backward = ((Call) back).expression();
      } else {
        backward = null;
      }
      final var refusal = write(row, s.property(), t.property(), ((Call) forward).method());
      if (refusal != null) return refusal;
      final var forwardMethod = ((Call) forward).method();
      if (
        !twoWay &&
        !transforms.containsKey(s.property()) &&
        forwardMethod.getKind() == ElementKind.METHOD &&
        forwardMethod.getModifiers().contains(Modifier.STATIC) &&
        ((TypeElement) forwardMethod.getEnclosingElement()).getNestingKind() == NestingKind.TOP_LEVEL
      ) {
        // A static method of a top-level class converting forward only is called by name from the
        // bridge, with no generated class between them.
        transforms.put(s.property(), ((TypeElement) forwardMethod.getEnclosingElement()).getQualifiedName().toString());
        transformMethods.put(s.property(), forwardMethod.getSimpleName().toString());
        forwardOnly.add(s.property());
      }
      if (!transforms.containsKey(s.property())) {
        final var from = rowTyped(s);
        final var into = rowTyped(t);
        final var unnamed = unnameable(from, into);
        if (unnamed != null) return unnamed;
        final var gives = converted(args.get(2), forwardMethod, into);
        if (gives instanceof Refused refused) return refused;
        final var name = bridgeName + camel(s.property()) + "Fn";
        transforms.put(s.property(), qualified(name));
        if (!twoWay) forwardOnly.add(s.property());
        classes.add(bridgeFn(name, from.type(), (TypeMirror) gives, ((Call) forward).expression(), backward));
      }
      rowKinds.add(twoWay ? "TypedTransformTo" : "ForwardOnlyTransformTo");
      rowAccessors.add(s.method() + ">" + t.method());
      return null;
    }

    /**
     * {@code side} as the row's converter takes or gives it, where the property's own type names a
     * type variable of its class: javac types a method reference to a member of a class named raw
     * by the erasure, or by the type the row's type arguments give it, and the class the processor
     * writes for the row takes that type, as a {@code BridgeFn} class declared for the row does.
     * Otherwise {@code side} as it is.
     */
    private Accessor rowTyped(final Accessor side) {
      if (nameable(side.type())) return side;
      // A row is read only once javac has attributed it, so its accessor is an Accessor<A, X>.
      final var accessor = (DeclaredType) trees.getTypeMirror(TreePath.getPath(unit, side.at()));
      final var typed = accessor.getTypeArguments().get(1);
      if (typed instanceof WildcardType || !nameable(typed)) return side;
      return new Accessor(side.property(), typed, side.primitive(), side.method(), side.at());
    }

    /**
     * What the class the processor writes for a converting row gives its values as: the property
     * {@code into}'s own class, or a wider class that {@code forward}'s method returns, whose
     * result is written into the property after a check of its class, as the runtime writes it. The
     * refusal where the method returns a class no value of the property can be. Where the return
     * names a type variable, the class Java infers for the row is read in its place, and where
     * neither has a name the written class can use, the property's own.
     */
    private Object converted(final ExpressionTree forward, final ExecutableElement method, final Accessor into) {
      final var types = env.getTypeUtils();
      var gives = declaredResult(method);
      if (gives == null) gives = inferredResult(forward);
      if (gives == null || !nameable(gives) || types.isAssignable(gives, into.type())) return into.type();
      if (types.isSubtype(types.erasure(into.type()), types.erasure(gives))) return gives;
      return new Refused(
        forward,
        forward + " gives " + gives + ", which the " + shown(into) + " property '" + into.property() + "' does not hold"
      );
    }

    /**
     * What {@code method} returns, boxed, or the class a constructor builds; null where it names a
     * type variable.
     */
    private TypeMirror declaredResult(final ExecutableElement method) {
      final var types = env.getTypeUtils();
      final var result =
        method.getKind() == ElementKind.CONSTRUCTOR ? method.getEnclosingElement().asType() : method.getReturnType();
      if (result.getKind().isPrimitive()) return types.boxedClass((PrimitiveType) result).asType();
      if (result.getKind() == TypeKind.TYPEVAR || !nameable(result)) return null;
      return result;
    }

    /** The result type Java infers for a reference converting one value, or null. */
    private TypeMirror inferredResult(final ExpressionTree forward) {
      if (!(trees.getTypeMirror(TreePath.getPath(unit, forward)) instanceof DeclaredType function)) return null;
      final var arguments = function.getTypeArguments();
      if (arguments.size() != 2) return null;
      final var gives = arguments.get(1);
      return gives instanceof WildcardType wildcard ? wildcard.getExtendsBound() : gives;
    }

    /**
     * A {@code to} row converting through one {@code BridgeFn}: a class literal, which the bridge
     * builds once through its no-argument constructor, or a {@code static final} field holding one,
     * which a generated class calls.
     */
    private Refused twoWay(final MethodInvocationTree row) {
      final var args = row.getArguments();
      final var src = accessor(args.get(0), source);
      if (src instanceof Refused refused) return refused;
      final var tgt = accessor(args.get(1), target);
      if (tgt instanceof Refused refused) return refused;
      final var s = (Accessor) src;
      final var t = (Accessor) tgt;
      final var conversion = args.get(2);
      final var named = classLiteral(unit, conversion);
      final var element = named != null ? named : elementAt(unit, conversion);
      final String using;
      final GeneratedClass delegate;
      if (named != null) {
        final var unbuildable = unbuildable(named);
        if (unbuildable != null) return new Refused(conversion, unbuildable);
        using = named.getQualifiedName().toString();
        delegate = null;
      } else if (
        (conversion instanceof IdentifierTree || conversion instanceof MemberSelectTree) &&
        element instanceof VariableElement held &&
        held.getKind() == ElementKind.FIELD &&
        held.getModifiers().contains(Modifier.STATIC) &&
        held.getModifiers().contains(Modifier.FINAL)
      ) {
        final var heldOwner = (TypeElement) held.getEnclosingElement();
        if (!reachable(held, heldOwner)) return new Refused(conversion, unreachable(heldOwner, held.getSimpleName()));
        final var unnamed = unnameable(s, t);
        if (unnamed != null) return unnamed;
        final var raw = rawMisfit(held, s, t);
        if (raw != null) return new Refused(conversion, raw);
        final var reference = heldOwner.getQualifiedName() + "." + held.getSimpleName();
        final var name = bridgeName + camel(s.property()) + "Fn";
        using = qualified(name);
        delegate = bridgeFn(name, s.type(), t.type(), reference + ".forward(v)", reference + ".backward(v)");
      } else {
        return new Refused(
          conversion,
          "a @Bridge mapper converts through a BridgeFn the generated bridge can name: pass its class" +
            " (EmailFn.class), or a static final field holding it (Emails.CONVERSION)"
        );
      }
      final var refusal = write(row, s.property(), t.property(), element);
      if (refusal != null) return refusal;
      if (!transforms.containsKey(s.property())) {
        transforms.put(s.property(), using);
        if (delegate != null) classes.add(delegate);
      }
      rowKinds.add("TypedTransformTo");
      rowAccessors.add(s.method() + ">" + t.method());
      return null;
    }

    /**
     * Why a {@code BridgeFn} that {@code held} declares raw cannot convert {@code src} into {@code
     * tgt}, or null when it can: a raw class's {@code forward} and {@code backward} take and give
     * what its type variables erase to, and the class the processor writes calls them with the
     * property's values and returns what they give.
     */
    private String rawMisfit(final VariableElement held, final Accessor src, final Accessor tgt) {
      // A field the row reads a BridgeFn from is typed by a class, never by a variable.
      final var type = (DeclaredType) held.asType();
      if (!type.getTypeArguments().isEmpty()) return null;
      if (((TypeElement) type.asElement()).getTypeParameters().isEmpty()) return null;
      final var types = env.getTypeUtils();
      final var members = ElementFilter.methodsIn(env.getElementUtils().getAllMembers((TypeElement) type.asElement()));
      for (final var direction : List.of("forward", "backward")) {
        final var takes = direction.equals("forward") ? src.type() : tgt.type();
        final var gives = direction.equals("forward") ? tgt.type() : src.type();
        ExecutableType misfit = null;
        var fits = false;
        for (final var method : members) {
          if (!method.getSimpleName().contentEquals(direction) || method.getParameters().size() != 1) continue;
          if (method.getModifiers().contains(Modifier.STATIC)) continue;
          final var erased = (ExecutableType) types.asMemberOf(type, method);
          if (
            types.isAssignable(takes, erased.getParameterTypes().getFirst()) &&
            types.isAssignable(erased.getReturnType(), gives)
          ) {
            fits = true;
          } else if (misfit == null) {
            misfit = erased;
          }
        }
        if (fits || misfit == null) continue;
        return (
          held.getSimpleName() +
          " holds a raw " +
          type.asElement().getSimpleName() +
          ", whose " +
          direction +
          " is (" +
          misfit.getParameterTypes().getFirst() +
          ") -> " +
          misfit.getReturnType() +
          ", which does not carry " +
          takes +
          " -> " +
          gives +
          "; declare the field with its type arguments"
        );
      }
      return null;
    }

    private Refused orElse(final MethodInvocationTree row, final String kind) {
      final var args = row.getArguments();
      if (args.size() != 3) {
        return new Refused(row, kind + "(...) with a predicate has no generated form yet");
      }
      final var src = accessor(args.get(0), source);
      if (src instanceof Refused refused) return refused;
      final var tgt = accessor(args.get(1), target);
      if (tgt instanceof Refused refused) return refused;
      final var s = (Accessor) src;
      final var t = (Accessor) tgt;
      final String fallback;
      if (kind.equals("toOrElse")) {
        final var value = constantText(args.get(2), t);
        if (value instanceof Refused refused) return refused;
        fallback = (String) value;
      } else {
        final var supplier = call(args.get(2), null, 0);
        if (supplier instanceof Refused refused) return refused;
        final var refusedType = returnFits(args.get(2), ((Call) supplier).method(), t);
        if (refusedType != null) return refusedType;
        fallback = ((Call) supplier).expression();
      }
      final var refusal = write(row, s.property(), t.property(), row);
      if (refusal != null) return refusal;
      if (transforms.containsKey(s.property())) {
        return new Refused(row, "a @Bridge mapper converts one source property through one row");
      }
      final var unnamed = unnameable(s, t);
      if (unnamed != null) return unnamed;
      final var name = bridgeName + camel(s.property()) + "Fn";
      transforms.put(s.property(), qualified(name));
      classes.add(bridgeFn(name, s.type(), t.type(), "v == null ? " + fallback + " : v", "v"));
      rowKinds.add("TypedTransformTo");
      rowAccessors.add(s.method() + ">" + t.method());
      return null;
    }

    private Refused via(final MethodInvocationTree row) {
      final var args = row.getArguments();
      final var src = accessor(args.get(0), source);
      if (src instanceof Refused refused) return refused;
      final var tgt = accessor(args.get(1), target);
      if (tgt instanceof Refused refused) return refused;
      if (args.size() == 4) return viaMethods(row, (Accessor) src, (Accessor) tgt);
      final var named = classLiteral(unit, args.get(2));
      if (named != null) return viaClass(row, (Accessor) src, (Accessor) tgt, named);
      final var mapper = elementAt(unit, args.get(2));
      if (
        !(args.get(2) instanceof IdentifierTree || args.get(2) instanceof MemberSelectTree) ||
        !(mapper instanceof VariableElement mapperField) ||
        mapperField.getKind() != ElementKind.FIELD ||
        !annotatedBridge(mapperField)
      ) {
        return new Refused(
          args.get(2),
          "via(...) in a @Bridge mapper names another @Bridge mapper field, whose generated bridge the" +
            " generated bridge calls, a class's static forward and backward methods, as Hw::forward," +
            " Hw::backward, or the class itself, as Hw.class"
        );
      }
      final var s = ((Accessor) src).property();
      final var refusal = write(row, s, ((Accessor) tgt).property(), mapperField);
      if (refusal != null) return refusal;
      final var owner = (TypeElement) mapperField.getEnclosingElement();
      final var ownerPkg = env.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
      final var viaName = bridgeSimpleName(mapperField);
      if (viaName == null) {
        return new Refused(
          args.get(2),
          "via(...) names " +
            owner.getSimpleName() +
            "." +
            mapperField.getSimpleName() +
            ", whose type does not name its pair; declare it as Mapper<Source, Target>"
        );
      }
      final var via = (ownerPkg.isEmpty() ? "" : ownerPkg + ".") + viaName;
      if (!viaMappers.containsKey(s)) viaMappers.put(s, via);
      if (env.getElementUtils().getTypeElement(via) == null) {
        if (trees.getTree(mapperField) == null) {
          return new Refused(
            args.get(2),
            "via(...) names " +
              owner.getSimpleName() +
              "." +
              mapperField.getSimpleName() +
              ", whose generated bridge " +
              via +
              " is not on the class path; run the annotation processor where that field is compiled"
          );
        }
        awaits.add(via);
      }
      rowKinds.add("Via");
      rowAccessors.add(((Accessor) src).method() + ">" + ((Accessor) tgt).method());
      return null;
    }

    /**
     * A {@code via} row naming a class written by hand, whose static methods the bridge chooses for
     * the property's types, converting the whole property or each element of a container.
     */
    private Refused viaClass(
      final MethodInvocationTree row,
      final Accessor src,
      final Accessor tgt,
      final TypeElement named
    ) {
      final var refusal = write(row, src.property(), tgt.property(), named);
      if (refusal != null) return refusal;
      if (!viaMappers.containsKey(src.property())) viaMappers.put(src.property(), named.getQualifiedName().toString());
      rowKinds.add("ViaClass");
      rowAccessors.add(src.method() + ">" + tgt.method());
      return null;
    }

    /**
     * A {@code via} row converting through two methods: the static {@code forward} and {@code
     * backward} of one class, which compiles as the row naming that class does, so the bridge calls
     * them on the whole property or on each element of a container, as the class's methods take.
     */
    private Refused viaMethods(final MethodInvocationTree row, final Accessor src, final Accessor tgt) {
      final var args = row.getArguments();
      final var forward = call(args.get(2), "v", 1);
      if (forward instanceof Refused refused) return refused;
      final var backward = call(args.get(3), "v", 1);
      if (backward instanceof Refused refused) return refused;
      final var f = ((Call) forward).method();
      final var b = ((Call) backward).method();
      final var owner = (TypeElement) f.getEnclosingElement();
      if (
        !f.getModifiers().contains(Modifier.STATIC) ||
        !b.getModifiers().contains(Modifier.STATIC) ||
        !f.getSimpleName().contentEquals("forward") ||
        !b.getSimpleName().contentEquals("backward") ||
        !b.getEnclosingElement().equals(owner)
      ) {
        return new Refused(
          f.getSimpleName().contentEquals("forward") && f.getModifiers().contains(Modifier.STATIC)
            ? args.get(3)
            : args.get(2),
          "via(...) with two methods in a @Bridge mapper takes the static forward and backward methods of one" +
            " class, as Hw::forward, Hw::backward, which the generated bridge calls on each value"
        );
      }
      final var narrowed = narrowedBy(src, f, b);
      if (narrowed != null) return new Refused(
        args.get(3),
        "In the rows of @Bridge field " + fieldName + ": " + narrowed
      );
      final var refusal = write(row, src.property(), tgt.property(), owner);
      if (refusal != null) return refusal;
      if (!viaMappers.containsKey(src.property())) viaMappers.put(src.property(), owner.getQualifiedName().toString());
      rowKinds.add("ViaMethods");
      rowAccessors.add(src.method() + ">" + tgt.method());
      return null;
    }

    /**
     * The refusal of a via row of two methods whose {@code backward} returns a class narrower than
     * what {@code src} holds, or null. The compiler types {@code forward} as taking what {@code
     * backward} returns, and the lambda it builds casts each value to that class, so the row is
     * refused where neither the property's elements nor the property are one, in the words the
     * runtime path refuses with. A {@code forward} whose own parameter admits neither is left to
     * the bridge's refusal of a class that never fits.
     */
    private String narrowedBy(final Accessor src, final ExecutableElement forward, final ExecutableElement backward) {
      final var types = env.getTypeUtils();
      final var takes = boxedErasure(backward.getReturnType());
      final var declared = boxedErasure(forward.getParameters().getFirst().asType());
      final var property = boxedErasure(src.type());
      final var element = elementOf(src.type());
      if (element != null && types.isAssignable(element, takes)) return null;
      if (types.isAssignable(property, takes)) return null;
      final var forElement = element != null && types.isAssignable(element, declared);
      if (!forElement && !types.isAssignable(property, declared)) return null;
      return PairingMessages.viaMethodsNarrowed(
        simple(source),
        simple(target),
        src.property(),
        simpleNameOf(takes),
        simpleNameOf(forElement ? element : property)
      );
    }

    /** {@code type}'s wrapper class where it is a primitive, and its erasure otherwise. */
    private TypeMirror boxedErasure(final TypeMirror type) {
      final var types = env.getTypeUtils();
      return type.getKind().isPrimitive() ? types.boxedClass((PrimitiveType) type).asType() : types.erasure(type);
    }

    /**
     * The erased class of the elements of {@code type} when it is a {@code List}, {@code Set} or
     * {@code Optional}, or of its values when it is a {@code Map}, or null.
     */
    private TypeMirror elementOf(final TypeMirror type) {
      if (!(type instanceof DeclaredType declared)) return null;
      final var types = env.getTypeUtils();
      final var elements = env.getElementUtils();
      final var erased = types.erasure(type);
      final int index;
      if (types.isAssignable(erased, types.erasure(elements.getTypeElement("java.util.Map").asType()))) index = 1;
      else if (
        types.isAssignable(erased, types.erasure(elements.getTypeElement("java.util.List").asType())) ||
        types.isAssignable(erased, types.erasure(elements.getTypeElement("java.util.Set").asType())) ||
        types.isAssignable(erased, types.erasure(elements.getTypeElement("java.util.Optional").asType()))
      ) index = 0;
      else return null;
      final var arguments = declared.getTypeArguments();
      if (arguments.size() <= index || !(arguments.get(index) instanceof DeclaredType argument)) return null;
      return types.erasure(argument);
    }

    /** The simple name of the class {@code type} erases to. */
    private String simpleNameOf(final TypeMirror type) {
      final var element = env.getTypeUtils().asElement(type);
      return element == null ? type.toString() : element.getSimpleName().toString();
    }

    private Refused drop(final MethodInvocationTree row) {
      final var args = row.getArguments();
      if (args.size() != 1) {
        return new Refused(row, "drop(...) keyed to a nested pair has no generated form yet");
      }
      final var src = accessor(args.get(0), source);
      if (src instanceof Refused refused) return refused;
      final var property = ((Accessor) src).property();
      if (written.containsKey(property) || !drops.add(property)) {
        return new Refused(
          row,
          "drop(...) of '" +
            property +
            "', which another row of this mapper also names; a property is dropped once, by a row of" +
            " its own"
        );
      }
      rowKinds.add("Drop");
      rowAccessors.add(((Accessor) src).method() + ">");
      return null;
    }

    private Refused filled(final MethodInvocationTree row, final boolean constant) {
      final var args = row.getArguments();
      final var tgt = accessor(args.get(0), target);
      if (tgt instanceof Refused refused) return refused;
      final var t = (Accessor) tgt;
      final String value;
      if (constant) {
        final var text = constantText(args.get(1), t);
        if (text instanceof Refused refused) return refused;
        value = (String) text;
      } else {
        final var held = heldSupplier(args.get(1), t);
        if (held instanceof Refused refused) return refused;
        if (held instanceof String read) {
          value = read;
        } else {
          final var supplier = call(args.get(1), null, 0);
          if (supplier instanceof Refused refused) return refused;
          final var refusedType = returnFits(args.get(1), ((Call) supplier).method(), t);
          if (refusedType != null) return refusedType;
          value = ((Call) supplier).expression();
        }
      }
      if (!targetsWritten.add(t.property()) || computes.containsKey(t.property())) {
        return new Refused(row, PairingMessages.duplicateTargetRow(simple(source), simple(target), t.property()));
      }
      final var filledAs = nameable(t.type()) ? t.type() : suppliedType(args.get(1), constant);
      if (filledAs == null || !nameable(filledAs)) return unnameable(t);
      final var name = bridgeName + camel(t.property()) + (constant ? "Constant" : "Compute");
      computes.put(t.property(), qualified(name));
      classes.add(supplier(name, filledAs, value));
      rowKinds.add(constant ? "Constant" : "Compute");
      rowAccessors.add(">");
      return null;
    }

    /**
     * The class a constant or compute row's value is, which the class the processor writes for the
     * row supplies where the property's own type has no name it can use, as a type variable: the
     * value's own type for a constant, and what the supplier gives for a compute row; null for a
     * null constant, which has no class.
     */
    private TypeMirror suppliedType(final ExpressionTree arg, final boolean constant) {
      final var types = env.getTypeUtils();
      final TypeMirror given;
      if (constant) {
        given = trees.getTypeMirror(TreePath.getPath(unit, arg));
      } else if (elementAt(unit, arg) instanceof VariableElement held) {
        // The field holding the supplier, which the row was read through.
        final var get = env
          .getElementUtils()
          .getTypeElement("java.util.function.Supplier")
          .getEnclosedElements()
          .stream()
          .filter(e -> e.getKind() == ElementKind.METHOD && e.getSimpleName().contentEquals("get"))
          .findFirst()
          .orElseThrow();
        given = ((ExecutableType) types.asMemberOf((DeclaredType) held.asType(), get)).getReturnType();
      } else {
        // The method reference the row was read through.
        final var method = (ExecutableElement) elementAt(unit, arg);
        given =
          method.getKind() == ElementKind.CONSTRUCTOR ? method.getEnclosingElement().asType() : method.getReturnType();
      }
      if (given.getKind() == TypeKind.NULL) return null;
      return given.getKind().isPrimitive() ? types.boxedClass((PrimitiveType) given).asType() : given;
    }

    /**
     * The expression a {@code compute} row reads its value with when its supplier is a {@code
     * static final} field, {@code Clock.NOW} or {@code NOW}: a call of that field's {@code get()},
     * which a generated class makes, as the runtime path calls the same instance. The refusal where
     * the field is not static and final, the generated bridge cannot reach it, or its supplier
     * gives a value the target's property does not hold; null where {@code arg} names no field.
     */
    private Object heldSupplier(final ExpressionTree arg, final Accessor into) {
      if (!(arg instanceof IdentifierTree || arg instanceof MemberSelectTree)) return null;
      if (!(elementAt(unit, arg) instanceof VariableElement held) || held.getKind() != ElementKind.FIELD) return null;
      if (!held.getModifiers().contains(Modifier.STATIC) || !held.getModifiers().contains(Modifier.FINAL)) {
        return new Refused(
          arg,
          "a @Bridge mapper computes a value from a supplier the generated bridge can name: a method" +
            " reference (Clock::now), or a static final field holding the supplier; " +
            held.getSimpleName() +
            " is not static and final"
        );
      }
      final var owner = (TypeElement) held.getEnclosingElement();
      if (!reachable(held, owner)) return new Refused(arg, unreachable(owner, held.getSimpleName()));
      final var types = env.getTypeUtils();
      final var get = env
        .getElementUtils()
        .getTypeElement("java.util.function.Supplier")
        .getEnclosedElements()
        .stream()
        .filter(e -> e.getKind() == ElementKind.METHOD && e.getSimpleName().contentEquals("get"))
        .findFirst()
        .orElseThrow();
      final var gives = ((ExecutableType) types.asMemberOf((DeclaredType) held.asType(), get)).getReturnType();
      if (!types.isAssignable(types.erasure(gives), types.erasure(into.type()))) {
        return new Refused(
          arg,
          arg + " gives " + gives + ", which the " + shown(into) + " property '" + into.property() + "' does not hold"
        );
      }
      return owner.getQualifiedName() + "." + held.getSimpleName() + ".get()";
    }

    /**
     * A {@code writeBean} or {@code writeBeans} hint, which decides how the bridge rebuilds a side
     * of its pair, as {@code @Bridge(writeStrategy = ...)} does for both: {@code writeBean} keyed
     * to the source or the target decides that side, and {@code writeBeans} every side no {@code
     * writeBean} names. A {@code writeBean} keyed to any other class, and a {@code writeBeans}
     * default on a pair whose bridge builds a nested bean pair, would reach a pair the bridge does
     * not rebuild itself, which the generated bridge has no form for; the first is refused here,
     * and the second when the nested pair is planned.
     */
    private Refused hint(final MethodInvocationTree row, final String kind) {
      final var args = row.getArguments();
      final var strategy = strategyOf(args.get(args.size() - 1));
      if (strategy == null) {
        return new Refused(
          args.get(args.size() - 1),
          "a @Bridge mapper compiles a write hint whose strategy is a WriteStrategy constant written in place," +
            " such as SETTERS or WriteStrategy.BUILDER"
        );
      }
      if (kind.equals("writeBeans")) {
        if (defaultStrategy != null) return new Refused(row, PairingMessages.duplicateWriteBeansDefault());
        defaultStrategy = strategy;
        rowKinds.add("DefaultWriteHint");
        return null;
      }
      final var keyed = classLiteral(unit, args.get(0));
      if (keyed == null) {
        return new Refused(args.get(0), "a @Bridge mapper compiles a writeBean hint keyed to a class literal");
      }
      final var binary = env.getElementUtils().getBinaryName(keyed).toString();
      if (keyed.getKind() == ElementKind.RECORD) {
        return new Refused(row, PairingMessages.writeBeanTargetsRecord(binary));
      }
      if (!keyed.equals(source) && !keyed.equals(target)) {
        return new Refused(
          row,
          "writeBean(" +
            keyed.getSimpleName() +
            ".class, ...) is keyed to a class other than " +
            simple(source) +
            " and " +
            simple(target) +
            ", a nested pair the generated bridge has no hint for yet; give that pair its own @Bridge field" +
            " with its own writeBean row"
        );
      }
      if (beanStrategies.put(keyed.getQualifiedName().toString(), strategy) != null) {
        return new Refused(row, PairingMessages.duplicateWriteBeanHint(binary));
      }
      rowKinds.add("BeanWriteHint");
      return null;
    }

    /** The name of the {@code WriteStrategy} constant {@code arg} names, or null. */
    private String strategyOf(final ExpressionTree arg) {
      if (!(arg instanceof IdentifierTree || arg instanceof MemberSelectTree)) return null;
      if (!(elementAt(unit, arg) instanceof VariableElement constant)) return null;
      // javac typed the argument as a WriteStrategy, so an enum constant here is one of its
      // constants.
      if (constant.getKind() != ElementKind.ENUM_CONSTANT) return null;
      return constant.getSimpleName().toString();
    }

    /**
     * Records that {@code src} is written into {@code tgt}, refusing a second write into one target
     * and a source written into two targets through different conversions.
     */
    private Refused write(final Tree row, final String src, final String tgt, final Object conversion) {
      if (!targetsWritten.add(tgt)) {
        return new Refused(row, PairingMessages.duplicateTargetRow(simple(source), simple(target), tgt));
      }
      if (drops.contains(src)) {
        return new Refused(row, "'" + src + "' is dropped by another row of this mapper, so no row can write it");
      }
      final var primary = written.get(src);
      if (primary == null) {
        written.put(src, tgt);
        conversions.put(src, conversion);
        if (!src.equals(tgt)) renames.put(src, tgt);
        return null;
      }
      if (!Objects.equals(conversions.get(src), conversion)) {
        return new Refused(
          row,
          "a @Bridge mapper writes one source property into several targets through one conversion;" +
            " property '" +
            src +
            "' is converted differently for '" +
            tgt +
            "', which the generated bridge has no form for yet"
        );
      }
      if (strict) {
        return new Refused(
          row,
          "Telescope.mapper(...) converts both ways, so property '" +
            src +
            "' can be written into only one target; write it into several with Telescope.mapperForward(...)"
        );
      }
      // The first target written is the rename the fan-out extends, the property's own name
      // included.
      renames.putIfAbsent(src, primary);
      fanouts.computeIfAbsent(src, unused -> new ArrayList<>()).add(tgt);
      return null;
    }

    private final Map<String, String> written = new LinkedHashMap<>();
    private final Map<String, Object> conversions = new LinkedHashMap<>();

    private String qualified(final String simple) {
      return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    /** The accessor a row argument names on {@code side}, or the refusal. */
    private Object accessor(final ExpressionTree arg, final TypeElement side) {
      if (!(arg instanceof MemberReferenceTree)) {
        return new Refused(
          arg,
          "a @Bridge mapper compiles a row whose accessors are method references (User::email); a telescope" +
            " path or another expression has no generated form yet"
        );
      }
      final var path = TreePath.getPath(unit, arg);
      if (!(trees.getElement(path) instanceof ExecutableElement accessor)) {
        return new Refused(arg, "the processor cannot resolve this accessor");
      }
      final var declaring = (TypeElement) accessor.getEnclosingElement();
      final var types = env.getTypeUtils();
      if (!types.isSubtype(types.erasure(side.asType()), types.erasure(declaring.asType()))) {
        return new Refused(
          arg,
          "this row reads " +
            declaring.getSimpleName() +
            "::" +
            accessor.getSimpleName() +
            ", which is not a property of " +
            side.getSimpleName() +
            "; a row keyed to a nested pair has no generated form yet"
        );
      }
      if (!propertyAccessor(accessor, declaring)) {
        return new Refused(
          arg,
          declaring.getSimpleName() +
            "::" +
            accessor.getSimpleName() +
            " is not the accessor of a property of " +
            side.getSimpleName() +
            "; a row names a record component, or a bean's getX() or isX()"
        );
      }
      final var declared = types.asMemberOf((DeclaredType) side.asType(), accessor);
      final var read = ((ExecutableType) declared).getReturnType();
      final var type = read.getKind().isPrimitive() ? types.boxedClass((PrimitiveType) read).asType() : read;
      final var method = accessor.getSimpleName().toString();
      return new Accessor(property(method, side), type, read.getKind().isPrimitive(), method, arg);
    }

    /**
     * The Java expression calling the method a reference names, on {@code argument} when the
     * reference takes one ({@code arity} 1), or with none ({@code arity} 0); or the refusal.
     */
    private Object call(final ExpressionTree arg, final String argument, final int arity) {
      if (!(arg instanceof MemberReferenceTree reference)) {
        return new Refused(
          arg,
          "a @Bridge mapper compiles a converter or supplier written as a method reference" +
            " (Conversions::trim, String::strip, UserId::new); this argument is not one"
        );
      }
      final var path = TreePath.getPath(unit, arg);
      if (!(trees.getElement(path) instanceof ExecutableElement method)) {
        return new Refused(arg, "the processor cannot resolve this method reference");
      }
      final var qualifier = reference.getQualifierExpression();
      final var qualifierElement = elementAt(unit, qualifier);
      final var params = method.getParameters().size();
      final var owner = (TypeElement) method.getEnclosingElement();
      final String text;
      if (reference.getMode() == MemberReferenceTree.ReferenceMode.NEW) {
        if (params != arity) return new Refused(
          arg,
          "this constructor reference does not take " + arity + " argument(s)"
        );
        final var generic = !owner.getTypeParameters().isEmpty();
        text = "new " + owner.getQualifiedName() + (generic ? "<>" : "") + "(" + (arity == 1 ? argument : "") + ")";
      } else if (method.getModifiers().contains(Modifier.STATIC)) {
        if (params != arity) return new Refused(arg, "this static method does not take " + arity + " argument(s)");
        text = owner.getQualifiedName() + "." + method.getSimpleName() + "(" + (arity == 1 ? argument : "") + ")";
      } else if (qualifierElement instanceof TypeElement) {
        if (arity != 1 || params != 0) {
          return new Refused(arg, "this instance method reference does not convert one value");
        }
        text = argument + "." + method.getSimpleName() + "()";
      } else if (
        qualifierElement instanceof VariableElement held &&
        held.getKind() == ElementKind.FIELD &&
        held.getModifiers().contains(Modifier.STATIC) &&
        held.getModifiers().contains(Modifier.FINAL)
      ) {
        if (params != arity) return new Refused(arg, "this method does not take " + arity + " argument(s)");
        final var heldOwner = (TypeElement) held.getEnclosingElement();
        text =
          heldOwner.getQualifiedName() +
          "." +
          held.getSimpleName() +
          "." +
          method.getSimpleName() +
          "(" +
          (arity == 1 ? argument : "") +
          ")";
        if (!reachable(held, heldOwner)) return new Refused(arg, unreachable(heldOwner, held.getSimpleName()));
      } else {
        return new Refused(
          arg,
          "a method reference on " +
            qualifier +
            " has a receiver the generated bridge cannot name; reference a static method, an unbound" +
            " instance method, a constructor, or a method of a static final field"
        );
      }
      if (!reachable(method, owner)) return new Refused(arg, unreachable(owner, method.getSimpleName()));
      return new Call(text, method);
    }

    /**
     * The Java source of a constant row's value, written into the property {@code into}, or the
     * refusal: a value that is not a literal, a literal cast to a primitive type, or an enum
     * constant, a {@code null} for a primitive property, or a value whose type the property does
     * not hold. A {@code short} or {@code byte} value is written as a cast literal, since Java
     * converts no {@code int} literal to {@code Short} or {@code Byte} in a method's argument.
     */
    private Object constantText(final ExpressionTree arg, final Accessor into) {
      final String text;
      if (arg instanceof LiteralTree literal && literal.getKind() == Tree.Kind.NULL_LITERAL) {
        if (into.primitive()) {
          return new Refused(
            arg,
            "the value null cannot be written into the property '" +
              into.property() +
              "', which holds a primitive; write a value of its type"
          );
        }
        return "null";
      } else if (arg instanceof LiteralTree literal) {
        text = literal.toString();
      } else if (
        arg instanceof UnaryTree unary &&
        unary.getKind() == Tree.Kind.UNARY_MINUS &&
        unary.getExpression() instanceof LiteralTree
      ) {
        text = "(" + unary + ")";
      } else if (
        arg instanceof TypeCastTree cast &&
        cast.getType() instanceof PrimitiveTypeTree &&
        (cast.getExpression() instanceof LiteralTree ||
          (cast.getExpression() instanceof UnaryTree negated &&
            negated.getKind() == Tree.Kind.UNARY_MINUS &&
            negated.getExpression() instanceof LiteralTree))
      ) {
        text = "(" + cast + ")";
      } else if (
        (arg instanceof IdentifierTree || arg instanceof MemberSelectTree) &&
        elementAt(unit, arg) instanceof VariableElement constant &&
        constant.getKind() == ElementKind.ENUM_CONSTANT
      ) {
        text = ((TypeElement) constant.getEnclosingElement()).getQualifiedName() + "." + constant.getSimpleName();
      } else {
        return new Refused(
          arg,
          "a value in a @Bridge mapper must be a literal or an enum constant, which the generated bridge" +
            " writes in place"
        );
      }
      // A property typed by its class's variable holds whatever the variable's bound holds; the
      // class written for the row then supplies the value as its own class.
      final var types = env.getTypeUtils();
      final var holds = nameable(into.type()) ? into.type() : types.erasure(into.type());
      final var valueType = trees.getTypeMirror(TreePath.getPath(unit, arg));
      if (!types.isAssignable(valueType, holds)) {
        return new Refused(
          arg,
          "the value " +
            arg +
            " is " +
            valueType +
            ", which the " +
            shown(into) +
            " property '" +
            into.property() +
            "' does not hold; write it as " +
            withArticle(shown(into)) +
            " value"
        );
      }
      return text;
    }

    /** {@code type} as a refusal names it after "a", or "an" before a vowel: "an int", "a long". */
    private static String withArticle(final TypeMirror type) {
      final var name = type.toString();
      return ("aeiouAEIOU".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + name;
    }

    /** The type a refusal names {@code into}'s property by: the primitive, where it holds one. */
    private TypeMirror shown(final Accessor into) {
      return into.primitive() ? env.getTypeUtils().unboxedType(into.type()) : into.type();
    }

    /** The refusal of a supplier whose result the property {@code into} does not hold, or null. */
    private Refused returnFits(final ExpressionTree arg, final ExecutableElement method, final Accessor into) {
      final var types = env.getTypeUtils();
      final var gives =
        method.getKind() == ElementKind.CONSTRUCTOR ? method.getEnclosingElement().asType() : method.getReturnType();
      if (types.isAssignable(types.erasure(gives), types.erasure(into.type()))) return null;
      return new Refused(
        arg,
        arg + " gives " + gives + ", which the " + shown(into) + " property '" + into.property() + "' does not hold"
      );
    }

    /**
     * Why a {@code BridgeFn} class a row names cannot be built from the generated bridge, or null
     * when it can: it is top-level, concrete, a {@code BridgeFn}, and has a no-argument constructor
     * the bridge's package can call.
     */
    private String unbuildable(final TypeElement named) {
      final var types = env.getTypeUtils();
      final var bridgeFn = env.getElementUtils().getTypeElement("io.github.eschizoid.telescope.conversion.BridgeFn");
      final var fix = "; declare it as a public top-level class with a public no-argument constructor";
      if (named.getNestingKind() != NestingKind.TOP_LEVEL || !reachable(named, named)) {
        return named.getSimpleName() + " cannot be built from the generated bridge in package '" + pkg + "'" + fix;
      }
      if (named.getKind() != ElementKind.CLASS || named.getModifiers().contains(Modifier.ABSTRACT)) {
        return named.getSimpleName() + " is abstract or not a class, so the generated bridge cannot build it" + fix;
      }
      if (bridgeFn != null && !types.isAssignable(types.erasure(named.asType()), types.erasure(bridgeFn.asType()))) {
        return named.getSimpleName() + " is not a BridgeFn, which a two-way conversion row takes";
      }
      final var constructors = ElementFilter.constructorsIn(named.getEnclosedElements());
      for (final var constructor : constructors) {
        if (constructor.getParameters().isEmpty() && reachable(constructor, named)) return null;
      }
      return named.getSimpleName() + " has no no-argument constructor the generated bridge can call" + fix;
    }

    /**
     * Whether code in the bridge's package can name {@code member} of {@code owner}: neither it nor
     * any class enclosing it is private, and each is public or in that package.
     */
    private boolean reachable(final Element member, final TypeElement owner) {
      final var samePackage = env.getElementUtils().getPackageOf(owner).getQualifiedName().contentEquals(pkg);
      for (Element at = member; at != null && at.getKind() != ElementKind.PACKAGE; at = at.getEnclosingElement()) {
        if (at.getModifiers().contains(Modifier.PRIVATE)) return false;
        if (!at.getModifiers().contains(Modifier.PUBLIC) && !samePackage) return false;
        if (at == owner && owner.getNestingKind() == NestingKind.TOP_LEVEL) break;
      }
      return true;
    }

    private String unreachable(final TypeElement owner, final CharSequence member) {
      return (
        owner.getSimpleName() +
        "." +
        member +
        " cannot be called from the generated bridge in package '" +
        pkg +
        "'; make it public, or move it into that package"
      );
    }

    private GeneratedClass bridgeFn(
      final String name,
      final TypeMirror from,
      final TypeMirror to,
      final String forward,
      final String backward
    ) {
      final var fnType = "io.github.eschizoid.telescope.conversion.BridgeFn<" + from + ", " + to + ">";
      final var out = new StringBuilder();
      if (!pkg.isEmpty()) out.append("package ").append(pkg).append(";\n\n");
      out
        .append("/** Generated by telescope-codegen: a conversion a row of ")
        .append(bridgeName)
        .append(" names. */\n")
        .append("public final class ")
        .append(name)
        .append(" implements ")
        .append(fnType)
        .append(" {\n\n")
        .append("  @Override\n  public ")
        .append(to)
        .append(" forward(final ")
        .append(from)
        .append(" v) {\n    return ")
        .append(forward)
        .append(";\n  }\n\n")
        .append("  @Override\n  public ")
        .append(from)
        .append(" backward(final ")
        .append(to)
        .append(" v) {\n");
      if (backward == null) {
        out.append("    throw new UnsupportedOperationException(\"the row converts forward only\");\n");
      } else {
        out.append("    return ").append(backward).append(";\n");
      }
      out.append("  }\n}\n");
      return new GeneratedClass(qualified(name), out.toString());
    }

    private GeneratedClass supplier(final String name, final TypeMirror type, final String value) {
      final var out = new StringBuilder();
      if (!pkg.isEmpty()) out.append("package ").append(pkg).append(";\n\n");
      out
        .append("/** Generated by telescope-codegen: a value a row of ")
        .append(bridgeName)
        .append(" fills the target with. */\n")
        .append("public final class ")
        .append(name)
        .append(" implements java.util.function.Supplier<")
        .append(type)
        .append("> {\n\n")
        .append("  @Override\n  public ")
        .append(type)
        .append(" get() {\n    return ")
        .append(value)
        .append(";\n  }\n}\n");
      return new GeneratedClass(qualified(name), out.toString());
    }

    /**
     * The refusal of a row whose conversion the processor writes as a class of its own, when that
     * class cannot name the type of one of {@code sides}, as a type variable of a generic class; or
     * null.
     */
    private Refused unnameable(final Accessor... sides) {
      for (final var side : sides) {
        if (!nameable(side.type())) {
          return new Refused(
            side.at(),
            "the type " +
              side.type() +
              " this accessor reads has no name the class the processor writes for the row's conversion" +
              " can use; convert it with a BridgeFn class, to(src, tgt, ItsFn.class), instead"
          );
        }
      }
      return null;
    }

    /**
     * Whether a class the processor writes can name {@code type}: a primitive, an array of a type
     * it can name, or a declared type whose every type argument it can name, a wildcard argument
     * included where its bound has a name. A type variable has none outside its declaration.
     */
    private boolean nameable(final TypeMirror type) {
      if (type.getKind() == TypeKind.ARRAY) return nameable(((ArrayType) type).getComponentType());
      if (type instanceof WildcardType wildcard) {
        final var bound = wildcard.getExtendsBound() != null ? wildcard.getExtendsBound() : wildcard.getSuperBound();
        return bound == null || nameable(bound);
      }
      if (type instanceof DeclaredType declared) {
        for (final var argument : declared.getTypeArguments()) if (!nameable(argument)) return false;
        return true;
      }
      return type.getKind().isPrimitive();
    }
  }

  /**
   * A property a row's method reference reads: its name, its type, boxed where the accessor returns
   * a primitive, which {@code primitive} says, the accessor's name, and the reference itself.
   */
  private record Accessor(String property, TypeMirror type, boolean primitive, String method, Tree at) {}

  /**
   * Whether {@code accessor} of {@code declaring} reads a property: a record's component accessor,
   * or a bean getter ({@code getX()}, or {@code isX()} returning a boolean) that no class but
   * {@code Object} declares.
   */
  private static boolean propertyAccessor(final ExecutableElement accessor, final TypeElement declaring) {
    if (!accessor.getParameters().isEmpty() || accessor.getModifiers().contains(Modifier.STATIC)) return false;
    if (accessor.getReturnType().getKind() == TypeKind.VOID) return false;
    final var name = accessor.getSimpleName().toString();
    if (declaring.getKind() == ElementKind.RECORD) {
      for (final var component : declaring.getRecordComponents()) {
        if (component.getSimpleName().contentEquals(name)) return true;
      }
      return false;
    }
    if (declaring.getQualifiedName().contentEquals("java.lang.Object")) return false;
    if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) return true;
    return name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2));
  }

  /** The simple name of the method a call selects, whatever qualifies it. */
  private static String selectedName(final MethodInvocationTree call) {
    final var select = call.getMethodSelect();
    if (select instanceof MemberSelectTree member) return member.getIdentifier().toString();
    return select instanceof IdentifierTree name ? name.getName().toString() : "";
  }

  private record Call(String expression, ExecutableElement method) {}

  private static String simple(final TypeElement type) {
    return type.getSimpleName().toString();
  }

  private Element elementAt(final CompilationUnitTree unit, final Tree tree) {
    final var path = TreePath.getPath(unit, tree);
    return path == null ? null : trees.getElement(path);
  }

  /** The property an accessor method reads, named as the verifier and the runtime name it. */
  private static String property(final String method, final TypeElement side) {
    return side.getKind() == ElementKind.RECORD ? method : PropertyNames.property(method);
  }
}
