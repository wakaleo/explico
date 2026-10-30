package vm

import rego.v1

# METADATA
# scope: document
# title: Twenty-seven distinct values
# description: Fixture control VM-1.
# custom:
#   control-id: VM-1
#   frameworks:
#     - ISO 27001 A.8.32
vm_one if {
	json.marshal(input.v01) == "x"
	json.marshal(input.v02) == "x"
	json.marshal(input.v03) == "x"
	json.marshal(input.v04) == "x"
	json.marshal(input.v05) == "x"
	json.marshal(input.v06) == "x"
	json.marshal(input.v07) == "x"
	json.marshal(input.v08) == "x"
	json.marshal(input.v09) == "x"
	json.marshal(input.v10) == "x"
	json.marshal(input.v11) == "x"
	json.marshal(input.v12) == "x"
	json.marshal(input.v13) == "x"
	json.marshal(input.v14) == "x"
	json.marshal(input.v15) == "x"
	json.marshal(input.v16) == "x"
	json.marshal(input.v17) == "x"
	json.marshal(input.v18) == "x"
	json.marshal(input.v19) == "x"
	json.marshal(input.v20) == "x"
	json.marshal(input.v21) == "x"
	json.marshal(input.v22) == "x"
	json.marshal(input.v23) == "x"
	json.marshal(input.v24) == "x"
	json.marshal(input.v25) == "x"
	json.marshal(input.v26) == "x"
	json.marshal(input.v27) == "x"
}
