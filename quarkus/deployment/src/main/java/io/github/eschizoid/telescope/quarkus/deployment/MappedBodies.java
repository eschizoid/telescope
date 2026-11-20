package io.github.eschizoid.telescope.quarkus.deployment;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequest;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequestBody;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.rest.runtime.ValueShape;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.PrimitiveType;
import org.jboss.jandex.Type;

/**
 * Finds every mapped body in the application's index and classifies where its values sit. A method
 * or parameter the runtime could not map is refused here, with the method named, so a misconfigured
 * endpoint fails the build, which in dev and test mode is the application's boot.
 */
final class MappedBodies {

  static final DotName RESPONSE = DotName.createSimple(TelescopeResponse.class);
  static final DotName REQUEST_BODY = DotName.createSimple(TelescopeRequestBody.class);
  static final DotName REQUEST = DotName.createSimple(TelescopeRequest.class);
  static final DotName PROJECTION = DotName.createSimple(TelescopeProjection.class);

  private static final String RESPONSE_NAME = "@" + TelescopeResponse.class.getSimpleName();
  private static final String REQUEST_BODY_NAME = "@" + TelescopeRequestBody.class.getSimpleName();

  private static final DotName VOID = DotName.createSimple(Void.class);
  private static final DotName OBJECT = DotName.createSimple(Object.class);
  private static final DotName LIST = DotName.createSimple(List.class);
  private static final DotName COLLECTION = DotName.createSimple("java.util.Collection");
  private static final DotName SET = DotName.createSimple(Set.class);
  private static final DotName OPTIONAL = DotName.createSimple("java.util.Optional");
  private static final DotName VALID = DotName.createSimple("jakarta.validation.Valid");
  private static final DotName HTTP_METHOD = DotName.createSimple("jakarta.ws.rs.HttpMethod");
  private static final DotName RESPONSE_TYPE = DotName.createSimple("jakarta.ws.rs.core.Response");
  private static final DotName REST_RESPONSE = DotName.createSimple("org.jboss.resteasy.reactive.RestResponse");
  private static final DotName MULTI = DotName.createSimple("io.smallrye.mutiny.Multi");
  private static final Set<DotName> ASYNC = Set.of(
    DotName.createSimple("io.smallrye.mutiny.Uni"),
    DotName.createSimple("java.util.concurrent.CompletionStage"),
    DotName.createSimple("java.util.concurrent.CompletableFuture")
  );
  private static final Set<String> HTTP_METHODS = Set.of(
    "jakarta.ws.rs.GET",
    "jakarta.ws.rs.POST",
    "jakarta.ws.rs.PUT",
    "jakarta.ws.rs.PATCH",
    "jakarta.ws.rs.DELETE",
    "jakarta.ws.rs.HEAD",
    "jakarta.ws.rs.OPTIONS"
  );
  // Parameter annotations that make Quarkus REST bind a parameter from somewhere other than the
  // body, so the body reader never sees it.
  private static final List<String> BINDING_PACKAGES = List.of("jakarta.ws.rs.", "org.jboss.resteasy.reactive.Rest");
  private static final Set<String> BINDING_EXCEPTIONS = Set.of(
    "jakarta.ws.rs.Consumes",
    "jakarta.ws.rs.Produces",
    "jakarta.ws.rs.Encoded",
    "jakarta.ws.rs.DefaultValue"
  );
  // JDK types that are never a domain value to map.
  private static final List<Class<?>> NOT_SOURCES = List.of(
    CharSequence.class,
    Number.class,
    Boolean.class,
    Character.class
  );

  private MappedBodies() {}

  /**
   * Every mapped body in {@code index}.
   *
   * @param validation whether the application validates, so a {@code @Valid} body is validated as
   *     read
   * @throws IllegalStateException naming the first method or parameter that cannot be mapped
   */
  static List<MappedBody> scan(final IndexView index, final boolean validation) {
    final var bodies = new ArrayList<MappedBody>();
    for (final var annotation : index.getAnnotations(RESPONSE))
      if (annotation.target().kind() == AnnotationTarget.Kind.METHOD) bodies.add(
        response(index, annotation.target().asMethod(), annotation)
      );
    final var parameters = new LinkedHashMap<MethodInfo, Integer>();
    for (final var annotation : index.getAnnotations(REQUEST_BODY)) {
      if (annotation.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
        final var parameter = annotation.target().asMethodParameter();
        parameters.putIfAbsent(parameter.method(), (int) parameter.position());
      }
    }
    // A typed TelescopeRequest parameter is a body only on a method Quarkus REST routes; a helper,
    // a bean method or a lambda's synthetic method may take one as any other value.
    for (final var user : index.getKnownUsers(REQUEST))
      for (final var method : user.methods()) {
        if (method.isSynthetic() || !routed(index, method)) continue;
        for (var position = 0; position < method.parametersCount(); position++) if (
          method.parameterType(position).name().equals(REQUEST)
        ) parameters.putIfAbsent(method, position);
      }
    for (final Map.Entry<MethodInfo, Integer> parameter : parameters.entrySet())
      bodies.add(request(index, parameter.getKey(), parameter.getValue(), validation));
    return bodies;
  }

  private static MappedBody response(
    final IndexView index,
    final MethodInfo method,
    final AnnotationInstance annotation
  ) {
    final var where = where(method);
    try {
      requireRouted(index, method, RESPONSE_NAME);
      final var to = classValue(annotation, "to", VOID);
      final var using = classValue(annotation, "using", PROJECTION);
      if (to == null && using == null) throw refused("set to or using on " + RESPONSE_NAME + ".");
      final var returnType = method.returnType();
      final var shape = responseShape(index, returnType);
      return new MappedBody(
        methodIds(index, method),
        where,
        RESPONSE_NAME,
        false,
        shape.shape(),
        shape.element(),
        to,
        using,
        false,
        false,
        false,
        null,
        method
      );
    } catch (final IllegalArgumentException e) {
      throw new IllegalStateException(where + ": " + e.getMessage(), e);
    }
  }

  private static MappedBody request(
    final IndexView index,
    final MethodInfo method,
    final int position,
    final boolean validation
  ) {
    final var given = method.parameterName(position);
    final var name = given == null ? "arg" + position : given;
    final var where = where(method) + "(" + name + ")";
    try {
      requireRouted(index, method, REQUEST_BODY_NAME);
      final var annotation = method
        .parameters()
        .get(position)
        .declaredAnnotations()
        .stream()
        .filter(each -> each.name().equals(REQUEST_BODY))
        .findFirst()
        .orElse(null);
      for (final var other : method.parameters().get(position).declaredAnnotations()) {
        final var otherName = other.name().toString();
        if (
          !BINDING_EXCEPTIONS.contains(otherName) && BINDING_PACKAGES.stream().anyMatch(otherName::startsWith)
        ) throw refused(
          "remove @" + other.name().withoutPackagePrefix() + "; " + REQUEST_BODY_NAME + " reads the body."
        );
      }
      final var from = annotation == null ? null : classValue(annotation, "from", VOID);
      final var using = annotation == null ? null : classValue(annotation, "using", PROJECTION);
      final var requiredValue = annotation == null ? null : annotation.value("required");
      final var required = requiredValue == null || requiredValue.asBoolean();
      final var validated =
        validation &&
        method
          .parameters()
          .get(position)
          .declaredAnnotations()
          .stream()
          .anyMatch(each -> each.name().equals(VALID));
      final var type = method.parameterType(position);
      if (type.name().equals(REQUEST)) {
        if (from != null) throw refused(
          "from is not allowed on a TelescopeRequest parameter; its first type argument is the wire type."
        );
        final var arguments =
          type.kind() == Type.Kind.PARAMETERIZED_TYPE ? type.asParameterizedType().arguments() : List.<Type>of();
        final var original = arguments.size() == 2 ? concrete(index, arguments.get(0)) : null;
        final var mapped = arguments.size() == 2 ? concrete(index, arguments.get(1)) : null;
        if (original == null || mapped == null) throw refused(
          "TelescopeRequest needs two concrete type arguments, such as TelescopeRequest<CreateUserRequest, UserDto>."
        );
        return new MappedBody(
          methodIds(index, method),
          where,
          REQUEST_BODY_NAME,
          true,
          ValueShape.VALUE,
          original,
          mapped,
          using,
          true,
          required,
          validated,
          method.name() + "." + name + ".original",
          method
        );
      }
      final var shape = parameterShape(index, type);
      if (from == null && using == null) throw refused("set from or using on " + REQUEST_BODY_NAME + ".");
      return new MappedBody(
        methodIds(index, method),
        where,
        REQUEST_BODY_NAME,
        true,
        shape.shape(),
        from,
        shape.element(),
        using,
        false,
        required,
        validated,
        method.name() + "." + name,
        method
      );
    } catch (final IllegalArgumentException e) {
      throw new IllegalStateException(where + ": " + e.getMessage(), e);
    }
  }

  private record Shape(ValueShape shape, String element) {}

  private static Shape responseShape(final IndexView index, final Type returnType) {
    var type = returnType;
    if (type.kind() == Type.Kind.PARAMETERIZED_TYPE && ASYNC.contains(type.name())) type = type
      .asParameterizedType()
      .arguments()
      .getFirst();
    if (type.name().equals(MULTI)) throw refused(
      "a Multi writes its elements as they arrive, past the response filters, so they cannot be mapped. Return" +
        " Uni<List<S>> instead."
    );
    if (type.name().equals(RESPONSE_TYPE)) throw refused(
      "a Response names no entity type to find a mapper for. Return RestResponse<S> instead, where S is the type" +
        " the mapper reads."
    );
    if (type.kind() == Type.Kind.PARAMETERIZED_TYPE && type.name().equals(REST_RESPONSE)) type = type
      .asParameterizedType()
      .arguments()
      .getFirst();
    final var shape = containerShape(index, type);
    if (shape == null) throw refused(
      "unsupported return type " +
        returnType +
        ". A mapped response returns S, List<S>, Collection<S>, Set<S>, Optional<S>, a RestResponse of one of" +
        " those, or a Uni or CompletionStage of any of these, where S is the type the mapper reads."
    );
    return shape;
  }

  private static Shape parameterShape(final IndexView index, final Type type) {
    final var shape = containerShape(index, type);
    if (shape == null || shape.shape() == ValueShape.OPTIONAL) throw refused(
      "unsupported parameter type " +
        type +
        ". A mapped request body is read into T, List<T>, Collection<T> or Set<T>, or a TelescopeRequest<S, T>."
    );
    return shape;
  }

  // The shape of a body type and the type of the values it holds, or null when it is none.
  private static Shape containerShape(final IndexView index, final Type type) {
    if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
      final var name = type.name();
      final ValueShape shape;
      if (name.equals(LIST) || name.equals(COLLECTION)) shape = ValueShape.LIST;
      else if (name.equals(SET)) shape = ValueShape.SET;
      else if (name.equals(OPTIONAL)) shape = ValueShape.OPTIONAL;
      else return null;
      final var element = concrete(index, type.asParameterizedType().arguments().getFirst());
      return element == null ? null : new Shape(shape, element);
    }
    final var element = concrete(index, type);
    return element == null ? null : new Shape(ValueShape.VALUE, element);
  }

  // The binary name of a class a mapper can read or write, or null for a primitive, an array, a
  // type variable, a class with type parameters used raw, Object, Void or a JDK scalar. A class
  // outside the index, such as a framework or library type, is loaded to read its type parameters;
  // loading application classes in a build step is safe in Quarkus.
  private static String concrete(final IndexView index, final Type type) {
    if (type.kind() != Type.Kind.CLASS) return null;
    final var name = type.name();
    if (name.equals(OBJECT) || name.equals(VOID)) return null;
    final var info = index.getClassByName(name);
    if (info != null) return info.typeParameters().isEmpty() ? name.toString() : null;
    final Class<?> loaded;
    try {
      loaded = Class.forName(name.toString(), false, Thread.currentThread().getContextClassLoader());
    } catch (final ClassNotFoundException unknown) {
      return name.toString();
    }
    if (loaded.getTypeParameters().length > 0) return null;
    for (final var excluded : NOT_SOURCES) if (excluded.isAssignableFrom(loaded)) return null;
    return name.toString();
  }

  // Whether Quarkus REST routes the method: it carries one of the JAX-RS HTTP method annotations,
  // or an annotation that is itself annotated with @HttpMethod.
  static boolean routed(final IndexView index, final MethodInfo method) {
    for (final var each : method.declaredAnnotations()) {
      if (each.target().kind() != AnnotationTarget.Kind.METHOD) continue;
      if (HTTP_METHODS.contains(each.name().toString())) return true;
      final var type = index.getClassByName(each.name());
      if (type != null && type.hasDeclaredAnnotation(HTTP_METHOD)) return true;
    }
    return false;
  }

  private static void requireRouted(final IndexView index, final MethodInfo method, final String annotation) {
    if (!routed(index, method)) throw refused(
      annotation +
        " is on a method Quarkus REST does not route. Annotate it with @GET, @POST, @PUT, @PATCH, @DELETE," +
        " @HEAD or @OPTIONS."
    );
  }

  // The class named by an annotation member, or null when the member is unset or names the
  // member's sentinel.
  private static String classValue(final AnnotationInstance annotation, final String member, final DotName unset) {
    final AnnotationValue value = annotation.value(member);
    if (value == null) return null;
    final var name = value.asClass().name();
    return name.equals(unset) ? null : name.toString();
  }

  // The method's id for each resource class that routes it: the class declaring it when concrete,
  // and every concrete class that inherits it.
  private static List<String> methodIds(final IndexView index, final MethodInfo method) {
    return methodIds(index, method.declaringClass(), method);
  }

  /**
   * The id of {@code method} for each resource class that routes it as a member of {@code
   * resource}: {@code resource} when concrete, and every concrete class that inherits from it.
   */
  static List<String> methodIds(final IndexView index, final ClassInfo resource, final MethodInfo method) {
    final var classes = new ArrayList<ClassInfo>();
    classes.add(resource);
    if (resource.isInterface()) classes.addAll(index.getAllKnownImplementations(resource.name()));
    else classes.addAll(index.getAllKnownSubclasses(resource.name()));
    final var ids = new ArrayList<String>();
    for (final var routing : classes) {
      if (routing.isInterface() || Modifier.isAbstract(routing.flags())) continue;
      ids.add(methodId(index, routing, method));
    }
    return ids;
  }

  /** The id Quarkus REST keys {@code method} by when {@code resource} routes it. */
  static String methodId(final IndexView index, final ClassInfo resource, final MethodInfo method) {
    final var parameters = new String[method.parametersCount()];
    for (var position = 0; position < parameters.length; position++) parameters[position] = erasure(
      index,
      method.parameterType(position)
    );
    return resource.name() + "#" + method.name() + "(" + Arrays.toString(parameters) + ")";
  }

  // The binary name of a parameter type's erasure, as Class.getName spells it.
  private static String erasure(final IndexView index, final Type type) {
    return switch (type.kind()) {
      case TYPE_VARIABLE -> {
        final var bounds = type.asTypeVariable().bounds();
        yield bounds.isEmpty() ? OBJECT.toString() : erasure(index, bounds.getFirst());
      }
      case ARRAY -> {
        final var array = type.asArrayType();
        final var component = array.elementType();
        final var prefix = "[".repeat(array.deepDimensions());
        yield switch (component.kind()) {
          case PRIMITIVE -> prefix + descriptor(component.asPrimitiveType().primitive());
          default -> prefix + "L" + erasure(index, component) + ";";
        };
      }
      default -> type.name().toString();
    };
  }

  private static char descriptor(final PrimitiveType.Primitive primitive) {
    return switch (primitive) {
      case BOOLEAN -> 'Z';
      case BYTE -> 'B';
      case CHAR -> 'C';
      case DOUBLE -> 'D';
      case FLOAT -> 'F';
      case INT -> 'I';
      case LONG -> 'J';
      case SHORT -> 'S';
    };
  }

  private static String where(final MethodInfo method) {
    return method.declaringClass().simpleName() + "#" + method.name();
  }

  private static IllegalArgumentException refused(final String message) {
    return new IllegalArgumentException(message);
  }
}
