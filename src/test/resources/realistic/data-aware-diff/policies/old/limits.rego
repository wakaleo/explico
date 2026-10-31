package bank.limits

import rego.v1

# METADATA
# scope: document
# title: Payments above the single-approver limit need a second approver
# description: A payment above the limit for its currency needs approval from someone other than its initiator.
# custom:
#   control-id: BANK-LIM-001
#   frameworks:
#     - SOX ITGC-AC-2
deny contains msg if {
	payment := input.payment
	payment.amount_minor > data.limits.single_approver_minor[payment.currency]
	not payment.second_approver
	msg := sprintf("payment %v needs a second approver", [payment.id])
}
