package com.checkout.backend.security.google;

/** Lo que Cash-Out usa de un ID token de Google ya verificado. */
public record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {
}
