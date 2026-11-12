package io.github.eschizoid.telescope.demo.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An invoice. No one object holds all of it: the number and date come from the order, the billing
 * name and email from the customer, the seller's details from configuration and the amounts from
 * the pricing rules.
 */
public record InvoiceDto(
  String number,
  LocalDate issuedOn,
  String billTo,
  String email,
  String sellerName,
  String sellerVatId,
  BigDecimal subtotal,
  BigDecimal discount,
  BigDecimal total
) {}
