package com.wil.strudel37;

/**
 * Small synthesized drum kit for Strudel's s("bd hh sd") patterns (no samples).
 * Runs on the audio thread only; render() adds into the synth's output buffers.
 */
public final class DrumMachine {
    public static final String[] NAMES = {"bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr", "rd", "cb", "sh"};
    private static final int BD = 0, SD = 1, HH = 2, OH = 3, CP = 4, RIM = 5, LT = 6, MT = 7, HT = 8, CR = 9, RD = 10, CB = 11, SH = 12;
    private static final int MAX = 24;
    private static final double[] PAN = {0, 0.05, -0.25, -0.25, 0.1, 0.2, 0.3, 0, -0.3, 0.35, -0.35, 0.15, 0.25};

    private final double sr;
    private final int[] type = new int[MAX];
    private final double[] t = new double[MAX], vel = new double[MAX], ph = new double[MAX], ph2 = new double[MAX];
    private final double[] hp = new double[MAX], bp1 = new double[MAX], bp2 = new double[MAX];
    private final boolean[] on = new boolean[MAX];
    private int seed = 22222;
    public double volume = 0.5;

    public DrumMachine(double sr) { this.sr = sr; }

    public void trigger(int k, double v) {
        if (k < 0 || k >= NAMES.length) return;
        if (k == HH) for (int i = 0; i < MAX; i++) if (on[i] && type[i] == OH) on[i] = false; // closed hat chokes the open one
        int slot = -1; double oldest = -1;
        for (int i = 0; i < MAX; i++) {
            if (!on[i]) { slot = i; break; }
            if (t[i] > oldest) { oldest = t[i]; slot = i; }
        }
        type[slot] = k; t[slot] = 0; vel[slot] = Math.max(0, Math.min(1.5, v));
        ph[slot] = 0; ph2[slot] = 0; hp[slot] = 0; bp1[slot] = 0; bp2[slot] = 0; on[slot] = true;
    }

    public void clear() { for (int i = 0; i < MAX; i++) on[i] = false; }

    private double noise() { seed = seed * 196314165 + 907633515; return (seed >> 8) / 8388608.0; }

    /** Adds the kit into L/R[off .. off+n). */
    public void render(float[] L, float[] R, int off, int n) {
        final double dt = 1 / sr;
        for (int v = 0; v < MAX; v++) {
            if (!on[v]) continue;
            final int k = type[v];
            final double g = vel[v] * volume, pl = Math.sqrt(0.5 - PAN[k] * 0.5), pr = Math.sqrt(0.5 + PAN[k] * 0.5);
            for (int i = off; i < off + n; i++) {
                double tt = t[v], s;
                switch (k) {
                    case BD: {
                        double f = 48 + 110 * Math.exp(-tt * 28);
                        ph[v] += f * dt;
                        s = Math.sin(2 * Math.PI * ph[v]) * Math.exp(-tt * 6.5) * 1.15 + noise() * Math.exp(-tt * 300) * 0.25;
                        s = Math.tanh(s * 1.4);
                        if (tt > 0.9) on[v] = false;
                        break;
                    }
                    case SD: {
                        ph[v] += (175 + 40 * Math.exp(-tt * 40)) * dt;
                        double nz = noise(); hp[v] += (nz - hp[v]) * 0.25;
                        s = Math.sin(2 * Math.PI * ph[v]) * Math.exp(-tt * 22) * 0.6 + (nz - hp[v]) * Math.exp(-tt * 16) * 0.75;
                        if (tt > 0.5) on[v] = false;
                        break;
                    }
                    case HH: case OH: case SH: {
                        // six detuned square waves (808 style) through a high-pass, or plain noise for the shaker
                        double m;
                        if (k == SH) m = noise();
                        else {
                            m = 0; double[] fr = {205.3, 304.4, 369.6, 522.7, 540.0, 800.0};
                            for (int j = 0; j < 6; j++) m += ((tt * fr[j] * 1.7) % 1.0) < 0.5 ? 1 : -1;
                            m = m / 6 + noise() * 0.4;
                        }
                        hp[v] += (m - hp[v]) * 0.55;
                        double dec = k == HH ? 55 : k == OH ? 6.5 : 30;
                        double env = k == SH ? Math.min(1, tt * 120) * Math.exp(-tt * dec) : Math.exp(-tt * dec);
                        s = (m - hp[v]) * env * 1.2;
                        if (tt > (k == OH ? 0.9 : 0.25)) on[v] = false;
                        break;
                    }
                    case CP: {
                        double nz = noise();
                        bp1[v] += 0.35 * (nz - bp1[v] - bp2[v] * 0.6); bp2[v] += 0.35 * bp1[v];
                        double e = 0;
                        for (int j = 0; j < 3; j++) { double d = tt - j * 0.011; if (d >= 0 && d < 0.011) e = Math.max(e, Math.exp(-d * 280)); }
                        e = Math.max(e, tt > 0.022 ? Math.exp(-(tt - 0.022) * 18) * 0.7 : 0);
                        s = bp1[v] * e * 1.3;
                        if (tt > 0.45) on[v] = false;
                        break;
                    }
                    case RIM: {
                        ph[v] += 1720 * dt; ph2[v] += 480 * dt;
                        s = (Math.sin(2 * Math.PI * ph[v]) * 0.6 + Math.sin(2 * Math.PI * ph2[v]) * 0.4) * Math.exp(-tt * 70) + noise() * Math.exp(-tt * 500) * 0.3;
                        if (tt > 0.12) on[v] = false;
                        break;
                    }
                    case LT: case MT: case HT: {
                        double base = k == LT ? 90 : k == MT ? 135 : 200;
                        ph[v] += base * (1 + 0.6 * Math.exp(-tt * 25)) * dt;
                        s = Math.sin(2 * Math.PI * ph[v]) * Math.exp(-tt * 9) * 0.85 + noise() * Math.exp(-tt * 200) * 0.15;
                        if (tt > 0.7) on[v] = false;
                        break;
                    }
                    case CR: case RD: {
                        double m = 0; double[] fr = {335.4, 501.8, 647.1, 878.9, 1127.0, 1553.3};
                        for (int j = 0; j < 6; j++) m += ((tt * fr[j] * (k == CR ? 2.3 : 3.1)) % 1.0) < 0.5 ? 1 : -1;
                        m = m / 6 + noise() * (k == CR ? 0.7 : 0.25);
                        hp[v] += (m - hp[v]) * 0.6;
                        s = (m - hp[v]) * Math.exp(-tt * (k == CR ? 2.2 : 3.5)) * (k == CR ? 0.75 : 0.6);
                        if (tt > 2.2) on[v] = false;
                        break;
                    }
                    default: { // CB
                        ph[v] += 540 * dt; ph2[v] += 800 * dt;
                        double m = ((ph[v] % 1.0) < 0.5 ? 1 : -1) + ((ph2[v] % 1.0) < 0.5 ? 1 : -1);
                        bp1[v] += 0.3 * (m - bp1[v] - bp2[v] * 0.5); bp2[v] += 0.3 * bp1[v];
                        s = bp1[v] * Math.exp(-tt * 12) * 0.35;
                        if (tt > 0.5) on[v] = false;
                        break;
                    }
                }
                t[v] = tt + dt;
                s *= g;
                L[i] += (float) (s * pl);
                R[i] += (float) (s * pr);
                if (!on[v]) break;
            }
        }
    }
}
