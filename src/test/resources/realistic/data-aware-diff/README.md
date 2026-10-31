Fixture for RealisticDataAwareDiffAcceptanceIT and CliDataAwareDiffProcessTest. `policies/old` has three controls: BANK-LIM-001 reads a limit map with a
dynamic key (`data.limits.single_approver_minor[payment.currency]`, so the plain prefix is the whole map), BANK-HRC-001 reads a list with `in`
(`data.high_risk_countries`), BANK-SAN-001 reads no data. `policies/new-logic` changes only BANK-LIM-001's comparison (`>` to `>=`). `data/old.json` is the
baseline; every other file changes ONE thing: limit-changed (EUR), key-added (CHF), list-added (SY), unread-changed (`audit.retention_days`, read by no control),
list-removed-entirely (`high_risk_countries` gone), and same-reformatted (identical values, different key order and layout).
