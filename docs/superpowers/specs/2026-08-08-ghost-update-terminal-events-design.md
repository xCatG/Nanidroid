# Ghost Update Terminal Event Recovery Design

## Scope

Issue #183 ensures a headless ghost-update worker preserves terminal SHIORI
events until the exact updated ghost can receive them. It does not change
network retry behavior, staging cleanup, WorkManager adapter coverage, or the
Compose surface rebind behavior.

## Durable ownership and identity

The durable ghost-update operation record is the source of truth. A pending
terminal event belongs to exactly one operation handle, which already binds an
operation ID and attempt ID. Delivery additionally requires the record's
canonical ghost root and ghost ID. A loaded ghost, selected ghost, or Activity
field is never a durable claim.

## Transition table

| Trigger | Durable state | Result |
| --- | --- | --- |
| Terminal event with exact ghost currently loaded | No pending event | Dispatch immediately through the existing bound root/ID guard. |
| Terminal event with no exact ghost loaded | Pending payload on the exact operation handle, including its just-terminal record | Preserve the event; do not deliver it to a different ghost. |
| Matching ghost construction or reload | Pending payload for the matching handle/root/ID | Dispatch once, then remove that payload. |
| Process death before removal | Pending payload remains | Retry delivery only after the exact ghost is constructed. |
| Retry, replacement, or deletion | Different current handle or no record | Old payload is never delivered. |
| Recovery of a journal created before ghost identity was recorded | No pending payload | Complete/rollback recovery normally; do not guess an identity from its path. |

## Boundaries and failure handling

The worker/event adapter decides immediate versus deferred delivery. The
durable-operation store owns deferred payload persistence and exact removal.
Ghost construction is the consumer boundary. An event is removed only after a
successful dispatch through the existing bound ghost guard. Repeated worker
execution and repeated construction may observe the same pending payload, but
only the matching handle can claim it for delivery.

## Tests

JVM tests cover a headless terminal completion, delivery after matching ghost
construction, mismatched ghost/root rejection, duplicate construction, and
restart preservation. Tests use the durable store and bound dispatch behavior;
they do not depend on an Activity.
