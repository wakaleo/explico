package probes.p16_function_overloading

import rego.v1

severity_of(level) := 3 if level == "critical"

severity_of(level) := 2 if level == "high"

severity_of(level) := 1 if not level in {"critical", "high"}

deny if {
	severity_of(input.finding.level) >= 2
}
