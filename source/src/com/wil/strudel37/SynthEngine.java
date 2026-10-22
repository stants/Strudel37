package com.wil.strudel37;

import java.util.Arrays;

/**
 * Native port of the page's Synth37Engine (paraphonic 2-osc synth, ladder filter, DAHDSR envelopes,
 * 2 LFOs, glide, arpeggiator/sequencer, FX noise + reverb). Not thread safe: only the audio thread
 * calls into it; other threads go through NativeSynth's message queue.
 */
public final class SynthEngine {
    public interface Listener { void arpNote(int n, boolean on); void beat(); }

    /* parameter ids: same names and order as DEF in index.html */
    static final String[] KEYS = {"fine","arpRate","arpOn","arpLatch","arpBF","arpInv","arpRange","arpPat","arpSeq","glTime","glOn","glOsc","glType","glGated","glLeg",
        "l1Rate","m1Src","l1Hi","l1Sync","l1Reset","m1Pitch","m1Filt","m1Osc","m1Wheel","m1Dest","m1Amt","l2Rate","m2Src","l2Hi","l2Sync","l2Reset","m2Pitch","m2Filt","m2Osc","m2Wheel","m2Dest","m2Amt",
        "o1Oct","o1Wave","hardSync","oscReset","o2Oct","o2Wave","kbCtrl","duo","o2Freq","o2Beat","mO1","o1On","mSub","subOn","mO2","o2On","mNoise","noiseOn","mFb","fbOn",
        "cutoff","res","drive","slope","egAmt","kbTrk","fA","fD","fS","fR","fDel","fHold","fVel","fKb","fMulti","fReset","fSync","fLoop","fLatch",
        "aA","aD","aS","aR","aDel","aHold","aVel","aKb","aMulti","aReset","aSync","aLoop","aLatch","vol","hp","mute","wn","rvMix","rvDecay","rvType","wnType"};
    static final java.util.HashMap<String,Integer> IDX = new java.util.HashMap<>();
    static int I(String k) { return IDX.get(k); }
    static { for (int i = 0; i < KEYS.length; i++) IDX.put(KEYS[i], i); }
    static final int FINE=I("fine"),ARP_RATE=I("arpRate"),ARP_ON=I("arpOn"),ARP_LATCH=I("arpLatch"),ARP_BF=I("arpBF"),ARP_INV=I("arpInv"),ARP_RANGE=I("arpRange"),ARP_PAT=I("arpPat"),ARP_SEQ=I("arpSeq"),
        GL_TIME=I("glTime"),GL_ON=I("glOn"),GL_OSC=I("glOsc"),GL_TYPE=I("glType"),GL_GATED=I("glGated"),GL_LEG=I("glLeg"),
        L1_RATE=I("l1Rate"),L1_HI=I("l1Hi"),L1_SYNC=I("l1Sync"),L1_RESET=I("l1Reset"),L2_RATE=I("l2Rate"),L2_HI=I("l2Hi"),L2_SYNC=I("l2Sync"),L2_RESET=I("l2Reset"),
        O1_OCT=I("o1Oct"),O1_WAVE=I("o1Wave"),HARD_SYNC=I("hardSync"),OSC_RESET=I("oscReset"),O2_OCT=I("o2Oct"),O2_WAVE=I("o2Wave"),KB_CTRL=I("kbCtrl"),DUO=I("duo"),
        O2_FREQ=I("o2Freq"),O2_BEAT=I("o2Beat"),MO1=I("mO1"),O1_ON=I("o1On"),MSUB=I("mSub"),SUB_ON=I("subOn"),MO2=I("mO2"),O2_ON=I("o2On"),MNOISE=I("mNoise"),NOISE_ON=I("noiseOn"),
        MFB=I("mFb"),FB_ON=I("fbOn"),CUTOFF=I("cutoff"),RES=I("res"),DRIVE=I("drive"),SLOPE=I("slope"),EG_AMT=I("egAmt"),KB_TRK=I("kbTrk"),
        VOL=I("vol"),MUTE=I("mute"),WN=I("wn"),RV_MIX=I("rvMix"),RV_DECAY=I("rvDecay"),RV_TYPE=I("rvType"),WN_TYPE=I("wnType");
    /* per-mod-slot ids [slot] */
    static final int[] M_SRC={I("m1Src"),I("m2Src")}, M_PITCH={I("m1Pitch"),I("m2Pitch")}, M_FILT={I("m1Filt"),I("m2Filt")}, M_OSC={I("m1Osc"),I("m2Osc")},
        M_WHEEL={I("m1Wheel"),I("m2Wheel")}, M_DEST={I("m1Dest"),I("m2Dest")}, M_AMT={I("m1Amt"),I("m2Amt")};
    static final int SEQ_REST = -1000, SEQ_TIE = -2000;

    static final class Env {
        final int A, D, S, R, DEL, HOLD, VEL, KB, MULTI, RESET, LOOP, LATCH;
        int stage; double level, timer, peak = 1, peakT = 1, ca, cd, cr, decT, del, hold, sus; boolean loop, latch;
        Env(String pre) {
            A=I(pre+"A"); D=I(pre+"D"); S=I(pre+"S"); R=I(pre+"R"); DEL=I(pre+"Del"); HOLD=I(pre+"Hold"); VEL=I(pre+"Vel"); KB=I(pre+"Kb");
            MULTI=I(pre+"Multi"); RESET=I(pre+"Reset"); LOOP=I(pre+"Loop"); LATCH=I(pre+"Latch");
        }
    }

    final double sr;
    final double[] p = new double[KEYS.length];
    boolean ready;
    Listener listener;

    final IntList phys = new IntList(), arpHeld = new IntList(), voiceHeld = new IntList(), arpActive = new IntList();
    int[] seq = new int[0];
    double ph1, ph2, sub = 1; boolean first = true;
    final double[] cur = {60, 60}, tgt = {60, 60}, lctRate = {0, 0};
    final Env fe = new Env("f"), ae = new Env("a");
    double amp, boot, wnLvl, rvWet; final double ampK, peakK, bootK, smK;
    int rs = 22222; long rng = 0x9E3779B97F4A7C15L;
    final double[] lfoPh = new double[2], lfoSh = new double[2], lfoV = new double[2];
    double y1, y2, y3, y4, fb, pink, dcx, dcy;
    double bend, modw, vel = 1; int lastNote = 60;
    double arpCount, arpStepLen = 1e9; int arpIdx = -1, arpStepNo;
    final double[] egScale = {1, 1}, lfoRateMul = {1, 1};
    /* mod slot values for the current block */
    final int[] mSrc = new int[2], mOsc = new int[2], mDest = new int[2];
    final double[] mW = new double[2], mPitch = new double[2], mFilt = new double[2], mAmt = new double[2];

    public SynthEngine(double sr) {
        this.sr = sr;
        ampK = 1 - Math.exp(-1 / (0.0012 * sr)); peakK = 1 - Math.exp(-1 / (0.004 * sr));
        bootK = 1 - Math.exp(-1 / (0.03 * sr)); smK = 1 - Math.exp(-1 / (0.02 * sr));
        rvInit();
    }

    double rnd() { rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17; return (rng >>> 11) * 0x1.0p-53; }
    double bip(int k) { return (p[k] - 0.5) * 2; }
    int pi(int k) { return (int) p[k]; }
    boolean pb(int k) { return p[k] != 0; }

    /* ---------- reverb: 8-line FDN (hall/room) and dispersive spring tank ---------- */
    float[][] rvBuf; int rvMask, rvW; final double[] rvLp = new double[8], rvG = new double[8]; final int[] rvLen = new int[8];
    float[] pdBuf; int pdW, rvPre; float[][] apBuf; final int[] apIdx = new int[4], apLen = new int[4]; double apG, rvDamp;
    final double[] spAp = new double[24]; double spHp, spHx, rvL, rvR; int rvTypeCur = -1; double rvDecayCur = -1;

    void rvInit() {
        double sc = sr / 48000; int size = 1 << (int) Math.ceil(Math.log(3200 * sc + 64) / Math.log(2));
        rvMask = size - 1; rvBuf = new float[8][size];
        pdBuf = new float[1 << (int) Math.ceil(Math.log(0.05 * sr + 2) / Math.log(2))];
        apBuf = new float[4][512 * (int) Math.ceil(sc) + 8];
    }
    void rvSet(int type, double decay) {
        if (type == rvTypeCur && Math.abs(decay - rvDecayCur) < 5e-4) return;
        boolean typeChanged = type != rvTypeCur; rvTypeCur = type; rvDecayCur = decay;
        double sc = sr / 48000;
        int[] base = {1557,1617,1491,1422,1277,1356,1188,1116};
        double mul = type == 0 ? 1.9 : type == 1 ? 0.62 : 1;
        double rt = type == 0 ? 1 + decay * decay * 9 : type == 1 ? 0.25 + decay * decay * 2 : 0.8 + decay * 4;
        for (int j = 0; j < 8; j++) {
            int len = type == 2 ? (int) Math.round((j == 0 ? 1410 : j == 1 ? 1655 : 100) * sc) : (int) Math.round(base[j] * mul * sc);
            rvLen[j] = len; rvG[j] = Math.pow(10, -3.0 * len / (rt * sr));
        }
        rvDamp = 1 - Math.exp(-2 * Math.PI * (type == 0 ? 5200 : type == 1 ? 7500 : 4200) / sr);
        rvPre = (int) Math.round((type == 0 ? 0.024 : type == 1 ? 0.006 : 0.002) * sr);
        int[] al = {142, 107, 379, 277};
        for (int a = 0; a < 4; a++) apLen[a] = (int) Math.round(al[a] * sc * (type == 1 ? 0.6 : 1));
        apG = type == 0 ? 0.7 : 0.6;
        if (typeChanged) {
            for (float[] b : rvBuf) Arrays.fill(b, 0); Arrays.fill(rvLp, 0); Arrays.fill(spAp, 0);
            for (float[] b : apBuf) Arrays.fill(b, 0); Arrays.fill(pdBuf, 0);
        }
    }
    final double[] nzL = new double[10], nzR = new double[10];
    /** FX noise colours from white w (-0.5..0.5): 0 white, 1 pink, 2 brown, 3 blue, 4 violet (same as nzColor in the page). */
    static double nzColor(double w, int t, double[] s) {
        if (t == 0) return w;
        if (t == 2) { s[7] = (s[7] + 0.04 * w) / 1.02; return s[7] * 4.8; }
        if (t == 4) { double d = w - s[8]; s[8] = w; return d * 0.72; }
        s[0] = 0.99886 * s[0] + w * 0.0555179; s[1] = 0.99332 * s[1] + w * 0.0750759; s[2] = 0.96900 * s[2] + w * 0.1538520;
        s[3] = 0.86650 * s[3] + w * 0.3104856; s[4] = 0.55000 * s[4] + w * 0.5329522; s[5] = -0.7616 * s[5] - w * 0.0168980;
        double pk = (s[0] + s[1] + s[2] + s[3] + s[4] + s[5] + s[6] + w * 0.5362) * 0.3; s[6] = w * 0.115926;
        if (t == 1) return pk;
        double d = pk - s[9]; s[9] = pk; return d * 1.9;
    }

    void rvTick(double x, int type) {
        final int m = rvMask, w = rvW; final double d = rvDamp;
        int pm = pdBuf.length - 1; pdBuf[pdW] = (float) x; double u = pdBuf[(pdW - rvPre) & pm]; pdW = (pdW + 1) & pm;
        double oL = 0, oR = 0;
        if (type == 2) {
            double hp = u - spHx + 0.97 * spHp; spHx = u; spHp = hp; u = hp;
            for (int j = 0; j < 2; j++) {
                double o = rvBuf[j][(w - rvLen[j]) & m];
                rvLp[j] += (o - rvLp[j]) * d;
                double v = u + rvLp[j] * rvG[j] * (j == 1 ? -1 : 1);
                for (int a = 0; a < 12; a++) { int k = j * 12 + a; double yv = 0.62 * v + spAp[k]; spAp[k] = v - 0.62 * yv; v = yv; }
                rvBuf[j][w] = (float) v;
                if (j == 1) oR = o; else oL = o;
            }
            oL *= 1.6; oR *= 1.6;
        } else {
            for (int a = 0; a < 4; a++) {
                float[] b = apBuf[a]; int L = apLen[a], n = b.length, i = apIdx[a];
                double dv = b[(i - L + n) % n], v = u + apG * dv; b[i] = (float) v; u = dv - apG * v; apIdx[a] = (i + 1) % n;
            }
            double o0=0,o1=0,o2=0,o3=0,o4=0,o5=0,o6=0,o7=0, sum = 0;
            for (int j = 0; j < 8; j++) {
                double r = rvBuf[j][(w - rvLen[j]) & m];
                switch (j) { case 0: o0=r; break; case 1: o1=r; break; case 2: o2=r; break; case 3: o3=r; break; case 4: o4=r; break; case 5: o5=r; break; case 6: o6=r; break; default: o7=r; }
                rvLp[j] += (r - rvLp[j]) * d; sum += rvLp[j] * rvG[j];
            }
            double f = sum * 0.25;
            for (int j = 0; j < 8; j++) rvBuf[j][w] = (float) (rvLp[j] * rvG[j] - f + ((j & 1) == 1 ? -u : u) * 0.5);
            double og = type == 0 ? 0.75 : 0.45; oL = (o0 + o2 + o4 + o6) * og; oR = (o1 + o3 + o5 + o7) * og;
        }
        rvW = (w + 1) & m; rvL = oL; rvR = oR;
    }

    static double tanh(double x) { if (x > 3) return 1; if (x < -3) return -1; double x2 = x * x; return x * (27 + x2) / (27 + 9 * x2); }
    static double tm(double v) { return 0.001 * Math.pow(10000, v); }

    /* ---------- messages (audio thread) ---------- */
    public void setAll(double[] values) { System.arraycopy(values, 0, p, 0, p.length); ready = true; }
    public void bootFade() { boot = 0; }
    public void setBend(double v) { bend = v; }
    public void setModWheel(double v) { modw = v; }
    public void setSeq(int[] s) { seq = s; }
    public void setParam(int k, double v) {
        double old = p[k]; p[k] = v;
        if (k == ARP_ON && old != v) {
            if (v != 0) { arpHeld.copyFrom(voiceHeld); releaseAll(); arpIdx = -1; arpCount = 1e9; }
            else { releaseArp(); arpHeld.clear(); for (int i = 0; i < phys.size; i++) voiceOn(phys.a[i], vel); }
        }
        if (k == ARP_LATCH && v == 0 && pb(ARP_ON)) { arpHeld.copyFrom(phys); if (arpHeld.size == 0) releaseArp(); }
        if (k == fe.LATCH && v == 0 && voiceHeld.size == 0) envRelease(fe, false);
        if (k == ae.LATCH && v == 0 && voiceHeld.size == 0) envRelease(ae, false);
    }
    public void panic() { phys.clear(); arpHeld.clear(); releaseArp(); voiceHeld.clear(); envRelease(fe, true); envRelease(ae, true); }

    /* ---------- note handling ---------- */
    public void noteOn(int n, double v, boolean direct) {
        if (!phys.contains(n)) phys.add(n);
        if (pb(ARP_ON) && !direct) {
            boolean wasEmpty = phys.size == 1;
            if (pb(ARP_LATCH) && wasEmpty) arpHeld.clear();
            boolean startNow = arpHeld.size == 0;
            if (!arpHeld.contains(n)) arpHeld.add(n);
            vel = v;
            if (startNow) { arpIdx = -1; arpStepNo = 0; arpCount = 1e9; }
        } else voiceOn(n, v);
    }
    public void noteOff(int n, boolean direct) {
        phys.remove(n);
        if (pb(ARP_ON) && !direct) { if (!pb(ARP_LATCH)) arpHeld.remove(n); }
        else voiceOff(n);
    }
    void releaseAll() { int[] h = voiceHeld.toArray(); for (int n : h) voiceOff(n); }
    void releaseArp() {
        for (int i = 0; i < arpActive.size; i++) { int n = arpActive.a[i]; voiceOff(n); if (listener != null) listener.arpNote(n, false); }
        arpActive.clear();
    }
    void voiceOn(int n, double v) {
        boolean legato = voiceHeld.size > 0;
        voiceHeld.remove(n); voiceHeld.add(n);
        vel = v; lastNote = n;
        assign(legato);
        if (!legato || pb(fe.MULTI)) envTrigger(fe);
        if (!legato || pb(ae.MULTI)) envTrigger(ae);
        if (!legato) {
            if (pb(L1_RESET)) lfoPh[0] = 0;
            if (pb(L2_RESET)) lfoPh[1] = 0;
            if (pb(OSC_RESET)) { ph1 = 0; ph2 = 0; }
        }
    }
    void voiceOff(int n) {
        int had = voiceHeld.size;
        voiceHeld.remove(n);
        if (voiceHeld.size > 0) { if (had != voiceHeld.size) assign(true); }
        else if (had > 0) { envRelease(fe, false); envRelease(ae, false); }
    }
    void assign(boolean legato) {
        IntList h = voiceHeld; if (h.size == 0) return;
        int last = h.a[h.size - 1];
        int n1 = last, n2 = last; boolean n2null = false;
        if (pb(DUO)) {
            int lo = h.min(), hi = h.max(), kc = pi(KB_CTRL);
            if (kc == 1) { n2 = lo; n1 = hi; }
            else if (kc == 2) { n2 = hi; n1 = lo; }
            else { n1 = last; n2null = true; }
        }
        boolean glideOK = pb(GL_ON) && (!pb(GL_LEG) || legato) && !first;
        for (int i = 0; i < 2; i++) {
            boolean isNull = i == 1 && n2null;
            int t = isNull ? 60 : (i == 0 ? n1 : n2);
            tgt[i] = t;
            int go = pi(GL_OSC);
            boolean g = glideOK && (go == 0 || go == i + 1) && !isNull;
            if (!g) cur[i] = t;
            else lctRate[i] = Math.abs(t - cur[i]) / Math.max(0.001, glideTime());
        }
        first = false;
    }
    double glideTime() { double v = p[GL_TIME]; return v < 0.005 ? 0 : 0.002 * Math.pow(5000, v); }

    /* ---------- envelopes ---------- */
    void envTrigger(Env e) {
        if (pb(e.RESET)) e.level = 0;
        e.stage = 1; e.timer = 0;
        // velocity glides to its new value instead of jumping (a jump while the envelope is open = pop)
        e.peakT = 1 - p[e.VEL] * (1 - vel);
        if (e.level < 1e-4) e.peak = e.peakT;
    }
    void envRelease(Env e, boolean force) { if (force || !pb(e.LATCH)) { if (e.stage != 0) e.stage = 6; } }
    void envPrep(Env e, double scale) {
        double kb = Math.pow(2, -(lastNote - 60) / 12.0 * p[e.KB] * 2);
        double s = scale * kb;
        double att = Math.max(0.003, tm(p[e.A]) * s), dec = tm(p[e.D]) * s, rel = tm(p[e.R]) * s;
        e.ca = 1 - Math.exp(-1.466 / (att * sr));
        e.cd = 1 - Math.exp(-4.6 / (dec * sr)); e.decT = dec;
        e.cr = 1 - Math.exp(-4.6 / (rel * sr));
        e.del = p[e.DEL] < 0.005 ? 0 : tm(p[e.DEL]) * s;
        e.hold = p[e.HOLD] < 0.005 ? 0 : tm(p[e.HOLD]) * s;
        e.sus = p[e.S]; e.loop = pb(e.LOOP); e.latch = pb(e.LATCH);
    }
    double envTick(Env e, boolean gate, double dt) {
        e.peak += (e.peakT - e.peak) * peakK;
        switch (e.stage) {
            case 1: e.timer += dt; if (e.timer >= e.del) e.stage = 2; break;
            case 2: e.level += (1.3 - e.level) * e.ca; if (e.level >= 1) { e.level = 1; e.stage = 3; e.timer = 0; } break;
            case 3: e.timer += dt; if (e.timer >= e.hold) { e.stage = 4; e.timer = 0; } break;
            case 4: e.level += (e.sus - e.level) * e.cd; e.timer += dt;
                if (e.timer >= e.decT) { if (e.loop && (gate || e.latch)) { e.stage = 1; e.timer = 0; } else e.stage = 5; } break;
            case 5: e.level += (e.sus - e.level) * 0.002; break;
            case 6: e.level -= e.level * e.cr; if (e.level < 1e-5) { e.level = 0; e.stage = 0; } break;
        }
        return e.level * e.peak;
    }

    /* ---------- arpeggiator ---------- */
    double arpBpm() { return 30 * Math.pow(280.0 / 30, p[ARP_RATE]); }
    final IntList arpList = new IntList(), arpBase = new IntList();
    void arpStep() {
        boolean tie = false, rest = false; int note = Integer.MIN_VALUE;
        if (pb(ARP_SEQ) && seq.length > 0) {
            if (arpHeld.size == 0) { releaseArp(); return; }
            int tr = arpHeld.a[arpHeld.size - 1] - 60;
            arpIdx = (arpIdx + 1) % seq.length;
            int s = seq[arpIdx];
            if (s == SEQ_REST) rest = true; else if (s == SEQ_TIE) tie = true; else note = s + tr;
        } else {
            if (arpHeld.size == 0) { releaseArp(); return; }
            arpBase.copyFrom(arpHeld);
            int pat = pi(ARP_PAT);
            if (pat != 2) arpBase.sort();
            int r = pi(ARP_RANGE), oc = Math.abs(r), sg = r < 0 ? -1 : 1;
            arpList.clear();
            for (int o = 0; o <= oc; o++) {
                int oo = pb(ARP_INV) ? (oc - o) : o;
                for (int i = 0; i < arpBase.size; i++) arpList.add(arpBase.a[i] + oo * sg * 12);
            }
            if (pat == 1) arpList.reverse();
            if (pb(ARP_BF) && arpList.size > 2) { int n = arpList.size; for (int i = n - 2; i >= 1; i--) arpList.add(arpList.a[i]); }
            if (pat == 3) note = arpList.a[(int) (rnd() * arpList.size)];
            else { arpIdx = (arpIdx + 1) % arpList.size; note = arpList.a[arpIdx]; }
        }
        if (tie) return;
        releaseArp();
        if (rest || note == Integer.MIN_VALUE) return;
        note = Math.max(0, Math.min(127, note));
        voiceOn(note, vel); arpActive.add(note);
        if (listener != null) listener.arpNote(note, true);
    }
    boolean nextIsTie() {
        if (!(pb(ARP_SEQ) && seq.length > 0)) return false;
        return seq[(arpIdx + 1 + seq.length) % seq.length] == SEQ_TIE;
    }

    /* ---------- waveforms ---------- */
    static double blep(double t, double dt) {
        if (t < dt) { t /= dt; return t + t - t * t - 1; }
        if (t > 1 - dt) { t = (t - 1) / dt; return t * t + t + t + 1; }
        return 0;
    }
    static double wave(double ph, double dt, double w) {
        if (w < 0) w = 0; else if (w > 1) w = 1;
        if (w < 1.0 / 3) { double x = w * 3; double tri = ph < 0.5 ? 4 * ph - 1 : 3 - 4 * ph; double saw = 2 * ph - 1 - blep(ph, dt); return tri * (1 - x) + saw * x; }
        if (w < 2.0 / 3) { double x = (w - 1.0 / 3) * 3; double saw = 2 * ph - 1 - blep(ph, dt); double sq = ph < 0.5 ? 1 : -1; sq += blep(ph, dt); sq -= blep((ph + 0.5) % 1, dt); return saw * (1 - x) + sq * x; }
        double x = (w - 2.0 / 3) * 3, pw = 0.5 - x * 0.45;
        double v = ph < pw ? 1 : -1; v += blep(ph, dt); v -= blep((ph - pw + 1) % 1, dt); return v;
    }
    static final double[] SYNC_MUL = {0.125,0.25,0.5,1,1.5,2,3,4,6,8,12,16};
    double lfoHz(int i) {
        double v = i == 1 ? p[L2_RATE] : p[L1_RATE]; boolean hi = pb(i == 1 ? L2_HI : L1_HI), sync = pb(i == 1 ? L2_SYNC : L1_SYNC);
        if (sync) return arpBpm() / 60 * SYNC_MUL[Math.min(11, (int) (v * 12))];
        return 0.1 * Math.pow(1000, v) * (hi ? 10 : 1);
    }

    static final int[] OCT = {-12, 0, 12, 24};

    /* ---------- render ---------- */
    public void render(float[] L, float[] R, int n) {
        if (!ready) { Arrays.fill(L, 0, n, 0); Arrays.fill(R, 0, n, 0); return; }
        final double dt = 1 / sr, twoPi = 2 * Math.PI;
        for (int i0 = 0; i0 < n; i0 += 16) {
            int i1 = Math.min(n, i0 + 16);
            /* control-rate */
            double bpm = arpBpm();
            arpStepLen = sr * 60 / bpm / 4;
            double lh0 = lfoHz(0) * lfoRateMul[0], lh1 = lfoHz(1) * lfoRateMul[1];
            envPrep(fe, egScale[0] * egScale[1]);
            envPrep(ae, egScale[0] * egScale[1]);
            double gT = glideTime(); int gType = pi(GL_TYPE);
            double lcr = gT > 0 ? 12 / gT * dt : 1e9, expc = gT > 0 ? 1 - Math.exp(-3 / (gT * sr)) : 1;
            boolean gate = voiceHeld.size > 0;
            boolean glideRun = !pb(GL_GATED) || gate;
            int oct1 = OCT[pi(O1_OCT) & 3], oct2 = OCT[pi(O2_OCT) & 3];
            double fine = bip(FINE) * 2 + bend * 2;
            boolean droneMode = pb(DUO) && pi(KB_CTRL) == 0;
            double o2f = bip(O2_FREQ) * (droneMode ? 36 : 7), beat = bip(O2_BEAT) * 3.5;
            double egOct = bip(EG_AMT) * 7, kt = p[KB_TRK] * 2;
            double baseCut = 20 * Math.pow(1000, p[CUTOFF]);
            double k = p[RES] * 4.4, drive = 1 + p[DRIVE] * p[DRIVE] * 14;
            int slope = pi(SLOPE);
            double vol = pb(MUTE) ? 0 : p[VOL] * p[VOL] * 1.02;
            int rvType = Math.min(1, pi(RV_TYPE)), wnType = pi(WN_TYPE); double rvMix = p[RV_MIX], wnT = p[WN] * p[WN] * 0.08;
            rvSet(rvType, p[RV_DECAY]);
            for (int j = 0; j < 2; j++) {
                double pa = bip(M_PITCH[j]), fa = bip(M_FILT[j]);
                mSrc[j] = pi(M_SRC[j]); mOsc[j] = pi(M_OSC[j]); mW[j] = pb(M_WHEEL[j]) ? modw : 1;
                mPitch[j] = pa * Math.abs(pa) * 24; mFilt[j] = fa * Math.abs(fa) * 6; mAmt[j] = bip(M_AMT[j]); mDest[j] = pi(M_DEST[j]);
            }
            // block-rate destinations: other LFO rate, EG time
            for (int j = 0; j < 2; j++) {
                double s = lfoV[j] * mAmt[j];
                if (mDest[j] == 0) lfoRateMul[1 - j] = Math.pow(2, s * 4); else lfoRateMul[1 - j] = lfoRateMul[1 - j] * 0.9 + 0.1;
                egScale[j] = mDest[j] == 6 ? Math.pow(2, s * 3) : 1;
            }
            for (int i = i0; i < i1; i++) {
                /* arp clock */
                if (pb(ARP_ON)) {
                    arpCount++;
                    if (arpCount >= arpStepLen) {
                        arpCount = 0; arpStep();
                        if (arpStepNo++ % 4 == 0 && listener != null) listener.beat();
                    } else if (arpCount >= arpStepLen * 0.5 && arpActive.size > 0 && !nextIsTie()) {
                        releaseArp();
                    }
                }
                /* glide */
                if (glideRun) for (int j = 0; j < 2; j++) {
                    double d = tgt[j] - cur[j];
                    if (d != 0) {
                        if (gType == 2) { cur[j] += d * expc; if (Math.abs(d) < 1e-4) cur[j] = tgt[j]; }
                        else { double st = gType == 0 ? lcr : lctRate[j] * dt; if (Math.abs(d) <= st) cur[j] = tgt[j]; else cur[j] += Math.signum(d) * st; }
                    }
                }
                /* envelopes */
                double fenv = envTick(fe, gate, dt);
                double aenv = envTick(ae, gate, dt);
                /* LFOs / mod sources */
                double pm1 = 0, pm2 = 0, fm = 0, vca = 1, w1m = 0, w2m = 0, nzm = 0;
                for (int j = 0; j < 2; j++) {
                    lfoPh[j] += (j == 0 ? lh0 : lh1) * dt;
                    if (lfoPh[j] >= 1) { lfoPh[j] -= Math.floor(lfoPh[j]); lfoSh[j] = rnd() * 2 - 1; }
                    double ph = lfoPh[j], s;
                    switch (mSrc[j]) {
                        case 0: s = ph < 0.5 ? 4 * ph - 1 : 3 - 4 * ph; break;
                        case 1: s = ph < 0.5 ? 1 : -1; break;
                        case 2: s = 1 - 2 * ph; break;
                        case 3: s = 2 * ph - 1; break;
                        case 4: s = lfoSh[j]; break;
                        default: s = fenv;
                    }
                    lfoV[j] = s;
                    double sw = s * mW[j];
                    if (mOsc[j] != 2) pm1 += mPitch[j] * sw;
                    if (mOsc[j] != 1) pm2 += mPitch[j] * sw;
                    fm += mFilt[j] * sw;
                    double a = mAmt[j] * s;
                    switch (mDest[j]) {
                        case 1: vca *= Math.max(0, 1 + a); break;
                        case 2: w1m += a * 0.5; break;
                        case 3: w1m += a * 0.5; w2m += a * 0.5; break;
                        case 4: w2m += a * 0.5; break;
                        case 5: nzm += a; break;
                    }
                }
                /* oscillators */
                double f1 = 440 * Math.pow(2, (cur[0] + oct1 + fine + pm1 - 69) / 12);
                double f2 = 440 * Math.pow(2, (cur[1] + oct2 + o2f + fine + pm2 - 69) / 12) + beat;
                if (f2 < 0) f2 = -f2;
                double dt1 = Math.min(0.49, f1 * dt), dt2 = Math.min(0.49, f2 * dt);
                ph1 += dt1; ph2 += dt2;
                if (ph1 >= 1) { ph1 -= 1; sub = -sub; if (pb(HARD_SYNC)) ph2 = ph1 * dt2 / dt1; }
                if (ph2 >= 1) ph2 -= Math.floor(ph2);
                double s1 = wave(ph1, dt1, p[O1_WAVE] + w1m);
                double s2 = wave(ph2, dt2, p[O2_WAVE] + w2m);
                double wn = rnd() * 2 - 1; pink += (wn - pink) * 0.25;
                double x = 0;
                if (pb(O1_ON)) x += s1 * p[MO1];
                if (pb(SUB_ON)) x += sub * p[MSUB];
                if (pb(O2_ON)) x += s2 * p[MO2];
                if (pb(NOISE_ON)) x += pink * 1.6 * Math.max(0, Math.min(1, p[MNOISE] + nzm));
                if (pb(FB_ON)) x += fb * p[MFB] * 1.5;
                x *= 0.55 * drive;
                /* ladder filter (2x oversampled) */
                double fc = baseCut * Math.pow(2, egOct * fenv + kt * (cur[0] - 60) / 12 + fm);
                if (fc < 8) fc = 8; else if (fc > sr * 0.45) fc = sr * 0.45;
                double g = 1 - Math.exp(-twoPi * fc / (2 * sr));
                double a1 = y1, a2 = y2, a3 = y3, a4 = y4;
                for (int o = 0; o < 2; o++) {
                    double u = tanh(x - k * tanh(a4));
                    a1 += g * (u - a1); a2 += g * (a1 - a2); a3 += g * (a2 - a3); a4 += g * (a3 - a4);
                }
                y1 = a1; y2 = a2; y3 = a3; y4 = a4;
                double y = slope == 0 ? a1 : slope == 1 ? a2 : slope == 2 ? a3 : a4;
                y *= (1 + k * 0.3) / (1 + p[DRIVE] * 1.2);
                fb = y;
                /* VCA + output; the VCA gain follows the envelope through a ~1 ms smoother (de-click) */
                amp += (aenv * vca * vol - amp) * ampK;
                y *= amp;
                double dc = y - dcx + 0.996 * dcy; dcx = y; dcy = dc;
                boot += (1 - boot) * bootK; rs = rs * 1664525 + 1013904223;
                rvWet += (rvMix - rvWet) * smK; wnLvl += (wnT - wnLvl) * smK;
                double dry = tanh(dc * 1.2) * 0.8 * boot;
                double oL = dry, oR = dry;
                if (rvWet > 1e-4) { rvTick(dry, rvType); double wg = rvWet; oL = dry * (1 - 0.45 * wg) + rvL * wg; oR = dry * (1 - 0.45 * wg) + rvR * wg; }
                // constant white noise (FX > NOISE) plus -96 dB dither so the output stream never idles
                double nz1 = (rs & 0xffffffffL) / 4294967296.0 - 0.5;
                rs = rs * 1664525 + 1013904223; double nz2 = (rs & 0xffffffffL) / 4294967296.0 - 0.5;
                oL += nz1 * 3e-5; oR += nz2 * 3e-5;
                if (wnLvl > 1e-6) { oL += nzColor(nz1, wnType, nzL) * wnLvl * 2; oR += nzColor(nz2, wnType, nzR) * wnLvl * 2; }
                L[i] = (float) oL; R[i] = (float) oR;
            }
        }
    }

    /** Tiny growable int list (no boxing on the audio thread). */
    static final class IntList {
        int[] a = new int[16]; int size;
        void add(int v) { if (size == a.length) a = Arrays.copyOf(a, size * 2); a[size++] = v; }
        void clear() { size = 0; }
        boolean contains(int v) { for (int i = 0; i < size; i++) if (a[i] == v) return true; return false; }
        void remove(int v) { int j = 0; for (int i = 0; i < size; i++) if (a[i] != v) a[j++] = a[i]; size = j; }
        void copyFrom(IntList o) { if (a.length < o.size) a = new int[o.a.length]; System.arraycopy(o.a, 0, a, 0, o.size); size = o.size; }
        int[] toArray() { return Arrays.copyOf(a, size); }
        int min() { int m = Integer.MAX_VALUE; for (int i = 0; i < size; i++) m = Math.min(m, a[i]); return m; }
        int max() { int m = Integer.MIN_VALUE; for (int i = 0; i < size; i++) m = Math.max(m, a[i]); return m; }
        void sort() { Arrays.sort(a, 0, size); }
        void reverse() { for (int i = 0, j = size - 1; i < j; i++, j--) { int t = a[i]; a[i] = a[j]; a[j] = t; } }
    }
}
