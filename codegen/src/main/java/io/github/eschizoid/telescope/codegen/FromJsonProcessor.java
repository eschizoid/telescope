package io.github.eschizoid.telescope.codegen;

import io.github.eschizoid.telescope.internal.pairing.BeanWriteStrategy;
import io.github.eschizoid.telescope.internal.pairing.JsonPropertyKeys;
import io.github.eschizoid.telescope.internal.pairing.MapValueTypes;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * Writes the reflection-free {@code <X>FromJson} binder for each {@code @FromJson} record or bean:
 * a {@code BINDER} constant holding a {@code JsonBinder<X>} whose reading of each object is a
 * generated parser loop over {@code jackson-core}, with no reflection, no {@code SerializedLambda}
 * and no lambda metafactory, so it needs no reflection registration in a native image.
 *
 * <p>The binder follows the rules {@code TelescopeJson.fromJson} follows, through the same shared
 * spec: which declared types are read and how is {@code MapValueTypes.classify}, how a property's
 * Jackson annotations decide its key is {@code JsonPropertyKeys}, every value is read by the
 * methods of {@code JsonReads} the runtime binder calls, and every refusal is worded by {@code
 * PairingMessages}. A bean is built the way the runtime writer builds it, by the strategy {@code
 * BeanWriteStrategy.AUTO_ORDER} picks, through the rebuild the other telescope processors emit.
 * What the processor cannot read as the runtime does is refused by name at compile time: a generic
 * type, an inner class, and a type whose {@code @FromMap} binder was compiled apart from it.
 *
 * <p>{@code @FromJson} lives in telescope-jackson and the Jackson annotations in
 * jackson-annotations; every one is matched by name, so the processor does nothing where neither is
 * on the class path, as for every other telescope trigger.
 */
@SupportedAnnotationTypes(FromJsonProcessor.ANNOTATION)
public final class FromJsonProcessor extends AbstractTelescopeProcessor {

  static final String ANNOTATION = "io.github.eschizoid.telescope.jackson.FromJson";

  private static final String FROM_MAP = "io.github.eschizoid.telescope.annotations.FromMap";
  private static final String FROM_MAP_PROVIDER = "io.github.eschizoid.telescope.conversion.FromMapProvider";
  private static final String READS = "io.github.eschizoid.telescope.jackson.JsonReads";
  private static final String BINDER = "io.github.eschizoid.telescope.jackson.JsonBinder";
  private static final String PARSER = "tools.jackson.core.JsonParser";
  private static final String TOKEN = "tools.jackson.core.JsonToken";
  private static final String NODE = "io.github.eschizoid.telescope.introspection.OpticNode";

  private static final String JACKSON = "com.fasterxml.jackson.annotation.";
  private static final String PROPERTY = JACKSON + "JsonProperty";
  private static final String ALIAS = JACKSON + "JsonAlias";
  private static final String IGNORE = JACKSON + "JsonIgnore";
  private static final String IGNORE_PROPERTIES = JACKSON + "JsonIgnoreProperties";
  private static final String CREATOR = JACKSON + "JsonCreator";
  private static final String ANY_SETTER = JACKSON + "JsonAnySetter";
  private static final String UNWRAPPED = JACKSON + "JsonUnwrapped";
  private static final String VIEW = JACKSON + "JsonView";
  private static final String SETTER = JACKSON + "JsonSetter";
  private static final String FORMAT = JACKSON + "JsonFormat";
  private static final String INSIDE = JACKSON + "JacksonAnnotationsInside";
  private static final String NAMING = "tools.jackson.databind.annotation.JsonNaming";

  /**
   * Targets carrying a Lombok trigger, waiting for the final round: Lombok adds their members while
   * the rounds run, so they are read only once it has finished. Every other target is written in
   * the round it is seen, so code in the same module can name its binder.
   */
  private final Set<TypeElement> pending = new LinkedHashSet<>();

  /** Public no-arg constructor for {@code ServiceLoader} discovery by the Java compiler. */
  public FromJsonProcessor() {
    super();
  }

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment round) {
    final var annotation = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
    if (annotation == null) return false;
    for (final var element : round.getElementsAnnotatedWith(annotation)) {
      if (!round.processingOver() && carriesLombokTrigger(element)) pending.add((TypeElement) element);
      else generate(element);
    }
    if (round.processingOver()) {
      pending.forEach(this::generate);
      pending.clear();
    }
    return true;
  }

  /** A refusal found while a target's binder is planned, reported on the target. */
  private static final class Refusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    Refusal(final String message) {
      super(message, null, false, false);
    }
  }

  private void generate(final Element element) {
    if (element.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
      error(
        element,
        "@FromJson is only supported on top-level types (the generated binder is emitted beside the" +
          " type and cannot name a nested one)"
      );
      return;
    }
    if (element.getKind() != ElementKind.RECORD && element.getKind() != ElementKind.CLASS) {
      error(element, "@FromJson is only supported on records and classes");
      return;
    }
    final var target = (TypeElement) element;
    final var plan = new Plan(target);
    try {
      plan.frame((DeclaredType) target.asType());
    } catch (final Refusal refusal) {
      if (refusal.getMessage() != null) error(target, "@FromJson: " + refusal.getMessage());
      return;
    } catch (final IllegalArgumentException refusal) {
      error(target, "@FromJson: " + stripPrefix(refusal.getMessage()));
      return;
    }
    write(target, plan);
  }

  /** A runtime message without the factory it opens with, as a compile-time diagnostic reads it. */
  private static String stripPrefix(final String message) {
    final var prefix = "TelescopeJson.fromJson: ";
    return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
  }

  // ---------------------------------------------------------------------------------------------
  // The plan: one frame per type the target holds
  // ---------------------------------------------------------------------------------------------

  /** One property of a frame, decided. */
  private record Slot(
    String name,
    String key,
    List<String> aliases,
    String type,
    String declared,
    String absent,
    String read,
    boolean required,
    boolean ignored,
    boolean claimed,
    String setter
  ) {}

  /** One object level: the type it builds and how each property is read. */
  private static final class Frame {

    final TypeElement type;
    final String method;
    final String kind;
    final boolean record;
    final List<Slot> slots = new ArrayList<>();

    /** For a bean, its properties and how it is built; null for a record. */
    List<Prop> props;

    BeanRebuild rebuild;

    Frame(final TypeElement type, final String method) {
      this.type = type;
      this.method = method;
      this.record = type.getKind() == ElementKind.RECORD;
      this.kind = record ? "component" : "property";
    }
  }

  private final class Plan {

    final TypeElement target;
    final String binder;
    final Map<String, Frame> frames = new LinkedHashMap<>();

    Plan(final TypeElement target) {
      this.target = target;
      this.binder = target.getSimpleName() + "FromJson";
    }

    Frame frame(final DeclaredType declared) {
      final var type = (TypeElement) declared.asElement();
      final var name = type.getQualifiedName().toString();
      final var existing = frames.get(name);
      if (existing != null) return existing;
      final var frame = new Frame(type, "__read" + frames.size());
      frames.put(name, frame);
      fill(frame, (DeclaredType) type.asType());
      return frame;
    }

    private void fill(final Frame frame, final DeclaredType declared) {
      final var type = frame.type;
      final var owner = type.getSimpleName().toString();
      requireAccessible(type);
      if (!type.getTypeParameters().isEmpty()) {
        throw new Refusal(
          owner + " is generic, which the generated binder does not build; read it with TelescopeJson.fromJson"
        );
      }
      final var names = new ArrayList<String>();
      final var types = new ArrayList<TypeMirror>();
      final var writes = new ArrayList<List<Element>>();
      final var readers = new ArrayList<Element>();
      if (frame.record) recordProperties(type, declared, names, types, writes, readers);
      else beanSlots(frame, names, types, writes, readers);

      final var members = new LinkedHashMap<String, List<Element>>();
      for (var i = 0; i < names.size(); i++) {
        final var all = new ArrayList<>(writes.get(i));
        all.add(readers.get(i));
        members.put(names.get(i), all);
      }
      refuseUnsupported(type, frame.kind, members);

      final var n = names.size();
      final var keys = new JsonPropertyKeys.Keys[n];
      var readsOwnName = false;
      for (var i = 0; i < n; i++) {
        keys[i] = JsonPropertyKeys.decide(
          owner,
          frame.kind,
          names.get(i),
          facts(writes.get(i), readers.get(i)),
          frame.record
        );
        readsOwnName |= keys[i].key().equals(names.get(i)) && !keys[i].ignored();
      }
      final var declaredRequired = readsOwnName ? declaredRequired(type) : List.<String>of();
      final var indexByKey = new HashMap<String, Integer>();
      for (var i = 0; i < n; i++) {
        final var name = names.get(i);
        final var propertyType = types.get(i);
        final var setter = frame.record ? null : frame.rebuild.members()[i];
        final var absent = absentOf(propertyType);
        final var declaredName = declaredName(propertyType);
        final var local = sourceName(propertyType);
        if (keys[i].ignored()) {
          frame.slots.add(
            new Slot(name, keys[i].key(), List.of(), local, declaredName, absent, null, false, true, false, setter)
          );
          continue;
        }
        final var required = keys[i].required() || (keys[i].key().equals(name) && declaredRequired.contains(name));
        final var written = frame.record || frame.rebuild.strategy() == BeanWriteStrategy.CONSTRUCTOR || setter != null;
        String read = null;
        if (written) {
          read = resolve(propertyType, 1).orElseRefuse(reason ->
            PairingMessages.jsonUnbindable(frame.kind, name, owner, declaredName, reason)
          );
        } else if (!required) {
          frame.slots.add(
            new Slot(name, keys[i].key(), List.of(), local, declaredName, absent, null, false, false, false, setter)
          );
          continue;
        }
        for (final var alias : keys[i].aliases()) claim(indexByKey, alias, i, names, owner);
        claim(indexByKey, keys[i].key(), i, names, owner);
        frame.slots.add(
          new Slot(
            name,
            keys[i].key(),
            keys[i].aliases(),
            local,
            declaredName,
            absent,
            read,
            required,
            false,
            true,
            setter
          )
        );
      }
    }

    private void claim(
      final Map<String, Integer> indexByKey,
      final String key,
      final int index,
      final List<String> names,
      final String owner
    ) {
      final var previous = indexByKey.putIfAbsent(key, index);
      if (previous != null && previous != index) {
        throw new IllegalArgumentException(
          PairingMessages.jsonKeyReadTwice(key, names.get(previous), names.get(index), owner)
        );
      }
    }

    /**
     * A record's components: the canonical constructor's parameter and the field write each, the
     * accessor reads it.
     */
    private void recordProperties(
      final TypeElement type,
      final DeclaredType declared,
      final List<String> names,
      final List<TypeMirror> types,
      final List<List<Element>> writes,
      final List<Element> readers
    ) {
      final var components = type.getRecordComponents();
      final var canonical = canonicalConstructor(type);
      for (var i = 0; i < components.size(); i++) {
        final var component = components.get(i);
        final var accessor = component.getAccessor();
        names.add(component.getSimpleName().toString());
        types.add(((ExecutableType) processingEnv.getTypeUtils().asMemberOf(declared, accessor)).getReturnType());
        final var members = new ArrayList<Element>(2);
        if (canonical != null) members.add(canonical.getParameters().get(i));
        final var field = field(type, component.getSimpleName().toString());
        if (field != null) members.add(field);
        writes.add(members);
        readers.add(accessor);
      }
    }

    private ExecutableElement canonicalConstructor(final TypeElement type) {
      final var types = processingEnv.getTypeUtils();
      final var components = type.getRecordComponents();
      for (final var constructor : ElementFilter.constructorsIn(type.getEnclosedElements())) {
        final var parameters = constructor.getParameters();
        if (parameters.size() != components.size()) continue;
        var matches = true;
        for (var i = 0; i < parameters.size() && matches; i++) {
          matches = types.isSameType(
            types.erasure(parameters.get(i).asType()),
            types.erasure(components.get(i).asType())
          );
        }
        if (matches) return constructor;
      }
      return null;
    }

    /**
     * A bean's properties, as the runtime reads them, and how the bean is built: the strategy the
     * shared {@code BeanWriteStrategy.AUTO_ORDER} picks from the bean's builder, constructor and
     * setters, through the rebuild the other telescope processors emit. A property written through
     * a setter its own type cannot be passed to is read as the setter's type, as the runtime reads
     * it. A bean no strategy builds is refused in the runtime writer's words.
     */
    private void beanSlots(
      final Frame frame,
      final List<String> names,
      final List<TypeMirror> types,
      final List<List<Element>> writes,
      final List<Element> readers
    ) {
      final var type = frame.type;
      final var props = beanProperties(type);
      if (props.isEmpty()) {
        throw new IllegalArgumentException(PairingMessages.jsonNotBindable(type.getQualifiedName().toString()));
      }
      final var propertyNames = props.stream().map(Prop::name).toList();
      // The runtime writer offers setters for a bean with a no-argument constructor of any access
      // and any public setter, a property's or not.
      final var chosen = autoBeanStrategy(
        type,
        propertyNames,
        property -> props.get(propertyNames.indexOf(property)).type(),
        noArgConstructor(type) != null && anySetter(type)
      );
      if (chosen.isEmpty()) throw new IllegalArgumentException(writerRefusal(type, props));
      if (chosen.get() == BeanWriteStrategy.SETTERS && !hasAccessibleNoArgConstructor(type)) {
        throw new Refusal(
          type.getQualifiedName() +
            " is built through its private no-argument constructor by TelescopeJson.fromJson, which the" +
            " generated binder cannot call; give it a constructor the package can reach, or read it with" +
            " TelescopeJson.fromJson"
        );
      }
      final var rebuild = beanRebuildFor(type, props, "@FromJson", true);
      if (rebuild == null) throw new Refusal(null);
      if (rebuild.strategy() != chosen.get()) {
        throw new Refusal(
          type.getQualifiedName() +
            " is built through its " +
            chosen.get().name().toLowerCase(Locale.ROOT) +
            " by TelescopeJson.fromJson, which the generated binder cannot call from " +
            binder +
            "; read it with TelescopeJson.fromJson"
        );
      }
      frame.props = props;
      frame.rebuild = rebuild;
      final var setters = rebuild.strategy() == BeanWriteStrategy.SETTERS;
      for (var i = 0; i < props.size(); i++) {
        final var prop = props.get(i);
        names.add(prop.name());
        final var viaSetter = setters && rebuild.members()[i] != null;
        types.add(viaSetter ? conversionType(type, prop.name(), prop.type()) : prop.type());
        final var members = new ArrayList<Element>(setterChoice(type, prop.name()).candidates());
        final var field = field(type, prop.name());
        if (field != null) members.add(field);
        writes.add(members);
        readers.add(getter(type, prop.getter()));
      }
    }

    /**
     * Why the runtime writer builds {@code type} no way at all, in its words: a builder that cannot
     * carry every property, only private fields to write, or no name-based strategy.
     */
    private String writerRefusal(final TypeElement type, final List<Prop> props) {
      final var binaryName = processingEnv.getElementUtils().getBinaryName(type).toString();
      final var builderType = usableBuilderType(type);
      if (builderType != null) {
        final var missing = new ArrayList<String>();
        for (final var prop : props) {
          final var parameter = builderSetterParameter(type, builderType, prop.name());
          final var lost = parameter == null ? mutableField(type, prop.name()) : !takes(parameter, prop.type());
          if (lost) missing.add(prop.name());
        }
        return PairingMessages.builderCannotCarry(
          binaryName,
          processingEnv.getElementUtils().getBinaryName(builderType).toString(),
          missing,
          type.getSimpleName().toString()
        );
      }
      final var withArguments = ElementFilter.constructorsIn(type.getEnclosedElements())
        .stream()
        .anyMatch(constructor -> !constructor.getParameters().isEmpty());
      if (noArgConstructor(type) != null && !withArguments) {
        return PairingMessages.onlyPrivateFields(binaryName, type.getSimpleName().toString());
      }
      return PairingMessages.noNameBasedWriteStrategy(binaryName, props.size());
    }

    /** The no-argument constructor {@code type} declares, of any access, or null. */
    private ExecutableElement noArgConstructor(final TypeElement type) {
      for (final var constructor : ElementFilter.constructorsIn(type.getEnclosedElements())) {
        if (constructor.getParameters().isEmpty()) return constructor;
      }
      return null;
    }

    /**
     * Whether {@code type} has a public instance method of one parameter named as a setter, for any
     * property or none, which is what the runtime writer asks before it offers setters.
     */
    private boolean anySetter(final TypeElement type) {
      for (final var method : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(type))) {
        if (
          method.getModifiers().contains(Modifier.PUBLIC) &&
          !method.getModifiers().contains(Modifier.STATIC) &&
          method.getParameters().size() == 1 &&
          PropertyNames.afterSet(method.getSimpleName().toString()) != null
        ) return true;
      }
      return false;
    }

    /**
     * The type a write-only binder converts {@code property}'s value to before handing it to the
     * setter the shared rule chooses: the setter's own parameter type where a value of {@code
     * read}, the property's type, cannot be passed to it as it is, and {@code read} otherwise.
     */
    private TypeMirror conversionType(final TypeElement type, final String property, final TypeMirror read) {
      final var choice = setterChoice(type, property);
      return choice.member() != null && !choice.fit().compiles() ? choice.writeType() : read;
    }

    /**
     * Whether a builder member taking {@code parameter} can hold a value of {@code property}'s
     * type, by the rule the runtime writer applies to the erased types: a primitive on either side
     * must be the same primitive, and otherwise the parameter must be assignable from the property.
     */
    private boolean takes(final TypeMirror parameter, final TypeMirror property) {
      final var types = processingEnv.getTypeUtils();
      if (parameter.getKind().isPrimitive() || property.getKind().isPrimitive()) {
        return types.isSameType(parameter, property);
      }
      return types.isAssignable(types.erasure(property), types.erasure(parameter));
    }

    /** Whether {@code type} or a superclass declares a non-final instance field {@code name}. */
    private boolean mutableField(final TypeElement type, final String name) {
      final var declared = field(type, name);
      return (
        declared != null &&
        !declared.getModifiers().contains(Modifier.STATIC) &&
        !declared.getModifiers().contains(Modifier.FINAL)
      );
    }

    /** The public no-argument method {@code name} of {@code type}, inherited ones included. */
    private ExecutableElement getter(final TypeElement type, final String name) {
      for (final var method : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(type))) {
        if (method.getSimpleName().contentEquals(name) && method.getParameters().isEmpty()) return method;
      }
      throw new IllegalStateException("no getter " + name + " on " + type);
    }

    /**
     * Refuses a type the generated binder cannot name or construct from the target's package: one
     * private or package-private elsewhere at any level of its nesting, or an inner class, whose
     * instances need an enclosing instance the binder does not have. A static nested class, and a
     * nested record, enum or interface, which are static whatever they say, are reached as any
     * top-level type is.
     */
    private void requireAccessible(final TypeElement type) {
      for (Element e = type; e.getKind() != ElementKind.PACKAGE; e = e.getEnclosingElement()) {
        final var modifiers = e.getModifiers();
        if (modifiers.contains(Modifier.PRIVATE) || (!modifiers.contains(Modifier.PUBLIC) && !samePackage(type))) {
          throw new Refusal(
            type.getQualifiedName() +
              " cannot be reached from " +
              binder +
              ", which is generated in the package of " +
              target.getSimpleName() +
              "; make it public, or read it with TelescopeJson.fromJson"
          );
        }
        final var nested = e.getEnclosingElement().getKind() != ElementKind.PACKAGE;
        if (nested && e.getKind() == ElementKind.CLASS && !modifiers.contains(Modifier.STATIC)) {
          throw new Refusal(
            e +
              " is an inner class, whose instances need an instance of " +
              e.getEnclosingElement() +
              " the generated binder does not have; declare it static, or read it with TelescopeJson.fromJson"
          );
        }
      }
    }

    private boolean samePackage(final TypeElement type) {
      final var elements = processingEnv.getElementUtils();
      return elements.getPackageOf(type).equals(elements.getPackageOf(target));
    }

    /**
     * The keys the type's {@code @FromMap(required = ...)} lists, read from the annotation in this
     * compilation. A binder generated in an earlier one keeps its keys in code the processor cannot
     * read, so that type is refused rather than held to keys the processor does not know.
     */
    private List<String> declaredRequired(final TypeElement type) {
      final var listed = new ArrayList<String>();
      final var annotation = find(type, FROM_MAP);
      if (annotation != null) {
        final var value = attribute(annotation, "required");
        if (value != null) for (final var entry : asList(value)) listed.add((String) entry.getValue());
        return listed;
      }
      final var elements = processingEnv.getElementUtils();
      final var binderType = elements.getTypeElement(type.getQualifiedName() + "FromMap");
      final var spi = elements.getTypeElement(FROM_MAP_PROVIDER);
      if (binderType == null || spi == null) return listed;
      for (final var member : binderType.getEnclosedElements()) {
        if (member.getKind() == ElementKind.CLASS && member.getSimpleName().contentEquals("Provider")) {
          throw new Refusal(
            type.getSimpleName() +
              " has a @FromMap binder compiled apart from it, whose required keys the generated" +
              " binder cannot read; compile " +
              type.getSimpleName() +
              " with it, or read it with TelescopeJson.fromJson"
          );
        }
      }
      return listed;
    }

    // -------------------------------------------------------------------------------------------
    // Declared type → reading
    // -------------------------------------------------------------------------------------------

    /** A reading, as source given the parser's variable, or the reason the type has none. */
    private record Resolution(Emit emit, String reason) {
      String orElseRefuse(final Function<String, String> refusal) {
        if (emit == null) throw new IllegalArgumentException(refusal.apply(reason));
        return emit.at("p");
      }
    }

    /** Source reading one value, given the name of the parser variable in scope. */
    @FunctionalInterface
    private interface Emit {
      String at(String parser);
    }

    private Resolution resolve(final TypeMirror type, final int depth) {
      final var classified = MapValueTypes.classify(type, model);
      final var name = model.declaredName(type);
      final var refusal = PairingMessages.jsonNoReading(classified.kind(), binaryName(type), type.toString());
      if (refusal != null) return new Resolution(null, refusal);
      return switch (classified.kind()) {
        case SCALAR -> {
          final var method =
            "read" + Character.toUpperCase(classified.scalar().charAt(0)) + classified.scalar().substring(1);
          yield new Resolution(p -> READS + "." + method + "(" + p + ", " + classified.primitive() + ")", null);
        }
        case ENUM -> {
          refuseRenamedConstants((TypeElement) ((DeclaredType) type).asElement());
          yield new Resolution(p -> READS + ".readEnum(" + p + ", " + name + ".class)", null);
        }
        case NESTED -> {
          final var nested = frame((DeclaredType) type);
          final var simple = ((DeclaredType) type).asElement().getSimpleName();
          yield new Resolution(
            p -> READS + ".readObject(" + p + ", \"" + simple + "\", " + binder + "::" + nested.method + ")",
            null
          );
        }
        case LIST, SET -> {
          final var element = argument(type, 0);
          final var inner = resolve(element, depth + 1);
          if (inner.emit() == null) yield inner;
          final var q = "p" + depth;
          final var method = classified.kind() == MapValueTypes.Kind.LIST ? "readList" : "readSet";
          yield new Resolution(
            p ->
              READS + "." + method + "(" + p + ", " + q + " -> " + inner.emit().at(q) + ", " + absentOf(element) + ")",
            null
          );
        }
        case OPTIONAL -> {
          final var inner = resolve(argument(type, 0), depth + 1);
          if (inner.emit() == null) yield inner;
          final var q = "p" + depth;
          yield new Resolution(p -> READS + ".readOptional(" + p + ", " + q + " -> " + inner.emit().at(q) + ")", null);
        }
        case MAP -> {
          final var keyType = argument(type, 0);
          final var keyKind = MapValueTypes.classify(keyType, model).kind();
          final var keyRefusal = PairingMessages.jsonNoKeyReading(keyKind, keyType.toString());
          if (keyRefusal != null) yield new Resolution(null, keyRefusal);
          if (keyKind == MapValueTypes.Kind.ENUM) refuseRenamedConstants(
            (TypeElement) ((DeclaredType) keyType).asElement()
          );
          final var valueType = argument(type, 1);
          final var inner = resolve(valueType, depth + 1);
          if (inner.emit() == null) yield inner;
          final var q = "p" + depth;
          final var keyClass = model.declaredName(keyType) + ".class";
          yield new Resolution(
            p ->
              READS +
              ".readMap(" +
              p +
              ", " +
              keyClass +
              ", " +
              q +
              " -> " +
              inner.emit().at(q) +
              ", " +
              absentOf(valueType) +
              ")",
            null
          );
        }
        case CAST -> new Resolution(
          p -> READS + (name.equals("java.lang.String") ? ".readText(" : ".readCharSequence(") + p + ")",
          null
        );
        case AS_IS -> new Resolution(p -> READS + ".readNatural(" + p + ")", null);
        case STRING_BUILT -> new Resolution(p -> READS + ".readValue(" + p + ", " + name + ".class)", null);
        default -> throw new IllegalStateException("unrefused kind " + classified.kind());
      };
    }

    private TypeMirror argument(final TypeMirror type, final int index) {
      return ((DeclaredType) type).getTypeArguments().get(index);
    }

    /**
     * Whether the binder can build {@code type} from a JSON object: a record, or a concrete class
     * with properties, outside the JDK and not a container.
     */
    private boolean bindable(final TypeMirror type) {
      if (type.getKind() != TypeKind.DECLARED) return false;
      final var element = (TypeElement) ((DeclaredType) type).asElement();
      if (element.getKind() == ElementKind.RECORD) return !isJdk(element);
      if (element.getKind() != ElementKind.CLASS || element.getModifiers().contains(Modifier.ABSTRACT)) return false;
      if (isJdk(element) || model.isCollectionOrMap(type)) return false;
      return !beanProperties(element).isEmpty();
    }

    private final MapValueTypes.TypeModel<TypeMirror> model = new MapValueTypes.TypeModel<>() {
      @Override
      public String primitiveName(final TypeMirror type) {
        return type.getKind().isPrimitive() ? type.getKind().name().toLowerCase(Locale.ROOT) : null;
      }

      @Override
      public String declaredName(final TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return null;
        return ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
      }

      @Override
      public List<TypeMirror> typeArguments(final TypeMirror type) {
        return List.copyOf(((DeclaredType) type).getTypeArguments());
      }

      @Override
      public boolean isEnum(final TypeMirror type) {
        return ((DeclaredType) type).asElement().getKind() == ElementKind.ENUM;
      }

      /**
       * Every record and bean the binder can build is read as a nested object by its own frame.
       */
      @Override
      public boolean hasGeneratedBinder(final TypeMirror type) {
        return bindable(type);
      }

      @Override
      public boolean isCollectionOrMap(final TypeMirror type) {
        return assignableTo(type, "java.util.Collection") || assignableTo(type, "java.util.Map");
      }
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Jackson annotations, read from the compiler's model by name
  // ---------------------------------------------------------------------------------------------

  /** What the annotations on each member say, most preferred first, the read member last. */
  private List<JsonPropertyKeys.Member> facts(final List<Element> writes, final Element read) {
    final var all = new ArrayList<JsonPropertyKeys.Member>(writes.size() + 1);
    for (final var member : writes) all.add(fact(member, true));
    if (read != null) all.add(fact(read, false));
    return all;
  }

  private JsonPropertyKeys.Member fact(final Element member, final boolean writing) {
    final var ignore = find(member, IGNORE);
    final var ignored = ignore != null && (Boolean) attribute(ignore, "value").getValue();
    final var property = find(member, PROPERTY);
    JsonPropertyKeys.Property said = null;
    if (property != null) {
      final var access = ((VariableElement) attribute(property, "access").getValue()).getSimpleName().toString();
      said = new JsonPropertyKeys.Property(
        (String) attribute(property, "value").getValue(),
        access.equals("READ_ONLY"),
        (Boolean) attribute(property, "required").getValue()
      );
    }
    final var alias = find(member, ALIAS);
    final List<String> aliases =
      alias == null
        ? null
        : asList(attribute(alias, "value"))
            .stream()
            .map(v -> (String) v.getValue())
            .toList();
    final var setter = writing && member.getKind() == ElementKind.METHOD;
    return new JsonPropertyKeys.Member(
      writing,
      setter,
      member.getKind() == ElementKind.PARAMETER,
      ignored,
      said,
      aliases
    );
  }

  /**
   * Refuses {@code type} when it carries a Jackson annotation that changes how databind reads it
   * and that the binder does not read, at the places the runtime binder refuses it.
   */
  private void refuseUnsupported(final TypeElement type, final String kind, final Map<String, List<Element>> members) {
    final var owner = type.getSimpleName().toString();
    refuse(owner, type, "the class", NAMING, VIEW, FORMAT);
    refuseIgnoreList(owner, type, "the class");
    refuseComposite(owner, type, "the class");
    for (final var constructor : ElementFilter.constructorsIn(type.getEnclosedElements())) {
      refuse(owner, constructor, "constructor " + signature(owner, constructor), CREATOR);
    }
    for (
      TypeElement c = type;
      c != null && !c.getQualifiedName().contentEquals("java.lang.Object");
      c = superclass(c)
    ) {
      for (final var method : ElementFilter.methodsIn(c.getEnclosedElements())) {
        final var where = "method " + signature(method.getSimpleName().toString(), method);
        refuse(owner, method, where, ANY_SETTER);
        if (method.getModifiers().contains(Modifier.STATIC)) refuse(owner, method, where, CREATOR);
      }
      for (final var field : ElementFilter.fieldsIn(c.getEnclosedElements())) {
        refuse(owner, field, "field '" + field.getSimpleName() + "'", ANY_SETTER);
      }
    }
    for (final var property : members.entrySet()) {
      final var where = kind + " '" + property.getKey() + "'";
      for (final var member : property.getValue()) {
        if (member == null) continue;
        refuse(owner, member, where, UNWRAPPED, VIEW, SETTER, FORMAT);
        refuseIgnoreList(owner, member, where);
        refuseComposite(owner, member, where);
      }
    }
  }

  private void refuseRenamedConstants(final TypeElement type) {
    for (final var field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
      if (field.getKind() != ElementKind.ENUM_CONSTANT) continue;
      final var property = find(field, PROPERTY);
      final var renamed =
        property != null && !attribute(property, "value").getValue().equals(field.getSimpleName().toString());
      final var annotation = renamed ? PROPERTY : find(field, ALIAS) != null ? ALIAS : null;
      if (annotation != null) {
        throw new IllegalArgumentException(
          PairingMessages.jsonEnumConstantRenamed(
            type.getSimpleName().toString(),
            field.getSimpleName().toString(),
            simpleName(annotation)
          )
        );
      }
    }
  }

  private void refuse(final String owner, final Element member, final String where, final String... annotations) {
    for (final var annotation : annotations) {
      if (find(member, annotation) != null) {
        throw new IllegalArgumentException(
          PairingMessages.jsonAnnotationUnsupported(simpleName(annotation), where, owner)
        );
      }
    }
  }

  private void refuseIgnoreList(final String owner, final Element member, final String where) {
    final var ignoreProperties = find(member, IGNORE_PROPERTIES);
    if (ignoreProperties != null && !asList(attribute(ignoreProperties, "value")).isEmpty()) {
      throw new IllegalArgumentException(
        PairingMessages.jsonAnnotationUnsupported(simpleName(IGNORE_PROPERTIES), where, owner)
      );
    }
  }

  private void refuseComposite(final String owner, final Element member, final String where) {
    for (final var annotation : member.getAnnotationMirrors()) {
      final var annotationType = annotation.getAnnotationType().asElement();
      if (find(annotationType, INSIDE) != null) {
        throw new IllegalArgumentException(
          PairingMessages.jsonAnnotationUnsupported(
            annotationType.getSimpleName() + ", which bundles Jackson annotations,",
            where,
            owner
          )
        );
      }
    }
  }

  /** A constructor or method as a message names it: {@code Partner(String, int)}. */
  private static String signature(final String name, final ExecutableElement executable) {
    return (
      name +
      executable
        .getParameters()
        .stream()
        .map(p -> simpleTypeName(p.asType()))
        .collect(Collectors.joining(", ", "(", ")"))
    );
  }

  private static String simpleTypeName(final TypeMirror type) {
    if (type.getKind() == TypeKind.DECLARED) return ((DeclaredType) type).asElement().getSimpleName().toString();
    if (type.getKind() == TypeKind.ARRAY) return simpleTypeName(((ArrayType) type).getComponentType()) + "[]";
    return type.toString();
  }

  private static String simpleName(final String annotation) {
    return annotation.substring(annotation.lastIndexOf('.') + 1);
  }

  private static AnnotationMirror find(final Element element, final String type) {
    for (final var mirror : element.getAnnotationMirrors()) {
      if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(type)) return mirror;
    }
    return null;
  }

  private AnnotationValue attribute(final AnnotationMirror mirror, final String name) {
    for (final var entry : processingEnv.getElementUtils().getElementValuesWithDefaults(mirror).entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(name)) return entry.getValue();
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static List<? extends AnnotationValue> asList(final AnnotationValue value) {
    return (List<? extends AnnotationValue>) value.getValue();
  }

  // ---------------------------------------------------------------------------------------------
  // Types
  // ---------------------------------------------------------------------------------------------

  private VariableElement field(final TypeElement type, final String name) {
    for (
      TypeElement c = type;
      c != null && !c.getQualifiedName().contentEquals("java.lang.Object");
      c = superclass(c)
    ) {
      for (final var field : ElementFilter.fieldsIn(c.getEnclosedElements())) {
        if (field.getSimpleName().contentEquals(name)) return field;
      }
    }
    return null;
  }

  private static TypeElement superclass(final TypeElement type) {
    final var superclass = type.getSuperclass();
    return superclass.getKind() == TypeKind.DECLARED ? (TypeElement) ((DeclaredType) superclass).asElement() : null;
  }

  private static boolean isJdk(final TypeElement type) {
    return MapValueTypes.isJdk(type.getQualifiedName().toString());
  }

  private static boolean isBoolean(final TypeMirror type) {
    return (
      type.getKind() == TypeKind.BOOLEAN ||
      (type.getKind() == TypeKind.DECLARED &&
        ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().contentEquals("java.lang.Boolean"))
    );
  }

  private boolean assignableTo(final TypeMirror type, final String raw) {
    final var types = processingEnv.getTypeUtils();
    final var element = processingEnv.getElementUtils().getTypeElement(raw);
    return element != null && types.isAssignable(types.erasure(type), types.erasure(element.asType()));
  }

  /** The binary name a refusal names a type by, as the runtime's {@code Class.getName} does. */
  private String binaryName(final TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) return null;
    return processingEnv.getElementUtils().getBinaryName((TypeElement) ((DeclaredType) type).asElement()).toString();
  }

  /** What a property holds when the document leaves it without a value, as source. */
  private static String absentOf(final TypeMirror type) {
    return switch (type.getKind()) {
      case BOOLEAN -> "false";
      case CHAR -> "'\\0'";
      case LONG -> "0L";
      case DOUBLE -> "0.0d";
      case FLOAT -> "0.0f";
      case SHORT -> "(short) 0";
      case BYTE -> "(byte) 0";
      case INT -> "0";
      case DECLARED -> switch (((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString()) {
        case "java.util.List" -> "java.util.List.of()";
        case "java.util.Set" -> "java.util.Set.of()";
        case "java.util.Map" -> "java.util.Map.of()";
        case "java.util.Optional" -> "java.util.Optional.empty()";
        default -> "null";
      };
      default -> "null";
    };
  }

  /** A declared type as source, every class by its qualified name. */
  private static String sourceName(final TypeMirror type) {
    if (type.getKind().isPrimitive()) return type.getKind().name().toLowerCase(Locale.ROOT);
    if (type.getKind() != TypeKind.DECLARED) return "java.lang.Object";
    final var declared = (DeclaredType) type;
    final var name = ((TypeElement) declared.asElement()).getQualifiedName().toString();
    if (declared.getTypeArguments().isEmpty()) return name;
    return (
      name +
      declared
        .getTypeArguments()
        .stream()
        .map(FromJsonProcessor::sourceName)
        .collect(Collectors.joining(", ", "<", ">"))
    );
  }

  /** A declared type as a message names it: {@code List<Line>}, {@code int}. */
  private static String declaredName(final TypeMirror type) {
    if (type.getKind().isPrimitive()) return type.getKind().name().toLowerCase(Locale.ROOT);
    if (type.getKind() == TypeKind.ARRAY) return declaredName(((ArrayType) type).getComponentType()) + "[]";
    if (type.getKind() != TypeKind.DECLARED) return type.toString();
    final var declared = (DeclaredType) type;
    final var name = declared.asElement().getSimpleName().toString();
    if (declared.getTypeArguments().isEmpty()) return name;
    return (
      name +
      declared
        .getTypeArguments()
        .stream()
        .map(FromJsonProcessor::declaredName)
        .collect(Collectors.joining(", ", "<", ">"))
    );
  }

  // ---------------------------------------------------------------------------------------------
  // Emission
  // ---------------------------------------------------------------------------------------------

  private void write(final TypeElement target, final Plan plan) {
    final var elements = processingEnv.getElementUtils();
    final var pkg = elements.getPackageOf(target).getQualifiedName().toString();
    final var qualified = pkg.isEmpty() ? plan.binder : pkg + "." + plan.binder;
    final var ref = target.getQualifiedName().toString();
    try {
      final var file = processingEnv.getFiler().createSourceFile(qualified, target);
      try (final var out = new PrintWriter(file.openWriter())) {
        if (!pkg.isEmpty()) out.println("package " + pkg + ";");
        out.println();
        out.println("/** Generated by telescope-jackson for @FromJson " + target.getSimpleName() + ". */");
        out.println("public final class " + plan.binder + " {");
        out.println();
        out.println(
          "  /** Reads a document into " + article(target.getSimpleName().toString()) + " with no reflection. */"
        );
        out.println("  public static final " + BINDER + "<" + ref + "> BINDER = " + BINDER + ".generated(");
        out.println("    " + ref + ".class,");
        out.println("    " + plan.binder + "::" + plan.frames.get(ref).method + ",");
        out.println("    java.util.List.of(" + explain(plan.frames.get(ref)) + ")");
        out.println("  );");
        out.println();
        out.println("  private " + plan.binder + "() {}");
        for (final var frame : plan.frames.values()) {
          out.println();
          emitFrame(out, frame);
        }
        out.println("}");
      }
    } catch (final IOException e) {
      error(target, "@FromJson: could not write " + qualified + ": " + e.getMessage());
    }
  }

  private static String article(final String name) {
    return PairingMessages.withArticle(name);
  }

  private static String explain(final Frame frame) {
    final var rows = new ArrayList<String>();
    for (final var slot : frame.slots) {
      if (slot.ignored()) {
        rows.add("new " + NODE + ".Skipped(\"" + slot.name() + "\", " + NODE + ".Reason.DROPPED)");
      } else if (slot.read() == null && !slot.required()) {
        rows.add("new " + NODE + ".Skipped(\"" + slot.name() + "\", " + NODE + ".Reason.MISSING_SOURCE)");
      } else {
        rows.add(
          "new " +
            NODE +
            ".Extracted(" +
            literal(slot.key()) +
            ", \"" +
            slot.name() +
            "\", " +
            literal(slot.declared()) +
            ", " +
            NODE +
            ".WhenAbsent." +
            (slot.required() ? "REFUSES" : "DEFAULTS") +
            ")"
        );
      }
    }
    return rows.isEmpty() ? "" : "\n      " + String.join(",\n      ", rows) + "\n    ";
  }

  private static void emitFrame(final PrintWriter out, final Frame frame) {
    final var type = frame.type.getQualifiedName().toString();
    out.println("  private static " + type + " " + frame.method + "(final " + PARSER + " p) {");
    for (var i = 0; i < frame.slots.size(); i++) {
      final var slot = frame.slots.get(i);
      out.println("    " + slot.type() + " v" + i + " = " + slot.absent() + ";");
      if (slot.required()) out.println("    boolean s" + i + " = false;");
    }
    out.println("    for (java.lang.String key = p.nextName(); key != null; key = p.nextName()) {");
    out.println("      final " + TOKEN + " token = p.nextToken();");
    out.println("      switch (key) {");
    for (var i = 0; i < frame.slots.size(); i++) {
      final var slot = frame.slots.get(i);
      if (!slot.claimed()) continue;
      final var labels = new ArrayList<String>();
      labels.add(literal(slot.key()));
      for (final var alias : slot.aliases()) if (!alias.equals(slot.key())) labels.add(literal(alias));
      out.println("        case " + String.join(", ", labels) + " -> {");
      out.println("          if (token == " + TOKEN + ".VALUE_NULL) {");
      out.println("            v" + i + " = " + slot.absent() + ";");
      if (slot.required()) out.println("            s" + i + " = false;");
      out.println("          } else {");
      if (slot.read() == null) {
        out.println("            p.skipChildren();");
      } else {
        out.println("            try {");
        out.println("              v" + i + " = " + slot.read() + ";");
        out.println("            } catch (final java.lang.RuntimeException e) {");
        out.println("              throw " + READS + ".under(e, \"" + slot.name() + "\", key);");
        out.println("            }");
      }
      if (slot.required()) out.println("            s" + i + " = true;");
      out.println("          }");
      out.println("        }");
    }
    out.println("        default -> p.skipChildren();");
    out.println("      }");
    out.println("    }");
    final var required = new ArrayList<Integer>();
    for (var i = 0; i < frame.slots.size(); i++) if (frame.slots.get(i).required()) required.add(i);
    if (!required.isEmpty()) {
      final var guard = required
        .stream()
        .map(i -> "!s" + i)
        .collect(Collectors.joining(" || "));
      final var entries = required
        .stream()
        .map(
          i ->
            "s" +
            i +
            " ? null : " +
            literal("\"" + frame.slots.get(i).key() + "\" (" + frame.kind + " '" + frame.slots.get(i).name() + "')")
        )
        .collect(Collectors.joining(", "));
      out.println(
        "    if (" +
          guard +
          ") throw " +
          READS +
          ".missing(p, \"" +
          frame.type.getSimpleName() +
          "\", " +
          entries +
          ");"
      );
    }
    if (frame.record) {
      final var args = new ArrayList<String>();
      for (var i = 0; i < frame.slots.size(); i++) args.add("v" + i);
      out.println("    return new " + type + "(" + String.join(", ", args) + ");");
    } else {
      emitBeanBuild(out, frame, type);
    }
    out.println("  }");
  }

  /** The primitive types, as a local's declared type names them. */
  private static final Set<String> PRIMITIVES = Set.of(
    "boolean",
    "byte",
    "char",
    "short",
    "int",
    "long",
    "float",
    "double"
  );

  /**
   * Builds a bean from the frame's locals, {@code v<i>} for property {@code i}, through the
   * strategy its rebuild took: a builder chain, a constructor call or setter calls, as the other
   * telescope processors write them.
   */
  private static void emitBeanBuild(final PrintWriter out, final Frame frame, final String type) {
    final var rebuild = frame.rebuild;
    final var members = rebuild.members();
    switch (rebuild.strategy()) {
      case BUILDER -> {
        final var chain = new StringBuilder("    return " + type + ".builder()");
        for (var i = 0; i < members.length; i++) {
          if (members[i] != null) chain.append("." + members[i] + "(v" + i + ")");
        }
        out.println(chain.append(".build();"));
      }
      case CONSTRUCTOR -> {
        final var prelude = new StringBuilder();
        final var args = guardedArguments(
          frame.props,
          rebuild.ctorOrder(),
          rebuild,
          prop -> "v" + frame.props.indexOf(prop),
          prelude
        );
        if (!prelude.isEmpty()) out.println("    " + prelude.toString().strip());
        out.println("    return new " + type + "(" + String.join(", ", args) + ");");
      }
      default -> {
        out.println("    final " + type + " bean = new " + type + "();");
        for (var i = 0; i < members.length; i++) {
          if (members[i] == null) continue;
          final var call = rebuild.setterCall(i, "bean", members[i], "v" + i);
          final var nullable = !PRIMITIVES.contains(frame.slots.get(i).type());
          out.println("    " + (rebuild.nullGuarded()[i] && nullable ? "if (v" + i + " != null) " : "") + call);
        }
        out.println("    return bean;");
      }
    }
  }

  /** {@code text} as a Java string literal. */
  private static String literal(final String text) {
    final var out = new StringBuilder("\"");
    for (final var c : text.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    return out.append('"').toString();
  }
}
