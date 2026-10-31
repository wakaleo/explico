package payments.release

import rego.v1

# METADATA
# scope: document
# title: Large payments need a second approver
# description: A payment above the limit needs a second approver. Payments pre-approved by Treasury are exempt.
# custom:
#   control-id: PAY-REL-001
#   owner: Payments Risk
#   criticality: high
#   frameworks:
#     - SOX ITGC-AC-2
deny contains msg if {
	input.payment.amount_minor > 100000
	not input.payment.second_approver
	not input.payment.treasury_preapproved
	msg := sprintf("payment %v is denied", [input.payment.id])
}
