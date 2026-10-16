package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the shared rules read the type variables of two generic classes paired with each other, and
 * which pairs of generic sealed interfaces they dispatch on, in the reflection world.
 */
class GenericCasePairingTest {

  /** The source cases the variable reading is asked about. */
  sealed interface Source<T> permits Gen, Two, Bounded, Ranked, Listed {}

  /** The target cases. */
  sealed interface Target<T> permits GenDto, RankedDto, HalfRanked, ListedDto {}

  record Gen<T>(T v) implements Source<T> {}

  record GenDto<U>(U v) implements Target<U> {}

  record Two<A, B>(A a) implements Source<A> {}

  record Bounded<T extends Number>(T v) implements Source<T> {}

  record Ranked<T extends Number & Comparable<T>>(T v) implements Source<T> {}

  record RankedDto<U extends Number & Comparable<U>>(U v) implements Target<U> {}

  record HalfRanked<U extends Number>(U v) implements Target<U> {}

  record Listed<T>(List<? extends T> vs, T[] array) implements Source<T> {}

  record ListedDto<U>(List<? extends U> vs, U[] array) implements Target<U> {}

  /** A generic record that is no case of a sealed interface. */
  record Loose<T>(T v) {}

  /** Another. */
  record LooseDto<U>(U v) {}

  sealed interface Pair<A, B> permits Both {}

  record Both<A, B>(A a, B b) implements Pair<A, B> {}

  sealed interface PairDto<A, B> permits BothDto, Swapped, RawBoth {}

  record BothDto<A, B>(A a, B b) implements PairDto<A, B> {}

  record Swapped<A, B>(A a, B b) implements PairDto<B, A> {}

  /** A case giving its interface no arguments at all. */
  @SuppressWarnings("rawtypes")
  record RawBoth(String s) implements PairDto {}

  sealed interface One<T> permits Single {}

  record Single<T>(T v) implements One<T> {}

  sealed interface OneDto<T> permits SingleDto {}

  record SingleDto<T>(T v) implements OneDto<T> {}

  sealed interface Num<T> permits NumOk, NumInt {}

  record NumOk<T>(T v) implements Num<T> {}

  record NumInt(int n) implements Num<Integer> {}

  sealed interface NumDto<T> permits NumOkDto, NumIntDto {}

  record NumOkDto<T>(T v) implements NumDto<T> {}

  record NumIntDto(int n) implements NumDto<Integer> {}

  sealed interface Many<T> permits ListOk {}

  record ListOk<T>(List<T> vs) implements Many<List<T>> {}

  sealed interface ManyDto<T> permits ListOkDto {}

  record ListOkDto<T>(List<T> vs) implements ManyDto<List<T>> {}

  sealed interface Plain permits Bare {}

  record Bare(String s) implements Plain {}

  private final ReflectionProps props = new ReflectionProps();

  private final PairingRules<Type> rules = new PairingRules<>(props);

  private static Type component(final Class<?> record, final int index) {
    return record.getRecordComponents()[index].getGenericType();
  }

  @Test
  @DisplayName("a target's type variable reads as the source's in the same position")
  void aTargetVariableReadsAsTheSourceVariableInTheSamePosition() {
    final var aligned = rules.alignedToSource(component(GenDto.class, 0), Gen.class, GenDto.class);
    assertEquals(Gen.class.getTypeParameters()[0], aligned);
    // Inside an array and a wildcard too.
    assertEquals(
      component(Listed.class, 0),
      rules.alignedToSource(component(ListedDto.class, 0), Listed.class, ListedDto.class)
    );
    assertEquals(
      component(Listed.class, 1),
      rules.alignedToSource(component(ListedDto.class, 1), Listed.class, ListedDto.class)
    );
  }

  @Test
  @DisplayName("variables bounded alike, an intersection and a bound over the variable itself included, read across")
  void variablesBoundedAlikeReadAcross() {
    assertEquals(
      Ranked.class.getTypeParameters()[0],
      rules.alignedToSource(component(RankedDto.class, 0), Ranked.class, RankedDto.class)
    );
  }

  @Test
  @DisplayName(
    "nothing reads across between classes declaring a different number of variables, or bounding them differently"
  )
  void nothingReadsAcrossWhereTheVariablesDiffer() {
    final var target = component(GenDto.class, 0);
    assertEquals(target, rules.alignedToSource(target, Two.class, GenDto.class));
    assertEquals(target, rules.alignedToSource(target, Bounded.class, GenDto.class));
    final var half = component(HalfRanked.class, 0);
    assertEquals(half, rules.alignedToSource(half, Ranked.class, HalfRanked.class));
    // A class declaring no variables has none to read.
    assertEquals(String.class, rules.alignedToSource(String.class, Gen.class, String.class));
  }

  @Test
  @DisplayName("nothing reads across unless both classes are cases of sealed interfaces")
  void nothingReadsAcrossOutsideSealedCases() {
    final var loose = component(LooseDto.class, 0);
    assertEquals(loose, rules.alignedToSource(loose, Loose.class, LooseDto.class));
    assertEquals(loose, rules.alignedToSource(loose, Gen.class, LooseDto.class));
    final var target = component(GenDto.class, 0);
    assertEquals(target, rules.alignedToSource(target, Loose.class, GenDto.class));
    // A pair of such records given arguments is refused as any parameterized pair is.
    final var refused = assertInstanceOf(
      PairDecision.Incompatible.class,
      rules.decidePair(held("loose"), held("looseDto"), "b")
    );
    assertTrue(refused.message().contains("has incompatible source/target shapes"), refused.message());
  }

  @Test
  @DisplayName(
    "paired cases giving their roots the same arguments convert, and ones giving them in another order are refused"
  )
  void pairedCasesMustGiveTheRootsTheSameArguments() {
    assertNull(rules.caseArgumentsRefusal(Pair.class, PairDto.class, Both.class, BothDto.class));
    final var refusal = rules.caseArgumentsRefusal(Pair.class, PairDto.class, Both.class, Swapped.class);
    assertTrue(refusal != null && refusal.contains("the type arguments <A, B>") && refusal.contains("<B, A>"), refusal);
    final var raw = rules.caseArgumentsRefusal(Pair.class, PairDto.class, Both.class, RawBoth.class);
    assertTrue(raw != null && raw.contains(PairDto.class.getCanonicalName() + " <>"), raw);
  }

  @Test
  @DisplayName("roots declaring no variables, or a different number of them, carry none across")
  void rootsWithoutMatchingVariablesCarryNoneAcross() {
    assertNull(rules.caseArgumentsRefusal(Plain.class, Plain.class, Bare.class, Bare.class));
    assertNull(rules.caseArgumentsRefusal(One.class, PairDto.class, Single.class, Swapped.class));
  }

  @Test
  @DisplayName(
    "two generic sealed interfaces given the same arguments dispatch on the case, whose pairing is decided apart"
  )
  void sameArgumentsDispatch() throws NoSuchFieldException {
    final var one = Holder.class.getDeclaredField("one").getGenericType();
    final var oneDto = Holder.class.getDeclaredField("oneDto").getGenericType();
    assertInstanceOf(PairDecision.DispatchCases.class, rules.decidePair(one, oneDto, "r"));
    // A case giving the interface its variables in another order still holds the arguments, so
    // whether it pairs is decided where the cases are paired.
    final var pair = Holder.class.getDeclaredField("pair").getGenericType();
    final var pairDto = Holder.class.getDeclaredField("pairDto").getGenericType();
    assertInstanceOf(PairDecision.DispatchCases.class, rules.decidePair(pair, pairDto, "r"));
  }

  @SuppressWarnings("unused")
  static final class Holder {

    One<Integer> one;
    OneDto<Integer> oneDto;
    OneDto<String> strings;
    One<? extends Number> someNumber;
    OneDto<? extends Number> someNumberDto;
    Pair<Integer, Integer> pair;
    PairDto<Integer, Integer> pairDto;
    Num<Integer> integers;
    NumDto<Integer> integersDto;
    Num<String> words;
    NumDto<String> wordsDto;
    Many<List<Integer>> many;
    ManyDto<List<Integer>> manyDto;
    Plain plain;
    String text;
    Loose<Integer> loose;
    LooseDto<Integer> looseDto;
  }

  private static Type held(final String name) {
    try {
      return Holder.class.getDeclaredField(name).getGenericType();
    } catch (final NoSuchFieldException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName(
    "sealed interfaces given arguments that differ, a wildcard, or a different number of them are refused by name"
  )
  void differingArgumentsAreRefused() {
    for (final var pair : List.of(
      List.of("one", "strings"),
      List.of("someNumber", "someNumberDto"),
      List.of("one", "someNumberDto"),
      List.of("pair", "oneDto")
    )) {
      final var refused = assertInstanceOf(
        PairDecision.Incompatible.class,
        rules.decidePair(held(pair.get(0)), held(pair.get(1)), "r")
      );
      assertTrue(
        refused.message().contains("sealed interfaces whose type arguments are not the same"),
        refused.message()
      );
    }
  }

  @Test
  @DisplayName("a case fixing the argument converts where it is the field's, and is refused by name where it is not")
  void aFixedCaseConvertsOnlyForItsOwnArgument() {
    assertInstanceOf(PairDecision.DispatchCases.class, rules.decidePair(held("integers"), held("integersDto"), "r"));
    final var refused = assertInstanceOf(
      PairDecision.Incompatible.class,
      rules.decidePair(held("words"), held("wordsDto"), "r")
    );
    assertTrue(
      refused.message().contains("NumInt gives the interface the type arguments <java.lang.Integer>"),
      refused.message()
    );
  }

  @Test
  @DisplayName("a case giving the interface a type built over its own variable is refused by name")
  void aCaseBuiltOverItsVariableIsRefused() {
    final var refused = assertInstanceOf(
      PairDecision.Incompatible.class,
      rules.decidePair(held("many"), held("manyDto"), "r")
    );
    assertTrue(
      refused.message().contains("ListOk gives the interface the type arguments <java.util.List<T>>"),
      refused.message()
    );
  }

  @Test
  @DisplayName(
    "a sealed interface used raw dispatches against another used raw, and is refused against one given arguments"
  )
  void aRawSealedSideDispatchesOnlyAgainstARawOne() {
    assertInstanceOf(PairDecision.DispatchCases.class, rules.decidePair(Num.class, NumDto.class, "r"));
    final var refused = assertInstanceOf(
      PairDecision.Incompatible.class,
      rules.decidePair(Num.class, held("integersDto"), "r")
    );
    assertTrue(
      refused.message().contains("sealed interfaces whose type arguments are not the same"),
      refused.message()
    );
    // A sealed interface against a type that is not one is no case dispatch.
    final var shapes = assertInstanceOf(
      PairDecision.Incompatible.class,
      rules.decidePair(held("integers"), held("text"), "r")
    );
    assertTrue(shapes.message().contains("has incompatible source/target shapes"), shapes.message());
  }

  @Test
  @DisplayName("a type naming no class declares no variables, permits nothing and gives no supertype arguments")
  void aTypeNamingNoClassHasNoGenericFacts() {
    final var wildcard = ((ParameterizedType) component(Listed.class, 0)).getActualTypeArguments()[0];
    assertInstanceOf(WildcardType.class, wildcard);
    assertEquals(List.of(), props.typeParameters(wildcard));
    assertEquals(List.of(), props.upperBounds(String.class));
    assertEquals(List.of(), props.permittedSubclasses(Gen.class));
    assertTrue(props.isSealedCase(Gen.class));
    assertTrue(!props.isSealedCase(Loose.class));
    assertTrue(!props.isSealedCase(wildcard));
    assertEquals(List.of(), props.permittedSubclasses(wildcard));
    assertEquals(List.of(), props.declaredArgumentsAs(wildcard, One.class));
    assertEquals(List.of(), props.declaredArgumentsAs(Single.class, wildcard));
    // A replacement for something that is not a variable replaces nothing, and a variable with no
    // replacement stays.
    assertEquals(String.class, props.substitute(String.class, List.of(String.class), List.of(Integer.class)));
    final var variable = Gen.class.getTypeParameters()[0];
    assertEquals(variable, props.substitute(variable, List.of(variable), List.of()));
  }
}
