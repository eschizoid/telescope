package io.github.eschizoid.telescope.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

class TelescopeMappingAnnotationsTest {

  interface Projection extends TelescopeProjection<String, Integer> {}

  static class Endpoints {

    @TelescopeGetMapping(value = "/get", to = Integer.class)
    String get() {
      return "";
    }

    @TelescopePostMapping(path = "/post", produces = "application/json", using = Projection.class)
    String post() {
      return "";
    }

    @TelescopePutMapping(value = "/put", to = Integer.class)
    String put() {
      return "";
    }

    @TelescopePatchMapping(value = "/patch", to = Integer.class)
    String patch() {
      return "";
    }

    @TelescopeDeleteMapping(value = "/delete", to = Integer.class)
    String delete() {
      return "";
    }
  }

  @Test
  void eachAnnotationIsARequestMappingForItsVerb() throws NoSuchMethodException {
    assertMapping("get", RequestMethod.GET, "/get");
    assertMapping("post", RequestMethod.POST, "/post");
    assertMapping("put", RequestMethod.PUT, "/put");
    assertMapping("patch", RequestMethod.PATCH, "/patch");
    assertMapping("delete", RequestMethod.DELETE, "/delete");
  }

  @Test
  void toAndUsingReachTheMetaAnnotation() throws NoSuchMethodException {
    final var get = MergedAnnotations.from(method("get")).get(TelescopeResponse.class);
    assertThat(get.getClass("to")).isEqualTo(Integer.class);
    assertThat(get.getClass("using")).isEqualTo(TelescopeProjection.class);

    final var post = MergedAnnotations.from(method("post")).get(TelescopeResponse.class);
    assertThat(post.getClass("to")).isEqualTo(Void.class);
    assertThat(post.getClass("using")).isEqualTo(Projection.class);
    assertThat(
      AnnotatedElementUtils.findMergedAnnotation(method("post"), RequestMapping.class).produces()
    ).containsExactly("application/json");
  }

  private static void assertMapping(final String name, final RequestMethod verb, final String path)
    throws NoSuchMethodException {
    final var mapping = AnnotatedElementUtils.findMergedAnnotation(method(name), RequestMapping.class);
    assertThat(mapping).isNotNull();
    assertThat(mapping.method()).containsExactly(verb);
    assertThat(mapping.path()).containsExactly(path);
  }

  private static Method method(final String name) throws NoSuchMethodException {
    return Endpoints.class.getDeclaredMethod(name);
  }
}
