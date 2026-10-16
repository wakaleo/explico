package diff.literalcollection

import rego.v1

# a comment that changes nothing about the logic
deny contains msg if {
	input.tags == {"a",    "b"}
	msg := "m"
}
