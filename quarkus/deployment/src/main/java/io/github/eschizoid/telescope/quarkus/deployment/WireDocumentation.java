package io.github.eschizoid.telescope.quarkus.deployment;

import io.smallrye.openapi.api.SmallRyeOpenAPI;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.logging.Logger;

/**
 * Documents every mapped body with its wire type. For each document it scans the application again,
 * together with the generated {@link WireResources}, through SmallRye OpenAPI's public builder: an
 * operation handler marks each operation with the ids Quarkus REST keys its resource method by, one
 * for each concrete class that routes it, since SmallRye reports a resource whose {@code @Path}
 * sits on an interface as that interface. A mapped method's operation is so found under the path
 * and HTTP method SmallRye gave it, whether the method is declared on the resource class or on an
 * interface whose {@code @Path} it takes, or on a sub-resource a locator returns. The scan resolves
 * the context root as the application's own document does, from {@code quarkus.http.root-path} and
 * either {@code @ApplicationPath} or {@code quarkus.rest.path}, so the paths of the two documents
 * agree. Every success response of each matched operation that carries a body, and its request
 * body, then take the generated method's schemas in each of their media types, and {@link
 * WireComponents} brings the component schemas those name into the document.
 */
final class WireDocumentation implements OASFilter {

  private static final Logger LOGGER = Logger.getLogger(WireDocumentation.class);

  private static final String METHOD = "x-telescope-resource-method";

  private final IndexView index;
  private final Map<String, WireResources.Method> generated;
  private final Map<String, String> generatedByMethodId;
  private final ClassLoader loader;
  private final Config config;
  private final Function<Collection<ClassInfo>, String> contextRoot;

  /**
   * @param index the application's index with the generated wire resources
   * @param generated the generated wire resources
   * @param loader the class loader the scan loads the application's classes with
   * @param config the configuration the scan reads
   * @param contextRoot the context root of every path, given the application classes
   */
  WireDocumentation(
    final IndexView index,
    final WireResources.Generated generated,
    final ClassLoader loader,
    final Config config,
    final Function<Collection<ClassInfo>, String> contextRoot
  ) {
    this.index = index;
    this.generated = generated.classes();
    this.generatedByMethodId = new HashMap<>();
    for (final var entry : this.generated.entrySet())
      for (final var id : entry.getValue().methodIds()) generatedByMethodId.put(id, entry.getKey());
    this.loader = loader;
    this.config = config;
    this.contextRoot = contextRoot;
  }

  @Override
  public void filterOpenAPI(final OpenAPI document) {
    if (document.getPaths() == null || document.getPaths().getPathItems() == null) return;
    final var scanned = scan();
    final var wires = new HashMap<String, Operation>();
    final var located = new HashMap<String, List<Located>>();
    for (final var path : pathItems(scanned).entrySet())
      for (final var operation : operations(path.getValue()).entrySet()) {
        final var extensions = operation.getValue().getExtensions();
        final var ids = extensions == null ? null : (String) extensions.get(METHOD);
        if (ids == null) continue;
        for (final var id : ids.split("\n")) {
          final var owner = id.substring(0, id.indexOf('#'));
          if (generated.containsKey(owner)) wires.put(owner, operation.getValue());
          else if (generatedByMethodId.containsKey(id)) located
            .computeIfAbsent(id, key -> new ArrayList<>())
            .add(new Located(path.getKey(), operation.getKey()));
        }
      }
    // A sub-resource reached through several locators is one operation under each of their paths.
    final var matched = new ArrayList<Matched>();
    for (final var entry : generatedByMethodId.entrySet()) {
      final var wire = wires.get(entry.getValue());
      var found = false;
      for (final var where : located.getOrDefault(entry.getKey(), List.of())) {
        final var item = document.getPaths().getPathItem(where.path());
        final var operation = item == null ? null : operations(item).get(where.method());
        if (operation == null || wire == null) continue;
        matched.add(new Matched(operation, wire, generated.get(entry.getValue())));
        found = true;
      }
      if (!found) LOGGER.debugf(
        "%s is not in the OpenAPI document, so its wire types are not documented",
        entry.getKey()
      );
    }
    final var roots = new ArrayList<Schema>();
    for (final var each : matched) {
      if (each.method().response() != null) roots.add(responseSchema(each.wire()));
      if (each.method().request() != null) roots.add(requestSchema(each.wire()));
    }
    final Map<String, Schema> available =
      scanned.getComponents() == null || scanned.getComponents().getSchemas() == null
        ? Map.of()
        : scanned.getComponents().getSchemas();
    WireComponents.copy(document, available, roots);
    for (final var each : matched) {
      if (each.method().response() != null) documentResponse(each.operation(), responseSchema(each.wire()));
      if (each.method().request() != null) documentRequest(each.operation(), each.wire());
    }
  }

  private OpenAPI scan() {
    return SmallRyeOpenAPI.builder()
      .withConfig(config)
      .withIndex(index)
      .withApplicationClassLoader(loader)
      .withScannerClassLoader(loader)
      .enableModelReader(false)
      .enableStandardStaticFiles(false)
      .enableStandardFilter(false)
      .enableAnnotationScan(true)
      .enableUnannotatedPathParameters(true)
      .withContextRootResolver(contextRoot)
      .withOperationHandler((operation, resource, method) -> operation.addExtension(METHOD, ids(resource, method)))
      .build()
      .model();
  }

  // The ids of the method an operation was built from. A concrete resource class routes it itself;
  // a resource SmallRye reports as an interface or an abstract class is routed by each concrete
  // class inheriting from it.
  private String ids(final ClassInfo resource, final MethodInfo method) {
    if (!resource.isInterface() && !Modifier.isAbstract(resource.flags())) return MappedBodies.methodId(
      index,
      resource,
      method
    );
    return String.join("\n", MappedBodies.methodIds(index, resource, method));
  }

  private static Schema responseSchema(final Operation wire) {
    return firstSchema(wire.getResponses().getAPIResponse("200").getContent());
  }

  private static Schema requestSchema(final Operation wire) {
    return firstSchema(wire.getRequestBody().getContent());
  }

  // Every success response that carries a body takes the wire schema in each of its media types.
  private static void documentResponse(final Operation operation, final Schema schema) {
    if (
      schema == null || operation.getResponses() == null || operation.getResponses().getAPIResponses() == null
    ) return;
    for (final var response : operation.getResponses().getAPIResponses().entrySet())
      if (response.getKey().startsWith("2")) withSchema(response.getValue().getContent(), schema);
  }

  // The request body takes the wire schema in each of its media types, and whether one is required.
  private static void documentRequest(final Operation operation, final Operation wire) {
    final var schema = requestSchema(wire);
    if (schema == null || operation.getRequestBody() == null) return;
    withSchema(operation.getRequestBody().getContent(), schema);
    operation.getRequestBody().setRequired(wire.getRequestBody().getRequired());
  }

  private static void withSchema(final Content content, final Schema schema) {
    if (content == null || content.getMediaTypes() == null) return;
    for (final var media : content.getMediaTypes().values()) media.setSchema(schema);
  }

  private static Schema firstSchema(final Content content) {
    if (content == null || content.getMediaTypes() == null) return null;
    for (final var media : content.getMediaTypes().values()) if (media.getSchema() != null) return media.getSchema();
    return null;
  }

  private static Map<String, PathItem> pathItems(final OpenAPI model) {
    return model.getPaths() == null || model.getPaths().getPathItems() == null
      ? Map.of()
      : model.getPaths().getPathItems();
  }

  private static Map<PathItem.HttpMethod, Operation> operations(final PathItem item) {
    final var operations = item.getOperations();
    return operations == null ? Map.of() : operations;
  }

  private record Located(String path, PathItem.HttpMethod method) {}

  private record Matched(Operation operation, Operation wire, WireResources.Method method) {}
}
