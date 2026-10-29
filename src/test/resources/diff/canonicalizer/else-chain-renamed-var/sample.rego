package diffcanon

verdict := "a" if {
	some x in input.items
	x.ok
} else := "b" if {
	some y in input.others
	y.ok
} else := "c"
