package bank.situation

import rego.v1

# METADATA
# scope: document
# title: Payments must be screened
# description: A payment may only be released once screening is clear.
# custom:
#   control-id: BANK-SIT-001
#   frameworks:
#     - SOX ITGC-AC-2
deny contains msg if {
	input.payment.screening_status != "clear"
	msg := sprintf("payment %v is not screened", [input.payment.id])
}

deny contains msg if {
	input.payment.beneficiary_country == "IR"
	msg := sprintf("payment %v goes to a blocked country", [input.payment.id])
}
