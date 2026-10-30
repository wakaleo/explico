package tp_test

import rego.v1

import data.tp

test_denies if {
	count(tp.deny) > 0 with input as {"a": 1}
}

todo_test_later if {
	tp.used_only_by_a_test with input as {"b": 1}
}
