package verdict_declared

# METADATA
# scope: document
# title: Owner required (declared verdict)
# custom:
#   control-id: CTRL-V-DECL
#   verdict:
#     outcome: compliant
#     reason: commentary
decision := {"compliant": false, "commentary": "No owner recorded"} if {
	not input.owner
} else := {"compliant": true, "commentary": "Owner recorded"}
