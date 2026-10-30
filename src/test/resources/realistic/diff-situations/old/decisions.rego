package sit

verdict(commentary) = {"compliant": false, "commentary": commentary}

# METADATA
# scope: document
# title: Risk tier, reordered
# custom:
#   control-id: SIT-1
tier_reordered := "high" if {
	input.risk > 80
} else := "medium" if {
	input.risk > 40
} else := "low"

# METADATA
# scope: document
# title: Commentary changed
# custom:
#   control-id: SIT-2
commentary_changed := verdict("No attestation provided") if {
	not input.form
} else := verdict("Attestation has expired") if {
	input.form.expired
} else := verdict("Attestation is valid")

# METADATA
# scope: document
# title: Situations added and removed
# custom:
#   control-id: SIT-3
band := "gold" if {
	input.score > 90
} else := "silver" if {
	input.score > 60
} else := "bronze"

# METADATA
# scope: document
# title: Unchanged chain
# custom:
#   control-id: SIT-4
steady := "a" if {
	input.x
} else := "b"

# METADATA
# scope: document
# title: Single value changed
# custom:
#   control-id: SIT-5
limit := 10 if input.kind == "small"

# METADATA
# scope: document
# title: Sibling definitions
# custom:
#   control-id: SIT-6
label := "first" if input.a

label := "second" if input.b

# METADATA
# scope: document
# title: Declared verdict
# custom:
#   control-id: SIT-7
#   verdict:
#     outcome: compliant
#     reason: commentary
declared := verdict("fixed text") if input.x
