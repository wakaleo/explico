package probes.p40_non_negated_partial_set_rule_reference

import rego.v1

flagged contains item if {
	some item in input.items
	item.risk == "high"
}

deny if {
	flagged
}
