package io.github.eschizoid.telescope.sealedroots;

import static io.github.eschizoid.telescope.mapping.Cases.cases;
import static io.github.eschizoid.telescope.mapping.NullHint.NullStrategy.DEFAULT;
import static io.github.eschizoid.telescope.mapping.NullHint.nullSourceValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.introspection.OpticNode.Dispatched;
import io.github.eschizoid.telescope.mapping.Cases;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A runtime mapper between two sealed interfaces converts each value through the pair its own case
 * belongs to. A case pairs with the case a {@code cases} row names for it, and otherwise with the
 * case its own {@code @Bridge} converts to, which is how the bridge a {@code @Bridge} on the sealed
 * source generates pairs them. The generated bridges beside the {@code Sr} fixtures are the
 * reference each runtime answer is held to.
 */
class SealedRootsTest {

  private static final SrCircle CIRCLE = new SrCircle(2.0);
  private static final SrSquare SQUARE = new SrSquare(3.0, "sq");

  @Test
  @DisplayName("forward and backward convert each case through the case its own bridge pairs it with")
  void forwardAndBackwardDispatchOnTheCase() {
    final var mapper = Telescope.mapper(SrShape.class, SrShapeDto.class);
    for (final SrShape shape : List.of(CIRCLE, SQUARE)) {
      assertEquals(SrShapeBridge.forward(shape), mapper.forward(shape));
    }
    for (final SrShapeDto dto : List.of(new SrCircleDto(5.0), new SrSquareDto(4.0, "dto"))) {
      assertEquals(SrShapeBridge.backward(dto), mapper.backward(dto));
    }
    assertEquals(new SrSquareDto(3.0, "sq"), mapper.forward(SQUARE));
    assertEquals(new SrCircle(5.0), mapper.backward(new SrCircleDto(5.0)));
  }

  @Test
  @DisplayName("a case converts through its own bridge's renames, and patches through that bridge's patch")
  void aCaseConvertsAndPatchesThroughItsOwnBridge() {
    final var mapper = Telescope.mapper(SbNote.class, SbNoteDto.class);
    final var text = new SbText("hello", 5);
    assertEquals(new SbTextDto("hello", 5), mapper.forward(text));
    assertEquals(SbNoteBridge.forward(text), mapper.forward(text));
    assertEquals(new SbText("bye", 3), mapper.backward(new SbTextDto("bye", 3)));
    final var partial = new SbTextDto("edited", 0);
    assertEquals(SbNoteBridge.patch(text, partial), mapper.patch(text, partial));
    assertEquals(new SbText("edited", 0), mapper.patch(text, partial));
  }

  @Test
  @DisplayName("Telescope.map and a forward-only mapper with rows dispatch on the case too")
  void mapAndMapperForwardDispatchOnTheCase() {
    assertEquals(SrShapeBridge.forward(SQUARE), Telescope.map(SrShape.class, SrShapeDto.class).read(SQUARE));
    assertEquals(
      SrShapeBridge.forward(CIRCLE),
      Telescope.mapperForward(SrShape.class, SrShapeDto.class, nullSourceValues(DEFAULT)).forward(CIRCLE)
    );
  }

  @Test
  @DisplayName("patch overlays a partial of the same case, as the generated sealed patch does")
  void patchOverlaysAPartialOfTheSameCase() {
    final var mapper = Telescope.mapper(SrShape.class, SrShapeDto.class);
    final var partial = new SrSquareDto(9.0, null);
    final var patched = mapper.patch(SQUARE, partial);
    assertEquals(SrShapeBridge.patch(SQUARE, partial), patched);
    assertEquals(new SrSquare(9.0, "sq"), patched);
  }

  @Test
  @DisplayName("patch ignores a partial of another case or a null one, and still returns a new object")
  void patchIgnoresAPartialOfAnotherCase() {
    final var mapper = Telescope.mapper(SrShape.class, SrShapeDto.class);
    for (final SrShapeDto partial : Arrays.asList(new SrCircleDto(1.0), null)) {
      final var patched = mapper.patch(SQUARE, partial);
      assertEquals(SrShapeBridge.patch(SQUARE, partial), patched);
      assertEquals(SQUARE, patched);
      assertNotSame(SQUARE, patched);
    }
  }

  @Test
  @DisplayName("into writes a value into a target of the case it converts to, and refuses any other case")
  void intoWritesIntoTheTargetOfItsCase() {
    final var mapper = Telescope.mapper(SrShape.class, SrShapeDto.class);
    final var managed = new SrSquareDto(0.0, "old");
    assertSame(managed, mapper.into(managed, SQUARE));
    assertEquals(new SrSquareDto(3.0, "sq"), managed);
    final var refused = assertThrows(IllegalArgumentException.class, () -> mapper.into(new SrSquareDto(), CIRCLE));
    assertTrue(refused.getMessage().contains("converts to SrCircleDto"), refused::getMessage);
    assertTrue(refused.getMessage().contains("target is a SrSquareDto"), refused::getMessage);
    assertThrows(UnsupportedOperationException.class, () -> mapper.into(new SrCircleDto(0.0), CIRCLE));
  }

  @Test
  @DisplayName("a sealed value in a field and inside a List, Optional and Map converts as the generated parent does")
  void aNestedSealedValueConvertsAsTheGeneratedParentDoes() {
    final var drawing = new SrDrawing("d", CIRCLE, List.of(SQUARE, CIRCLE), Optional.of(SQUARE), Map.of("a", CIRCLE));
    final var mapper = Telescope.mapper(SrDrawing.class, SrDrawingDto.class);
    final var dto = SrDrawingBridge.forward(drawing);
    assertEquals(dto, mapper.forward(drawing));
    assertEquals(SrDrawingBridge.backward(dto), mapper.backward(dto));
    assertEquals(drawing, mapper.backward(dto));
  }

  @Test
  @DisplayName("cases rows pair the cases of two sealed interfaces no bridge pairs, in every operation")
  void casesRowsPairCasesNoBridgePairs() {
    final var mapper = Telescope.mapper(
      SuPet.class,
      SuPetDto.class,
      cases(SuCat.class, SuCatDto.class),
      cases(SuDog.class, SuDogDto.class)
    );
    assertEquals(new SuDogDto("rex", 3), mapper.forward(new SuDog("rex", 3)));
    assertEquals(new SuCat("tom"), mapper.backward(new SuCatDto("tom")));
    assertEquals(new SuDog("rex", 4), mapper.patch(new SuDog("rex", 3), new SuDogDto(null, 4)));
    assertEquals(new SuDog("rex", 3), mapper.patch(new SuDog("rex", 3), new SuCatDto("tom")));
  }

  @Test
  @DisplayName("a case that neither a row nor its own bridge pairs is refused by name when the mapper is built")
  void anUnpairedCaseIsRefusedByName() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(SuPet.class, SuPetDto.class, cases(SuCat.class, SuCatDto.class))
    );
    assertEquals(PairingMessages.unpairedCase("SuPet", "SuPetDto", "SuDog"), refused.getMessage());
  }

  @Test
  @DisplayName("a row wins over the case's own bridge")
  void aRowWinsOverTheCaseBridge() {
    final var forwardOnly = Telescope.mapperForward(
      SrShape.class,
      SrShapeDto.class,
      cases(SrCircle.class, SrSquareDto.class)
    );
    assertEquals(new SrSquareDto(0.0, null), forwardOnly.forward(CIRCLE));
    assertEquals(new SrSquareDto(3.0, "sq"), forwardOnly.forward(SQUARE));
  }

  @Test
  @DisplayName("a mapper converting backward refuses a target case no case pairs with, by name")
  void aTargetCaseNoCasePairsWithIsRefusedByName() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(SrShape.class, SrShapeDto.class, cases(SrCircle.class, SrSquareDto.class))
    );
    assertEquals(PairingMessages.targetCaseUnpaired("SrShape", "SrShapeDto", "SrCircleDto"), refused.getMessage());
  }

  @Test
  @DisplayName("two rows pairing the same case are refused by name")
  void twoRowsPairingOneCaseAreRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(
        SuPet.class,
        SuPetDto.class,
        cases(SuCat.class, SuCatDto.class),
        cases(SuCat.class, SuCatDto.class),
        cases(SuDog.class, SuDogDto.class)
      )
    );
    assertEquals(PairingMessages.caseRowPairedTwice("SuPet", "SuPetDto", "SuCat"), refused.getMessage());
  }

  @Test
  @DisplayName("a row no sealed pair the mapper converts takes is refused by name")
  void aRowNoSealedPairTakesIsRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(SrCircle.class, SrCircleDto.class, cases(SuCat.class, SuCatDto.class))
    );
    assertEquals(
      PairingMessages.caseRowsNeverDispatched(
        "SrCircle",
        "SrCircleDto",
        List.of(PairingMessages.caseRow("SuCat", "SuCatDto"))
      ),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a case of the mapper's own pair is walked strictly, as the pair a mapper is built for is")
  void aCaseOfTheMappersOwnPairIsWalkedStrictly() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(
        SuPet.class,
        SuPetDto.class,
        cases(SuCat.class, SuDogDto.class),
        cases(SuDog.class, SuCatDto.class)
      )
    );
    assertEquals(PairingMessages.noSameNameSource("SuCat", "SuDogDto", "field", "field", "age"), refused.getMessage());
  }

  @Test
  @DisplayName("a case first met inside another case still patches field by field")
  void aCaseMetInsideAnotherCaseStillPatches() {
    final var mapper = Telescope.mapper(
      SwNode.class,
      SwNodeDto.class,
      cases(SwPair.class, SwPairDto.class),
      cases(SwLeaf.class, SwLeafDto.class)
    );
    assertEquals(new SwLeaf(2), mapper.patch(new SwLeaf(1), new SwLeafDto(2)));
    assertEquals(
      new SwPair(new SwLeaf(7), new SwLeaf(2)),
      mapper.patch(new SwPair(new SwLeaf(1), new SwLeaf(2)), new SwPairDto(new SwLeafDto(7), null))
    );
  }

  @Test
  @DisplayName("a row naming a class no sealed interface permits is refused where it is written")
  void aRowNamingNoCaseIsRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () -> cases(SrDrawing.class, SrCircleDto.class));
    assertEquals(
      PairingMessages.notACase(PairingMessages.caseRow("SrDrawing", "SrCircleDto"), "SrDrawing"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a case that holds its own sealed root converts at every depth, both ways")
  void aCaseHoldingItsOwnRootConvertsAtEveryDepth() {
    final var mapper = Telescope.mapper(
      SuExpr.class,
      SuExprDto.class,
      cases(SuLit.class, SuLitDto.class),
      cases(SuAdd.class, SuAddDto.class)
    );
    final var expr = new SuAdd(new SuLit(1), new SuAdd(new SuLit(2), new SuLit(3)));
    final var dto = new SuAddDto(new SuLitDto(1), new SuAddDto(new SuLitDto(2), new SuLitDto(3)));
    assertEquals(dto, mapper.forward(expr));
    assertEquals(expr, mapper.backward(dto));
    assertEquals(new SuAdd(new SuLit(9), expr.right()), mapper.patch(expr, new SuAddDto(new SuLitDto(9), null)));
  }

  @Test
  @DisplayName("explain names each case pairing, and trace marks the one the value took")
  void explainAndTraceShowTheDispatch() {
    final var mapper = Telescope.mapper(SrShape.class, SrShapeDto.class);
    assertEquals(
      List.of(
        new Dispatched("", SrCircle.class, SrCircleDto.class),
        new Dispatched("", SrSquare.class, SrSquareDto.class)
      ),
      mapper.explain().dispatched()
    );
    assertTrue(mapper.explain().toString().contains("Cases:"), () -> mapper.explain().toString());
    final var trace = mapper.trace(SQUARE).toString();
    assertTrue(trace.contains("SrCircleDto (not taken)"), trace);
    assertTrue(trace.contains("✓ SrSquare"), trace);
    final var nested = Telescope.mapper(SrDrawing.class, SrDrawingDto.class).explain().dispatched();
    assertTrue(nested.contains(new Dispatched("main", SrCircle.class, SrCircleDto.class)), nested::toString);
  }

  @Test
  @DisplayName("a pair whose own bridge transforms a sealed field converts through that bridge, as on main")
  void aBridgeTransformingASealedFieldStillDecides() {
    final var mapper = Telescope.mapper(SvTop.class, SvTopDto.class);
    final var top = new SvTop(new SvX("ab", new SvLeaf(1)));
    assertEquals(new SvTopDto(SvXBridge.forward(top.x())), mapper.forward(top));
    assertEquals(new SvTopDto(new SvXDto("AB", new SvLeafDto(100))), mapper.forward(top));
    assertEquals(
      new SvTop(new SvX("ab", new SvLeaf(5))),
      mapper.backward(new SvTopDto(new SvXDto("AB", new SvLeafDto(500))))
    );
  }

  @Test
  @DisplayName("a case whose own bridge the mapper passes over names that bridge and why when its walk fails")
  void aPassedOverCaseBridgeIsNamedWhenItsWalkFails() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(SbNote.class, SbNoteDto.class, nullSourceValues(DEFAULT))
    );
    assertEquals(
      PairingMessages.caseBridgePassedOver(
        "SbNote",
        "SbNoteDto",
        "SbText",
        "SbTextDto",
        "the mapper's nullSourceValues(DEFAULT) reaches every pair, and a bridge has no counterpart for it",
        PairingMessages.noSameNameSource("SbText", "SbTextDto", "field", "field", "text")
      ),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a sealed case first met inside another case patches through its own cases")
  void aSealedCaseMetInsideAnotherCasePatches() {
    final var mapper = Telescope.mapper(SmAnimal.class, SmAnimalDto.class, cases(SmBird.class, SmBirdDto.class));
    final var cat = new SmCat("a", "b");
    final var partial = new SmCatDto("z", null);
    assertEquals(SmMammalBridge.patch(cat, partial), mapper.patch(cat, partial));
    assertEquals(new SmCat("z", "b"), mapper.patch(cat, partial));
    final var rebuilt = mapper.patch(cat, null);
    assertEquals(cat, rebuilt);
    assertNotSame(cat, rebuilt);
  }

  @Test
  @DisplayName("a case whose @Bridge names several targets is refused with a message saying so")
  void aCaseBridgingSeveralTargetsIsRefusedSayingSo() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(SfShape.class, SfShapeDto.class)
    );
    assertEquals(
      PairingMessages.longFormCaseBridge("SfShape", "SfShapeDto", "SfA", "SfADto", "SfAToSfADtoBridge"),
      refused.getMessage()
    );
    assertEquals(
      new SfADto(3),
      Telescope.mapper(SfShape.class, SfShapeDto.class, cases(SfA.class, SfADto.class)).forward(new SfA(3))
    );
  }

  @Test
  @DisplayName("a mapper converting backward refuses a target case two cases pair with, by name")
  void aTargetCaseTwoCasesPairWithIsRefusedByName() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(
        SuPet.class,
        SuPetDto.class,
        cases(SuCat.class, SuCatDto.class),
        cases(SuDog.class, SuCatDto.class)
      )
    );
    assertEquals(
      PairingMessages.targetCasePairedTwice("SuPet", "SuPetDto", "SuCatDto", List.of("SuCat", "SuDog")),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("an enum case converts by constant name, and patch returns the base constant whatever the partial")
  void anEnumCasePatchesToItsBase() {
    final var mapper = Telescope.mapper(
      SePlan.class,
      SePlanDto.class,
      cases(SeTier.class, SeTierDto.class),
      cases(SeCustom.class, SeCustomDto.class)
    );
    assertEquals(SeTierDto.PRO, mapper.forward(SeTier.PRO));
    assertEquals(SeTier.FREE, mapper.backward(SeTierDto.FREE));
    for (final SePlanDto partial : Arrays.asList(SeTierDto.FREE, new SeCustomDto(3), null)) {
      assertSame(SeTier.PRO, mapper.patch(SeTier.PRO, partial));
    }
    assertEquals(new SeCustom(3), mapper.patch(new SeCustom(1), new SeCustomDto(3)));
  }

  @Test
  @DisplayName("a sealed bridge's BRIDGE_FN patches as its static patch does, for every partial")
  void aSealedBridgeFnPatchesAsItsStaticPatch() {
    for (final SrShapeDto partial : Arrays.asList(new SrSquareDto(9.0, null), new SrCircleDto(1.0), null)) {
      assertEquals(SrShapeBridge.patch(SQUARE, partial), SrShapeBridge.BRIDGE_FN.patch(SQUARE, partial));
    }
    assertEquals(new SrSquare(9.0, "sq"), SrShapeBridge.BRIDGE_FN.patch(SQUARE, new SrSquareDto(9.0, null)));
  }

  @Test
  @DisplayName("a cases row built directly through its record is checked as the factory checks it")
  void aCasePairRecordIsChecked() {
    assertThrows(IllegalArgumentException.class, () -> new Cases.CasePair(SrDrawing.class, SrCircleDto.class));
  }

  @Test
  @DisplayName(
    "a cycle through the cases of two sealed fields is cut at the same reference as the generated bridge cuts it"
  )
  void aCycleThroughTwoSealedFieldsIsCutAsOnTheGeneratedPath() {
    final var mapper = Telescope.mapper(ScSrc.class, ScTgt.class);
    final var source = cycle();
    assertEquals("Tgt(GroupDto(HolderDto(back=null)))", render(ScSrcBridge.forward(source)));
    assertEquals(render(ScSrcBridge.forward(source)), render(mapper.forward(source)));
    final var target = ScSrcBridge.forward(cycle());
    ((ScHolderDto) ((ScGroupDto) target.getS()).getNode()).setBack(target);
    assertEquals(render(ScSrcBridge.backward(target)), render(mapper.backward(target)));
    final var partial = new ScTgt();
    final var group = new ScGroupDto();
    group.setNode(new ScLeafDto(4));
    partial.setS(group);
    assertEquals(render(ScSrcBridge.patch(cycle(), partial)), render(mapper.patch(cycle(), partial)));
    assertEquals("Src(Group(ScLeaf[x=4]))", render(mapper.patch(cycle(), partial)));
  }

  @Test
  @DisplayName("a cases row for a sealed field reached through another sealed field's case is taken")
  void aRowForASealedFieldBehindAnotherIsTaken() {
    final var mapper = Telescope.mapper(ScSrc.class, ScTgt.class, cases(ScHolder.class, ScHolderDto.class));
    final var source = cycle();
    assertEquals(render(ScSrcBridge.forward(source)), render(mapper.forward(source)));
  }

  @Test
  @DisplayName("a pair whose own bridge transforms a field leading back to its parent converts through that bridge")
  void aBridgeTransformingAFieldLeadingBackStillDecides() {
    final var mapper = Telescope.mapper(StTop.class, StTopDto.class);
    final var top = new StTop(new StX("ab", new StBack(null)));
    assertEquals(new StTopDto(StXBridge.forward(top.x())), mapper.forward(top));
    assertEquals(new StTopDto(new StXDto("AB", new StBackDto(null))), mapper.forward(top));
  }

  /** The cyclic sample: a source whose group's holder points back at the source. */
  private static ScSrc cycle() {
    final var src = new ScSrc();
    final var group = new ScGroup();
    final var holder = new ScHolder();
    holder.setBack(src);
    group.setNode(holder);
    src.setS(group);
    return src;
  }

  /** A cycle's value, case by case, ending where the conversion cut it or after a few levels. */
  private static String render(final Object value) {
    return render(value, 0);
  }

  private static String render(final Object value, final int depth) {
    if (depth > 6) return "...";
    return switch (value) {
      case null -> "null";
      case ScSrc src -> "Src(" + render(src.getS(), depth + 1) + ")";
      case ScTgt tgt -> "Tgt(" + render(tgt.getS(), depth + 1) + ")";
      case ScGroup group -> "Group(" + render(group.getNode(), depth + 1) + ")";
      case ScGroupDto group -> "GroupDto(" + render(group.getNode(), depth + 1) + ")";
      case ScHolder holder -> "Holder(back=" + render(holder.getBack(), depth + 1) + ")";
      case ScHolderDto holder -> "HolderDto(back=" + render(holder.getBack(), depth + 1) + ")";
      default -> value.toString();
    };
  }
}
