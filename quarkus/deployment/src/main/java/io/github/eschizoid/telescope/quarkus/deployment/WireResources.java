package io.github.eschizoid.telescope.quarkus.deployment;

import io.github.eschizoid.telescope.quarkus.rest.runtime.ValueShape;
import io.quarkus.gizmo.AnnotationCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.Type;

/**
 * The resource classes that name each mapped method's wire types to SmallRye OpenAPI, one class per
 * mapped method, generated at build time and indexed in memory. Each has a single {@code POST}
 * method that carries an {@code @APIResponse} whose content's schema is the response's wire type,
 * and a {@code @RequestBody} whose content's schema is the request body's, each an array where the
 * body is a {@code List} or {@code Set}. The classes are never written to the application; only the
 * scan that reads their schemas sees them.
 */
final class WireResources {

  /** The package the generated classes are declared in. */
  static final String PACKAGE = "io.github.eschizoid.telescope.quarkus.deployment.wire";

  private WireResources() {}

  /**
   * One wire type and where its values sit in the body.
   *
   * @param type the wire type's binary name
   * @param shape where its values sit
   * @param required for a request body, whether the method requires one
   */
  record Wire(String type, ValueShape shape, boolean required) {}

  /** The wire types of one mapped method: its response's, its request body's, either null. */
  record Method(List<String> methodIds, Wire response, Wire request) {}

  /** The generated classes, by name, for {@code methods} in order, and their index. */
  record Generated(Map<String, Method> classes, IndexView index) {}

  static Generated generate(final List<Method> methods) {
    final var bytes = new ArrayList<byte[]>();
    final ClassOutput output = (name, data) -> bytes.add(data);
    final var classes = new LinkedHashMap<String, Method>();
    for (final var method : methods) {
      final var number = classes.size();
      final var name = PACKAGE + ".MappedMethod" + number;
      classes.put(name, method);
      try (final var creator = ClassCreator.builder().classOutput(output).className(name).build()) {
        creator.addAnnotation(Path.class).addValue("value", "/" + name);
        // Each generated operation carries its own method name and an operation id no Java method
        // name can spell, so no strategy and no duplicate check makes it collide with another.
        try (final var wire = creator.getMethodCreator("wire" + number, void.class)) {
          wire.addAnnotation(POST.class);
          wire.addAnnotation(Operation.class).addValue("operationId", "telescope-wire-" + number);
          wire.addAnnotation(Produces.class).addValue("value", new String[] { MediaType.APPLICATION_JSON });
          wire.addAnnotation(Consumes.class).addValue("value", new String[] { MediaType.APPLICATION_JSON });
          if (method.response() != null) wire
            .addAnnotation(APIResponse.class)
            .add("responseCode", "200")
            .add("description", "OK")
            .add("content", new AnnotationCreator[] { content(method.response()) });
          if (method.request() != null) wire
            .addAnnotation(RequestBody.class)
            .add("required", method.request().required())
            .add("content", new AnnotationCreator[] { content(method.request()) });
          wire.returnVoid();
        }
      }
    }
    final var indexer = new Indexer();
    try {
      for (final var each : bytes) indexer.index(new ByteArrayInputStream(each));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    final Index index = indexer.complete();
    return new Generated(Map.copyOf(classes), index);
  }

  private static AnnotationCreator content(final Wire wire) {
    final var schema = AnnotationCreator.of(Schema.class).add(
      "implementation",
      AnnotationValue.createClassValue(
        "implementation",
        Type.create(DotName.createSimple(wire.type()), Type.Kind.CLASS)
      )
    );
    if (wire.shape() == ValueShape.LIST || wire.shape() == ValueShape.SET) schema.add("type", SchemaType.ARRAY);
    if (wire.shape() == ValueShape.SET) schema.add("uniqueItems", true);
    return AnnotationCreator.of(Content.class).add("mediaType", MediaType.APPLICATION_JSON).add("schema", schema);
  }
}
