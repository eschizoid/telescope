package io.github.eschizoid.telescope.demo.spring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end integration test for the codegen-driven path. Reuses {@code OrderFixtures} and
 * asserts the same shape preservation as {@link RuntimeOrderFlowTest} — the two flows must be
 * behaviourally indistinguishable; only the mapper implementation differs.
 *
 * <p>The "normalise emails" endpoint is exclusive to this flow and demonstrates a server-side
 * deep-update through the codegen-emitted holders: re-load an order, mutate one field two levels
 * deep, save the result, return the JSON.
 */
@SpringBootTest
class CodegenOrderFlowTest {

  @Autowired private WebApplicationContext context;
  @Autowired private ObjectMapper objectMapper;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void postRoundTripPreservesShape() throws Exception {
    final var json = objectMapper.writeValueAsString(OrderFixtures.sampleOrder());

    mvc
      .perform(post("/orders/codegen").contentType(MediaType.APPLICATION_JSON).content(json))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.id").isNumber())
      .andExpect(jsonPath("$.orderNumber").value("ORD-2026-0001"))
      .andExpect(jsonPath("$.customer.email").value("alice@example.com"))
      .andExpect(jsonPath("$.customer.id").isNumber())
      .andExpect(jsonPath("$.shippingAddress.zip").value("11201"))
      .andExpect(jsonPath("$.lineItems[0].unitPrice").value(19.99))
      .andExpect(jsonPath("$.giftWrap.street").value("300 Gift Rd"));
  }

  @Test
  void normaliseEmailsAppliesDeepUpdateThroughHolderConstants() throws Exception {
    // Create.
    final var createResponse = mvc
      .perform(post("/orders/codegen").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(OrderFixtures.sampleOrder())))
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    final var createdId = objectMapper.readTree(createResponse).get("id").asLong();

    // Drive the dedicated normalise endpoint — re-loads, runs the deep-update through the
    // codegen-emitted holder constants, re-saves, returns JSON. Idempotent (already lowercase
    // after create).
    mvc
      .perform(post("/orders/codegen/normalise-emails/" + createdId).contentType(MediaType.APPLICATION_JSON))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.customer.email").value("alice@example.com"));
  }
}
