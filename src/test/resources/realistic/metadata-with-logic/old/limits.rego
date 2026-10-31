package payments.limits

import rego.v1

# METADATA
# scope: document
# title: Payments above the currency limit are denied
# description: A payment above the limit for its currency is denied.
# custom:
#   control-id: PAY-LIM-001
#   owner: Payments Risk
#   criticality: high
#   frameworks:
#     - SOX ITGC-AC-2
deny contains msg if {
	input.payment.amount_minor > data.limits.max[input.payment.currency]
	msg := sprintf("payment %v is denied", [input.payment.id])
}
