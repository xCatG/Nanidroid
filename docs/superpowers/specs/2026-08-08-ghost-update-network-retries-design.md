# Ghost Update Network Retry Design

Issue #184 distinguishes absent update files from transient transport failures.
The durable operation handle and WorkManager UUID remain unchanged for every
retry. The Android adapter exposes `Found`, `NotFound`, and
`RetryableFailure`; only the definitive not-found outcome preserves the
existing absent-manifest/file behavior.

HTTP 408, 429, and 5xx responses, plus transport failures while reading a
returned response body, are retryable. Local candidate-output and cleanup I/O
failures remain terminal.

`GhostUpdateRepository` maps a retryable network outcome to `Interrupted`, and
the worker already maps that result to `Result.retry()` unless exact user
cancellation was requested. Ghost-update work receives a CONNECTED constraint.
No transport failure automatically requests cancellation or terminalizes the
operation.
