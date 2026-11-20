package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequestBody;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Note;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.Unrelated;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A request body whose pair has no mapper, and whose same-name forward mapping pairs no component,
 * fails startup naming the parameter, the pair and the components on each side.
 */
class UnpairedRequestRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Notes.class, Unrelated.class).addPackage(Order.class.getPackage()))
    .assertException(
      refusedWith(
        "Notes#add(note): Unrelated -> Note pairs nothing: no field of Note (text) is named like a field of" +
          " Unrelated (zzz, yyy), so the mapping would leave each at its default. Check the type the endpoint" +
          " names, or map the pair with a TelescopeProjection, a Mapper bean or a @Bridge."
      )
    );

  @Path("/notes")
  @Consumes(MediaType.APPLICATION_JSON)
  public static class Notes {

    @POST
    public String add(@TelescopeRequestBody(from = Unrelated.class) final Note note) {
      return note.text();
    }
  }

  @Test
  @DisplayName("a request body whose pair pairs no component by name is refused at startup")
  void aBodyThatPairsNothingFailsStartup() {
    fail("the application booted");
  }
}
