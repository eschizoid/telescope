package io.github.eschizoid.telescope.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.spring.web.fixtures.User;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ResponseShapeTest {

  private static final User ADA = new User("1", "Ada");
  private static final User BEA = new User("2", "Bea");
  private static final Function<Object, Object> TO_DTO = value -> {
    final var user = (User) value;
    return new UserDto(user.id(), user.name());
  };

  // Generic return types are read from these declarations, the way the resolver reads a handler
  // method.
  interface Returns {
    User single();

    List<User> list();

    Collection<User> collection();

    Set<User> set();

    Optional<User> optional();

    Page<User> page();

    ResponseEntity<User> entity();

    ResponseEntity<List<User>> entityList();

    Map<String, User> map();

    User[] array();

    Stream<User> stream();

    CompletableFuture<User> future();

    List<?> wildcard();

    @SuppressWarnings("rawtypes")
    List raw();

    ResponseEntity<ResponseEntity<User>> nestedEntity();

    String string();

    void nothing();
  }

  @Test
  void singleMapsTheValue() throws NoSuchMethodException {
    final var shape = shape("single");
    assertThat(shape.source()).isEqualTo(User.class);
    assertThat(shape.map(ADA, TO_DTO)).isEqualTo(new UserDto("1", "Ada"));
    assertThat(shape.map(null, TO_DTO)).isNull();
    assertThat(shape.mappedType(UserDto.class)).isEqualTo(UserDto.class);
    assertThat(shape.isEntity()).isFalse();
  }

  @Test
  void listAndCollectionMapEachElementInOrder() throws NoSuchMethodException {
    for (final var name : List.of("list", "collection")) {
      final var shape = shape(name);
      assertThat(shape.source()).isEqualTo(User.class);
      assertThat(shape.map(List.of(ADA, BEA), TO_DTO)).isEqualTo(
        List.of(new UserDto("1", "Ada"), new UserDto("2", "Bea"))
      );
      assertThat(typeName(shape.mappedType(UserDto.class))).isEqualTo(typeName(List.class, UserDto.class));
    }
  }

  @Test
  void listKeepsNullElements() throws NoSuchMethodException {
    assertThat(shape("list").map(Arrays.asList(ADA, null), TO_DTO)).isEqualTo(
      Arrays.asList(new UserDto("1", "Ada"), null)
    );
  }

  @Test
  void setKeepsIterationOrder() throws NoSuchMethodException {
    final var shape = shape("set");
    final var mapped = shape.map(new LinkedHashSet<>(List.of(BEA, ADA)), TO_DTO);
    assertThat(mapped).isInstanceOf(Set.class);
    assertThat(List.copyOf((Set<?>) mapped)).isEqualTo(List.of(new UserDto("2", "Bea"), new UserDto("1", "Ada")));
    assertThat(typeName(shape.mappedType(UserDto.class))).isEqualTo(typeName(Set.class, UserDto.class));
  }

  @Test
  void optionalMapsAPresentValueAndKeepsEmpty() throws NoSuchMethodException {
    final var shape = shape("optional");
    assertThat(shape.map(Optional.of(ADA), TO_DTO)).isEqualTo(Optional.of(new UserDto("1", "Ada")));
    assertThat(shape.map(Optional.empty(), TO_DTO)).isEqualTo(Optional.empty());
    assertThat(shape.bodyType(UserDto.class)).isEqualTo(UserDto.class);
  }

  @Test
  void pageMapsItsContentAndKeepsPaging() throws NoSuchMethodException {
    final var shape = shape("page");
    final var mapped = (Page<?>) shape.map(new PageImpl<>(List.of(ADA, BEA), PageRequest.of(0, 2), 5), TO_DTO);
    assertThat(mapped.getContent()).isEqualTo(List.of(new UserDto("1", "Ada"), new UserDto("2", "Bea")));
    assertThat(mapped.getTotalElements()).isEqualTo(5);
    assertThat(typeName(shape.mappedType(UserDto.class))).isEqualTo(typeName(Page.class, UserDto.class));
  }

  @Test
  void entityMapsItsBodyAndKeepsStatusAndHeaders() throws NoSuchMethodException {
    final var shape = shape("entity");
    assertThat(shape.isEntity()).isTrue();
    final var mapped = (ResponseEntity<?>) shape.map(
      ResponseEntity.status(HttpStatus.CREATED).header("X-Trace", "abc").body(ADA),
      TO_DTO
    );
    assertThat(mapped.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(mapped.getHeaders().getFirst("X-Trace")).isEqualTo("abc");
    assertThat(mapped.getBody()).isEqualTo(new UserDto("1", "Ada"));
    assertThat(typeName(shape.mappedType(UserDto.class))).isEqualTo(typeName(ResponseEntity.class, UserDto.class));
    assertThat(shape.bodyType(UserDto.class)).isEqualTo(UserDto.class);
  }

  @Test
  void entityWithoutBodyKeepsItsStatus() throws NoSuchMethodException {
    final var mapped = (ResponseEntity<?>) shape("entity").map(ResponseEntity.notFound().build(), TO_DTO);
    assertThat(mapped.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(mapped.getBody()).isNull();
  }

  @Test
  void entityOfAListMapsTheListInside() throws NoSuchMethodException {
    final var shape = shape("entityList");
    assertThat(shape.source()).isEqualTo(User.class);
    assertThat(((ResponseEntity<?>) shape.map(ResponseEntity.ok(List.of(ADA)), TO_DTO)).getBody()).isEqualTo(
      List.of(new UserDto("1", "Ada"))
    );
    assertThat(typeName(shape.bodyType(UserDto.class))).isEqualTo(typeName(List.class, UserDto.class));
  }

  @Test
  void unsupportedReturnTypesAreRefused() {
    for (final var name : List.of(
      "map",
      "array",
      "stream",
      "future",
      "wildcard",
      "raw",
      "nestedEntity",
      "string",
      "nothing"
    )) {
      assertThatThrownBy(() -> shape(name))
        .as(name)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("unsupported return type");
    }
  }

  private static ResponseShape shape(final String method) throws NoSuchMethodException {
    return ResponseShape.of(Returns.class.getDeclaredMethod(method).getGenericReturnType());
  }

  private static String typeName(final Type type) {
    return ResolvableType.forType(type).toString();
  }

  private static String typeName(final Class<?> raw, final Class<?> argument) {
    return ResolvableType.forClassWithGenerics(raw, argument).toString();
  }
}
