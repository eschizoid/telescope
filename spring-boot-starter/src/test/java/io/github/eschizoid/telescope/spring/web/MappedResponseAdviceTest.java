package io.github.eschizoid.telescope.spring.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import io.github.eschizoid.telescope.spring.web.WebMvcTestSupport.PlainProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.User;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

class MappedResponseAdviceTest {

  abstract static class BaseController {

    @TelescopeGetMapping(value = "/advised/{id}", to = UserDto.class)
    User user(@PathVariable final String id) {
      return new User(id, "Ada");
    }
  }

  @RestController
  static class AdvisedController extends BaseController {}

  // Scoped by annotation, so it applies only when the advice chain sees the controller's bean type.
  @RestControllerAdvice(annotations = RestController.class)
  static class StampAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(
      final MethodParameter returnType,
      final Class<? extends HttpMessageConverter<?>> converterType
    ) {
      return true;
    }

    @Override
    public Object beforeBodyWrite(
      final Object body,
      final MethodParameter returnType,
      final MediaType selectedContentType,
      final Class<? extends HttpMessageConverter<?>> selectedConverterType,
      final ServerHttpRequest request,
      final ServerHttpResponse response
    ) {
      response.getHeaders().add("X-Advised", "yes");
      return body;
    }
  }

  @Test
  void scopedAdviceStillAppliesToAnInheritedMappedMethod() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(PlainProjection.class, AdvisedController.class, StampAdvice.class)
      .run(context ->
        MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext())
          .build()
          .perform(get("/advised/1"))
          .andExpect(jsonPath("$.fullName").value("Ada"))
          .andExpect(header().string("X-Advised", "yes"))
      );
  }
}
