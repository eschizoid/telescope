package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.parity.ElsewhereBridgeProvider;
import io.github.eschizoid.telescope.quarkus.tests.parity.Ticket;
import io.github.eschizoid.telescope.quarkus.tests.parity.TicketDto;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A pair with no projection or mapper bean maps as {@code Telescope.mapperForward} maps it, which
 * is what the Spring starter calls: the bridge holder beside the source wins over a bridge a
 * provider elsewhere registers for the same pair, so both starters write the same body.
 */
class ForwardParityTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar ->
    jar
      .addClasses(Tickets.class)
      .addPackage(Ticket.class.getPackage())
      .addAsServiceProvider(BridgeProvider.class, ElsewhereBridgeProvider.class)
  );

  @Path("/tickets")
  @Produces(MediaType.APPLICATION_JSON)
  public static class Tickets {

    @GET
    @TelescopeResponse(to = TicketDto.class)
    public Ticket ticket() {
      return new Ticket("T-1");
    }
  }

  @Test
  @DisplayName("a pair maps through the holder beside the source ahead of a provider's bridge, as mapperForward does")
  void theHolderBesideTheSourceWins() {
    final var body = given().get("/tickets").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"id\":\"T-1\",\"via\":\"holder\"}");
    assertThat(Telescope.mapperForward(Ticket.class, TicketDto.class).forward(new Ticket("T-1")).via()).isEqualTo(
      "holder"
    );
  }
}
