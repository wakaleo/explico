package bank.added

import rego.v1

# METADATA
# scope: document
# title: Swiss payments need review
# description: A CHF payment is denied.
# custom:
#   control-id: BANK-NEW-001
#   frameworks:
#     - SOX ITGC-AC-2
deny contains msg if {
	input.payment.currency == "CHF"
	msg := sprintf("payment %v is in CHF", [input.payment.id])
}
