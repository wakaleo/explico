package bank.limits

import rego.v1

# METADATA
# scope: document
# title: Payments above the currency limit are denied
# description: A payment above the limit for its currency is denied.
# custom:
#   control-id: BANK-LIM-001
#   frameworks:
#     - SOX ITGC-AC-2
deny contains msg if {
	input.payment.amount_minor > data.limits.max[input.payment.currency]
	msg := sprintf("payment %v is above the limit", [input.payment.id])
}
