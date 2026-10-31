Fixture for RealisticMetadataWithLogicDiffAcceptanceIT. `old`/`new` hold four controls: PAY-REL-001 changes logic (an exemption removed) AND its description, frameworks
and criticality; PAY-SAN-001 changes logic only (metadata identical); PAY-DOC-001 changes only its title (DOCS_CHANGED); PAY-LIM-001 changes only its description in the
Rego but its limit differs in `data/old.json` -> `data/new.json`, so with the data-aware diff it is raised from DOCS_CHANGED to LOGIC_CHANGED.
