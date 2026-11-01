Fixture for the B group of RealisticReportClarityAcceptanceIT (the "Showing M of N examples" line on a card). `policies/` has two single-rule controls: EX-1 matches `tag == "x"`, EX-2 matches
`tag == "y"`. A card lists at most 3 matching then 2 non-matching examples. `examples-7` (tags x x x x y y y): both cards list 5 of 7. `examples-5` (x x x y y): EX-1 lists all 5 (no line), EX-2 lists 4 of 5.
`examples-2` (x y): everything is listed. `grouped/policies` is one control (G-1) over two rules (tag x and tag y), rendered with --group-by-control against `examples-7`.
