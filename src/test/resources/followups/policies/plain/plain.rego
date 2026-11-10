package plain_flow

import data.shared.non_compliant

# No custom.verdict here: the inlined object is listed as its fields, never read as a verdict.
decision = non_compliant("Flow is broken", [], []) if {
    input.flow.broken
}
