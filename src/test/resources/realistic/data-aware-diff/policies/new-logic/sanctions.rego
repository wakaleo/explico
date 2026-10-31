package bank.sanctions

import rego.v1

# METADATA
# scope: document
# title: Payments must be sanctions-screened
# description: A payment may only be released once its screening result is clear.
# custom:
#   control-id: BANK-SAN-001
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.payment.screening.status != "clear"
	msg := sprintf("payment %v is not screened", [input.payment.id])
}
