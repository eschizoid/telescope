package io.github.eschizoid.telescope.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The names the wire component schemas take in a document: a component the document describes the
 * same way under its own name, or that name with a duplicate number, keeps the document's name, and
 * any other takes its own name or that name with the next free number.
 */
class WireComponentsTest {

  private static Schema object(final String property, final Schema value) {
    return OASFactory.createSchema().addType(Schema.SchemaType.OBJECT).addProperty(property, value);
  }

  private static Schema text() {
    return OASFactory.createSchema().addType(Schema.SchemaType.STRING);
  }

  private static Schema ref(final String name) {
    return OASFactory.createSchema().ref("#/components/schemas/" + name);
  }

  private static OpenAPI document(final Map<String, Schema> schemas) {
    final var components = OASFactory.createComponents();
    for (final var entry : schemas.entrySet()) components.addSchema(entry.getKey(), entry.getValue());
    return OASFactory.createOpenAPI().components(components);
  }

  private static Map<String, Schema> ordered(final Object... namesAndSchemas) {
    final var map = new LinkedHashMap<String, Schema>();
    for (var i = 0; i < namesAndSchemas.length; i += 2) map.put(
      (String) namesAndSchemas[i],
      (Schema) namesAndSchemas[i + 1]
    );
    return map;
  }

  @Test
  @DisplayName("a component whose name the document gives another takes its whole name with the next free number")
  void aClashingNameKeepsItsDigitsAndTakesTheNextNumber() {
    final var document = document(ordered("OrderV2", object("other", text()), "OrderV21", object("older", text())));
    final var root = ref("OrderV2");
    WireComponents.copy(document, ordered("OrderV2", object("v", text())), List.of(root));
    assertThat(root.getRef()).isEqualTo("#/components/schemas/OrderV22");
    assertThat(document.getComponents().getSchemas()).containsOnlyKeys("OrderV2", "OrderV21", "OrderV22");
    assertThat(document.getComponents().getSchemas().get("OrderV22").getProperties()).containsOnlyKeys("v");
  }

  @Test
  @DisplayName("an equal component under a name its own is not a numbering of stays apart, under its own name")
  void anEqualComponentUnderAnotherClassNameIsNotReused() {
    final var document = document(ordered("SameV1", object("v", text())));
    final var root = ref("SameV2");
    WireComponents.copy(document, ordered("SameV2", object("v", text())), List.of(root));
    assertThat(root.getRef()).isEqualTo("#/components/schemas/SameV2");
    assertThat(document.getComponents().getSchemas()).containsOnlyKeys("SameV1", "SameV2");
  }

  @Test
  @DisplayName("a component the document describes the same way under another number keeps the document's name")
  void anEqualComponentUnderAnotherNumberIsReused() {
    final var document = document(ordered("Node", object("zv", text()), "Node3", object("xv", text())));
    final var root = ref("Node");
    WireComponents.copy(document, ordered("Node", object("xv", text())), List.of(root));
    assertThat(root.getRef()).isEqualTo("#/components/schemas/Node3");
    assertThat(document.getComponents().getSchemas()).containsOnlyKeys("Node", "Node3");
  }

  @Test
  @DisplayName("two components referring to each other match two document components that do, whatever their numbers")
  void aCycleMatchesTheDocumentsCycle() {
    final var document = document(
      ordered(
        "Node",
        object("zv", text()),
        "Node1",
        object("back", ref("Node2")),
        "Node2",
        object("other", ref("Node1"))
      )
    );
    final var root = ref("Node");
    WireComponents.copy(
      document,
      ordered("Node", object("other", ref("Node1")), "Node1", object("back", ref("Node"))),
      List.of(root)
    );
    assertThat(root.getRef()).isEqualTo("#/components/schemas/Node2");
    assertThat(document.getComponents().getSchemas()).containsOnlyKeys("Node", "Node1", "Node2");
    assertThat(document.getComponents().getSchemas().get("Node2").getProperties().get("other").getRef()).isEqualTo(
      "#/components/schemas/Node1"
    );
  }

  @Test
  @DisplayName("a component equal but for a reference to one that differs is copied, its reference following the copy")
  void aComponentNamingADifferentOneIsCopied() {
    final var document = document(ordered("Parcel", object("item", ref("Item")), "Item", object("qty", text())));
    final var root = ref("Parcel");
    WireComponents.copy(
      document,
      ordered("Parcel", object("item", ref("Item")), "Item", object("sku", text())),
      List.of(root)
    );
    assertThat(root.getRef()).isEqualTo("#/components/schemas/Parcel1");
    final var schemas = document.getComponents().getSchemas();
    assertThat(schemas).containsOnlyKeys("Parcel", "Item", "Parcel1", "Item1");
    assertThat(schemas.get("Parcel1").getProperties().get("item").getRef()).isEqualTo("#/components/schemas/Item1");
    assertThat(schemas.get("Parcel").getProperties().get("item").getRef()).isEqualTo("#/components/schemas/Item");
  }

  @Test
  @DisplayName("a component the document has no name for keeps its own, and an equal one by the same name is shared")
  void aNewComponentKeepsItsNameAndAnEqualOneIsShared() {
    final var document = document(ordered("Shared", object("s", text())));
    final var root = object("fresh", ref("Fresh"));
    WireComponents.copy(
      document,
      ordered("Fresh", object("shared", ref("Shared")), "Shared", object("s", text())),
      List.of(root)
    );
    assertThat(document.getComponents().getSchemas()).containsOnlyKeys("Shared", "Fresh");
    assertThat(root.getProperties().get("fresh").getRef()).isEqualTo("#/components/schemas/Fresh");
  }
}
