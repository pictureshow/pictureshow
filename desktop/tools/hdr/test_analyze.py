# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Negative controls distinguish clipped, scaled, wrong-gamut and malformed pixels."""
import copy
import unittest
from analyze import analyze, transform, TO709
from fixture import HDR,SDR,NAMES

class GateTests(unittest.TestCase):
    def setUp(self):
        self.manifest={'sdrReferenceWhiteNits':203,'files':{key:{'primaries':prim,'patches':[{'name':n,'quantizedRgbNits':rgb} for n,rgb in zip(NAMES,colors)]} for key,prim,colors in [('pq','bt2020',HDR),('sdr','bt709',SDR)]}}
        self.capture={'encoding':'linear','primaries':'bt2020','referenceWhiteNits':203,'samples':[{'name':n,'rgb':[c/203 for c in rgb]} for n,rgb in zip(NAMES,HDR)]}
    def test_correct_pq(self):
        self.assertTrue(analyze(self.manifest,'pq',self.capture)['pass'])
    def test_clipped_to_sdr_fails(self):
        for s in self.capture['samples']:s['rgb']=[min(c,1) for c in s['rgb']]
        self.assertFalse(analyze(self.manifest,'pq',self.capture)['pass'])
    def test_wrong_scale_fails(self):
        self.capture['referenceWhiteNits']=80
        self.assertFalse(analyze(self.manifest,'pq',self.capture)['pass'])
    def test_wrong_primaries_fail(self):
        self.capture['primaries']='bt709'
        self.assertFalse(analyze(self.manifest,'pq',self.capture)['pass'])
    def test_valid_scrgb_negative_channels(self):
        self.capture['primaries']='bt709';self.capture['referenceWhiteNits']=80
        for s,rgb in zip(self.capture['samples'],HDR):s['rgb']=[c/80 for c in transform(rgb,TO709)]
        self.assertTrue(analyze(self.manifest,'pq',self.capture)['pass'])
    def test_nonfinite_rejected(self):
        self.capture['samples'][0]['rgb'][0]=float('nan')
        with self.assertRaises(ValueError):analyze(self.manifest,'pq',self.capture)
    def test_duplicate_rejected(self):
        self.capture['samples'][0]['name']='gray100'
        with self.assertRaises(ValueError):analyze(self.manifest,'pq',self.capture)
    def test_sdr_linear(self):
        self.capture['primaries']='bt709'
        for s,rgb in zip(self.capture['samples'],SDR):s['rgb']=[c/203 for c in rgb]
        self.assertTrue(analyze(self.manifest,'sdr',self.capture)['pass'])
    def test_oversaturated_sdr_fails(self):
        self.capture['primaries']='bt709'
        for s,rgb in zip(self.capture['samples'],SDR):s['rgb']=[c/100 for c in rgb]
        self.assertFalse(analyze(self.manifest,'sdr',self.capture)['pass'])

if __name__=='__main__':unittest.main()
