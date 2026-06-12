package io.github.eschizoid.telescope.demo.spring.domain.payment;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.demo.spring.legacy.PayPalEntity;

@Bridge(PayPalEntity.class)
public record PayPal(String email, String token) implements Payment {}
