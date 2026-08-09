# UI-audit post-interaction SHIORI evidence (#256)

Snake/Otacon corpus results expose a `dialogueProbe.sequence`, but its
post-choice records currently retain only request method, event ID, and an
unbounded-style reference array. The corpus validator therefore cannot prove
that interaction evidence has a stable ghost identity or a complete, explicit
dispatch envelope.

The focused contract adds a bounded `postInteractionEvidence` entry for every
post-choice/input dispatch. Each entry retains `ghostIdentity`, `method`,
`eventId`, `scope`, `coordinates`, `identifier`, `button`, `source`, and
`references` normalized to slots 0 through 6. Fields without an interaction
equivalent use JSON null rather than being omitted. The host validator rejects
missing fields, a non-seven-slot reference list, or an event other than the
choice/input dispatch being audited.
