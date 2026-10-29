package diffcanon

verdict := "a" if {
	some x in input.items
	x.ok
} else := "b" if {
	some x in input.others
	x.ok
} else := "c"
