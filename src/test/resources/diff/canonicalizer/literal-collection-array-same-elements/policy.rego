package diff.literalcollection

import rego.v1

deny contains msg if {
	input.tags == ["a", "b"]
	msg := "m"
}
