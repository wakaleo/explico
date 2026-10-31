package payments.docs

import rego.v1

# METADATA
# scope: document
# title: Blocked currencies (updated)
# description: Payments in a blocked currency are denied.
# custom:
#   control-id: PAY-DOC-001
#   owner: Payments Risk
#   criticality: high
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.payment.currency == "KPW"
	msg := sprintf("payment %v is denied", [input.payment.id])
}
