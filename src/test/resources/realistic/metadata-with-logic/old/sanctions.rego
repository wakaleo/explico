package payments.sanctions

import rego.v1

# METADATA
# scope: document
# title: Payments must be screened
# description: A payment may only be released once screening is clear.
# custom:
#   control-id: PAY-SAN-001
#   owner: Payments Risk
#   criticality: critical
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.payment.screening_status != "clear"
	msg := sprintf("payment %v is denied", [input.payment.id])
}
