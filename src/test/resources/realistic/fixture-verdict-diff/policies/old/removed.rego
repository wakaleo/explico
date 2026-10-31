package bank.removed

import rego.v1

# METADATA
# scope: document
# title: Pending payments wait
# description: A pending payment is denied.
# custom:
#   control-id: BANK-REM-001
#   frameworks:
#     - SOX ITGC-AC-2
deny contains msg if {
	input.payment.screening_status == "pending"
	msg := sprintf("payment %v is pending", [input.payment.id])
}
