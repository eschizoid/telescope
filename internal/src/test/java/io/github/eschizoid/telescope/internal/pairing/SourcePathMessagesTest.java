package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text of each refusal a row's source path can meet. The mapper and the compile-time verifier
 * both build these messages and compare them by equality, so each one names the mapper's pair, the
 * row, the class at fault, and the fix in exactly these words.
 */
class SourcePathMessagesTest {

  @Test
  @DisplayName("a component read on a class the source neither is nor extends names the property and that class")
  void sourceRowOffSource() {
    assertEquals(
      "Deep map Tree → TreeDto: to row reads 'label' of Twig, which Tree neither is nor extends. Name the" +
        " property through Tree or a class it extends, and narrow with as(...) first to read a property only a" +
        " subclass of Tree has.",
      PairingMessages.sourceRowOffSource("Tree", "TreeDto", "to", "Twig", "label")
    );
  }

  @Test
  @DisplayName("a narrowing no source can match names the class it narrows to")
  void sourcePathNarrowsOffSource() {
    assertEquals(
      "Deep map Sub → Dto: zip row's source path narrows to Other, which Sub neither is nor extends nor is" +
        " extended by, so it never reads a Sub. Narrow to Sub, a class it extends, or a subclass of it.",
      PairingMessages.sourcePathNarrowsOffSource("Sub", "Dto", "zip", "Other")
    );
  }

  @Test
  @DisplayName("a root no source can be an instance of names the root and asks for the path to start at the source")
  void sourcePathNotOnSource() {
    assertEquals(
      "Deep map Sub → Dto: to row's source path starts at Other, which Sub neither is nor extends nor is" +
        " extended by, and cannot be started again at Sub because its first step off the root is not an" +
        " accessor navigation such as field(...). Start the path at Sub.",
      PairingMessages.sourcePathNotOnSource("Sub", "Dto", "to", "Other")
    );
  }

  @Test
  @DisplayName("a source of another class than the path's root names both classes")
  void sourcePathGivenOffRoot() {
    assertEquals(
      "Deep map Base → Dto: to row's source path starts at Sub and was given a Base, which is not a Sub." +
        " Give the mapper only a Sub here, or start the path at Base.",
      PairingMessages.sourcePathGivenOffRoot("Base", "Dto", "to", "Sub", "Base")
    );
  }

  @Test
  @DisplayName("a write back that built another class than the source names the class it built")
  void sourcePathWroteOffSource() {
    assertEquals(
      "Deep map Sub → Dto: to row's source path returned Base from a write on Sub, which is not a Sub. A lens" +
        " or iso started at another class must return the instance it is given, or another Sub.",
      PairingMessages.sourcePathWroteOffSource("Sub", "Dto", "to", "Base")
    );
  }

  @Test
  @DisplayName("a via mapper handed a property it never takes names the side, the property and the mapper's class")
  void viaMapperNeverFits() {
    assertEquals(
      "Deep map Src → Tgt: via row hands the source's 'children' to a mapper of Ch, but it never holds a Ch, nor" +
        " is it a List, Set, Optional or map's values of one. Give the row a mapper of the whole property, or" +
        " of the elements of one of those containers.",
      PairingMessages.viaMapperNeverFits("Src", "Tgt", "source", "children", "Ch")
    );
  }
}
