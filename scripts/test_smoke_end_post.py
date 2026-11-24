"""Deliberate-break fixtures for the END path postcondition window (``post_within``); no game."""
import unittest

from smoke_checks import EndContext

MOTHER = '11111111-1111-1111-1111-111111111111'
CHILD = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
INSIDE = 'alive=true and nest=INSIDE_SUPPORTED'
PUT_DOWN = 'carry put down reason=reached_nest hold=brim cause=brim_hop purpose=PROTECT child=%s nest=ON_RIM' % CHILD


def end_context(inside_from):
    """The put-down at t=100 lands on the rim; the child reads INSIDE_SUPPORTED from tick ``inside_from`` on (or never)."""
    records = [dict(k='ev', u=MOTHER, t=100, seq=1, cat='family', msg=PUT_DOWN),
               dict(k='geo', u=CHILD, t=200, tags=['scn_baby'])]
    samples = [dict(u=CHILD, t=t, state=dict(alive=True, hp=5, nest='INSIDE_SUPPORTED' if inside_from is not None and t >= inside_from else 'ON_RIM',
                                              tags=['scn_baby']))
               for t in range(90, 201)]
    return EndContext(records, samples)


def path(window):
    return dict(steps=[('actors', r'seen:family:carry put down .*child=$subject .*nest=ON_RIM')],
                post=[('$subject', INSIDE)], **({'post_within': window} if window else {}))


def verdicts(context, window):
    return [r['verdict'] for r in context.path(path(window), {'subject': CHILD})]


class PostWindow(unittest.TestCase):
    def test_one_tick_sample_misses_a_hop_that_lands_ten_ticks_later(self):
        self.assertNotEqual(set(verdicts(end_context(110), 0)), {'pass'})

    def test_window_accepts_a_hop_inside_forty_ticks(self):
        self.assertEqual(set(verdicts(end_context(110), 40)), {'pass'})

    def test_window_rejects_a_child_that_never_steps_in(self):
        self.assertNotEqual(set(verdicts(end_context(None), 40)), {'pass'})

    def test_window_rejects_a_hop_after_the_window(self):
        self.assertNotEqual(set(verdicts(end_context(150), 40)), {'pass'})


class FinalWindow(unittest.TestCase):
    def final(self, inside_ticks, window):
        """The child reads INSIDE_SUPPORTED on the given ticks of 1..400 and ON_RIM otherwise; the clause is judged at t=400."""
        samples = [dict(u=CHILD, t=t, state=dict(alive=True, hp=5, nest='INSIDE_SUPPORTED' if t in inside_ticks else 'OUTSIDE', tags=['scn_baby']))
                   for t in range(1, 401)]
        context = EndContext([dict(k='geo', u=CHILD, t=400, tags=['scn_baby'])], samples)
        return [r['verdict'] for r in context.clauses([('baby', 'nest=INSIDE_SUPPORTED') + window], 400, {}, True)]

    def test_child_inside_most_of_the_window_passes_even_if_it_steps_out_at_the_end(self):
        self.assertEqual(self.final(set(range(100, 380)), ((300, .6),)), ['pass'])

    def test_child_inside_only_a_third_of_the_window_fails(self):
        self.assertEqual(self.final(set(range(300, 400)), ((300, .6),)), ['fail'])

    def test_child_never_inside_fails(self):
        self.assertEqual(self.final(set(), ((300, .6),)), ['fail'])

    def test_without_a_window_the_hundred_tick_rule_still_needs_the_last_sample(self):
        self.assertEqual(self.final(set(range(100, 399)), ()), ['fail'])


class Hold(unittest.TestCase):
    def verdicts(self, at_nest_ticks):
        """Put-down at t=100 (rim); the child is at the nest on the given ticks of 90..500 and OUTSIDE otherwise."""
        records = [dict(k='ev', u=MOTHER, t=100, seq=1, cat='family', msg=PUT_DOWN), dict(k='geo', u=CHILD, t=500, tags=['scn_baby'])]
        samples = [dict(u=CHILD, t=t, state=dict(alive=True, hp=5, nest='PARTIAL' if t in at_nest_ticks else 'OUTSIDE', tags=['scn_baby']))
                   for t in range(90, 501)]
        spec = dict(path(0), hold=dict(after=300, span=200, share=.8, clauses=[('$subject', 'nest!=OUTSIDE')]), post=[('$subject', 'alive=true')])
        return [r['verdict'] for r in EndContext(records, samples).path(spec, {'subject': CHILD})]

    def test_child_that_stays_at_the_nest_passes(self):
        self.assertEqual(set(self.verdicts(set(range(100, 500)))), {'pass'})

    def test_child_that_wanders_off_after_the_put_down_fails(self):
        self.assertNotEqual(set(self.verdicts(set(range(100, 240)))), {'pass'})

    def test_child_that_returns_only_after_the_hold_window_fails(self):
        self.assertNotEqual(set(self.verdicts(set(range(100, 150)) | set(range(420, 500)))), {'pass'})


class WalksHome(unittest.TestCase):
    def verdicts(self, nest_after, events=()):
        """A child that is never picked up (``events`` empty) must reach INSIDE_SUPPORTED by itself after its STAY_HOME phase starts."""
        from smoke_checks import endings
        records = [dict(k='geo', u=CHILD, t=300, tags=['scn_baby']), dict(k='geo', u=MOTHER, t=300, tags=['scn_mother']),
                   dict(k='ev', u=MOTHER, t=1, seq=0, cat='family', msg='noop'),
                   *[dict(k='ev', u=MOTHER, t=t, seq=i + 1, cat='family', msg=m) for i, (t, m) in enumerate(events)]]
        samples = [dict(u=CHILD, t=t, state=dict(alive=True, hp=5, g=True, w=False, att=dict(kind='free'), phase='STAY_HOME',
                                                  nest='INSIDE_SUPPORTED' if nest_after is not None and t >= nest_after else 'OUTSIDE', tags=['scn_baby']))
                   for t in range(1, 301)]
        end = dict(final=[], paths=[dict(each='baby', walks_home=True, steps=[('mother', r'seen:family:^carry pickup .*child=$subject\b')])])
        return [r['verdict'] for r in endings(records, samples, end)]

    def test_unpicked_child_that_walks_into_the_nest_passes(self):
        self.assertEqual(self.verdicts(120), ['pass'])

    def test_unpicked_child_that_never_reaches_the_nest_fails(self):
        self.assertEqual(self.verdicts(None), ['fail'])

    def test_picked_child_is_judged_by_its_chain_not_by_its_walk(self):
        picked = [(50, f'carry pickup hold=brim purpose=PROTECT child={CHILD}')]
        self.assertEqual(self.verdicts(None, picked), ['pass'])


if __name__ == '__main__':
    unittest.main()
