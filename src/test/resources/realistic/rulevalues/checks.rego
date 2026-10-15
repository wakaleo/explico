package rulevalues.checks

import rego.v1

import data.rulevalues.config

threshold := 5

flagged contains x if {
	some x in input.flags
}

deny contains msg if {
	count(input.items) > threshold
	config.max_items < count(input.items)
	data.rulevalues.config.region_prefix == input.region_prefix
	data.external.limit > 0
	msg := "rule value references"
}

warn contains msg if {
	count(flagged) > 1
	msg := "many flags"
}
