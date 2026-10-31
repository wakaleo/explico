package payments.release

import rego.v1

# METADATA
# scope: document
# title: Large payments need a second approver
# description: A payment above the limit needs a second approver.
# custom:
#   control-id: PAY-REL-001
#   owner: Payments Risk
#   criticality: critical
#   frameworks:
#     - SOX ITGC-AC-2
#     - PSD2 Art. 97
deny contains msg if {
	input.payment.amount_minor > 100000
	not input.payment.second_approver
	msg := sprintf("payment %v is denied", [input.payment.id])
}
