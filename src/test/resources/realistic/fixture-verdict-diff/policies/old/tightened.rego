package bank.tightened

import rego.v1

# METADATA
# scope: document
# title: Very high payments need CFO approval
# description: A payment above the CFO threshold needs CFO approval.
# custom:
#   control-id: BANK-TGT-001
#   frameworks:
#     - SOX ITGC-AC-2
deny contains msg if {
	input.payment.amount_minor > 500000
	not input.payment.cfo_approved
	msg := sprintf("payment %v needs CFO approval", [input.payment.id])
}
