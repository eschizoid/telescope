package io.github.eschizoid.telescope.ownbridge;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.NullHint.NullStrategy.DEFAULT;
import static io.github.eschizoid.telescope.mapping.NullHint.NullStrategy.PROPAGATE;
import static io.github.eschizoid.telescope.mapping.NullHint.nullSourceValues;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A runtime mapper reaching a nested pair the mapper does not configure converts it through the
 * bridge the pair's own {@code @Bridge} generated, so the pair converts as it does inside a
 * generated parent bridge.
 */
class NestedOwnBridgeTest {

  private static final ObOrderDto DTO = new ObOrderDto(
    "o-1",
    new ObLineDto(ObStatusDto.C),
    List.of(new ObLineDto(ObStatusDto.C), new ObLineDto(ObStatusDto.B))
  );

  @Test
  @DisplayName("a nested pair converts through its own bridge's transform, in a field and in a list")
  void aNestedPairConvertsThroughItsOwnBridge() {
    final var mapper = Telescope.mapper(ObOrder.class, ObOrderDto.class);
    final var order = new ObOrder("o-1", new ObLine(ObStatus.A), List.of(new ObLine(ObStatus.B)));
    assertEquals(
      new ObOrderDto("o-1", new ObLineDto(ObStatusDto.A), List.of(new ObLineDto(ObStatusDto.B))),
      mapper.forward(order)
    );
    assertEquals(
      new ObOrder("o-1", new ObLine(ObStatus.A), List.of(new ObLine(ObStatus.A), new ObLine(ObStatus.B))),
      mapper.backward(DTO)
    );
  }

  @Test
  @DisplayName("patch writes a nested component whole, through its own bridge's backward")
  void patchWritesANestedComponentThroughItsOwnBridge() {
    final var mapper = Telescope.mapper(ObOrder.class, ObOrderDto.class);
    final var base = new ObOrder("o-1", new ObLine(ObStatus.B), List.of());
    assertEquals(
      new ObOrder("o-1", new ObLine(ObStatus.A), List.of()),
      mapper.patch(base, new ObOrderDto(null, new ObLineDto(ObStatusDto.C), null))
    );
  }

  @Test
  @DisplayName("a forward-only mapper given a step converts a nested pair through its own bridge too")
  void aForwardOnlyMapperUsesTheNestedPairsOwnBridge() {
    // A step of any kind takes the mapper off the root's own bridge and onto the walk.
    final var mapper = Telescope.mapperForward(ObShipment.class, ObShipmentDto.class, nullSourceValues(PROPAGATE));
    assertEquals(
      new ObShipmentDto(new ObParcelDto("BOX", new ObTagDto("t"))),
      mapper.forward(new ObShipment(new ObParcel("box", new ObTag("t"))))
    );
  }

  @Test
  @DisplayName("explain names a pair its own bridge converts as one node, not by the fields a walk would read")
  void explainNamesABridgedPairAsOneNode() {
    final var nodes = Telescope.mapper(ObOrder.class, ObOrderDto.class).explain().nodes();
    assertTrue(nodes.contains(new OpticNode.Transformed("line", "line", "ObLine", "ObLineDto")), nodes::toString);
    assertTrue(
      nodes.stream().noneMatch(node -> node instanceof OpticNode.Mapped m && m.to().startsWith("line.")),
      nodes::toString
    );
    assertTrue(
      nodes.stream().noneMatch(node -> node instanceof OpticNode.Transformed t && t.to().startsWith("line.")),
      nodes::toString
    );
  }

  @Test
  @DisplayName("a row keyed to a nested pair decides that pair, and a sibling pair keeps its own bridge")
  void aRowForTheNestedPairWinsOverItsOwnBridge() {
    final var mapper = Telescope.mapper(
      ObManifest.class,
      ObManifestDto.class,
      to(
        ObLine::status,
        ObLineDto::status,
        (final ObStatus s) -> ObStatusDto.valueOf(s.name()),
        (final ObStatusDto s) -> s == ObStatusDto.C ? ObStatus.B : ObStatus.valueOf(s.name())
      )
    );
    final var back = mapper.backward(
      new ObManifestDto(new ObLineDto(ObStatusDto.C), new ObParcelDto("BOX", new ObTagDto("t")))
    );
    assertEquals(new ObManifest(new ObLine(ObStatus.B), new ObParcel("box", new ObTag("t"))), back);
  }

  @Test
  @DisplayName("a write hint naming a class of a nested pair decides that pair, rather than its own bridge")
  void aWriteHintForTheNestedPairWinsOverItsOwnBridge() {
    final var letter = new ObLetter(new ObNote("hi"));
    assertEquals("HI", Telescope.mapper(ObLetter.class, ObLetterDto.class).forward(letter).note().getText());
    final var hinted = Telescope.mapper(ObLetter.class, ObLetterDto.class, writeBean(ObNoteBean.class, SETTERS));
    assertEquals("hi", hinted.forward(letter).note().getText());
  }

  @Test
  @DisplayName("a row for a pair below a nested pair's own bridge makes the mapper walk that pair itself")
  void aRowBelowABridgedPairMakesTheMapperWalkIt() {
    final var shipment = new ObShipment(new ObParcel("box", new ObTag("t")));
    assertEquals(
      new ObShipmentDto(new ObParcelDto("BOX", new ObTagDto("t"))),
      Telescope.mapper(ObShipment.class, ObShipmentDto.class).forward(shipment)
    );
    final var withRow = Telescope.mapper(
      ObShipment.class,
      ObShipmentDto.class,
      to(ObTag::code, ObTagDto::code, (final String s) -> s + "!", (final String s) -> s)
    );
    assertEquals(new ObShipmentDto(new ObParcelDto("box", new ObTagDto("t!"))), withRow.forward(shipment));
  }

  @Test
  @DisplayName("a row below one bridged pair walks that pair, and a sibling pair keeps its own bridge")
  void aRowBelowOneBridgedPairLeavesItsSiblingBridged() {
    final var mapper = Telescope.mapper(
      ObManifest.class,
      ObManifestDto.class,
      to(ObTag::code, ObTagDto::code, (final String s) -> s + "!", (final String s) -> s)
    );
    final var manifest = new ObManifest(new ObLine(ObStatus.A), new ObParcel("box", new ObTag("t")));
    assertEquals(
      new ObManifestDto(new ObLineDto(ObStatusDto.A), new ObParcelDto("box", new ObTagDto("t!"))),
      mapper.forward(manifest)
    );
    final var back = mapper.backward(
      new ObManifestDto(new ObLineDto(ObStatusDto.C), new ObParcelDto("BOX", new ObTagDto("t")))
    );
    assertEquals(new ObLine(ObStatus.A), back.line());
  }

  @Test
  @DisplayName("a write hint for a class below a bridged pair makes the mapper walk that pair")
  void aWriteHintBelowABridgedPairMakesTheMapperWalkIt() {
    final var post = new ObPost(new ObEnvelope(new ObNote("hi")));
    assertEquals("HI", Telescope.mapper(ObPost.class, ObPostDto.class).forward(post).envelope().note().getText());
    final var hinted = Telescope.mapper(ObPost.class, ObPostDto.class, writeBean(ObNoteBean.class, SETTERS));
    assertEquals("hi", hinted.forward(post).envelope().note().getText());
  }

  @Test
  @DisplayName("a row for a pair below a bridged pair is found through a property a bean inherits from a generic base")
  void aRowBelowABridgedPairIsFoundThroughAnInheritedGenericProperty() {
    final var mapper = Telescope.mapper(
      ObGTop.class,
      ObGTopDto.class,
      to(ObTag::code, ObTagDto::code, (final String s) -> s + "!", (final String s) -> s)
    );
    final var sub = new ObGSub();
    sub.setValue(new ObTag("t"));
    final var converted = mapper.forward(new ObGTop(new ObLine(ObStatus.A), new ObGHolder("box", sub)));
    assertEquals(new ObTagDto("t!"), converted.holder().sub().getValue());
    assertEquals("box", converted.holder().label());
  }

  @Test
  @DisplayName("a nested pair holding a type bounded by itself still converts through its own bridge")
  void aSelfBoundedTypeBelowABridgedPairIsRead() {
    final var converted = Telescope.mapper(ObClimb.class, ObClimbDto.class).forward(
      new ObClimb(new ObLadder("up", new ObRankA()))
    );
    assertEquals("UP", converted.ladder().label());
  }

  @Test
  @DisplayName(
    "a row for the elements of a container class of its own, below a bridged pair, makes the mapper walk that pair"
  )
  void aRowForTheElementsOfAContainerClassBelowABridgedPairIsFound() {
    final var mapper = Telescope.mapper(
      ObCart.class,
      ObCartDto.class,
      to(ObTag::code, ObTagDto::code, (final String s) -> s + "!", (final String s) -> s)
    );
    final var bag = new ObBag();
    bag.add(new ObTag("t"));
    final var converted = mapper.forward(new ObCart(new ObSack("box", bag)));
    assertEquals(List.of(new ObTagDto("t!")), List.copyOf(converted.sack().bag()));
    assertEquals("box", converted.sack().label());
  }

  @Test
  @DisplayName("a mapper-wide null default reaches inside a nested pair, which the mapper then walks")
  void aMapperWideNullDefaultWalksANestedPair() {
    final var mapper = Telescope.mapper(ObShipment.class, ObShipmentDto.class, nullSourceValues(DEFAULT));
    assertEquals("", mapper.forward(new ObShipment(new ObParcel(null, new ObTag("t")))).parcel().label());
  }

  @Test
  @DisplayName("a mapper-wide write strategy reaches inside a nested pair, which the mapper then walks")
  void aMapperWideWriteStrategyWalksANestedPair() {
    final var mapper = Telescope.mapper(ObLetter.class, ObLetterDto.class, writeBeans(SETTERS));
    assertEquals("hi", mapper.forward(new ObLetter(new ObNote("hi"))).note().getText());
  }

  @Test
  @DisplayName("a nested pair that reaches back to its parent is walked, so an object reaching itself converts to null")
  void aNestedPairOnACycleIsWalked() {
    final var a = new ObCa();
    final var b = new ObCb();
    a.setName("a");
    a.setB(b);
    b.setLabel("b");
    b.setA(a);
    final var converted = Telescope.mapper(ObCa.class, ObCaDto.class).forward(a);
    assertNull(converted.getB().getA());
    assertEquals("b", converted.getB().getLabel());
  }

  @Test
  @DisplayName("a bridged pair met off a cycle first is still walked where it closes the cycle")
  void aPairBridgedOffACycleIsWalkedOnIt() {
    final var a = new ObCa();
    final var b = new ObCb();
    a.setName("a");
    a.setB(b);
    b.setLabel("b");
    b.setA(a);
    final var converted = Telescope.mapper(ObOff.class, ObOffDto.class).forward(new ObOff(b, a));
    assertNull(converted.a().getB().getA());
    assertEquals("b", converted.a().getB().getLabel());
    assertEquals("B", converted.b().getLabel());
  }

  @Test
  @DisplayName("a pair walked where it closes a cycle stays walked there when the mapper meets it again off the cycle")
  void aPairWalkedOnACycleStaysWalkedThere() {
    final var xs = new ArrayList<ObX>();
    final var p = new ObP(xs);
    final var x = new ObX(p);
    xs.add(x);
    final var converted = Telescope.mapper(ObRoot.class, ObRootDto.class).forward(new ObRoot(x, p));
    final var list = new ArrayList<ObXDto>();
    list.add(null);
    assertEquals(new ObPDto(list), converted.x().p());
  }

  @Test
  @DisplayName("the pair a mapper is built for is walked at every depth, though it has a bridge of its own")
  void theMappersOwnPairIsWalkedAtEveryDepth() {
    final var node = new ObNode("a", new ObNode("b", null));
    assertEquals(
      new ObNodeDto("a", new ObNodeDto("b", null)),
      Telescope.mapper(ObNode.class, ObNodeDto.class).forward(node)
    );
  }

  @Test
  @DisplayName("a mapper with no steps for a source declaring several targets converts through its long-form bridge")
  void aMapperWithNoStepsReadsTheLongFormBridge() {
    assertEquals(
      new ObMultiDto("X"),
      Telescope.mapperForward(ObMulti.class, ObMultiDto.class).forward(new ObMulti("x"))
    );
  }

  @Test
  @DisplayName("a nested pair whose source declares several targets converts through the bridge it declares")
  void aNestedPairReadsItsDeclaredLongFormBridge() {
    final var mapper = Telescope.mapper(ObMultiBox.class, ObMultiBoxDto.class);
    final var box = new ObMultiBox(new ObMulti("x"));
    assertEquals(new ObMultiBoxDto(new ObMultiDto("X")), mapper.forward(box));
    assertEquals(ObMultiBoxBridge.forward(box), mapper.forward(box));
    final var dto = new ObMultiBoxDto(new ObMultiDto("Y"));
    assertEquals(new ObMultiBox(new ObMulti("y")), mapper.backward(dto));
    assertEquals(ObMultiBoxBridge.backward(dto), mapper.backward(dto));
  }

  @Test
  @DisplayName("a class under a nested pair's long-form name with no BRIDGE field is no bridge, and the pair is walked")
  void aLongFormClassWithNoBridgeFieldIsNoBridge() {
    final var mapper = Telescope.mapper(ObHandBox.class, ObHandBoxDto.class);
    assertEquals(new ObHandBoxDto(new ObHandDto("q")), mapper.forward(new ObHandBox(new ObHand("q"))));
    assertEquals(new ObHandBox(new ObHand("Q")), mapper.backward(new ObHandBoxDto(new ObHandDto("Q"))));
  }

  @Test
  @DisplayName("a mapper with no steps reads a long-form bridge whose target a class elsewhere shares a name with")
  void aLongFormBridgeIsReadForItsOwnTarget() {
    assertEquals(
      new ObTwinDto("BOB"),
      Telescope.mapperForward(ObTwin.class, ObTwinDto.class).forward(new ObTwin("bob"))
    );
  }

  @Test
  @DisplayName("a nested pair converts through a carrier bridge registered for it")
  void aNestedPairConvertsThroughItsCarrierBridge() {
    final var mapper = Telescope.mapper(ObBoard.class, ObBoardDto.class);
    assertEquals(new ObBoard(new ObPin(ObStatus.A)), mapper.backward(new ObBoardDto(new ObPinDto(ObStatusDto.C))));
  }

  @Test
  @DisplayName("a bridge the processor derived for a pair with no @Bridge of its own is not the pair's own")
  void aDerivedBridgeIsNotThePairsOwn() {
    final var refusal = assertThrows(IllegalStateException.class, () -> Telescope.mapper(ObBox.class, ObBoxDto.class));
    assertTrue(
      refusal.getMessage().contains("ObStatus has no constant named C, which the backward direction needs"),
      refusal::getMessage
    );
  }
}
