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
import io.github.eschizoid.telescope.internal.pairing.MergeReaders;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import io.github.eschizoid.telescope.internal.pairing.SourceWrites;
import io.github.eschizoid.telescope.internal.pairing.TextFormats;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * pair, and those of a {@code Telescope.merge(...)} initializer into the rows its bridge is written
 * from. A row the generated bridge cannot express is refused by name, on the row.
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

  /** The name of the source a generated bridge's {@code forward} is handed. */
  static final String SOURCE = "s";

  private static final String MAPPING_FQN = "io.github.eschizoid.telescope.mapping.Mapping";
  private static final String WRITE_HINT_FQN = "io.github.eschizoid.telescope.mapping.WriteHint";
  private static final String BRIDGE_FQN = "io.github.eschizoid.telescope.annotations.Bridge";
  private static final Set<String> FACTORIES = Set.of("map", "mapper", "mapperForward", "merge");
  private static final String MERGE_STEP_FQN = "io.github.eschizoid.telescope.mapping.MergeStep";
  private static final Set<String> DISPATCHING = Set.of("mapper", "mapperForward");
  private static final Set<String> ROW_FACTORIES = Set.of(
    "to",
    "toOneWay",
    "toOrElse",
    "toOrElseGet",
    "toFormatted",
    "toFormattedOneWay",
    "toFormattedNumber",
    "toFormattedNumberOneWay",
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
  sealed interface Result permits Parsed, Merged, Refused {}

  /**
   * The rows of a {@code Telescope.merge(...)} initializer building {@code target} from several
   * sources: each row, in order, and the classes of the sources, in the order the rows first name
   * them, which the generated {@code forward} takes as its parameters. {@code rowKinds} and {@code
   * rowAccessors} are what the generated provider records, as a mapper's are.
   */
  record Merged(
    TypeElement target,
    List<MergeRow> rows,
    List<TypeElement> sources,
    List<String> rowKinds,
    List<String> rowAccessors
  ) implements Result {}

  /**
   * One merge row, at {@code index} among the rows: {@code from(Source::read, Target::written)},
   * naming its source class, the method it reads and the target property it writes, or {@code
   * auto(Source.class)}, naming its source class alone, with a null {@code read} and {@code
   * written}. {@code at} is the tree a refusal of the row is reported on.
   */
  record MergeRow(int index, TypeElement source, ExecutableElement read, String written, Tree at) {}

  /**
   * The configuration a field's rows declare. {@code classes} are the converter and supplier
   * classes the rows need written; {@code awaits} the classes, written by this compilation, that
   * must exist before the bridge is planned. {@code computedFrom} holds, by target property, the
   * expression a {@code compute} row reading the source fills it with: a direct call on {@link
   * #SOURCE}, the source a generated {@code forward} is handed. {@code rowKinds} are the classes of
   * the rows the runtime builds from the same call, in order, and {@code rowAccessors} the accessor
   * methods each names, as {@code source>target}; the generated provider records both. {@code
   * beanStrategies} are the write strategies {@code writeBean} rows name, by the qualified name of
   * the class each is keyed to, and {@code defaultStrategy} the one a {@code writeBeans} row names,
   * or null.
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
    Map<String, String> computedFrom,
    List<String> rowKinds,
    List<String> rowAccessors,
    List<GeneratedClass> classes,
    Set<String> awaits,
    Map<String, String> beanStrategies,
    String defaultStrategy,
    List<SourcePath> sourcePaths,
    List<TargetPath> targetPaths,
    List<PathToPath> pathsToPaths,
    Map<String, Gate> gatedTargets,
    Map<Integer, Gate> gatedPaths
  ) implements Result {
    /** Every target property a {@code compute} row fills, from a supplier or from the source. */
    Set<String> computedTargets() {
      final var computed = new LinkedHashSet<>(computes.keySet());
      computed.addAll(computedFrom.keySet());
      return computed;
    }

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

  /**
   * A row reading the source through a path into the target property {@code target}: the properties
   * the path steps through from the source, in order, and the method each is read by. {@code order}
   * is the row's place among the field's rows.
   */
  record SourcePath(String target, List<String> properties, List<String> methods, int order) {
    /** The properties the path steps through, joined with dots, as the shared spec names it. */
    String name() {
      return String.join(".", properties);
    }
  }

  /**
   * A row writing the source property {@code source} into the target through a path: the properties
   * the path steps through from the target, in order, and the method each is read by. {@code order}
   * is the row's place among the field's rows.
   */
  record TargetPath(String source, List<String> properties, List<String> methods, int order) {
    /** The properties the path steps through, joined with dots, as the shared spec names it. */
    String name() {
      return String.join(".", properties);
    }
  }

  /**
   * A row reading the source through one path and writing the value into the target through
   * another: {@code read} steps through the source, with no target property, and {@code written}
   * through the target, with no source property. Both carry the row's place among the field's rows.
   */
  record PathToPath(SourcePath read, TargetPath written) {}

  /**
   * The predicate a {@code when(...)} row gates its inner row on, and what a failure of it names:
   * {@code predicate} is a {@code Predicate} written as Java, a {@code static final} field holding
   * one when {@code held}, and otherwise a method reference that takes a cast to the type it tests;
   * {@code inner} the class of the row the runtime builds for the inner row, and {@code
   * sourceField} the method name of the source accessor it names, or null. A gated row's target
   * property is the key of {@link Parsed#gatedTargets}, and a gated path's place among the rows the
   * key of {@link Parsed#gatedPaths}.
   */
  record Gate(String predicate, boolean held, String inner, String sourceField) {
    /** The Java expression testing {@code value}, of type {@code type}. */
    String test(final String type, final String value) {
      return held
        ? predicate + ".test(" + value + ")"
        : "((java.util.function.Predicate<? super " + type + ">) " + predicate + ").test(" + value + ")";
    }
  }

  /** The rows a {@code when(...)} row gates, by the class the runtime builds for each. */
  private static final Set<String> GATED = Set.of(
    "TelescopeTo",
    "FromTelescopeTo",
    "TelescopeToTelescope",
    "Constant",
    "Compute",
    "ComputeFromSource"
  );

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
          " must be initialised by a Telescope.mapper(...), Telescope.mapperForward(...) or Telescope.merge(...)" +
          " call written in place, whose rows the processor compiles into the bridge"
      );
    }
    final var call = (MethodInvocationTree) variable.getInitializer();
    if (factory.equals("merge")) return merged(unit, call, fieldName);
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
    final var fannedOut = rows.fanOut();
    if (fannedOut != null) return fannedOut;
    final var offThePair = rows.hintsOffThePair();
    if (offThePair != null) return offThePair;
    return rows.parsed();
  }

  /**
   * The rows of a {@code Telescope.merge(Target.class, rows...)} call written in place: each a
   * {@code MergeStep.from} of two method references or an {@code auto} of a class literal, into a
   * public record. Every other row, and every other target, is refused by name.
   */
  private Result merged(final CompilationUnitTree unit, final MethodInvocationTree call, final String fieldName) {
    final var args = call.getArguments();
    final var target = args.isEmpty() ? null : classLiteral(unit, args.getFirst());
    if (target == null) {
      return new Refused(call, "@Bridge field " + fieldName + " must name its target class as a class literal");
    }
    if (target.getKind() != ElementKind.RECORD || !target.getModifiers().contains(Modifier.PUBLIC)) {
      return new Refused(
        args.getFirst(),
        "a @Bridge merge builds a public record, and " +
          target.getSimpleName() +
          " is not one; build this mapper without @Bridge to run it on the runtime path"
      );
    }
    final var rows = new ArrayList<MergeRow>();
    final var sources = new LinkedHashSet<TypeElement>();
    final var kinds = new ArrayList<String>();
    final var accessors = new ArrayList<String>();
    for (var i = 1; i < args.size(); i++) {
      final var index = i - 1;
      final var arg = args.get(i);
      if (
        !(arg instanceof MethodInvocationTree row) ||
        !(elementAt(unit, row.getMethodSelect()) instanceof ExecutableElement step) ||
        !(step.getEnclosingElement() instanceof TypeElement owner) ||
        !owner.getQualifiedName().contentEquals(MERGE_STEP_FQN)
      ) {
        return new Refused(
          arg,
          "a @Bridge merge compiles only rows written in place as MergeStep.from(Source::property," +
            " Target::property) or MergeStep.auto(Source.class)"
        );
      }
      final var rowArgs = row.getArguments();
      if (step.getSimpleName().contentEquals("auto")) {
        final var source = classLiteral(unit, rowArgs.getFirst());
        if (source == null) {
          return new Refused(
            rowArgs.getFirst(),
            "a @Bridge merge compiles auto(...) of a class literal, as Customer.class"
          );
        }
        sources.add(source);
        rows.add(new MergeRow(index, source, null, null, row));
        kinds.add("AutoSameName");
        accessors.add(env.getElementUtils().getBinaryName(source) + ">");
        continue;
      }
      for (final var reference : rowArgs) {
        if (!(reference instanceof MemberReferenceTree)) {
          return new Refused(
            reference,
            "a @Bridge merge compiles from(...) of two method references, as from(Customer::name, Profile::name)," +
              " so both paths read the same accessor"
          );
        }
      }
      final var read = trees.getElement(TreePath.getPath(unit, rowArgs.get(0)));
      final var written = trees.getElement(TreePath.getPath(unit, rowArgs.get(1)));
      if (!(read instanceof ExecutableElement reader) || !(written instanceof ExecutableElement writer)) {
        return Refused.javacReports(row);
      }
      // The reader names the source the row reads only when it is an accessor of it: an unbound
      // reference to an instance method taking no argument.
      final var qualifier = elementAt(unit, ((MemberReferenceTree) rowArgs.get(0)).getQualifierExpression());
      final var notAnAccessor = MergeReaders.refusal(
        index,
        reader.getEnclosingElement().getSimpleName().toString(),
        reader.getSimpleName().toString(),
        qualifier instanceof TypeElement &&
          !reader.getModifiers().contains(Modifier.STATIC) &&
          reader.getParameters().isEmpty()
      );
      if (notAnAccessor != null) return new Refused(rowArgs.get(0), notAnAccessor);
      final var source = (TypeElement) qualifier;
      sources.add(source);
      rows.add(new MergeRow(index, source, reader, writer.getSimpleName().toString(), row));
      kinds.add("FromInferred");
      accessors.add(reader.getSimpleName() + ">" + writer.getSimpleName());
    }
    return new Merged(target, List.copyOf(rows), List.copyOf(sources), List.copyOf(kinds), List.copyOf(accessors));
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
    private final Map<String, String> computedFrom = new LinkedHashMap<>();
    private final Set<String> targetsWritten = new LinkedHashSet<>();
    private final SourceWrites sourceWrites;
    private final List<String> rowKinds = new ArrayList<>();
    private final List<String> rowAccessors = new ArrayList<>();
    private final List<GeneratedClass> classes = new ArrayList<>();
    private final Set<String> awaits = new LinkedHashSet<>();
    private final Map<String, String> beanStrategies = new LinkedHashMap<>();
    private String defaultStrategy;
    private final List<SourcePath> sourcePaths = new ArrayList<>();
    private final List<TargetPath> targetPaths = new ArrayList<>();
    private final List<PathToPath> pathsToPaths = new ArrayList<>();

    /** How many rows have been read, which is the place of the row being read. */
    private int order;

    /** Whether the row being read is the inner row of a {@code when(...)}, which claims nothing. */
    private boolean gating;

    /** The target property the last constant or compute row read fills. */
    private String lastFilled;

    private final Map<String, Gate> gatedTargets = new LinkedHashMap<>();
    private final Map<Integer, Gate> gatedPaths = new LinkedHashMap<>();

    /** The classes a path of these rows steps through below its root, by qualified name. */
    private final Set<String> pathIntermediates = new HashSet<>();

    /** The refusal of each writeBean hint keyed to a class other than the pair's, by that class. */
    private final Map<String, Refused> keyedElsewhere = new LinkedHashMap<>();

    /**
     * The refusal of the first writeBean hint keyed to a class neither side of the pair is and no
     * path steps through, or null.
     */
    Refused hintsOffThePair() {
      for (final var keyed : keyedElsewhere.entrySet()) {
        if (!pathIntermediates.contains(keyed.getKey())) return keyed.getValue();
      }
      return null;
    }

    /** Records the classes {@code hops} steps through below its root. */
    private void stepsThrough(final List<?> hops) {
      for (final var hop : hops.subList(0, hops.size() - 1)) {
        if (((Accessor) hop).type() instanceof DeclaredType declared) {
          pathIntermediates.add(((TypeElement) declared.asElement()).getQualifiedName().toString());
        }
      }
    }

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
      this.sourceWrites = new SourceWrites(simple(source), simple(target), strict);
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
        computedFrom,
        rowKinds,
        rowAccessors,
        classes,
        awaits,
        beanStrategies,
        defaultStrategy,
        sourcePaths,
        targetPaths,
        pathsToPaths,
        gatedTargets,
        gatedPaths
      );
    }

    /** Reads one row, or answers why it cannot be compiled. */
    Refused read(final ExpressionTree arg) {
      order++;
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
            " to, toOneWay, toOrElse, toOrElseGet, toFormatted, toFormattedOneWay, toFormattedNumber," +
            " toFormattedNumberOneWay, via, drop, constant, compute and when, and the hints writeBean and" +
            " writeBeans"
        );
      }
      final var rowArgs = row.getArguments();
      final var kind = rowExec.getSimpleName().toString();
      final var computeForm = kind.equals("compute") ? ComputeForms.computeForm(rowExec) : null;
      if (ComputeForms.FROM_SOURCE_FQN.equals(computeForm) && rowArgs.get(1) instanceof LambdaExpressionTree lambda) {
        return new Refused(
          lambda,
          "an inline lambda can't be compiled into the generated bridge; write the compute row's function as" +
            " a method reference the bridge calls directly, such as a static method taking the source" +
            " (Names::full) or a method of the source (User::initials)"
        );
      }
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
      return switch (kind) {
        case "to" -> switch (rowArgs.size()) {
          case 2 -> pathParameter(rowExec, 0)
            ? pathParameter(rowExec, 1)
              ? pathToPath(row)
              : sourcePath(row)
            : pathParameter(rowExec, 1)
              ? targetPath(row)
              : passThrough(row);
          case 3 -> twoWay(row);
          default -> converting(row, true);
        };
        case "toOneWay" -> converting(row, false);
        case "toOrElse", "toOrElseGet" -> orElse(row, kind);
        case "when" -> gated(row);
        case "toFormatted", "toFormattedOneWay", "toFormattedNumber", "toFormattedNumberOneWay" -> formatted(row, kind);
        case "via" -> via(row);
        case "drop" -> drop(row);
        case "constant" -> filled(row, true);
        case "compute" -> ComputeForms.FROM_SOURCE_FQN.equals(computeForm) ? computedFrom(row) : filled(row, false);
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

    /**
     * A {@code to(path, accessor)} row: the target property the path's value is written into, and
     * the properties the path steps through from the source.
     */
    private Refused sourcePath(final MethodInvocationTree row) {
      final var args = row.getArguments();
      final var path = steps(args.get(0), source);
      if (path instanceof Refused refused) return refused;
      final var hops = (List<?>) path;
      final var tgt = accessor(args.get(1), target);
      if (tgt instanceof Refused refused) return refused;
      final var t = (Accessor) tgt;
      final var leaf = (Accessor) hops.getLast();
      final var types = env.getTypeUtils();
      if (!types.isAssignable(leaf.type(), t.type())) {
        return new Refused(
          args.get(0),
          "the path reads " +
            shown(leaf) +
            ", which the " +
            shown(t) +
            " property '" +
            t.property() +
            "' does not hold; end the path at a property of that type"
        );
      }
      final var written = sourcePathOf(t.property(), hops);
      final var named = written.name();
      if (!targetsWritten.add(t.property())) {
        return new Refused(row, PairingMessages.duplicateTargetRow(simple(source), simple(target), t.property()));
      }
      if (!gating) {
        final var claimed = sourceWrites.write(named, t.property());
        if (claimed != null) return new Refused(row, claimed);
        if (sourceWrites.fannedOut(named)) fannedOutAt.putIfAbsent(named, row);
      }
      sourcePaths.add(written);
      stepsThrough(hops);
      rowKinds.add("FromTelescopeTo");
      rowAccessors.add(named + ">" + t.method());
      return null;
    }

    /**
     * A {@code to(accessor, path)} row: the source property written through the path, and the
     * properties the path steps through from the target.
     */
    private Refused targetPath(final MethodInvocationTree row) {
      final var args = row.getArguments();
      final var src = accessor(args.get(0), source);
      if (src instanceof Refused refused) return refused;
      final var s = (Accessor) src;
      final var path = steps(args.get(1), target);
      if (path instanceof Refused refused) return refused;
      final var hops = (List<?>) path;
      final var leaf = (Accessor) hops.getLast();
      final var types = env.getTypeUtils();
      if (!types.isAssignable(s.type(), leaf.type()) || !types.isAssignable(leaf.type(), s.type())) {
        return new Refused(
          args.get(1),
          "the path writes " +
            shown(leaf) +
            ", which is not the " +
            shown(s) +
            " the property '" +
            s.property() +
            "' holds; end the path at a property of that type, which backward reads back into it"
        );
      }
      final var written = targetPathOf(s.property(), hops);
      final var named = written.name();
      if (!gating) {
        final var claimed = sourceWrites.write(s.property(), named);
        if (claimed != null) return new Refused(row, claimed);
        if (sourceWrites.fannedOut(s.property())) fannedOutAt.putIfAbsent(s.property(), row);
      }
      targetPaths.add(written);
      stepsThrough(hops);
      rowKinds.add("TelescopeTo");
      rowAccessors.add(s.method() + ">" + named);
      return null;
    }

    /**
     * A {@code when(predicate, row)} row: the inner row, read as it is read on its own but claiming
     * nothing, gated on the predicate, which forward tests on the source. The runtime refuses a
     * nested {@code when} and any row it does not gate, and so does the reader, in the same words.
     * The predicate is a method reference or a {@code static final} field holding one.
     */
    private Refused gated(final MethodInvocationTree row) {
      final var args = row.getArguments();
      if (
        !(args.get(1) instanceof MethodInvocationTree inner) ||
        !(elementAt(unit, inner.getMethodSelect()) instanceof ExecutableElement innerExec) ||
        !(innerExec.getEnclosingElement() instanceof TypeElement innerOwner) ||
        !innerOwner.getQualifiedName().contentEquals(MAPPING_FQN)
      ) {
        return new Refused(
          args.get(1),
          "a @Bridge mapper compiles a when(...) row whose inner row is written in place as a Mapping factory call"
        );
      }
      final var kind = innerKind(innerExec, inner);
      if (kind.equals("Conditional")) return new Refused(row, PairingMessages.whenNested());
      final var innerArgs = inner.getArguments();
      if (!GATED.contains(kind)) {
        return new Refused(
          row,
          PairingMessages.whenWrapsOnly(
            kind,
            referencedName(innerArgs.get(0)),
            kind.equals("Drop") ? null : referencedName(innerArgs.get(1))
          )
        );
      }
      final var predicate = predicateValue(args.get(0));
      if (predicate instanceof Refused refused) return refused;
      final var sourceField = kind.equals("TelescopeTo") ? referencedName(innerArgs.get(0)) : null;
      // The inner row takes the place of the when(...) row among the rows.
      order--;
      gating = true;
      lastFilled = null;
      final var refused = read(inner);
      gating = false;
      if (refused != null) return refused;
      final var gate = new Gate(((Gate) predicate).predicate(), ((Gate) predicate).held(), kind, sourceField);
      if (kind.startsWith("Compute") || kind.equals("Constant")) {
        gatedTargets.put(lastFilled, gate);
      } else {
        gatedPaths.put(order, gate);
      }
      // The runtime builds one Conditional row, which names the inner row's flat accessors alone.
      rowKinds.set(rowKinds.size() - 1, "Conditional");
      rowAccessors.set(
        rowAccessors.size() - 1,
        (kind.equals("TelescopeTo") ? sourceField : "") +
          ">" +
          (kind.equals("FromTelescopeTo") ? referencedName(innerArgs.get(1)) : "")
      );
      return null;
    }

    /**
     * The class of the row the runtime builds for {@code row}, a call of the factory {@code exec}.
     */
    private String innerKind(final ExecutableElement exec, final MethodInvocationTree row) {
      final var args = row.getArguments();
      return switch (exec.getSimpleName().toString()) {
        case "to" -> args.size() != 2
          ? "TypedTransformTo"
          : pathParameter(exec, 0)
            ? pathParameter(exec, 1)
              ? "TelescopeToTelescope"
              : "FromTelescopeTo"
            : pathParameter(exec, 1)
              ? "TelescopeTo"
              : "SameTypedTo";
        case "toOneWay" -> "ForwardOnlyTransformTo";
        case "via" -> args.size() == 4 ? "ViaMethods" : classLiteral(unit, args.get(2)) != null ? "ViaClass" : "Via";
        case "drop" -> "Drop";
        case "constant" -> "Constant";
        case "compute" -> ComputeForms.FROM_SOURCE_FQN.equals(ComputeForms.computeForm(exec))
          ? "ComputeFromSource"
          : "Compute";
        case "zip" -> "TelescopeToTelescope";
        case "when" -> "Conditional";
        default -> "TypedTransformTo";
      };
    }

    /** The name of the method {@code arg} references, or null when it is not a method reference. */
    private static String referencedName(final ExpressionTree arg) {
      return arg instanceof MemberReferenceTree reference ? reference.getName().toString() : null;
    }

    /**
     * The predicate {@code arg} names, as a {@link Gate} carrying only its Java text: a {@code
     * static final} field holding a predicate, or a method reference written with its class in
     * full, which the bridge calls through the same kind of predicate the runtime does; or the
     * refusal.
     */
    private Object predicateValue(final ExpressionTree arg) {
      if (
        (arg instanceof IdentifierTree || arg instanceof MemberSelectTree) &&
        elementAt(unit, arg) instanceof VariableElement held &&
        held.getKind() == ElementKind.FIELD
      ) {
        if (!held.getModifiers().contains(Modifier.STATIC) || !held.getModifiers().contains(Modifier.FINAL)) {
          return new Refused(
            arg,
            "a @Bridge mapper tests a predicate the generated bridge can name: a method reference" +
              " (Orders::shipped), or a static final field holding the predicate; " +
              held.getSimpleName() +
              " is not static and final"
          );
        }
        final var owner = (TypeElement) held.getEnclosingElement();
        if (!reachable(held, owner)) return new Refused(arg, unreachable(owner, held.getSimpleName()));
        return new Gate(owner.getQualifiedName() + "." + held.getSimpleName(), true, null, null);
      }
      if (!(arg instanceof MemberReferenceTree reference)) {
        return new Refused(
          arg,
          "a @Bridge mapper tests a predicate written as a method reference (Orders::shipped, String::isBlank)," +
            " or a static final field holding one; this argument is neither"
        );
      }
      final var checked = call(arg, "v", 1);
      if (checked instanceof Refused refused) return refused;
      final var qualifier = elementAt(unit, reference.getQualifierExpression());
      final String receiver;
      if (qualifier instanceof TypeElement type) {
        receiver = type.getQualifiedName().toString();
      } else {
        // A reference call(...) accepts is on a type or on a static final field.
        final var held = (VariableElement) qualifier;
        receiver = ((TypeElement) held.getEnclosingElement()).getQualifiedName() + "." + held.getSimpleName();
      }
      return new Gate(receiver + "::" + reference.getName(), false, null, null);
    }

    /**
     * A {@code to(path, path)} row: the properties the source path steps through, read into the
     * value the target path writes. Both paths end at a property of one type, since forward writes
     * the value the one reads through the other and backward writes it back. The row claims no
     * source property and no target property, as the runtime mapper's claims none: the first
     * property of each path pairs by name, or not, as it would without the row, and the value is
     * written over it.
     */
    private Refused pathToPath(final MethodInvocationTree row) {
      final var args = row.getArguments();
      final var read = steps(args.get(0), source);
      if (read instanceof Refused refused) return refused;
      final var written = steps(args.get(1), target);
      if (written instanceof Refused refused) return refused;
      final var readHops = (List<?>) read;
      final var writtenHops = (List<?>) written;
      final var from = (Accessor) readHops.getLast();
      final var into = (Accessor) writtenHops.getLast();
      final var types = env.getTypeUtils();
      if (!types.isAssignable(from.type(), into.type()) || !types.isAssignable(into.type(), from.type())) {
        return new Refused(
          args.get(1),
          "the path writes " +
            shown(into) +
            ", which is not the " +
            shown(from) +
            " the source path reads; end both paths at a property of one type, which backward reads back" +
            " across"
        );
      }
      final var readPath = sourcePathOf(null, readHops);
      final var writtenPath = targetPathOf(null, writtenHops);
      pathsToPaths.add(new PathToPath(readPath, writtenPath));
      stepsThrough(readHops);
      stepsThrough(writtenHops);
      rowKinds.add("TelescopeToTelescope");
      rowAccessors.add(readPath.name() + ">" + writtenPath.name());
      return null;
    }

    /** The source path {@code hops} step through into {@code target}, at this row's place. */
    private SourcePath sourcePathOf(final String target, final List<?> hops) {
      final var properties = new ArrayList<String>();
      final var methods = new ArrayList<String>();
      for (final var hop : hops) {
        properties.add(((Accessor) hop).property());
        methods.add(((Accessor) hop).method());
      }
      return new SourcePath(target, List.copyOf(properties), List.copyOf(methods), order);
    }

    /** The target path {@code hops} step through from {@code source}, at this row's place. */
    private TargetPath targetPathOf(final String source, final List<?> hops) {
      final var properties = new ArrayList<String>();
      final var methods = new ArrayList<String>();
      for (final var hop : hops) {
        properties.add(((Accessor) hop).property());
        methods.add(((Accessor) hop).method());
      }
      return new TargetPath(source, List.copyOf(properties), List.copyOf(methods), order);
    }

    /**
     * The properties {@code arg}, a telescope path written in place from {@code Telescope.of} or
     * {@code Telescope.ofBean}, steps through from {@code side}, each the accessor a {@code field}
     * step reads it by; or the refusal of any other expression or step.
     */
    private Object steps(final ExpressionTree arg, final TypeElement side) {
      final var calls = new ArrayList<MethodInvocationTree>();
      ExpressionTree at = arg;
      while (at instanceof MethodInvocationTree call) {
        calls.addFirst(call);
        at = call.getMethodSelect() instanceof MemberSelectTree select ? select.getExpression() : null;
      }
      final var written =
        "a @Bridge mapper compiles a path written in place as Telescope.of(Type.class) or" +
        " Telescope.ofBean(Type.class) followed by field(...) steps, or as Telescope.path(...) of" +
        " method references, each through a property";
      if (calls.isEmpty() || !(elementAt(unit, calls.getFirst().getMethodSelect()) instanceof ExecutableElement root)) {
        return new Refused(arg, written + "; this argument is not such a path");
      }
      final var rootOwner = (TypeElement) root.getEnclosingElement();
      final var rootName = root.getSimpleName().toString();
      final var shorthand = rootOwner.getQualifiedName().contentEquals(TELESCOPE_FQN) && rootName.equals("path");
      final var rooted = shorthand
        ? pathRoot(calls.getFirst())
        : calls.getFirst().getArguments().size() == 1
          ? classLiteral(unit, calls.getFirst().getArguments().getFirst())
          : null;
      if (
        !rootOwner.getQualifiedName().contentEquals(TELESCOPE_FQN) ||
        !(shorthand || rootName.equals("of") || rootName.equals("ofBean")) ||
        rooted == null
      ) {
        return new Refused(arg, written + "; this path starts at " + calls.getFirst().getMethodSelect() + "(...)");
      }
      if (!shorthand && calls.size() < 2) return new Refused(arg, written + "; this argument is not such a path");
      // Each accessor a path(...) call takes is the step a field(...) call would take, in order.
      // The tree a step's refusal is reported on: the field(...) call, or the accessor path(...)
      // takes.
      final var references = new ArrayList<ExpressionTree>();
      final var stepsAt = new ArrayList<Tree>();
      if (shorthand) {
        references.addAll(calls.getFirst().getArguments());
        stepsAt.addAll(calls.getFirst().getArguments());
      }
      final var types = env.getTypeUtils();
      if (!types.isSubtype(types.erasure(side.asType()), types.erasure(rooted.asType()))) {
        return new Refused(
          calls.getFirst(),
          "this path starts at " +
            rooted.getSimpleName() +
            ", which is not " +
            side.getSimpleName() +
            " or a class it extends; start it at " +
            side.getSimpleName()
        );
      }
      for (final var step : calls.subList(1, calls.size())) {
        final var name = selectedName(step);
        if (!name.equals("field") || step.getArguments().size() != 1) {
          return new Refused(
            step,
            name +
              "(...) in a path has no generated form; " +
              written +
              ", so build this mapper without @Bridge to run it on the runtime path"
          );
        }
        references.add(step.getArguments().getFirst());
        stepsAt.add(step);
      }
      final var hops = new ArrayList<Accessor>();
      var on = (DeclaredType) side.asType();
      TypeMirror reached = on;
      for (var i = 0; i < references.size(); i++) {
        final var reference = references.get(i);
        final var step = stepsAt.get(i);
        if (reference instanceof LambdaExpressionTree) {
          return new Refused(
            reference,
            "an inline lambda in a path can't be compiled into the generated bridge; name the property with a" +
              " method reference, such as Customer::city, so both paths read the same accessor"
          );
        }
        if (on == null) {
          return new Refused(
            step,
            "this step reads a property of " +
              (reached.getKind() == TypeKind.TYPEVAR ? "the type variable " + reached : reached) +
              ", which is not a class with properties"
          );
        }
        final var hop = accessor(reference, (TypeElement) on.asElement(), on);
        if (hop instanceof Refused refused) return refused;
        final var read = (Accessor) hop;
        hops.add(read);
        reached = read.type();
        on = !read.primitive() && read.type() instanceof DeclaredType declared ? declared : null;
      }
      return hops;
    }

    /**
     * The class a {@code Telescope.path(...)} call starts at: the first type argument of the {@code
     * Telescope<A, Z>} javac gives the call, which is the class the first accessor is applied to
     * where it is written, as the runtime reads it from that accessor's instantiation; or null when
     * javac gives the call no such class.
     */
    private TypeElement pathRoot(final MethodInvocationTree call) {
      final var typed = trees.getTypeMirror(TreePath.getPath(unit, call));
      if (!(typed instanceof DeclaredType telescope) || telescope.getTypeArguments().isEmpty()) return null;
      final var root = env.getTypeUtils().erasure(telescope.getTypeArguments().getFirst());
      return env.getTypeUtils().asElement(root) instanceof TypeElement type ? type : null;
    }

    /** Whether the factory's parameter at {@code index} is a telescope path. */
    private boolean pathParameter(final ExecutableElement factory, final int index) {
      final var parameter = env.getTypeUtils().erasure(factory.getParameters().get(index).asType());
      return (
        env.getTypeUtils().asElement(parameter) instanceof TypeElement type &&
        type.getQualifiedName().contentEquals(TELESCOPE_FQN)
      );
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
     * A formatted row, {@code kind}: the source property written into the target's {@code String}
     * property through a pattern, which must be a string constant, with a zone or a locale held in
     * a {@code static final} field the bridge can name. The shared spec decides the pattern for the
     * property's class as the runtime decides it, and the processor writes the row's conversion as
     * a {@code BridgeFn} class holding the formatter as a constant.
     */
    private Refused formatted(final MethodInvocationTree row, final String kind) {
      final var args = row.getArguments();
      final var src = accessor(args.get(0), source);
      if (src instanceof Refused refused) return refused;
      final var tgt = accessor(args.get(1), target);
      if (tgt instanceof Refused refused) return refused;
      final var s = (Accessor) src;
      final var t = (Accessor) tgt;
      final var oneWay = TextFormats.oneWay(kind);
      if (oneWay && strict) {
        return new Refused(
          row,
          PairingMessages.formatOneWayInMapper("Telescope.mapper", simple(source), simple(target), kind, s.property())
        );
      }
      final var pattern = constantString(args.get(2));
      if (pattern == null) {
        return new Refused(
          args.get(2),
          "a @Bridge mapper takes the pattern of a " +
            kind +
            " row as a string constant, which the processor checks and the generated bridge holds; this" +
            " argument is not one"
        );
      }
      final var number = TextFormats.formatsNumber(kind);
      final String given;
      if (args.size() == 4) {
        given = heldField(args.get(3));
        if (given == null) {
          return new Refused(
            args.get(3),
            "a @Bridge mapper takes the " +
              (number ? "locale" : "zone") +
              " of a " +
              kind +
              " row as a static final field the generated bridge can name, such as " +
              (number ? "Locale.GERMANY" : "ZoneOffset.UTC") +
              "; this argument is not one"
          );
        }
      } else {
        given = null;
      }
      final var types = env.getTypeUtils();
      if (!(s.type() instanceof DeclaredType declared)) {
        return new Refused(
          args.get(0),
          "the type " + s.type() + " this accessor reads is no class a formatted row takes"
        );
      }
      final var typeElement = (TypeElement) declared.asElement();
      final var typeName = env.getElementUtils().getBinaryName(typeElement).toString();
      final var shown = s.primitive() ? types.unboxedType(s.type()).toString() : typeElement.getSimpleName().toString();
      final var refusal = TextFormats.refusal(kind, s.property(), typeName, shown, pattern, !number && given != null);
      if (refusal != null) return new Refused(row, refusal);
      final var refused = write(row, s.property(), t.property(), kind + ":" + pattern + ":" + given);
      if (refused != null) return refused;
      if (!transforms.containsKey(s.property())) {
        final var name = bridgeName + camel(s.property()) + "Fn";
        transforms.put(s.property(), qualified(name));
        if (oneWay) forwardOnly.add(s.property());
        final var failure = PairingMessages.formattedPhrase(shown, pattern);
        classes.add(
          number
            ? numberFn(name, s.type(), typeName, pattern, given, oneWay, t.property(), failure)
            : temporalFn(name, s.type(), typeName, pattern, given, oneWay, t.property(), failure)
        );
      }
      rowKinds.add(oneWay ? "ForwardOnlyTransformTo" : "TypedTransformTo");
      rowAccessors.add(s.method() + ">" + t.method());
      return null;
    }

    /** The value of {@code arg} where it is a string literal or a constant variable; else null. */
    private String constantString(final ExpressionTree arg) {
      if (arg instanceof LiteralTree literal) return literal.getValue() instanceof String text ? text : null;
      if (!(arg instanceof IdentifierTree || arg instanceof MemberSelectTree)) return null;
      return elementAt(unit, arg) instanceof VariableElement variable &&
        variable.getConstantValue() instanceof String text
        ? text
        : null;
    }

    /**
     * The qualified reference to the {@code static final} field {@code arg} names, where the
     * generated bridge can reach it; else null.
     */
    private String heldField(final ExpressionTree arg) {
      if (!(arg instanceof IdentifierTree || arg instanceof MemberSelectTree)) return null;
      if (
        !(elementAt(unit, arg) instanceof VariableElement held) ||
        held.getKind() != ElementKind.FIELD ||
        !held.getModifiers().contains(Modifier.STATIC) ||
        !held.getModifiers().contains(Modifier.FINAL)
      ) {
        return null;
      }
      final var owner = (TypeElement) held.getEnclosingElement();
      return reachable(held, owner) ? owner.getQualifiedName() + "." + held.getSimpleName() : null;
    }

    /** The head of the class the processor writes for a formatted row, through its constant. */
    private StringBuilder formatHead(final String name, final TypeMirror from, final String constant) {
      final var out = new StringBuilder();
      if (!pkg.isEmpty()) out.append("package ").append(pkg).append(";\n\n");
      return out
        .append("/** Generated by telescope-codegen: a formatted row of ")
        .append(bridgeName)
        .append(". */\n")
        .append("public final class ")
        .append(name)
        .append(" implements io.github.eschizoid.telescope.conversion.BridgeFn<")
        .append(from)
        .append(", java.lang.String> {\n\n")
        .append(constant)
        .append("\n");
    }

    /**
     * The opening of the {@code backward} of a formatted row's class, which for a forward-only row
     * refuses, as the class of a {@code toOneWay} row does; true where it continues.
     */
    private static boolean backwardHead(final StringBuilder out, final TypeMirror from, final boolean oneWay) {
      out.append("  @Override\n  public ").append(from).append(" backward(final java.lang.String v) {\n");
      if (oneWay) {
        out.append("    throw new UnsupportedOperationException(\"the row converts forward only\");\n  }\n}\n");
        return false;
      }
      out.append("    if (v == null) return null;\n");
      return true;
    }

    /** The failure of text {@code v} that does not read back, in the spec's words. */
    private static String failure(final String property, final String phrase) {
      return (
        javaLiteral(PairingMessages.notAValueHead(property)) +
        " + v + " +
        javaLiteral(PairingMessages.notAValueTail(phrase))
      );
    }

    private GeneratedClass temporalFn(
      final String name,
      final TypeMirror from,
      final String typeName,
      final String pattern,
      final String zone,
      final boolean oneWay,
      final String property,
      final String phrase
    ) {
      final var constant =
        "  private static final java.time.format.DateTimeFormatter FORMAT =\n" +
        "    java.time.format.DateTimeFormatter.ofPattern(" +
        javaLiteral(pattern) +
        ")" +
        (zone == null ? "" : ".withZone(" + zone + ")") +
        ";\n";
      final var out = formatHead(name, from, constant);
      out
        .append("  @Override\n  public java.lang.String forward(final ")
        .append(from)
        .append(" v) {\n    return v == null ? null : FORMAT.format(v);\n  }\n\n");
      if (backwardHead(out, from, oneWay)) {
        out
          .append("    try {\n      return FORMAT.parse(v, ")
          .append(typeName.replace('$', '.'))
          .append("::from);\n    } catch (final java.time.DateTimeException e) {\n")
          .append("      throw new java.lang.IllegalArgumentException(")
          .append(failure(property, phrase))
          .append(", e);\n    }\n  }\n}\n");
      }
      return new GeneratedClass(qualified(name), out.toString());
    }

    private GeneratedClass numberFn(
      final String name,
      final TypeMirror from,
      final String typeName,
      final String pattern,
      final String locale,
      final boolean oneWay,
      final String property,
      final String phrase
    ) {
      final var constant =
        "  private static final java.text.DecimalFormat FORMAT = format();\n\n" +
        "  private static java.text.DecimalFormat format() {\n" +
        "    final var format = new java.text.DecimalFormat(\n      " +
        javaLiteral(pattern) +
        ",\n      java.text.DecimalFormatSymbols.getInstance(" +
        (locale == null ? "java.util.Locale.getDefault(java.util.Locale.Category.FORMAT)" : locale) +
        ")\n    );\n" +
        "    format.setParseBigDecimal(true);\n" +
        "    return format;\n" +
        "  }\n";
      final var out = formatHead(name, from, constant);
      out
        .append("  @Override\n  public java.lang.String forward(final ")
        .append(from)
        .append(" v) {\n    return v == null ? null : ((java.text.DecimalFormat) FORMAT.clone()).format(v);\n  }\n\n");
      if (backwardHead(out, from, oneWay)) {
        final var scalar = TextFormats.numberOf(typeName);
        final var exact = TextFormats.exactConversion(scalar);
        final var inexact = TextFormats.inexactConversion(scalar);
        out
          .append("    final var position = new java.text.ParsePosition(0);\n")
          .append(
            "    final java.lang.Number parsed = ((java.text.DecimalFormat) FORMAT.clone()).parse(v, position);\n"
          )
          .append("    if (parsed == null || position.getIndex() != v.length()) throw failure(v, null);\n");
        if (inexact != null) {
          out.append("    return parsed.").append(inexact).append("();\n");
        } else {
          out.append("    if (!(parsed instanceof java.math.BigDecimal exact)) throw failure(v, null);\n");
          if (exact == null) {
            out.append("    return exact;\n");
          } else {
            out
              .append("    try {\n      return exact.")
              .append(exact)
              .append(
                "();\n    } catch (final java.lang.ArithmeticException e) {\n      throw failure(v, e);\n    }\n"
              );
          }
        }
        out
          .append("  }\n\n")
          .append("  private static java.lang.IllegalArgumentException failure(\n")
          .append("    final java.lang.String v,\n    final java.lang.Throwable cause\n  ) {\n")
          .append("    return new java.lang.IllegalArgumentException(")
          .append(failure(property, phrase))
          .append(", cause);\n  }\n}\n");
      }
      return new GeneratedClass(qualified(name), out.toString());
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

    /**
     * A {@code toOrElse} or {@code toOrElseGet} row: forward writes the fallback where the source
     * value is null, or where the row's predicate, tested only on a value that is not null, accepts
     * it, and the value itself otherwise; backward writes the target value back as it is.
     */
    private Refused orElse(final MethodInvocationTree row, final String kind) {
      final var args = row.getArguments();
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
      final String missing;
      if (args.size() == 4) {
        final var predicate = predicateValue(args.get(3));
        if (predicate instanceof Refused refused) return refused;
        missing = "v == null || " + ((Gate) predicate).test(s.type().toString(), "v");
      } else {
        missing = "v == null";
      }
      final var name = bridgeName + camel(s.property()) + "Fn";
      transforms.put(s.property(), qualified(name));
      classes.add(bridgeFn(name, s.type(), t.type(), missing + " ? " + fallback + " : v", "v"));
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
      final var claimed = sourceWrites.drop(property);
      if (claimed != null) return new Refused(row, claimed);
      if (!drops.add(property)) {
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
          final var rowExec = (ExecutableElement) elementAt(unit, row.getMethodSelect());
          if (ComputeForms.FRESH_FQN.equals(ComputeForms.computeForm(rowExec))) {
            final var bothForms = ComputeForms.sourceFormRefusal(
              trees,
              env.getTypeUtils(),
              env.getElementUtils(),
              unit,
              args.get(1),
              source,
              target,
              t.property()
            );
            if (bothForms != null) return new Refused(args.get(1), bothForms);
          }
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
      lastFilled = t.property();
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
     * A {@code compute} row filling a target property with what a function makes of the source,
     * written into the bridge as a direct call on {@link #SOURCE}: {@code Names.full(s)} for a
     * static method, {@code s.initials()} for a method of the source or of a type it extends, a
     * constructor call, a call on a {@code static final} field, or {@code apply} on a {@code static
     * final} field holding the function. The row names no source property, so it records no write
     * of one, and the target property it fills counts as written.
     */
    private Refused computedFrom(final MethodInvocationTree row) {
      final var args = row.getArguments();
      final var tgt = accessor(args.get(0), target);
      if (tgt instanceof Refused refused) return refused;
      final var t = (Accessor) tgt;
      final var arg = args.get(1);
      final var held = heldFunction(arg, t);
      if (held instanceof Refused refused) return refused;
      final String called;
      final TypeMirror gives;
      if (held instanceof Held read) {
        called = read.expression();
        gives = read.gives();
      } else {
        final var function = call(arg, SOURCE, 1);
        if (function instanceof Refused refused) return refused;
        final var method = ((Call) function).method();
        final var types = env.getTypeUtils();
        final var functionType = trees.getTypeMirror(TreePath.getPath(unit, arg));
        final var qualifier = elementAt(unit, ((MemberReferenceTree) arg).getQualifierExpression());
        final var unbound =
          ((MemberReferenceTree) arg).getMode() == MemberReferenceTree.ReferenceMode.INVOKE &&
          !method.getModifiers().contains(Modifier.STATIC) &&
          qualifier instanceof TypeElement;
        final var offSource = ComputeForms.offSourceRefusal(
          types,
          functionType,
          method,
          unbound,
          source,
          target,
          t.property()
        );
        if (offSource != null) return new Refused(arg, offSource);
        // A method of a held receiver gives what the receiver's type says it does, which a generic
        // interface such as Function names by a type variable.
        if (method.getKind() == ElementKind.CONSTRUCTOR) {
          gives = method.getEnclosingElement().asType();
        } else if (qualifier instanceof VariableElement receiver) {
          gives = ((ExecutableType) types.asMemberOf((DeclaredType) receiver.asType(), method)).getReturnType();
        } else {
          gives = method.getReturnType();
        }
        if (!types.isAssignable(types.erasure(gives), types.erasure(t.type()))) {
          return new Refused(
            arg,
            arg + " gives " + gives + ", which the " + shown(t) + " property '" + t.property() + "' does not hold"
          );
        }
        called = ((Call) function).expression();
      }
      // A wrapper written into a primitive property takes the primitive's default where it is null,
      // as a conversion's result does on both paths.
      final var value =
        t.primitive() && !gives.getKind().isPrimitive()
          ? "java.util.Objects.requireNonNullElse(" + called + ", " + primitiveDefault(shown(t).getKind()) + ")"
          : called;
      if (!targetsWritten.add(t.property()) || computes.containsKey(t.property())) {
        return new Refused(row, PairingMessages.duplicateTargetRow(simple(source), simple(target), t.property()));
      }
      computedFrom.put(t.property(), value);
      lastFilled = t.property();
      rowKinds.add("ComputeFromSource");
      rowAccessors.add(">");
      return null;
    }

    /**
     * The expression a {@code compute} row reads its value with when its function is a {@code
     * static final} field: a call of that field's {@code apply} on {@link #SOURCE}, with what it
     * gives. The refusal where the field is not static and final, the generated bridge cannot reach
     * it, its function does not take the source, or it gives a value the target's property does not
     * hold; null where {@code arg} names no field.
     */
    private Object heldFunction(final ExpressionTree arg, final Accessor into) {
      if (!(arg instanceof IdentifierTree || arg instanceof MemberSelectTree)) return null;
      if (!(elementAt(unit, arg) instanceof VariableElement held) || held.getKind() != ElementKind.FIELD) return null;
      if (!held.getModifiers().contains(Modifier.STATIC) || !held.getModifiers().contains(Modifier.FINAL)) {
        return new Refused(
          arg,
          "a @Bridge mapper computes a value from a function the generated bridge can name: a method" +
            " reference (Names::full), or a static final field holding the function; " +
            held.getSimpleName() +
            " is not static and final"
        );
      }
      final var owner = (TypeElement) held.getEnclosingElement();
      if (!reachable(held, owner)) return new Refused(arg, unreachable(owner, held.getSimpleName()));
      final var types = env.getTypeUtils();
      final var apply = env
        .getElementUtils()
        .getTypeElement("java.util.function.Function")
        .getEnclosedElements()
        .stream()
        .filter(e -> e.getKind() == ElementKind.METHOD && e.getSimpleName().contentEquals("apply"))
        .findFirst()
        .orElseThrow();
      final var typed = (ExecutableType) types.asMemberOf((DeclaredType) held.asType(), apply);
      final var takes = types.erasure(typed.getParameterTypes().get(0));
      if (!types.isAssignable(types.erasure(source.asType()), takes)) {
        return new Refused(
          arg,
          PairingMessages.computeFunctionOffSource(
            simple(source),
            simple(target),
            into.property(),
            heldName(held, takes),
            ComputeForms.shownName(types, takes)
          )
        );
      }
      final var gives = typed.getReturnType();
      if (!types.isAssignable(types.erasure(gives), types.erasure(into.type()))) {
        return new Refused(
          arg,
          arg + " gives " + gives + ", which the " + shown(into) + " property '" + into.property() + "' does not hold"
        );
      }
      return new Held(owner.getQualifiedName() + "." + held.getSimpleName() + ".apply(" + SOURCE + ")", gives);
    }

    /**
     * How a message names the function {@code held} holds, as the runtime path names it from the
     * function itself: "a lambda" for a lambda, the reference for a method reference written as the
     * field's initializer in this compilation, where {@code takes} is the class it takes, and the
     * field otherwise.
     */
    private String heldName(final VariableElement held, final TypeMirror takes) {
      final var path = trees.getPath(held);
      if (path != null && path.getLeaf() instanceof VariableTree variable) {
        final var initializer = variable.getInitializer();
        if (initializer instanceof LambdaExpressionTree) return "a lambda";
        final var heldUnit = path.getCompilationUnit();
        if (
          initializer instanceof MemberReferenceTree reference &&
          elementAt(heldUnit, reference) instanceof ExecutableElement method
        ) {
          final var unbound =
            reference.getMode() == MemberReferenceTree.ReferenceMode.INVOKE &&
            !method.getModifiers().contains(Modifier.STATIC) &&
            elementAt(heldUnit, reference.getQualifierExpression()) instanceof TypeElement;
          return ComputeForms.referenceName(env.getTypeUtils(), method, unbound, takes);
        }
      }
      return ((TypeElement) held.getEnclosingElement()).getSimpleName() + "." + held.getSimpleName();
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
        // A class a path steps through is built with the hint too; any other class is a nested
        // pair the bridge does not build itself, which is refused once every row is read.
        keyedElsewhere.putIfAbsent(
          keyed.getQualifiedName().toString(),
          new Refused(
            row,
            "writeBean(" +
              keyed.getSimpleName() +
              ".class, ...) is keyed to a class other than " +
              simple(source) +
              " and " +
              simple(target) +
              ", a nested pair the generated bridge has no hint for yet; give that pair its own @Bridge field" +
              " with its own writeBean row"
          )
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
     * Records that {@code src} is written into {@code tgt}, refusing a second write into one
     * target, a source property the shared spec refuses to claim twice, and a source written into
     * two targets through different conversions.
     */
    private Refused write(final Tree row, final String src, final String tgt, final Object conversion) {
      if (!targetsWritten.add(tgt)) {
        return new Refused(row, PairingMessages.duplicateTargetRow(simple(source), simple(target), tgt));
      }
      final var claimed = sourceWrites.write(src, tgt);
      if (claimed != null) return new Refused(row, claimed);
      if (sourceWrites.fannedOut(src)) {
        fannedOutAt.putIfAbsent(src, row);
        return null;
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
      // The first target written is the rename the fan-out extends, the property's own name
      // included.
      renames.putIfAbsent(src, primary);
      fanouts.computeIfAbsent(src, unused -> new ArrayList<>()).add(tgt);
      return null;
    }

    private final Map<String, String> written = new LinkedHashMap<>();
    private final Map<String, Object> conversions = new LinkedHashMap<>();

    /** The row that wrote each refused fan-out's second target, in the order they were met. */
    private final Map<String, Tree> fannedOutAt = new LinkedHashMap<>();

    /**
     * The refusal of a source property the rows wrote into more than one target of a mapper that
     * converts both ways, reported at the row giving its second target, once every row is read; or
     * null.
     */
    Refused fanOut() {
      final var refusal = sourceWrites.fanOutRefusal();
      return refusal == null ? null : new Refused(fannedOutAt.values().iterator().next(), refusal);
    }

    private String qualified(final String simple) {
      return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    /** The accessor a row argument names on {@code side}, or the refusal. */
    private Object accessor(final ExpressionTree arg, final TypeElement side) {
      return accessor(arg, side, (DeclaredType) side.asType());
    }

    /**
     * The accessor a row argument names on {@code side}, read as a member of {@code as}, the type
     * {@code side} is given where the row reads it; or the refusal.
     */
    private Object accessor(final ExpressionTree arg, final TypeElement side, final DeclaredType as) {
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
      final var declared = types.asMemberOf(as, accessor);
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

  /**
   * A function a {@code static final} field holds: the call of its {@code apply}, and what it
   * gives.
   */
  private record Held(String expression, TypeMirror gives) {}

  /**
   * {@code text} as a Java string literal, every character outside printable ASCII written as a
   * unicode escape, so the generated source reads the same in any encoding.
   */
  private static String javaLiteral(final String text) {
    final var out = new StringBuilder("\"");
    for (var i = 0; i < text.length(); i++) {
      final var c = text.charAt(i);
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c < 0x20) {
        // A unicode escape of a line terminator ends the literal, so a control is written in octal.
        out.append(String.format(Locale.ROOT, "\\%03o", (int) c));
      } else if (c > 0x7e) {
        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.append('"').toString();
  }

  /** The Java literal of the default value of the primitive {@code kind}. */
  private static String primitiveDefault(final TypeKind kind) {
    return switch (kind) {
      case BOOLEAN -> "false";
      case INT -> "0";
      default -> "(" + kind.name().toLowerCase(Locale.ROOT) + ") 0";
    };
  }

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
