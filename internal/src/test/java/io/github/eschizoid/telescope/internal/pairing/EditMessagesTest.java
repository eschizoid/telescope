package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text of the refusals an edit mapper gives, which the runtime mapper, the generated bridge and
 * the compile-time verifier all raise in these words.
 */
class EditMessagesTest {

  @Test
  @DisplayName("a keep outside edits names the factory, the property and both ways out")
  void keepOutsideEdits() {
    assertEquals(
      "Telescope.mapper(Src, Tgt, ...) cannot take keep(...) for 'id': keep says no edit writes a target property," +
        " and only Telescope.edits writes into a target it is handed. Use Telescope.edits(Src.class, Tgt.class," +
        " ...), or drop the row.",
      PairingMessages.keepOutsideEdits("Telescope.mapper", "Src", "Tgt", "id")
    );
  }

  @Test
  @DisplayName("a via naming an edit mapper outside edits names the factory, the property and both ways out")
  void viaEditsOutsideEdits() {
    assertEquals(
      "Telescope.mapperForward(Src, Tgt, ...) cannot convert 'lines' through an EditMapper: an edit mapper has no" +
        " backward and runs only inside another edit mapper. Name a Mapper or a ForwardMapper in the via row, or" +
        " build this mapper with Telescope.edits.",
      PairingMessages.viaEditsOutsideEdits("Telescope.mapperForward", "Src", "Tgt", "lines")
    );
  }

  @Test
  @DisplayName("the backward of an edit mapper's bridge names the bridge and the mapper to declare instead")
  void editHasNoBackward() {
    assertEquals(
      "SrcToTgtBridge is the bridge of an edit mapper, which converts in the direction the edit flows and has no" +
        " backward. Declare a Mapper or a ForwardMapper for the other direction.",
      PairingMessages.editHasNoBackward("SrcToTgtBridge")
    );
  }

  @Test
  @DisplayName("a keep beside a row writing the same property names the property and both rows")
  void keepBesideWrite() {
    assertEquals(
      "Deep map Src → Tgt: keep(...) says no edit writes 'price', and another row writes it. Remove the keep, or" +
        " the row that writes 'price'.",
      PairingMessages.keepBesideWrite("Src", "Tgt", "price")
    );
  }

  @Test
  @DisplayName("a target nothing fills says whether the source has a property of its name another row takes")
  void editUnfilled() {
    assertEquals(
      "Deep map Src → Tgt: an edit writes target property 'price', and nothing fills it: no row writes it and the" +
        " source has no property of its name. Add a row that writes 'price', add it to Src, or keep it with" +
        " keep(...) when no edit may change it.",
      PairingMessages.editUnfilled("Src", "Tgt", "property", "price", false)
    );
    assertEquals(
      "Deep map Src → Tgt: an edit writes target field 'price', and nothing fills it: no row writes it and the" +
        " source field of its name is read by another row or dropped. Add a row that writes 'price', add it to" +
        " Src, or keep it with keep(...) when no edit may change it.",
      PairingMessages.editUnfilled("Src", "Tgt", "field", "price", true)
    );
  }

  @Test
  @DisplayName("an element mapper whose field holds none yet names the field and both ways out")
  void editElementMapperNotBuilt() {
    assertEquals(
      "An edit mapper converts through Mappers.LINE, the pair's own edit mapper, and Mappers.LINE holds none yet: a" +
        " mapper was used while its class was still initialising. Use the mapper once the class has initialised," +
        " or name the element mapper in a via(...) row.",
      PairingMessages.editElementMapperNotBuilt("Mappers.LINE")
    );
  }

  @Test
  @DisplayName("an element mapper whose field cannot be read names the field and the three ways out")
  void editElementMapperUnreadable() {
    assertEquals(
      "An edit mapper converts through Mappers.LINE, the pair's own edit mapper, and cannot read the field: its" +
        " module does not open the package to io.github.eschizoid.telescope. Make the field and its class public" +
        " in an exported package, open the package, or name the element mapper in a via(...) row.",
      PairingMessages.editElementMapperUnreadable("Mappers.LINE")
    );
  }

  @Test
  @DisplayName("a class whose fields a native image does not list names the class and both ways out")
  void editFieldsNotRegistered() {
    assertEquals(
      "shop.Mappers builds an edit mapper, and the native image lists none of its fields, so the edit mappers it" +
        " declares for the pairs below cannot be found. Register the class's fields for reflection, or generate" +
        " its bridges with telescope-codegen.",
      PairingMessages.editFieldsNotRegistered("shop.Mappers")
    );
  }

  @Test
  @DisplayName("two element mappers for one pair are named, with both ways out")
  void editElementMappersAmbiguous() {
    assertEquals(
      "Deep map Src → Tgt: 'lines' converts through its pair's own edit mapper, and 2 fields declare one:" +
        " Mappers.LINE, Mappers.OTHER. Keep one, or name the one to use in a via(...) row.",
      PairingMessages.editElementMappersAmbiguous("Src", "Tgt", "lines", List.of("Mappers.LINE", "Mappers.OTHER"))
    );
  }

  @Test
  @DisplayName("an element mapper declared where no edit mapper looks names both fields and both ways out")
  void editElementMapperUnreached() {
    assertEquals(
      "Deep map Src → Tgt: 'lines' converts a pair Prices.LINE is the edit mapper of, and Mappers.EDITS would" +
        " not convert through it: an edit mapper converts a pair through its own edit mapper only where it is" +
        " declared in the owner's class or in one of the pair's two classes. Name it in a via(...) row, as" +
        " via(..., Prices.LINE), or move it into one of those classes.",
      PairingMessages.editElementMapperUnreached("Src", "Tgt", "lines", "Mappers.EDITS", "Prices.LINE")
    );
  }

  @Test
  @DisplayName("a target path through something other than properties names the target and the operations left")
  void editPathNotProperties() {
    assertEquals(
      "Deep map Src → Tgt: into and patchInto write a target path into the objects the target holds, and a row of" +
        " this mapper writes through a path that steps through something other than properties. Call" +
        " forward(source) or patch(base, partial) for a new Tgt.",
      PairingMessages.editPathNotProperties("Src", "Tgt")
    );
  }
}
