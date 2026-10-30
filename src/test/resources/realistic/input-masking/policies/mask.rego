package mask

import rego.v1

# METADATA
# scope: document
# title: A change needs a ticket
# custom:
#   control-id: MK-1
#   mask:
#     - requester.email
#     - subjectId
#     - ticket
no_ticket contains msg if {
	not input.ticket
	msg := sprintf("%v (subject %v) has no ticket", [input.requester.email, input.subjectId])
}

# METADATA
# scope: document
# title: A change needs an approval (nothing masked)
# custom:
#   control-id: MK-2
no_approval contains msg if {
	not input.approval
	msg := sprintf("%v has no approval", [input.requester.email])
}

# METADATA
# scope: document
# title: No inactive people on an account
# custom:
#   control-id: MK-3
#   mask:
#     - people[].email
#     - accountNumber
has_inactive contains msg if {
	some p in input.people
	p.active == false
	msg := sprintf("account %v: %v is inactive", [input.accountNumber, p.email])
}

# METADATA
# scope: document
# title: Attestation decision
# custom:
#   control-id: MK-4
#   mask:
#     - subjectId
#   verdict:
#     outcome: compliant
#     reason: commentary
#   actions:
#     at: actions
#     label: label
decision := {
	"compliant": false,
	"commentary": sprintf("No attestation for %v", [input.subjectId]),
	"actions": [{"label": sprintf("complete the form for %v", [input.subjectId])}],
} if {
	not input.form
}
