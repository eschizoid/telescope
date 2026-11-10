package io.github.telescopeorganization.telescope.demo.spring.domain.payment;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.demo.spring.legacy.PayPalEntity;

@Bridge(PayPalEntity.class)
public record PayPal(String email, String token) implements Payment {}
