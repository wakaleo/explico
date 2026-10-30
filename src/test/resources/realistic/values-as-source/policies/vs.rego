package vs

import rego.v1

# METADATA
# scope: document
# title: Same value twice
# description: Fixture control VS-1.
# custom:
#   control-id: VS-1
#   frameworks:
#     - ISO 27001 A.8.32
vs_one if {
	input.kind == "a"
	json.marshal(input.a) == "1"
	input.ok == true
	json.marshal(input.a) == "2"
	json.marshal(input.b) == "3"
}

# METADATA
# scope: document
# title: Same value, nested
# description: Fixture control VS-2.
# custom:
#   control-id: VS-2
#   frameworks:
#     - ISO 27001 A.8.32
vs_two if {
	json.marshal(input.a) == "4"
	count(json.marshal(input.c)) > 0
}

vs_fn(x) if {
	json.marshal(x) == "5"
}

helper_v if {
	json.marshal(input.h) == "6"
}
