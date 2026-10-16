package jsonbuiltins

import rego.v1

valid_payload contains msg if {
	json.is_valid(input.payload)
	msg := "ok"
}

invalid_payload contains msg if {
	not json.is_valid(input.payload)
	msg := "bad"
}

any_item contains msg if {
	json.is_valid(input.items[_])
	msg := "i"
}

via_param(p) if {
	json.is_valid(p)
}

marshal_rule contains msg if {
	x := json.marshal(input.p)
	x == "1"
	msg := "m"
}
