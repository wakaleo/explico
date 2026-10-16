package funcvalues

import rego.v1

import data.funcvalues.other

severity_of(level) := 3 if level == "critical"

severity_of(level) := 2 if level == "high"

severity_of(level) := 1 if not level in {"critical", "high"}

limit_for(env) := 100 if env == input.allowed

deny contains msg if {
	severity_of(input.finding.level) >= 2
	msg := "severe"
}

cap contains msg if {
	input.count > limit_for(input.env)
	msg := "over cap"
}

nested contains msg if {
	count(input.items) > limit_for(severity_of(input.level))
	msg := "n"
}

via_var contains msg if {
	s := severity_of(input.level)
	s > 1
	msg := "v"
}

bad_arg contains msg if {
	limit_for({e | some e in input.envs}) > 1
	msg := "b"
}

unknown contains msg if {
	other.helper(input.x) > 1
	msg := "u"
}
