package io.github.eschizoid.telescope.spring.web;

import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.spring.TelescopeAutoConfiguration;
import io.github.eschizoid.telescope.spring.web.fixtures.User;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;
import io.github.eschizoid.telescope.spring.web.fixtures.plain.PlainUserDtoProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.upper.UpperUserDtoProjection;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * A servlet web context with Spring MVC and both telescope auto-configurations, and the mappers
 * tests opt into.
 */
final class WebMvcTestSupport {

  private WebMvcTestSupport() {}

  static WebApplicationContextRunner runner() {
    return new WebApplicationContextRunner()
      .withConfiguration(
        AutoConfigurations.of(TelescopeAutoConfiguration.class, TelescopeWebMvcAutoConfiguration.class)
      )
      .withUserConfiguration(Mvc.class);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class Mvc {}

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(basePackageClasses = PlainUserDtoProjection.class)
  static class PlainProjection {}

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(basePackageClasses = UpperUserDtoProjection.class)
  static class UpperProjection {}

  @Configuration(proxyBeanMethods = false)
  static class UpperMapperBean {

    @Bean
    Mapper<User, UserDto> upperUserDto() {
      return Telescope.mapper(
        User.class,
        UserDto.class,
        to(User::name, UserDto::fullName, String::toUpperCase, String::toLowerCase)
      );
    }
  }
}
