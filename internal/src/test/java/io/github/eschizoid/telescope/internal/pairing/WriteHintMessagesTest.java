package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text of the refusals of a write hint the runtime mapper and the generated bridge's processor
 * both refuse. Each path builds it from here and the two are compared by equality, so it names the
 * hint, the class it is keyed to and the limit in exactly these words.
 */
class WriteHintMessagesTest {

  @Test
  @DisplayName("a second writeBeans default names the hint and the one default a call may give")
  void duplicateDefault() {
    assertEquals(
      "Duplicate writeBeans(...) default — at most one default write strategy per Telescope.map(...) call.",
      PairingMessages.duplicateWriteBeansDefault()
    );
  }

  @Test
  @DisplayName("a second writeBean hint for one class names the class by its binary name")
  void duplicateHint() {
    assertEquals(
      "Duplicate writeBean hint for a.Outer$Bean. Each target class may declare at most one writeBean(...) row" +
        " per Telescope.map(...) call.",
      PairingMessages.duplicateWriteBeanHint("a.Outer$Bean")
    );
  }

  @Test
  @DisplayName("a writeBean hint keyed to a record names the record and the fix")
  void hintOnARecord() {
    assertEquals(
      "writeBean hint targets a record class (a.Point). Records are always reconstructed via the canonical" +
        " constructor; the hint cannot apply. Remove the writeBean(...) row, or move it to the bean side of the" +
        " mapping.",
      PairingMessages.writeBeanTargetsRecord("a.Point")
    );
  }

  @Test
  @DisplayName("a writeBeans SETTERS default that builds no bean names the bean and the missing constructor")
  void settersDefaultUnbuilt() {
    assertEquals(
      "Deep map Src → Tgt: the mapper builds Bean, and writeStrategy = SETTERS, which the mapper's writeBeans" +
        " default names for it, finds no no-argument constructor a rebuild can call. Switch to AUTO, CONSTRUCTOR," +
        " or BUILDER, or add a no-argument constructor the mapper's package can call.",
      PairingMessages.beanUnbuiltBy("Src", "Tgt", "Bean", "SETTERS")
    );
  }

  @Test
  @DisplayName("a writeBeans BUILDER default that builds no bean names the missing builder")
  void builderDefaultUnbuilt() {
    assertEquals(
      "Deep map Src → Tgt: the mapper builds Bean, and writeStrategy = BUILDER, which the mapper's writeBeans" +
        " default names for it, finds no static builder() method returning a builder class. Switch to AUTO," +
        " CONSTRUCTOR, or SETTERS, or add a builder().",
      PairingMessages.beanUnbuiltBy("Src", "Tgt", "Bean", "BUILDER")
    );
  }

  @Test
  @DisplayName("a writeBeans CONSTRUCTOR default that builds no bean names the missing constructor")
  void constructorDefaultUnbuilt() {
    assertEquals(
      "Deep map Src → Tgt: the mapper builds Bean, and writeStrategy = CONSTRUCTOR, which the mapper's" +
        " writeBeans default names for it, finds no public constructor whose parameter names match its" +
        " properties. Switch to AUTO, BUILDER, or SETTERS, or add a name-matched constructor.",
      PairingMessages.beanUnbuiltBy("Src", "Tgt", "Bean", "CONSTRUCTOR")
    );
  }

  @Test
  @DisplayName("an object a path builds that the named strategy cannot build names the path and the strategy")
  void pathObjectUnbuilt() {
    assertEquals(
      "Deep map Src → Tgt: the path 'a.b' steps through Mid, which a write through the path builds where it is" +
        " null, and writeStrategy = SETTERS for it finds no no-argument constructor a rebuild can call. Switch to" +
        " AUTO, CONSTRUCTOR, or BUILDER, or add a no-argument constructor the mapper's package can call.",
      PairingMessages.pathIntermediateUnbuiltBy("Src", "Tgt", "a.b", "Mid", "SETTERS")
    );
  }

  @Test
  @DisplayName("a property a hinted strategy cannot write names the strategy the hint names")
  void hintedStrategyDropsAProperty() {
    assertEquals(
      "Deep map Src → Tgt: the mapper writes 'p' into Bean, and writeStrategy = SETTERS for it writes no 'p'" +
        " into it, so the value would be dropped. Give Bean a member that writes 'p', or name a strategy that" +
        " writes it.",
      PairingMessages.beanPropertyUnwritable("Src", "Tgt", "Bean", "p", "SETTERS")
    );
  }

  @Test
  @DisplayName("a property the strategy the shared order takes cannot write names no strategy")
  void takenStrategyDropsAProperty() {
    assertEquals(
      "Deep map Src → Tgt: the mapper writes 'p' into Bean, and the write strategy it takes writes no 'p' into" +
        " it, so the value would be dropped. Give Bean a member that writes 'p', or name a strategy that writes" +
        " it.",
      PairingMessages.beanPropertyUnwritable("Src", "Tgt", "Bean", "p", null)
    );
  }
}
