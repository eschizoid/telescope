package io.github.eschizoid.telescope.demo.spring.bughunt.sealed;

/**
 * Root record carrying a sealed {@link Payment}. Drives the bug-hunt chain:
 *
 * <pre>{@code
 * Telescope.of(PaymentRoot.class)
 *     .field(PaymentRoot::payment)                // record-side Payment
 *     .then(paymentMapper.asTelescope())          // hop to bean-side PaymentEntity
 *     .as(CreditCardEntity.class)                 // sealed narrow on the BEAN side
 *     .field(CreditCardEntity::getCardNumber)     // bean getter — needs BeanFieldOptics dispatch
 *     .update(root, masker)
 * }</pre>
 *
 * <p>If {@code .as()} narrows correctly but the subsequent {@code .field()} still routes through
 * the entry-point's record-side {@code FieldOptics}, the bean getter accessor cannot be resolved.
 */
public record PaymentRoot(String orderId, Payment payment) {}
