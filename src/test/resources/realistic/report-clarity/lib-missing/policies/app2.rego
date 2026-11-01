package app2

import rego.v1

import data.lib.helpers

# METADATA
# scope: document
# title: Unknown function in a loaded package
# description: Description of APP-2.
# custom:
#   control-id: APP-2
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	helpers.missing_fn(input.n)
	matches := regex.find_all_string_submatch_n("a+", input.s, 1)
	count(input.xs) > 0
	msg := "denied"
}
