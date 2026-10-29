package grp_signoff

# METADATA
# scope: document
# custom:
#   control-id: SEC-28
valid_signature if {
	input.signoff.signature != ""
	signed_off
}
