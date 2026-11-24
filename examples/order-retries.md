# Order retry behavior

This fictional order service retries transient upstream failures with exponential backoff. A successful retry uses the original idempotency key to avoid creating a second order.
