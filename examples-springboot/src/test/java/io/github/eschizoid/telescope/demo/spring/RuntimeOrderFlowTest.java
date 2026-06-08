package io.github.eschizoid.telescope.demo.spring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * End-to-end integration test for the runtime-resolution path. Drives the full Spring stack
 * (Tomcat-equivalent test runner, Spring Data JPA against H2, Jackson-driven JSON binding) and
 * pins three pieces of behaviour:
 *
 * <ol>
 *   <li><b>POST round-trip preserves shape.</b> JSON → record → entity → DB → entity → record →
 *       JSON returns equal nested values (modulo Hibernate-assigned ids).
 *   <li><b>Pre-write normalisation hits a nested field.</b> The controller lowercases
 *       {@code customer.email} via a single {@code Telescope.of(Order.class).field(...).field(...).update(...)}
 *       call before persistence.
 *   <li><b>PATCH overlays only non-null fields.</b> Sending {@code {"orderNumber":"ORD-PATCHED"}}
 *       changes only the order number; customer, addresses, line items stay as the previous POST
 *       left them. Demonstrates {@code mapper.patch(existing, partial)}.
 * </ol>
 */
@SpringBootTest
class RuntimeOrderFlowTest {

  @Autowired private WebApplicationContext context;
  @Autowired private ObjectMapper objectMapper;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void postRoundTripPreservesShapeAndLowercasesEmail() throws Exception {
    final var request = OrderFixtures.sampleOrder();
    final var json = objectMapper.writeValueAsString(request);

    mvc
      .perform(post("/orders/runtime").contentType(MediaType.APPLICATION_JSON).content(json))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.id").isNumber())
      .andExpect(jsonPath("$.orderNumber").value("ORD-2026-0001"))
      // Customer email was "ALICE@example.com" in the request; the controller's pre-write
      // normalisation lowercased it before persistence.
      .andExpect(jsonPath("$.customer.email").value("alice@example.com"))
      .andExpect(jsonPath("$.customer.id").isNumber()) // Hibernate-assigned
      .andExpect(jsonPath("$.shippingAddress.city").value("Brooklyn"))
      .andExpect(jsonPath("$.billingAddress.city").value("Brooklyn"))
      .andExpect(jsonPath("$.lineItems[0].sku").value("SKU-A"))
      .andExpect(jsonPath("$.lineItems[0].quantity").value(2))
      // BigDecimal ↔ long-cents round-trip preserved to 2 decimals.
      .andExpect(jsonPath("$.lineItems[0].unitPrice").value(19.99))
      .andExpect(jsonPath("$.lineItems[1].unitPrice").value(49.50))
      .andExpect(jsonPath("$.giftWrap.street").value("300 Gift Rd"));
  }

  @Test
  void patchOnlyOverlaysProvidedFields() throws Exception {
    // First create an order so we have something to patch.
    final var createResponse = mvc
      .perform(post("/orders/runtime").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(OrderFixtures.sampleOrder())))
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    final var createdId = objectMapper.readTree(createResponse).get("id").asLong();

    // Patch only the order number.
    final var patchBody = objectMapper.writeValueAsString(OrderFixtures.patchOrderNumberOnly("ORD-PATCHED"));
    mvc
      .perform(patch("/orders/runtime/" + createdId).contentType(MediaType.APPLICATION_JSON).content(patchBody))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.orderNumber").value("ORD-PATCHED"))
      // Customer email survived (still lowercased from the create) — patch didn't replace it.
      .andExpect(jsonPath("$.customer.email").value("alice@example.com"))
      .andExpect(jsonPath("$.shippingAddress.city").value("Brooklyn"));
  }

  @Test
  void getReturns404ForUnknownId() throws Exception {
    mvc.perform(get("/orders/runtime/999999")).andExpect(status().isNotFound());
  }
}
