package io.github.eschizoid.telescope.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.spring.web.WebMvcTestSupport.PlainProjection;
import io.github.eschizoid.telescope.spring.web.WebMvcTestSupport.UpperMapperBean;
import io.github.eschizoid.telescope.spring.web.WebMvcTestSupport.UpperProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.Account;
import io.github.eschizoid.telescope.spring.web.fixtures.AccountView;
import io.github.eschizoid.telescope.spring.web.fixtures.User;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;
import io.github.eschizoid.telescope.spring.web.fixtures.UserView;
import io.github.eschizoid.telescope.spring.web.fixtures.plain.PlainUserDtoProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.upper.UpperUserDtoProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.upper.UpperUserDtoProjectionImpl;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

class TelescopeResponsesTest {

  private static final User ADA = new User("1", "Ada");

  @RestController
  static class ViewController {

    @TelescopeGetMapping(value = "/views/{id}", to = UserView.class)
    User user(@PathVariable final String id) {
      return ADA;
    }
  }

  @RestController
  static class AccountController {

    @TelescopeGetMapping(value = "/accounts/{id}", to = AccountView.class)
    Account account(@PathVariable final String id) {
      return new Account(id, "Ada", "hash");
    }
  }

  @RestController
  static class DtoController {

    @TelescopeGetMapping(value = "/dtos/{id}", to = UserDto.class)
    User user(@PathVariable final String id) {
      return ADA;
    }
  }

  @RestController
  static class UsingController {

    @TelescopeGetMapping(value = "/upper/{id}", using = UpperUserDtoProjection.class)
    User user(@PathVariable final String id) {
      return ADA;
    }
  }

  interface UserApi {
    @TelescopeGetMapping(value = "/api/users/{id}", to = UserView.class)
    User user(@PathVariable String id);
  }

  @RestController
  static class ApiController implements UserApi {

    @Override
    public User user(final String id) {
      return ADA;
    }
  }

  @RestController
  static class WrongSourceController {

    @TelescopeGetMapping(value = "/wrong-source/{id}", using = PlainUserDtoProjection.class)
    Account user(@PathVariable final String id) {
      return new Account(id, "Ada", "hash");
    }
  }

  @RestController
  static class WrongTargetController {

    @TelescopeGetMapping(value = "/wrong-target/{id}", to = UserView.class, using = PlainUserDtoProjection.class)
    User user(@PathVariable final String id) {
      return ADA;
    }
  }

  @RestController
  static class NoTargetController {

    @TelescopeGetMapping("/no-target/{id}")
    User user(@PathVariable final String id) {
      return ADA;
    }
  }

  @RestController
  static class MapController {

    @TelescopeGetMapping(value = "/by-id/{id}", to = UserView.class)
    Map<String, User> user(@PathVariable final String id) {
      return Map.of(id, ADA);
    }
  }

  @Controller
  static class ViewNameController {

    @TelescopeGetMapping(value = "/page/{id}", to = UserView.class)
    User user(@PathVariable final String id) {
      return ADA;
    }
  }

  @Test
  void sameNameTargetNeedsNoMapper() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(ViewController.class)
      .run(context -> {
        final var response = response(context, ViewController.class);
        assertThat(response.apply(ADA)).isEqualTo(new UserView("1", "Ada"));
        assertThat(response.mappedType()).isEqualTo(UserView.class);
      });
  }

  @Test
  void sourceComponentTheTargetLacksIsIgnored() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(AccountController.class)
      .run(context -> {
        final var method = AccountController.class.getDeclaredMethod("account", String.class);
        final var response = context.getBean(TelescopeResponses.class).find(method);
        assertThat(response.apply(new Account("7", "Ada", "hash"))).isEqualTo(new AccountView("7", "Ada"));
      });
  }

  @Test
  void projectionForThePairIsFound() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(PlainProjection.class, DtoController.class)
      .run(context -> assertThat(response(context, DtoController.class).apply(ADA)).isEqualTo(new UserDto("1", "Ada")));
  }

  @Test
  void mapperBeanForThePairIsFound() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(UpperMapperBean.class, DtoController.class)
      .run(context -> assertThat(response(context, DtoController.class).apply(ADA)).isEqualTo(new UserDto("1", "ADA")));
  }

  @Test
  void usingChoosesBetweenTwoProjectionsAndSuppliesTheTarget() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(PlainProjection.class, UpperProjection.class, UsingController.class)
      .run(context -> {
        final var response = response(context, UsingController.class);
        assertThat(response.apply(ADA)).isEqualTo(new UserDto("1", "ADA"));
        assertThat(response.mappedType()).isEqualTo(UserDto.class);
      });
  }

  @Test
  void annotationOnAnInterfaceMethodIsFound() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(ApiController.class)
      .run(context ->
        assertThat(response(context, ApiController.class).apply(ADA)).isEqualTo(new UserView("1", "Ada"))
      );
  }

  @Test
  void unannotatedMethodIsNotMapped() throws NoSuchMethodException {
    final var method = Object.class.getDeclaredMethod("toString");
    WebMvcTestSupport.runner().run(context ->
      assertThat(context.getBean(TelescopeResponses.class).find(method)).isNull()
    );
  }

  @Test
  void twoProjectionsWithoutUsingFailStartup() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(PlainProjection.class, UpperProjection.class, DtoController.class)
      .run(context ->
        assertThat(context)
          .hasFailed()
          .getFailure()
          .hasStackTraceContaining("DtoController#user: 2 mappers for User -> UserDto: ")
          .hasStackTraceContaining("PlainUserDtoProjection")
          .hasStackTraceContaining("UpperUserDtoProjection")
          .hasStackTraceContaining("Add using = ... to @TelescopeGetMapping to choose one.")
      );
  }

  @Test
  void projectionAndMapperBeanFailStartup() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(PlainProjection.class, UpperMapperBean.class, DtoController.class)
      .run(context ->
        assertThat(context)
          .hasFailed()
          .getFailure()
          .hasStackTraceContaining("DtoController#user: 2 mappers for User -> UserDto: ")
          .hasStackTraceContaining("PlainUserDtoProjection, a Mapper<User, UserDto> bean")
      );
  }

  @Test
  void usingWithTheWrongSourceFailsStartup() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(PlainProjection.class, WrongSourceController.class)
      .run(context ->
        assertThat(context)
          .hasFailed()
          .getFailure()
          .hasStackTraceContaining(
            "WrongSourceController#user: PlainUserDtoProjection maps User, but the method returns Account"
          )
      );
  }

  @Test
  void usingWithTheWrongTargetFailsStartup() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(PlainProjection.class, WrongTargetController.class)
      .run(context ->
        assertThat(context)
          .hasFailed()
          .getFailure()
          .hasStackTraceContaining(
            "WrongTargetController#user: PlainUserDtoProjection maps to UserDto, but to = UserView"
          )
      );
  }

  @Test
  void usingWithNoBeanFailsStartup() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(UsingController.class)
      .run(context ->
        assertThat(context)
          .hasFailed()
          .getFailure()
          .hasStackTraceContaining("UsingController#user: no UpperUserDtoProjection bean")
      );
  }

  @Test
  void usingWithSeveralBeansListsThemAtStartup() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(UpperProjection.class, UsingController.class)
      .withBean("secondUpper", UpperUserDtoProjectionImpl.class)
      .run(context ->
        assertThat(context)
          .hasFailed()
          .getFailure()
          .hasStackTraceContaining("UsingController#user: 2 UpperUserDtoProjection beans: ")
          .hasStackTraceContaining("upperUserDtoProjectionImpl")
          .hasStackTraceContaining("secondUpper")
          .hasStackTraceContaining("Mark one @Primary, or keep one bean of that type.")
      );
  }

  @Test
  void neitherToNorUsingFailsStartup() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(NoTargetController.class)
      .run(context ->
        assertThat(context)
          .hasFailed()
          .getFailure()
          .hasStackTraceContaining("NoTargetController#user: set to or using on @TelescopeGetMapping")
      );
  }

  @Test
  void unsupportedReturnTypeFailsStartup() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(MapController.class)
      .run(context ->
        assertThat(context)
          .hasFailed()
          .getFailure()
          .hasStackTraceContaining("MapController#user: unsupported return type java.util.Map<")
      );
  }

  @Test
  void viewHandlerFailsStartup() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(ViewNameController.class)
      .run(context ->
        assertThat(context)
          .hasFailed()
          .getFailure()
          .hasStackTraceContaining("ViewNameController#user: the method returns a view name, not a response body")
      );
  }

  private static ResolvedResponse response(final AssertableWebApplicationContext context, final Class<?> controller)
    throws NoSuchMethodException {
    return context.getBean(TelescopeResponses.class).find(controller.getDeclaredMethod("user", String.class));
  }
}
