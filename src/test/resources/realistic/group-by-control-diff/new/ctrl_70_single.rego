package grp_single

# METADATA
# scope: document
# title: Single rule
# custom:
#   control-id: SEC-70
only if {
	input.single.a
	input.single.b
}
