package io.github.eschizoid.telescope.codegen;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import java.util.ArrayList;
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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

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
 * value the target is filled with. The bridge converts through such a class the way it converts
 * through a {@code @Transform} or {@code @Compute} class, so every check the emitter makes on those
 * applies to the row unchanged.
 */
final class FieldRows {

  static final String TELESCOPE_FQN = "io.github.eschizoid.telescope.Telescope";
  private static final String MAPPING_FQN = "io.github.eschizoid.telescope.mapping.Mapping";
  private static final String BRIDGE_FQN = "io.github.eschizoid.telescope.annotations.Bridge";
  private static final Set<String> FACTORIES = Set.of("map", "mapper", "mapperForward");

  /** What the reader found for a field. */
  sealed interface Result permits Parsed, Refused {}

  /**
   * The configuration a field's rows declare. {@code classes} are the converter and supplier
   * classes the rows need written; {@code awaits} the classes, written by this compilation, that
   * must exist before the bridge is planned. {@code rowKinds} are the classes of the rows the
   * runtime builds from the same call, in order, which the generated provider records.
   */
  record Parsed(
    TypeElement source,
    TypeElement target,
    boolean strict,
    Set<String> drops,
    Map<String, String> renames,
    Map<String, List<String>> fanouts,
    Map<String, String> transforms,
    Set<String> forwardOnly,
    Map<String, String> viaMappers,
    Map<String, String> computes,
    List<String> rowKinds,
    List<GeneratedClass> classes,
    Set<String> awaits
  ) implements Result {}

  /** A class the reader writes beside the bridge, by qualified name, with its source. */
  record GeneratedClass(String qualifiedName, String source) {}

  /**
   * A row, or the field, the reader refuses, with the tree to report it on (null for the field).
   */
  record Refused(Tree at, String message) implements Result {}

  private final ProcessingEnvironment env;
  private final Trees trees;

  FieldRows(final ProcessingEnvironment env, final Trees trees) {
    this.env = env;
    this.trees = trees;
  }

  /**
   * The simple name of the bridge a {@code @Bridge} field generates: its class, its name, Bridge.
   */
  static String bridgeSimpleName(final VariableElement field) {
    return field.getEnclosingElement().getSimpleName() + camel(field.getSimpleName().toString()) + "Bridge";
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
   * The mapper-factory calls in {@code type}'s source for the pair of {@code source} and {@code
   * target}, other than {@code own}. A call there would be handed the bridge the field generates,
   * since the runtime finds that bridge by the calling class and the pair. The calls are matched by
   * their names and their class literals resolved by name, so nothing outside the field's own
   * initializer is attributed during the round: attributing a class body there, a lambda's or an
   * anonymous class's, leaves state the compiler trips over when it attributes the body again.
   */
  List<MethodInvocationTree> otherCallsFor(
    final TypeElement type,
    final TypeElement source,
    final TypeElement target,
    final MethodInvocationTree own
  ) {
    final var path = trees.getPath(type);
    if (path == null) return List.of();
    final var unit = path.getCompilationUnit();
    final var found = new ArrayList<MethodInvocationTree>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
        if (node != own && node.getArguments().size() >= 2 && namesFactory(node)) {
          final var s = classLiteralByName(unit, node.getArguments().get(0), type);
          final var t = classLiteralByName(unit, node.getArguments().get(1), type);
          if (Objects.equals(s, source) && Objects.equals(t, target)) found.add(node);
        }
        return super.visitMethodInvocation(node, unused);
      }
    }
      .scan(path, null);
    return found;
  }

  /**
   * Whether {@code call} is written as a mapper factory: {@code Telescope.mapper(...)}, {@code
   * mapperForward} or {@code map}, qualified by the class or statically imported.
   */
  private static boolean namesFactory(final MethodInvocationTree call) {
    final var select = call.getMethodSelect();
    if (select instanceof MemberSelectTree member) {
      final var qualifier = member.getExpression().toString();
      return (
        FACTORIES.contains(member.getIdentifier().toString()) &&
        (qualifier.equals("Telescope") || qualifier.equals(TELESCOPE_FQN))
      );
    }
    return select instanceof IdentifierTree name && FACTORIES.contains(name.getName().toString());
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
    final var bridgeName = bridgeSimpleName(field);
    final var rows = new Rows(unit, source, target, strict, pkg, bridgeName);
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
    private final Set<String> forwardOnly = new LinkedHashSet<>();
    private final Map<String, String> viaMappers = new LinkedHashMap<>();
    private final Map<String, String> computes = new LinkedHashMap<>();
    private final Set<String> targetsWritten = new LinkedHashSet<>();
    private final List<String> rowKinds = new ArrayList<>();
    private final List<GeneratedClass> classes = new ArrayList<>();
    private final Set<String> awaits = new LinkedHashSet<>();

    Rows(
      final CompilationUnitTree unit,
      final TypeElement source,
      final TypeElement target,
      final boolean strict,
      final String pkg,
      final String bridgeName
    ) {
      this.unit = unit;
      this.source = source;
      this.target = target;
      this.strict = strict;
      this.pkg = pkg;
      this.bridgeName = bridgeName;
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
        forwardOnly,
        viaMappers,
        computes,
        rowKinds,
        classes,
        awaits
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
      if (
        !(rowElement instanceof ExecutableElement rowExec) ||
        !(rowExec.getEnclosingElement() instanceof TypeElement rowOwner) ||
        !rowOwner.getQualifiedName().contentEquals(MAPPING_FQN)
      ) {
        return new Refused(
          row,
          row.getMethodSelect() +
            "(...) is not a row a @Bridge mapper compiles; the generated bridge takes the Mapping rows" +
            " to, toOneWay, toOrElse, toOrElseGet, via, drop, constant and compute"
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
      if (!transforms.containsKey(s.property())) {
        final var name = bridgeName + camel(s.property()) + "Fn";
        transforms.put(s.property(), qualified(name));
        if (!twoWay) forwardOnly.add(s.property());
        classes.add(bridgeFn(name, s.type(), t.type(), ((Call) forward).expression(), backward));
      }
      rowKinds.add(twoWay ? "TypedTransformTo" : "ForwardOnlyTransformTo");
      return null;
    }

    /**
     * A {@code to} row converting through one {@code BridgeFn}: a class literal, which the bridge
     * builds once as it builds a {@code @Transform} class, or a {@code static final} field holding
     * one, which a generated class calls.
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
        if (named.getNestingKind() != NestingKind.TOP_LEVEL || !reachable(named, named)) {
          return new Refused(
            conversion,
            named.getSimpleName() +
              " cannot be built from the generated bridge in package '" +
              pkg +
              "'; declare it as a public top-level class with a public no-argument constructor"
          );
        }
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
        final var value = constantText(args.get(2));
        if (value instanceof Refused refused) return refused;
        fallback = (String) value;
      } else {
        final var supplier = call(args.get(2), null, 0);
        if (supplier instanceof Refused refused) return refused;
        fallback = ((Call) supplier).expression();
      }
      final var refusal = write(row, s.property(), t.property(), row);
      if (refusal != null) return refusal;
      if (transforms.containsKey(s.property())) {
        return new Refused(row, "a @Bridge mapper converts one source property through one row");
      }
      final var name = bridgeName + camel(s.property()) + "Fn";
      transforms.put(s.property(), qualified(name));
      classes.add(bridgeFn(name, s.type(), t.type(), "v == null ? " + fallback + " : v", "v"));
      rowKinds.add("TypedTransformTo");
      return null;
    }

    private Refused via(final MethodInvocationTree row) {
      final var args = row.getArguments();
      final var src = accessor(args.get(0), source);
      if (src instanceof Refused refused) return refused;
      final var tgt = accessor(args.get(1), target);
      if (tgt instanceof Refused refused) return refused;
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
            " generated bridge calls"
        );
      }
      final var s = ((Accessor) src).property();
      final var refusal = write(row, s, ((Accessor) tgt).property(), mapperField);
      if (refusal != null) return refusal;
      final var owner = (TypeElement) mapperField.getEnclosingElement();
      final var ownerPkg = env.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
      final var via = (ownerPkg.isEmpty() ? "" : ownerPkg + ".") + bridgeSimpleName(mapperField);
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
      return null;
    }

    private Refused drop(final MethodInvocationTree row) {
      final var args = row.getArguments();
      if (args.size() != 1) {
        return new Refused(row, "drop(...) keyed to a nested pair has no generated form yet");
      }
      final var src = accessor(args.get(0), source);
      if (src instanceof Refused refused) return refused;
      drops.add(((Accessor) src).property());
      rowKinds.add("Drop");
      return null;
    }

    private Refused filled(final MethodInvocationTree row, final boolean constant) {
      final var args = row.getArguments();
      final var tgt = accessor(args.get(0), target);
      if (tgt instanceof Refused refused) return refused;
      final var t = (Accessor) tgt;
      final String value;
      if (constant) {
        final var text = constantText(args.get(1));
        if (text instanceof Refused refused) return refused;
        value = (String) text;
      } else {
        final var supplier = call(args.get(1), null, 0);
        if (supplier instanceof Refused refused) return refused;
        value = ((Call) supplier).expression();
      }
      if (!targetsWritten.add(t.property()) || computes.containsKey(t.property())) {
        return new Refused(row, PairingMessages.duplicateTargetRow(simple(source), simple(target), t.property()));
      }
      final var name = bridgeName + camel(t.property()) + (constant ? "Constant" : "Compute");
      computes.put(t.property(), qualified(name));
      classes.add(supplier(name, t.type(), value));
      rowKinds.add(constant ? "Constant" : "Compute");
      return null;
    }

    /**
     * Records that {@code src} is written into {@code tgt}, refusing a second write into one target
     * and a source written into two targets through different conversions.
     */
    private Refused write(final Tree row, final String src, final String tgt, final Object conversion) {
      if (!targetsWritten.add(tgt)) {
        return new Refused(row, PairingMessages.duplicateTargetRow(simple(source), simple(target), tgt));
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
      if (!(trees.getTypeMirror(path) instanceof DeclaredType fn) || fn.getTypeArguments().size() != 2) {
        return new Refused(arg, "the processor cannot resolve this accessor's type");
      }
      final var type = fn.getTypeArguments().get(1);
      if (!nameable(type)) {
        return new Refused(arg, "the type " + type + " this accessor reads has no name the generated bridge can write");
      }
      return new Accessor(property(accessor.getSimpleName().toString(), side), type);
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

    /** The Java source of a constant row's value, or the refusal. */
    private Object constantText(final ExpressionTree arg) {
      if (arg instanceof LiteralTree literal && literal.getKind() != Tree.Kind.NULL_LITERAL) return literal.toString();
      if (
        arg instanceof UnaryTree unary &&
        unary.getKind() == Tree.Kind.UNARY_MINUS &&
        unary.getExpression() instanceof LiteralTree
      ) {
        return "(" + unary + ")";
      }
      if (
        (arg instanceof IdentifierTree || arg instanceof MemberSelectTree) &&
        elementAt(unit, arg) instanceof VariableElement constant &&
        constant.getKind() == ElementKind.ENUM_CONSTANT
      ) {
        return ((TypeElement) constant.getEnclosingElement()).getQualifiedName() + "." + constant.getSimpleName();
      }
      return new Refused(
        arg,
        "a value in a @Bridge mapper must be a literal or an enum constant, which the generated bridge" +
          " writes in place"
      );
    }

    private boolean reachable(final Element member, final TypeElement owner) {
      if (member.getModifiers().contains(Modifier.PUBLIC) && owner.getModifiers().contains(Modifier.PUBLIC)) {
        return true;
      }
      if (member.getModifiers().contains(Modifier.PRIVATE) || owner.getModifiers().contains(Modifier.PRIVATE)) {
        return false;
      }
      return env.getElementUtils().getPackageOf(owner).getQualifiedName().contentEquals(pkg);
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

    private boolean nameable(final TypeMirror type) {
      if (type.getKind() == TypeKind.TYPEVAR || type.getKind() == TypeKind.WILDCARD) return false;
      if (type instanceof DeclaredType declared) {
        for (final var argument : declared.getTypeArguments()) if (!nameable(argument)) return false;
        return true;
      }
      return type.getKind() == TypeKind.ARRAY || type.getKind().isPrimitive();
    }
  }

  private record Accessor(String property, TypeMirror type) {}

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
