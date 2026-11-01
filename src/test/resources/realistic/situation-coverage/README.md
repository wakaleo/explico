Fixture for RealisticSituationCoverageAcceptanceIT (the per-situation example coverage finding). One package per control. `examples/` has three fixtures:
01 matches SC-1 situation 1, SC-2 situation 1, SC-3, SC-5, SC-6 situation 1, SC-7 situation 1 and SC-8; 02 matches SC-1 situation 3 and SC-2 situation 2; 03 matches nothing.
So SC-1 (situation 2) and SC-7 (situations 2, 3) have a situation no example matches; SC-2 is fully covered, SC-3 has one body, SC-4 is matched by no fixture at all, SC-5 is a boolean
rule, SC-6 has a body whose message is not a template, SC-8 is an `else` chain -- none of those can be claimed. `examples-more/` adds a fourth fixture matching SC-7 situations 2 and 3
(so only SC-1 is left). `grouped/` has one control id over two rules (two bodies each) with one fixture: skipped under --group-by-control.
