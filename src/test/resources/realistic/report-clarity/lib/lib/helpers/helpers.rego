package lib.helpers

import rego.v1

is_big(x) if x > 100

names contains n if {
	some n in input.names
}
