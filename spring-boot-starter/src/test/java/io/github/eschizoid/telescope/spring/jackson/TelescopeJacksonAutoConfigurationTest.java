package io.github.eschizoid.telescope.spring.jackson;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.jackson.TelescopeModule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.json.JsonMapper;

class TelescopeJacksonAutoConfigurationTest {

  /** A ticket as the API writes it. */
  public record TicketWire(String subject) {}

  /** A ticket as the service holds it. */
  public record Ticket(String title) {}

  /** A note as the API writes it. */
  public record NoteWire(String text) {}

  /** A note as the service holds it. */
  public record Note(String body) {}

  static final Mapper<TicketWire, Ticket> TICKETS = Telescope.mapper(
    TicketWire.class,
    Ticket.class,
    to(TicketWire::subject, Ticket::title)
  );

  static final Mapper<NoteWire, Note> NOTES = Telescope.mapper(
    NoteWire.class,
    Note.class,
    to(NoteWire::text, Note::body)
  );

  private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
    AutoConfigurations.of(JacksonAutoConfiguration.class, TelescopeJacksonAutoConfiguration.class)
  );

  @Configuration(proxyBeanMethods = false)
  static class Customizers {

    @Bean
    @Order(2)
    TelescopeModuleCustomizer notes() {
      return module -> module.add(NOTES);
    }

    @Bean
    @Order(1)
    TelescopeModuleCustomizer tickets() {
      return module -> module.add(TICKETS);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnModule {

    @Bean
    TelescopeModule ownModule() {
      return TelescopeModule.of(NOTES);
    }
  }

  /** A mapper bean no customizer names. */
  @Configuration(proxyBeanMethods = false)
  static class MapperBean {

    @Bean
    Mapper<TicketWire, Ticket> tickets() {
      return TICKETS;
    }
  }

  @Test
  @DisplayName("Boot's JsonMapper reads and writes the mappers the customizers name, in their order")
  void customizersNameTheMappers() {
    runner
      .withUserConfiguration(Customizers.class)
      .run(context -> {
        assertThat(context.getBean(TelescopeModule.class).mappers()).isEqualTo(List.of(TICKETS, NOTES));
        final var json = context.getBean(JsonMapper.class);
        assertThat(json.readValue("{\"subject\":\"printer\"}", Ticket.class)).isEqualTo(new Ticket("printer"));
        assertThat(json.writeValueAsString(new Note("hi"))).isEqualTo("{\"text\":\"hi\"}");
      });
  }

  @Test
  @DisplayName("a Mapper bean no customizer names does not change how Jackson reads its domain type")
  void mapperBeansAreNotRegisteredOnTheirOwn() {
    runner
      .withUserConfiguration(MapperBean.class)
      .run(context -> {
        assertThat(context.getBean(TelescopeModule.class).mappers()).isEmpty();
        final var json = context.getBean(JsonMapper.class);
        assertThat(json.writeValueAsString(new Ticket("printer"))).isEqualTo("{\"title\":\"printer\"}");
      });
  }

  @Test
  @DisplayName("an application's own TelescopeModule bean replaces the starter's")
  void ownModuleWins() {
    runner
      .withUserConfiguration(OwnModule.class, Customizers.class)
      .run(context -> {
        assertThat(context).hasSingleBean(TelescopeModule.class);
        assertThat(context.getBean(TelescopeModule.class).mappers()).isEqualTo(List.of(NOTES));
        assertThat(context.getBean(JsonMapper.class).writeValueAsString(new Note("hi"))).isEqualTo("{\"text\":\"hi\"}");
      });
  }

  @Test
  @DisplayName("without telescope-jackson, or without Jackson 3 databind, nothing is installed")
  void stepsAsideWithoutItsClasses() {
    runner
      .withClassLoader(new FilteredClassLoader(TelescopeModule.class))
      .run(context -> assertThat(context).doesNotHaveBean(TelescopeModule.class));
    new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(TelescopeJacksonAutoConfiguration.class))
      .withClassLoader(new FilteredClassLoader(JsonMapper.class))
      .run(context -> assertThat(context).doesNotHaveBean(TelescopeModule.class));
  }
}
