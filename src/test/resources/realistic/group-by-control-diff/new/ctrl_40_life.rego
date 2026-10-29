package grp_life

import data.grp_signoff

# METADATA
# scope: document
# title: Lifecycle
# custom:
#   control-id: SEC-40
keep if input.life.keep

# METADATA
# scope: document
# title: Lifecycle
# custom:
#   control-id: SEC-40
fresh if {
	input.life.fresh
	grp_signoff.signed_off
}
