package io.github.eschizoid.telescope.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.spring.samename.ItemMapper;
import io.github.eschizoid.telescope.spring.samename.ItemProjection;
import io.github.eschizoid.telescope.spring.samename.api.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** A mapper and a projection between an entity and a DTO that share a simple name. */
class SameNameModelTypesTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Scan.class);

  @Test
  @DisplayName("a plain mapper between two types named Item maps through their bridge")
  void plainMapperMapsBetweenTypesSharingASimpleName() {
    runner.run(context -> {
      final var mapper = context.getBean(ItemMapper.class);
      assertThat(mapper.map(new io.github.eschizoid.telescope.spring.samename.domain.Item("pen", "blue"))).isEqualTo(
        new Item("pen", "blue")
      );
    });
  }

  @Test
  @DisplayName("a projection between two types named Item maps both ways and patches")
  void projectionMapsAndPatchesBetweenTypesSharingASimpleName() {
    runner.run(context -> {
      final var projection = context.getBean(ItemProjection.class);
      final var base = new io.github.eschizoid.telescope.spring.samename.domain.Item("pen", "blue");
      assertThat(projection.map(base)).isEqualTo(new Item("pen", "blue"));
      assertThat(projection.backward(new Item("ink", "red"))).isEqualTo(
        new io.github.eschizoid.telescope.spring.samename.domain.Item("ink", "red")
      );
      final var partial = new Item(null, "red");
      assertThat(projection.patch(base, partial))
        .isEqualTo(new io.github.eschizoid.telescope.spring.samename.domain.Item("pen", "red"))
        .isEqualTo(
          Telescope.mapper(io.github.eschizoid.telescope.spring.samename.domain.Item.class, Item.class).patch(
            base,
            partial
          )
        );
    });
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(basePackageClasses = ItemMapper.class)
  static class Scan {}
}
