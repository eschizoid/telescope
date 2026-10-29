package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A source field a row reads, a rename, a drop or a path one field hop long, is not paired with the
 * target field of its own name: the row says where the field goes. That target field is left to the
 * other rows, so a strict mapper with no row writing it refuses it by name, at the mapper's own pair
 * and at a nested pair with rows of its own, and a forward-only mapper leaves it unwritten.
 */
class ReadSourceFieldPairingTest {

  record Src(String n) {}

  record Tgt(String label, String n) {}

  record Num(Integer n) {}

  record NumDto(String label, Long n) {}

  record Kept(String id, String n) {}

  record Inner(String v) {}

  record InnerDto(String w) {}

  record Holder(Inner n) {}

  record HolderDto(Inner label, InnerDto n) {}

  record Outer(String id, Num in) {}

  record OuterDto(String id, NumDto in) {}

  record KeptDto(String id, String n) {}

  record KeptOuter(String id, Kept in) {}

  record KeptOuterDto(String id, KeptDto in) {}

  record Wrap(Inner n) {}

  record WrapDto(String label, Inner n) {}

  private static String show(final Integer n) {
    return "#" + n;
  }

  private static Integer read(final String label) {
    return Integer.valueOf(label.substring(1));
  }

  @Test
  @DisplayName("a renamed field converts backward from the target field it was renamed to, beside a constant")
  void aRenameBesideAConstantConvertsBackwardFromItsTarget() {
    final var mapper = Telescope.mapper(Src.class, Tgt.class, to(Src::n, Tgt::label), constant(Tgt::n, "k"));
    assertEquals(new Tgt("v", "k"), mapper.forward(new Src("v")));
    assertEquals(new Src("lbl"), mapper.backward(new Tgt("lbl", "w")));
    assertEquals(new Src("lbl"), mapper.patch(new Src("v"), new Tgt("lbl", "w")));
  }

  @Test
  @DisplayName("a renamed field converts backward from the target field it was renamed to, beside a compute")
  void aRenameBesideAComputeConvertsBackwardFromItsTarget() {
    final var mapper = Telescope.mapper(Src.class, Tgt.class, to(Src::n, Tgt::label), compute(Tgt::n, () -> "made"));
    assertEquals(new Tgt("v", "made"), mapper.forward(new Src("v")));
    assertEquals(new Src("lbl"), mapper.backward(new Tgt("lbl", "w")));
  }

  @Test
  @DisplayName("a strict mapper refuses by name the target field whose same-name source a rename reads")
  void aStrictMapperRefusesTheTargetARenameLeaves() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(Src.class, Tgt.class, to(Src::n, Tgt::label))
    );
    assertEquals(
      "Deep map Src → Tgt: target field 'n' is not paired with the source field of its name, which another row" +
        " reads or drops. Add a row that writes 'n'.",
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a forward-only mapper leaves the target field a rename leaves unwritten")
  void aForwardOnlyMapperLeavesTheTargetUnwritten() {
    final var mapper = Telescope.mapperForward(Src.class, Tgt.class, to(Src::n, Tgt::label));
    assertEquals(new Tgt("v", null), mapper.forward(new Src("v")));
  }

  @Test
  @DisplayName("a renamed field is not held to the shape of the target field of its own name")
  void aRenamedFieldIsNotHeldToTheShapeOfItsNamesake() {
    final var mapper = Telescope.mapper(
      Num.class,
      NumDto.class,
      to(Num::n, NumDto::label, ReadSourceFieldPairingTest::show, ReadSourceFieldPairingTest::read),
      constant(NumDto::n, 5L)
    );
    assertEquals(new NumDto("#7", 5L), mapper.forward(new Num(7)));
    assertEquals(new Num(3), mapper.backward(new NumDto("#3", 9L)));
  }

  @Test
  @DisplayName("a renamed field of a nested pair is not held to the shape of the target field of its own name")
  void aNestedRenameIsNotHeldToTheShapeOfItsNamesake() {
    final var mapper = Telescope.mapperForward(
      Outer.class,
      OuterDto.class,
      to(Num::n, NumDto::label, ReadSourceFieldPairingTest::show, ReadSourceFieldPairingTest::read)
    );
    assertEquals(new OuterDto("i", new NumDto("#7", null)), mapper.forward(new Outer("i", new Num(7))));
  }

  @Test
  @DisplayName("a strict mapper refuses, at a nested pair with rows of its own, the target field a rename leaves")
  void aStrictMapperRefusesTheNamesakeARenameLeavesAtANestedPair() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(
        Outer.class,
        OuterDto.class,
        to(Num::n, NumDto::label, ReadSourceFieldPairingTest::show, ReadSourceFieldPairingTest::read)
      )
    );
    assertEquals(PairingMessages.sameNameSourceRead("Num", "NumDto", "field", "field", "n"), refused.getMessage());
  }

  @Test
  @DisplayName("a strict mapper refuses, at a nested pair, the target field of a dropped field's name")
  void aStrictMapperRefusesTheNamesakeADropLeavesAtANestedPair() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(KeptOuter.class, KeptOuterDto.class, drop(Kept::n, KeptDto.class))
    );
    assertEquals(PairingMessages.sameNameSourceRead("Kept", "KeptDto", "field", "field", "n"), refused.getMessage());
    assertEquals(
      new KeptOuterDto("o", new KeptDto("i", null)),
      Telescope.mapperForward(KeptOuter.class, KeptOuterDto.class, drop(Kept::n, KeptDto.class)).forward(
        new KeptOuter("o", new Kept("i", "v"))
      )
    );
  }

  @Test
  @DisplayName("a path one field hop long reads its field as an accessor does, so the field is not paired by name")
  void aOneHopPathIsNotPairedByName() {
    final var mapper = Telescope.mapperForward(Src.class, Tgt.class, to(Telescope.of(Src.class).field(Src::n), Tgt::label));
    assertEquals(new Tgt("v", null), mapper.forward(new Src("v")));
  }

  @Test
  @DisplayName("a path reading below a field leaves the field to pair by name")
  void aLongerPathLeavesItsFirstFieldToPairByName() {
    final var mapper = Telescope.mapperForward(
      Wrap.class,
      WrapDto.class,
      to(Telescope.of(Wrap.class).field(Wrap::n).field(Inner::v), WrapDto::label)
    );
    assertEquals(new WrapDto("x", new Inner("x")), mapper.forward(new Wrap(new Inner("x"))));
  }

  @Test
  @DisplayName("a dropped field is not written into the target field of its own name")
  void aDroppedFieldIsNotWrittenIntoItsNamesake() {
    final var mapper = Telescope.mapper(Kept.class, Kept.class, drop(Kept::n), constant(Kept::n, "k"));
    assertEquals(new Kept("i", "k"), mapper.forward(new Kept("i", "v")));
    assertEquals(new Kept("j", null), mapper.backward(new Kept("j", "w")));
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(Kept.class, Kept.class, drop(Kept::n))
    );
    assertTrue(refused.getMessage().contains("target field 'n' is not paired"), refused.getMessage());
    assertEquals(
      new Kept("i", null),
      Telescope.mapperForward(Kept.class, Kept.class, drop(Kept::n)).forward(new Kept("i", "v"))
    );
  }

  @Test
  @DisplayName("a row keyed to the pair only a renamed field's namesake leads to is refused as never visited")
  void aRowKeyedToAPairOnlyTheNamesakeReachesIsNeverVisited() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(
        Holder.class,
        HolderDto.class,
        to(Holder::n, HolderDto::label),
        constant(HolderDto::n, new InnerDto("k")),
        to(Inner::v, InnerDto::w)
      )
    );
    assertTrue(refused.getMessage().contains("never visits"), refused.getMessage());
  }
}
