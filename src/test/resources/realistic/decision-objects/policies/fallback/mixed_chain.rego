package decision_fallback

# METADATA
# scope: document
# title: Tier with an unrenderable branch
# custom:
#   control-id: CTRL-FALLBACK
tier = "gold" if {
    input.score >= 90
} else = "silver" if {
    input.score >= 50 with input.score as 60
} else = "bronze"
