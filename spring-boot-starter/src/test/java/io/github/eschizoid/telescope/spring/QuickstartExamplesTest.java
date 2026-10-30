package io.github.eschizoid.telescope.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.spring.quickstart.Contact;
import io.github.eschizoid.telescope.spring.quickstart.Customer;
import io.github.eschizoid.telescope.spring.quickstart.CustomerDto;
import io.github.eschizoid.telescope.spring.quickstart.CustomerEmailTransformer;
import io.github.eschizoid.telescope.spring.quickstart.CustomerMapper;
import io.github.eschizoid.telescope.spring.quickstart.CustomerProjection;
import io.github.eschizoid.telescope.spring.quickstart.CustomerSummaryProjection;
import io.github.eschizoid.telescope.spring.quickstart.NormalizedCustomerProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Runs the examples in the starter README's quickstart, so the documented behaviour stays true. */
class QuickstartExamplesTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Scan.class);

  @Test
  void bridgeBackedMapperIsInjectable() {
    runner.run(context -> {
      final var mapper = context.getBean(CustomerMapper.class);
      assertThat(mapper.map(new Customer("Ada", new Contact("ada@example.com")))).isEqualTo(
        new CustomerDto("Ada", new Contact("ada@example.com"))
      );
    });
  }

  @Test
  @DisplayName("a projection over the bridge maps both ways and patches, as Mapper.patch does")
  void projectionMapsBothWaysAndPatchesOverTheBridge() {
    runner.run(context -> {
      final var projection = context.getBean(CustomerProjection.class);
      final var customer = new Customer("Ada", new Contact("ada@example.com"));
      assertThat(projection.backward(projection.map(customer))).isEqualTo(customer);
      final var partial = new CustomerDto("Bea", null);
      final var patched = projection.patch(customer, partial);
      assertThat(patched).isEqualTo(new Customer("Bea", new Contact("ada@example.com")));
      assertThat(patched).isEqualTo(Telescope.mapper(Customer.class, CustomerDto.class).patch(customer, partial));
      assertThat(patched.contact()).isSameAs(customer.contact());
    });
  }

  @Test
  void transformerNormalizesBeforeMapping() {
    runner.run(context -> {
      final var email = context.getBean(CustomerEmailTransformer.class);
      final var projection = context.getBean(NormalizedCustomerProjection.class);
      final var customer = new Customer("Ada", new Contact(" ADA@EXAMPLE.COM "));

      assertThat(email.path().read(customer)).isEqualTo(" ADA@EXAMPLE.COM ");
      assertThat(email.apply(customer).contact().email()).isEqualTo("ada@example.com");
      assertThat(projection.map(customer)).isEqualTo(new CustomerDto("Ada", new Contact("ada@example.com")));
      assertThat(projection.map(new Customer("Ada", new Contact(null))).contact().email()).isEqualTo(
        "unknown@example.com"
      );
      assertThat(projection.map(new Customer("Ada", null)).contact()).isNull();
      assertThat(customer.contact().email()).isEqualTo(" ADA@EXAMPLE.COM ");
    });
  }

  @Test
  void translateRenamesAndStampsWithTypedRows() {
    runner.run(context -> {
      final var summary = context
        .getBean(CustomerSummaryProjection.class)
        .map(new Customer("Ada", new Contact("ada@example.com")));
      assertThat(summary.displayName()).isEqualTo("Ada");
      assertThat(summary.source()).isEqualTo("crm");
      assertThat(summary.generatedAt()).isNotNull();
    });
  }

  @Configuration
  @ComponentScan(basePackageClasses = Customer.class)
  static class Scan {}
}
