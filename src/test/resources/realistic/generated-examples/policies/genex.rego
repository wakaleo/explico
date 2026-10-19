package genex

import rego.v1

# METADATA
# title: Buckets must be encrypted
# custom:
#   control-id: GX-001
unencrypted contains msg if {
	input.encrypted == false
	msg := sprintf("bucket %v is not encrypted", [input.name])
}

# METADATA
# title: No privileged containers in production
# custom:
#   control-id: GX-002
privileged_in_prod contains msg if {
	input.env == "production"
	some c in input.containers
	c.privileged == true
	msg := "privileged container in production"
}

# METADATA
# scope: document
# title: Two independent ways to be rejected
# custom:
#   control-id: GX-003
two_ways contains msg if {
	input.kind == "a"
	msg := "kind a is not allowed"
}

two_ways contains msg if {
	input.size > 10
	msg := "size over ten"
}

big(n) if n > 10

# METADATA
# title: A rule whose body calls a function (cannot be solved mechanically)
# custom:
#   control-id: GX-004
unsolvable contains msg if {
	big(input.n)
	msg := "n is big"
}

# METADATA
# title: Author fixtures already cover a match and a non-match
# custom:
#   control-id: GX-005
fully_covered contains msg if {
	input.flag == true
	msg := "flag is set"
}

# METADATA
# title: Author fixtures only ever match this rule
# custom:
#   control-id: GX-006
only_matching_authored contains msg if {
	input.level == "high"
	input.region == "eu"
	msg := "high level in the EU"
}
