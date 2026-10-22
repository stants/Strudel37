package com.wil.strudel37;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs SynthEngine on a high-priority audio thread feeding a low-latency float AudioTrack.
 * Exposed to the page as window.AndroidSynth; MIDI input also lands here directly (no WebView hop).
 * Every call from another thread is queued and applied by the audio thread between buffers.
 */
public class NativeSynth implements SynthEngine.Listener {
    private final Context ctx;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private volatile SynthEngine engine;
    private volatile boolean running;
    private Thread thread;
    private AudioTrack track;
    private int sampleRate = 48000, burst = 192, bursts = 4;
    private String latency = "interactive";
    private volatile int midiChannel; // 0 = omni, else 1..16
    private double[] lastAll;
    private int[] lastSeq = new int[0];
    // Strudel scheduler: note events stamped in audio-clock seconds, applied sample-accurately by the audio thread
    private volatile long framesOut;
    private final java.util.ArrayList<double[]> timed = new java.util.ArrayList<>(); // {frame, type(0 off,1 on,2 drum), note|drum, vel}; audio thread only
    private volatile DrumMachine drums;

    NativeSynth(Context ctx, WebView web) { this.ctx = ctx; this.web = web; }

    /* ---------------- page API (window.AndroidSynth) ---------------- */

    @JavascriptInterface public synchronized void start(String lat) {
        if (lat != null) latency = lat;
        if (running) return;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        try { sampleRate = Integer.parseInt(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)); } catch (Exception e) { sampleRate = 48000; }
        try { burst = Integer.parseInt(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)); } catch (Exception e) { burst = 192; }
        if (burst <= 0) burst = 192;
        bursts = "playback".equals(latency) ? 8 : "balanced".equals(latency) ? 4 : 2;
        SynthEngine e = new SynthEngine(sampleRate);
        e.listener = this;
        if (lastAll != null) e.setAll(lastAll);
        e.setSeq(lastSeq);
        engine = e;
        drums = new DrumMachine(sampleRate);
        queue.clear();
        timed.clear();
        AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        AudioAttributes attr = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        AudioTrack.Builder b = new AudioTrack.Builder().setAudioAttributes(attr).setAudioFormat(fmt)
            .setBufferSizeInBytes(Math.max(min, burst * bursts * 8)).setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        track = b.build();
        try { track.setBufferSizeInFrames(burst * bursts); } catch (Exception ignored) {}
        track.play();
        running = true;
        thread = new Thread(new Runnable() { public void run() { loop(); } }, "Strudel 37 audio");
        thread.start();
    }

    @JavascriptInterface public synchronized void stop() {
        running = false;
        if (thread != null) { try { thread.join(500); } catch (InterruptedException ignored) {} thread = null; }
        if (track != null) { try { track.pause(); track.flush(); track.release(); } catch (Exception ignored) {} track = null; }
    }

    @JavascriptInterface public void restart(String lat) { stop(); start(lat); }

    @JavascriptInterface public String info() {
        return "{\"sr\":" + sampleRate + ",\"burst\":" + burst + ",\"bursts\":" + bursts + ",\"running\":" + running + "}";
    }

    @JavascriptInterface public void p(String key, double v) {
        final Integer k = SynthEngine.IDX.get(key);
        if (k == null) return;
        if (lastAll != null) lastAll[k] = v;
        final double val = v;
        queue.add(new Runnable() { public void run() { engine.setParam(k, val); } });
    }

    @JavascriptInterface public void on(final int n, final double v, final boolean direct) {
        queue.add(new Runnable() { public void run() { engine.noteOn(n, v, direct); } });
    }

    @JavascriptInterface public void off(final int n, final boolean direct) {
        queue.add(new Runnable() { public void run() { engine.noteOff(n, direct); } });
    }

    /** Audio clock in seconds: frames handed to the AudioTrack so far (runs ahead of the speaker by the buffer). */
    @JavascriptInterface public double now() { return framesOut / (double) sampleRate; }

    /** Schedules a note at audio time t (seconds, see now()) lasting dur seconds; always bypasses the arpeggiator. */
    @JavascriptInterface public void at(double t, final int n, final double v, double dur) {
        final double f0 = Math.floor(t * sampleRate), f1 = Math.floor((t + Math.max(0.005, dur)) * sampleRate);
        queue.add(new Runnable() { public void run() { addTimed(new double[]{f0, 1, n, v}); addTimed(new double[]{f1, 0, n, 0}); } });
    }

    /** Schedules a drum hit (DrumMachine.NAMES index) at audio time t, mixed into the synth output. */
    @JavascriptInterface public void drum(double t, final int k, final double v) {
        final double f0 = Math.floor(t * sampleRate);
        queue.add(new Runnable() { public void run() { addTimed(new double[]{f0, 2, k, v}); } });
    }

    /** Schedules a panel parameter change (lpf, room, ... from a pattern) at audio time t. */
    @JavascriptInterface public void atParam(double t, String key, final double v) {
        final Integer k = SynthEngine.IDX.get(key);
        if (k == null) return;
        final double f0 = Math.floor(t * sampleRate);
        queue.add(new Runnable() { public void run() { addTimed(new double[]{f0, -1, k, v}); } });
    }

    /** Drops every scheduled Strudel note and releases the ones sounding. */
    @JavascriptInterface public void clearTimed() {
        queue.add(new Runnable() { public void run() {
            for (double[] ev : timed) if (ev[1] == 0) engine.noteOff((int) ev[2], true);
            timed.clear();
            if (drums != null) drums.clear();
        } });
    }

    private void addTimed(double[] ev) {
        int i = timed.size();
        // keep sorted by frame; at the same frame note-offs come first so repeated notes retrigger
        while (i > 0) { double[] p = timed.get(i - 1); if (p[0] < ev[0] || (p[0] == ev[0] && p[1] <= ev[1])) break; i--; }
        timed.add(i, ev);
    }

    @JavascriptInterface public void setMidiChannel(int ch) { midiChannel = ch; }

    /** Less frequent messages as JSON: all, seq, bend, mw, panic, boot. */
    @JavascriptInterface public void msg(String json) {
        try {
            JSONObject m = new JSONObject(json);
            String t = m.getString("t");
            if (t.equals("all")) {
                JSONObject po = m.getJSONObject("p");
                final double[] all = new double[SynthEngine.KEYS.length];
                for (int i = 0; i < all.length; i++) all[i] = po.optDouble(SynthEngine.KEYS[i], 0);
                lastAll = all.clone();
                queue.add(new Runnable() { public void run() { engine.setAll(all); } });
            } else if (t.equals("seq")) {
                JSONArray a = m.getJSONArray("s");
                final int[] s = new int[a.length()];
                for (int i = 0; i < s.length; i++) {
                    JSONObject st = a.getJSONObject(i);
                    s[i] = st.has("r") ? SynthEngine.SEQ_REST : st.has("tie") ? SynthEngine.SEQ_TIE : st.getInt("n");
                }
                lastSeq = s;
                queue.add(new Runnable() { public void run() { engine.setSeq(s); } });
            } else if (t.equals("bend")) {
                final double v = m.getDouble("v");
                queue.add(new Runnable() { public void run() { engine.setBend(v); } });
            } else if (t.equals("mw")) {
                final double v = m.getDouble("v");
                queue.add(new Runnable() { public void run() { engine.setModWheel(v); } });
            } else if (t.equals("panic")) {
                queue.add(new Runnable() { public void run() { engine.panic(); } });
            } else if (t.equals("boot")) {
                queue.add(new Runnable() { public void run() { engine.bootFade(); } });
            } else if (t.equals("on")) {
                on(m.getInt("n"), m.getDouble("v"), m.optBoolean("direct"));
            } else if (t.equals("off")) {
                off(m.getInt("n"), m.optBoolean("direct"));
            } else if (t.equals("p")) {
                p(m.getString("k"), m.getDouble("v"));
            }
        } catch (Exception ignored) {}
    }

    /* ---------------- MIDI straight into the engine ---------------- */

    /** Returns true when the message was played here; the page then only updates its display. */
    boolean midi(int status, int d1, int d2) {
        if (!running) return false;
        int t = status & 0xF0, ch = status & 15;
        if (midiChannel != 0 && ch != midiChannel - 1) return true; // filtered out
        if (t == 0x90 && d2 > 0) on(d1, d2 / 127.0, false);
        else if (t == 0x80 || t == 0x90) off(d1, false);
        else if (t == 0xE0) { final double v = (((d2 << 7) | d1) / 16383.0 - 0.5) * 2; queue.add(new Runnable() { public void run() { engine.setBend(v); } }); }
        else if (t == 0xB0 && d1 == 1) { final double v = d2 / 127.0; queue.add(new Runnable() { public void run() { engine.setModWheel(v); } }); }
        else if (t == 0xB0 && (d1 == 123 || d1 == 120)) queue.add(new Runnable() { public void run() { engine.panic(); } });
        return true;
    }

    /* ---------------- audio thread ---------------- */

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final int n = burst;
        float[] L = new float[n], R = new float[n], TL = new float[n], TR = new float[n], out = new float[n * 2];
        SynthEngine e = engine;
        DrumMachine dm = drums;
        e.bootFade();
        long frame = framesOut;
        while (running) {
            Runnable r;
            while ((r = queue.poll()) != null) r.run();
            int done = 0;
            while (done < n) {
                // apply due events, then render up to the next one inside this buffer
                while (!timed.isEmpty() && timed.get(0)[0] <= frame + done) {
                    double[] ev = timed.remove(0);
                    if (ev[1] == -1) e.setParam((int) ev[2], ev[3]);
                    else if (ev[1] == 2) dm.trigger((int) ev[2], ev[3]);
                    else if (ev[1] == 1) e.noteOn((int) ev[2], ev[3], true); else e.noteOff((int) ev[2], true);
                }
                int end = n;
                if (!timed.isEmpty()) end = (int) Math.max(done + 1, Math.min(n, timed.get(0)[0] - frame));
                if (done == 0 && end == n) { e.render(L, R, n); dm.render(L, R, 0, n); }
                else {
                    int len = end - done;
                    e.render(TL, TR, len);
                    dm.render(TL, TR, 0, len);
                    System.arraycopy(TL, 0, L, done, len); System.arraycopy(TR, 0, R, done, len);
                }
                done = end;
            }
            frame += n;
            framesOut = frame;
            for (int i = 0, j = 0; i < n; i++) { out[j++] = L[i]; out[j++] = R[i]; }
            AudioTrack t = track;
            if (t == null) break;
            int w = t.write(out, 0, n * 2, AudioTrack.WRITE_BLOCKING);
            if (w < 0) break;
        }
    }

    /* ---------------- engine events to the page ---------------- */

    @Override public void arpNote(final int n, final boolean on) {
        ui.post(new Runnable() { public void run() { web.evaluateJavascript("window.__engineEvent&&__engineEvent({t:'an',n:" + n + ",on:" + (on ? 1 : 0) + "})", null); } });
    }

    @Override public void beat() {
        ui.post(new Runnable() { public void run() { web.evaluateJavascript("window.__engineEvent&&__engineEvent({t:'beat'})", null); } });
    }
}
