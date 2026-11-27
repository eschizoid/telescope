package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The words both paths refuse a container write in, and describe and refuse how a collection's
 * elements are tied to their owner. The runtime mapper, the generated bridge and the verifier each
 * read them from here, so the text is pinned once.
 */
class CollectionWriteMessagesTest {

  @Test
  @DisplayName("a container property nothing can write names the container it holds and what would fix it")
  void anUnchangeableContainerWithNoSetter() {
    assertEquals(
      "Deep map Src → Tgt: into writes 'lines' of the Order it is given, which holds a java.util.ListN that cannot" +
        " be changed, and Order has no setter for 'lines' to give it a new one, so into cannot update it. Give" +
        " Order a setter for 'lines' or a modifiable container, or call forward(source) for a new Order.",
      PairingMessages.intoUnwritable("into", "Src", "Tgt", "Order", "lines", "java.util.ListN")
    );
  }

  @Test
  @DisplayName("a container property with no container held and no setter says it holds none")
  void noContainerAndNoSetter() {
    assertEquals(
      "Deep map Src → Tgt: into writes 'lines' of the Order it is given, which holds no container for it, and" +
        " Order has no setter for 'lines' to give it a new one, so into cannot update it. Give Order a setter for" +
        " 'lines' or a modifiable container, or call forward(source) for a new Order.",
      PairingMessages.intoUnwritable("into", "Src", "Tgt", "Order", "lines", null)
    );
  }

  @Test
  @DisplayName("an undecided adder names every candidate and the name that would settle it")
  void anUndecidedAdderNamesTheCandidates() {
    assertEquals(
      "Order writes its collection 'lines' of OrderLine through the owner's adder, and addGift(OrderLine) and" +
        " addSample(OrderLine) each take a OrderLine with no name telling which collection they belong to. Name" +
        " the adder of 'lines' addLine, or give the other methods a name that starts with neither add nor remove.",
      PairingMessages.collectionMemberUndecided(
        "Order",
        "lines",
        "OrderLine",
        "adder",
        List.of("addGift(OrderLine)", "addSample(OrderLine)"),
        "addLine"
      )
    );
  }

  @Test
  @DisplayName("each way a collection is written is one line, as explain prints it")
  void eachCollectionWriteIsOneLine() {
    assertEquals(
      "Order.lines: through Order.addLine(OrderLine), removed through Order.removeLine(OrderLine)",
      PairingMessages.collectionWritten(
        "Order",
        "lines",
        "OrderLine",
        "addLine(OrderLine)",
        "removeLine(OrderLine)",
        "order",
        "MAPPED_BY",
        null
      )
    );
    assertEquals(
      "Order.lines: through Order.addLine(BaseLine), back-reference OrderLine.order cleared on removal",
      PairingMessages.collectionWritten(
        "Order",
        "lines",
        "OrderLine",
        "addLine(BaseLine)",
        null,
        "order",
        "MAPPED_BY",
        null
      )
    );
    assertEquals(
      "Order.lines: refilled in place, back-reference OrderLine.order set from mappedBy and cleared on removal",
      PairingMessages.collectionWritten("Order", "lines", "OrderLine", null, null, "order", "MAPPED_BY", null)
    );
    assertEquals(
      "Basket.items: refilled in place, back-reference Item.basket set by backReference and cleared on removal",
      PairingMessages.collectionWritten("Basket", "items", "Item", null, null, "basket", "ROW", null)
    );
    assertEquals(
      "Order.lines: refilled in place, back-reference OrderLine.order set by backReference," +
        " removed through Order.removeLine(OrderLine)",
      PairingMessages.collectionWritten(
        "Order",
        "lines",
        "OrderLine",
        null,
        "removeLine(OrderLine)",
        "order",
        "ROW",
        null
      )
    );
    assertEquals(
      "KOrder.lines: refilled in place; addBackup(KLine) and addPrimary(KLine) left unused: no name says which" +
        " belongs to 'lines'",
      PairingMessages.collectionWritten(
        "KOrder",
        "lines",
        "KLine",
        null,
        null,
        null,
        null,
        PairingMessages.collectionMembersLeftUnused("lines", List.of("addBackup(KLine)", "addPrimary(KLine)"))
      )
    );
    assertEquals(
      "Order.tags: refilled in place",
      PairingMessages.collectionWritten("Order", "tags", "String", null, null, null, null, null)
    );
  }

  @Test
  @DisplayName("a configured ADDERS strategy is refused with what replaced it")
  void addersInConfigurationIsRefused() {
    assertEquals(
      "telescope.default-write-strategy names ADDERS, which is no write strategy: a collection is written through" +
        " its owner's adder whenever the owner has one, whatever strategy builds the owner. Name BUILDER," +
        " CONSTRUCTOR or SETTERS, or remove the setting.",
      PairingMessages.addersRemoved("telescope.default-write-strategy")
    );
  }

  @Test
  @DisplayName("a container write refused inside patchInto names patchInto")
  void patchIntoNamesItself() {
    assertEquals(
      "Deep map Src → Tgt: patchInto writes 'lines' of the Order it is given, which holds no container for it, and" +
        " Order has no setter for 'lines' to give it a new one, so patchInto cannot update it. Give Order a setter" +
        " for 'lines' or a modifiable container, or call forward(source) for a new Order.",
      PairingMessages.intoUnwritable("patchInto", "Src", "Tgt", "Order", "lines", null)
    );
  }
}
