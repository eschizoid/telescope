package io.github.eschizoid.telescope.sealedroots;

import static io.github.eschizoid.telescope.mapping.Cases.cases;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Generic sealed interfaces and generic cases the runtime walks property by property, with no
 * generated bridge to convert a case through.
 */
class GenericSealedFieldTest {

  sealed interface Res<T> permits Ok, Count {}

  record Ok<T>(T v) implements Res<T> {}

  record Count(Integer n) implements Res<Integer> {}

  sealed interface ResDto<T> permits OkDto, CountDto {}

  record OkDto<U>(U v) implements ResDto<U> {}

  record CountDto(Integer n) implements ResDto<Integer> {}

  record Holder(Res<Integer> r, List<Res<Integer>> rs) {}

  record HolderDto(ResDto<Integer> r, List<ResDto<Integer>> rs) {}

  sealed interface Pair<A, B> permits Both {}

  record Both<A, B>(A a, B b) implements Pair<A, B> {}

  sealed interface PairDto<A, B> permits Swapped {}

  record Swapped<A, B>(A a, B b) implements PairDto<B, A> {}

  record Pairs(Pair<Integer, String> p) {}

  record PairsDto(PairDto<Integer, String> p) {}

  @Test
  @DisplayName("a generic case reads the paired case's type variable as its own, so its value converts unchanged")
  void aGenericCasePairConverts() {
    final var mapper = Telescope.mapper(Ok.class, OkDto.class);
    assertEquals(new OkDto<>(3), mapper.forward(new Ok<>(3)));
    assertEquals(new Ok<>("x"), mapper.backward(new OkDto<>("x")));
  }

  record Box<T>(T v) {}

  record BoxDto<U>(U v) {}

  @Test
  @DisplayName("a generic record that is no case of a sealed interface keeps its own variable, and is refused")
  void aGenericRecordOutsideASealedInterfaceIsRefused() {
    final var refused = assertThrows(IllegalStateException.class, () -> Telescope.mapper(Box.class, BoxDto.class));
    assertTrue(
      refused.getMessage().contains("component 'v' has incompatible source/target shapes"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a sealed field and a List of them given type arguments convert case by case")
  void aGenericSealedFieldConvertsCaseByCase() {
    final var mapper = Telescope.mapper(
      Holder.class,
      HolderDto.class,
      cases(Ok.class, OkDto.class),
      cases(Count.class, CountDto.class)
    );
    assertEquals(
      new HolderDto(new OkDto<>(3), List.of(new CountDto(4), new OkDto<>(5))),
      mapper.forward(new Holder(new Ok<>(3), List.of(new Count(4), new Ok<>(5))))
    );
    assertEquals(
      new Holder(new Count(6), List.of(new Ok<>(7))),
      mapper.backward(new HolderDto(new CountDto(6), List.of(new OkDto<>(7))))
    );
  }

  @Test
  @DisplayName("cases giving their interfaces the type arguments in another order are refused by name")
  void crossedCasesAreRefused() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(Pairs.class, PairsDto.class, cases(Both.class, Swapped.class))
    );
    assertTrue(refused.getMessage().contains("the type arguments <A, B>"), refused.getMessage());
    assertTrue(
      refused.getMessage().contains("gives " + PairDto.class.getCanonicalName() + " <B, A>"),
      refused.getMessage()
    );
  }
}
