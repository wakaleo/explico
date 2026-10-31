package bank.exemption

import rego.v1

# METADATA
# scope: document
# title: High-value payments need a second approver
# description: A payment above the limit needs a second approver.
# custom:
#   control-id: BANK-EXM-001
#   frameworks:
#     - SOX ITGC-AC-2
deny contains msg if {
	input.payment.amount_minor > 100000
	not input.payment.second_approver
	msg := sprintf("payment %v needs a second approver", [input.payment.id])
}
