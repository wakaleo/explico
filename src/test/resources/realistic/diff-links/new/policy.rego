package difflinks

import rego.v1

is_prod if input.env == "prod"

is_eu if input.region == "eu-west"

# METADATA
# scope: rule
# custom:
#   control-id: DL-001
deny contains msg if {
	is_prod
	input.replicas < 3
	msg := "prod needs replicas"
}

# METADATA
# scope: rule
# custom:
#   control-id: DL-002
warn contains msg if {
	is_eu
	input.tier == "paid"
	msg := "eu free tier"
}
