package app

import rego.v1

import data.lib.helpers

# METADATA
# scope: document
# title: Uses a helper package
# description: Description of APP-1.
# custom:
#   control-id: APP-1
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.kind == "x"
	helpers.is_big(input.n)
	not helpers.is_big(input.m)
	some n in helpers.names
	msg := "denied"
}
