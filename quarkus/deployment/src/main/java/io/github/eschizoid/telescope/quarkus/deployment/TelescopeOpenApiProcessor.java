package io.github.eschizoid.telescope.quarkus.deployment;

import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.util.JandexUtil;
import io.quarkus.smallrye.openapi.deployment.spi.AddToOpenAPIDefinitionBuildItem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBodySchema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

/**
 * Documents the mapped bodies in SmallRye OpenAPI, when the application has it. The build item this
 * step produces is consumed only by the SmallRye OpenAPI extension, so in an application without it
 * the step never runs, and nothing it reads is loaded.
 */
class TelescopeOpenApiProcessor {

  private static final Logger LOGGER = Logger.getLogger(TelescopeOpenApiProcessor.class);

  private static final DotName APPLICATION_PATH = DotName.createSimple("jakarta.ws.rs.ApplicationPath");
  private static final DotName API_RESPONSE = DotName.createSimple(APIResponse.class);
  private static final DotName API_RESPONSES = DotName.createSimple(APIResponses.class);
  private static final DotName API_RESPONSE_SCHEMA = DotName.createSimple(APIResponseSchema.class);
  private static final DotName REQUEST_BODY = DotName.createSimple(RequestBody.class);
  private static final DotName REQUEST_BODY_SCHEMA = DotName.createSimple(RequestBodySchema.class);

  @BuildStep
  void document(
    final MappedBodiesBuildItem bodies,
    final CombinedIndexBuildItem combined,
    final Capabilities capabilities,
    final BuildProducer<AddToOpenAPIDefinitionBuildItem> definitions
  ) {
    if (!capabilities.isPresent(Capability.SMALLRYE_OPENAPI) || bodies.bodies().isEmpty()) return;
    final var index = combined.getIndex();
    final var config = ConfigProvider.getConfig();
    final var read = new ScanFilter(config);
    final var documented = new ArrayList<MappedBody>();
    for (final var body : bodies.bodies())
      if (classes(body).stream().anyMatch(read::reads)) documented.add(body);
      else LOGGER.debug(
        body.where() + " is in a class the OpenAPI scan leaves out, so no wire type is generated for it"
      );
    final var methods = methods(documented, index);
    if (methods.isEmpty()) return;
    final var generated = WireResources.generate(methods);
    final var rootPath = config.getOptionalValue("quarkus.http.root-path", String.class).orElse("/");
    final var restPath = config.getOptionalValue("quarkus.rest.path", String.class).orElse("");
    definitions.produce(
      new AddToOpenAPIDefinitionBuildItem(
        new WireDocumentation(
          new ResourceScope(CompositeIndex.create(index, generated.index()), scope(documented, generated, index, read)),
          generated,
          Thread.currentThread().getContextClassLoader(),
          new EveryClassConfig(config),
          contextRoot(rootPath, restPath)
        ),
        null
      )
    );
  }

  // The wire types of each mapped method, a response and a request body found on one method
  // documented together. A body whose method names a content schema of its own in an OpenAPI
  // annotation keeps that schema, and is left out.
  private static List<WireResources.Method> methods(final List<MappedBody> bodies, final IndexView index) {
    final var byMethod = new LinkedHashMap<List<String>, WireResources.Wire[]>();
    for (final var body : bodies) {
      final var wire = new WireResources.Wire(wireType(body, index), body.shape(), body.required());
      if (wire.type() == null) continue;
      if (declaresSchema(body)) {
        LOGGER.debugf(
          "%s declares its own %s schema in an OpenAPI annotation, which is kept",
          body.where(),
          body.request() ? "request body" : "response"
        );
        continue;
      }
      final var both = byMethod.computeIfAbsent(body.methodIds(), ids -> new WireResources.Wire[2]);
      both[body.request() ? 1 : 0] = wire;
    }
    final var methods = new ArrayList<WireResources.Method>(byMethod.size());
    for (final var entry : byMethod.entrySet())
      methods.add(new WireResources.Method(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
    return methods;
  }

  // Whether the body's method names a schema for that body itself: an @APIResponse, directly or in
  // @APIResponses, or an @RequestBody, on the method or a parameter, whose content names one, or an
  // @APIResponseSchema or @RequestBodySchema.
  private static boolean declaresSchema(final MappedBody body) {
    final var method = body.method();
    if (body.request()) return (
      !method.annotations(REQUEST_BODY_SCHEMA).isEmpty() ||
      method.annotations(REQUEST_BODY).stream().anyMatch(TelescopeOpenApiProcessor::namesSchema)
    );
    if (method.hasDeclaredAnnotation(API_RESPONSE_SCHEMA)) return true;
    final var responses = new ArrayList<AnnotationInstance>();
    final var single = method.declaredAnnotation(API_RESPONSE);
    if (single != null) responses.add(single);
    final var container = method.declaredAnnotation(API_RESPONSES);
    if (container != null && container.value() != null) responses.addAll(List.of(container.value().asNestedArray()));
    return responses.stream().anyMatch(TelescopeOpenApiProcessor::namesSchema);
  }

  private static boolean namesSchema(final AnnotationInstance annotation) {
    final var content = annotation.value("content");
    if (content == null) return false;
    for (final var each : content.asNestedArray()) if (each.value("schema") != null) return true;
    return false;
  }

  // The classes that declare or route the body's method.
  private static List<DotName> classes(final MappedBody body) {
    final var classes = new ArrayList<DotName>();
    classes.add(body.method().declaringClass().name());
    for (final var id : body.methodIds()) classes.add(DotName.createSimple(id.substring(0, id.indexOf('#'))));
    return classes;
  }

  // The classes the wire scan finds resources in: the generated ones, each class that declares or
  // routes a documented method, and each class whose sub-resource locator returns one of those, at
  // any depth, leaving out every class the application's own scan does not read.
  private static Set<DotName> scope(
    final List<MappedBody> bodies,
    final WireResources.Generated generated,
    final IndexView index,
    final ScanFilter read
  ) {
    final var scoped = new LinkedHashSet<DotName>();
    for (final var name : generated.classes().keySet()) scoped.add(DotName.createSimple(name));
    for (final var body : bodies) for (final var name : classes(body)) if (read.reads(name)) scoped.add(name);
    var grown = true;
    while (grown) {
      grown = false;
      for (final var path : index.getAnnotations(ResourceScope.PATH)) {
        if (path.target().kind() != AnnotationTarget.Kind.METHOD) continue;
        final var locator = path.target().asMethod();
        final var parent = locator.declaringClass().name();
        if (
          MappedBodies.routed(index, locator) || !read.reads(parent) || !scoped.contains(locator.returnType().name())
        ) continue;
        if (scoped.add(parent)) grown = true;
      }
    }
    return scoped;
  }

  // The type a body is written as, or read as, which a projection named by using supplies when the
  // annotation does not.
  private static String wireType(final MappedBody body, final IndexView index) {
    final var named = body.request() ? body.source() : body.target();
    if (named != null || body.using() == null) return named;
    final List<Type> arguments = JandexUtil.resolveTypeParameters(
      DotName.createSimple(body.using()),
      MappedBodies.PROJECTION,
      index
    );
    final var argument = arguments.get(body.request() ? 0 : 1);
    return argument.kind() == Type.Kind.CLASS ? argument.name().toString() : null;
  }

  // The context root SmallRye OpenAPI prefixes every path with in a Quarkus application: the HTTP
  // root path, then the REST path unless an application class names its own path, which the
  // scanner then prefixes itself.
  private static Function<Collection<ClassInfo>, String> contextRoot(final String rootPath, final String restPath) {
    return applications -> {
      final var annotated = applications
        .stream()
        .map(application -> application.declaredAnnotation(APPLICATION_PATH))
        .anyMatch(Objects::nonNull);
      final var path = Stream.of(annotated ? new String[] { rootPath } : new String[] { rootPath, restPath })
        .map(TelescopeOpenApiProcessor::stripSlashes)
        .filter(segment -> !segment.isEmpty())
        .map("/"::concat)
        .collect(Collectors.joining());
      return path.isEmpty() ? null : path;
    };
  }

  private static String stripSlashes(final String segment) {
    var stripped = segment;
    if (stripped.startsWith("/")) stripped = stripped.substring(1);
    if (stripped.endsWith("/")) stripped = stripped.substring(0, stripped.length() - 1);
    return stripped;
  }
}
