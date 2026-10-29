package grp_evidence

import data.grp_other

# METADATA
# scope: document
# title: Evidence is attached
# custom:
#   control-id: SEC-28
evidence_present if {
	count(input.evidence) > 0
	grp_other.retention_ok
}
