package difffunctions

import rego.v1

lim(x) := 100 if x == input.allowed

# METADATA
# scope: rule
# custom:
#   control-id: DF-001
deny contains msg if {
	input.size > lim(input.env)
	input.replicas < 3
	msg := "m"
}
