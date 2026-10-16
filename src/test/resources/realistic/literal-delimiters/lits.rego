package litdelims

import rego.v1

minus_set contains msg if {
	count({"owner", "team"} - input.provided) > 0
	msg := "m"
}

cmp_array contains msg if {
	input.tags == ["a", "b"]
	msg := "m"
}

cmp_set contains msg if {
	input.tags == {"a", "b"}
	msg := "m"
}

member contains msg if {
	input.env in {"prod", "staging"}
	msg := "m"
}

ident_count contains msg if {
	count(["a", "b"]) > 1
	msg := "m"
}

joined contains msg if {
	concat("/", ["a", input.b]) == input.c
	msg := "m"
}
