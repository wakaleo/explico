package bank.hrc

import rego.v1

# METADATA
# scope: document
# title: Payments to high-risk countries need enhanced due diligence
# description: A payment whose beneficiary is in a high-risk country must have completed enhanced due diligence.
# custom:
#   control-id: BANK-HRC-001
#   frameworks:
#     - DORA Art. 9
deny contains msg if {
	input.payment.beneficiary.country in data.high_risk_countries
	not input.payment.edd_complete
	msg := sprintf("payment %v needs enhanced due diligence", [input.payment.id])
}
