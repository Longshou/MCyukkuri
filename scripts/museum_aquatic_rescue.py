"""Real aquatic rescue cast; museum_hat_boats supplies finite initial conditions."""


def rescue(c):
    c.actor('mom', 'marisa', 4, 0, 'B', '救援亲油魔理沙', aquatic=True)
    c.actor('kid', 'marisa', 7, 0, 'B', '落水小子油', stage='child', aquatic=True)
    c.water_floor()
