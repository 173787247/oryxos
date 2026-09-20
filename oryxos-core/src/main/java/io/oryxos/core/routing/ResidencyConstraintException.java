package io.oryxos.core.routing;

/** Raised when sensitive routing would violate configured residency (#477). */
public class ResidencyConstraintException extends RuntimeException {
  public ResidencyConstraintException(String message) {
    super(message);
  }
}
