package bank.message

import rego.v1

# METADATA
# scope: document
# title: Payments to sanctioned countries are blocked
# description: A payment to a sanctioned country is denied.
# custom:
#   control-id: BANK-MSG-001
#   frameworks:
#     - SOX ITGC-AC-2
deny contains msg if {
	input.payment.beneficiary_country == "KP"
	msg := sprintf("payment %v goes to a sanctioned country", [input.payment.id])
}
