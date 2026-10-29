package diffcanon

# A comment that changes nothing.
verdict := "blocked" if {
	input.deployment.environment == "production"   # trailing comment

	not input.change.ticket.approved
} else := "review" if {
	input.deployment.size   >   3
} else := "allowed"
