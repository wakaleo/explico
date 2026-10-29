package grp_signoff

import data.grp_evidence

# METADATA
# scope: document
# title: Sign-off
# description: A change is signed off by an approver.
# custom:
#   control-id: SEC-28
#   frameworks:
#     - SOC 2 CC8.1
signed_off if {
	input.signoff.approved
	input.signoff.approver != ""
	grp_evidence.evidence_present
}
