package probes.p20_not_over_partial_set_rule

import rego.v1

flagged contains item if {
	some item in input.items
	item.risk == "high"
}

deny if {
	not flagged
}
