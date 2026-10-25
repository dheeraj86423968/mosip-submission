package io.mosip.vivid.demo;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.SessionState.Phase;
import io.mosip.vivid.api.Workflow;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paints the attack-lab dashboard from a {@link Snapshot}: camera preview with oval guide and detected face box,
 * session phase, challenge prompt and progress, per-signal score bars, the decision banner with the user-facing
 * message and the evidence hash, and the live audit trail. Pure painting, so the same component renders on screen
 * and offscreen for recordings.
 */
public final class LabCanvas extends JComponent {
    public static final int W = 1280, H = 668;
    private static final String[] SIGNAL_ORDER = {"cnn_texture", "moire", "color_stats", "rppg", "parallax", "challenge_timing", "identity", "injection", "quality"};

    private volatile Snapshot snap = Snapshot.idle(Workflow.RESIDENT, Subject.LIVE.label(), true, null);
    private volatile String banner;   // optional caption across the top of the preview (used by recordings)

    public LabCanvas() {
        setPreferredSize(new Dimension(W, H));
        setOpaque(true);
    }

    public void setSnapshot(Snapshot s) { snap = s; repaint(); }
    public Snapshot snapshot() { return snap; }
    public void setCaption(String c) { banner = c; repaint(); }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Theme.BG);
            g.fillRect(0, 0, getWidth(), getHeight());
            Snapshot s = snap;
            paintPreview(g, s, 16, 12, 656, 516);
            paintAudit(g, s, 16, 536, 656, 120);
            paintSession(g, s, 688, 12, 576, 92);
            paintPrompt(g, s, 688, 112, 576, 120);
            paintSignals(g, s, 688, 240, 576, 236);
            paintDecision(g, s, 688, 484, 576, 172);
        } finally {
            g.dispose();
        }
    }

    // ---- cards ---------------------------------------------------------------------------------------------

    private static void card(Graphics2D g, int x, int y, int w, int h, String title) {
        g.setColor(Theme.CARD);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
        g.setColor(Theme.BORDER);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, 12, 12));
        if (title != null) {
            g.setFont(Theme.TITLE);
            g.setColor(Theme.MUTED);
            g.drawString(title.toUpperCase(Locale.ROOT), x + 14, y + 22);
        }
    }

    private void paintPreview(Graphics2D g, Snapshot s, int x, int y, int w, int h) {
        card(g, x, y, w, h, null);
        g.setFont(Theme.TITLE);
        g.setColor(Theme.MUTED);
        String src = s.synthetic() ? "CAMERA PREVIEW  ·  MOCK DEVICE (SYNTHETIC FRAMES)" : "CAMERA PREVIEW  ·  SBI DEVICE";
        g.drawString(src, x + 14, y + 22);
        int ix = x + 8, iy = y + 32, iw = 640, ih = 480;
        Shape clip = g.getClip();
        g.clip(new RoundRectangle2D.Double(ix, iy, iw, ih, 10, 10));
        g.setColor(Theme.PREVIEW_BG);
        g.fillRect(ix, iy, iw, ih);
        if (s.frame() != null) g.drawImage(s.frame(), ix, iy, iw, ih, null);
        Phase p = s.state() == null ? Phase.IDLE : s.state().phase();
        boolean ended = p == Phase.FAILED || p == Phase.LOCKED || p == Phase.CANCELLED;
        if (ended) {
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRect(ix, iy, iw, ih);
        }
        // oval guide
        Color oval = switch (p) {
            case PASSED -> Theme.GREEN;
            case ACTIVE_CHALLENGE -> new Color(0x34A853);
            case FAILED, LOCKED -> Theme.RED;
            case FACE_ACQUIRING, PASSIVE_EVALUATING -> new Color(0xF9AB00);
            default -> new Color(0x9AA0A6);
        };
        double ow = iw * 0.40, oh = ih * 0.70;
        Ellipse2D ellipse = new Ellipse2D.Double(ix + (iw - ow) / 2, iy + (ih - oh) / 2 - 6, ow, oh);
        g.setColor(new Color(oval.getRed(), oval.getGreen(), oval.getBlue(), 230));
        g.setStroke(new BasicStroke(4f));
        g.draw(ellipse);
        if (p == Phase.PASSIVE_EVALUATING) {   // sweep arc while checking
            double a = (s.elapsedMs() / 4.0) % 360;
            g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.WHITE);
            g.draw(new java.awt.geom.Arc2D.Double(ellipse.getBounds2D(), -a, 40, java.awt.geom.Arc2D.OPEN));
        }
        // detected face box
        Rectangle2D fb = s.faceBox();
        if (fb != null && !ended) {
            double bx = ix + fb.getX() * iw, by = iy + fb.getY() * ih, bw = fb.getWidth() * iw, bh = fb.getHeight() * ih;
            g.setColor(new Color(0x4FC3F7));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new Rectangle2D.Double(bx, by, bw, bh));
            g.setStroke(new BasicStroke(3f));
            double c = Math.min(bw, bh) * 0.18;
            g.draw(new java.awt.geom.Line2D.Double(bx, by, bx + c, by)); g.draw(new java.awt.geom.Line2D.Double(bx, by, bx, by + c));
            g.draw(new java.awt.geom.Line2D.Double(bx + bw, by, bx + bw - c, by)); g.draw(new java.awt.geom.Line2D.Double(bx + bw, by, bx + bw, by + c));
            g.draw(new java.awt.geom.Line2D.Double(bx, by + bh, bx + c, by + bh)); g.draw(new java.awt.geom.Line2D.Double(bx, by + bh, bx, by + bh - c));
            g.draw(new java.awt.geom.Line2D.Double(bx + bw, by + bh, bx + bw - c, by + bh)); g.draw(new java.awt.geom.Line2D.Double(bx + bw, by + bh, bx + bw, by + bh - c));
            g.setFont(Theme.SMALL);
            g.drawString("face", (float) bx + 4, (float) by - 5);
        }
        if (p == Phase.PASSED) {
            int cx = ix + iw / 2, cy = iy + ih / 2 - 6;
            g.setColor(new Color(0x1E8E3E));
            g.fillOval(cx - 34, cy - 34, 68, 68);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawPolyline(new int[]{cx - 16, cx - 4, cx + 18}, new int[]{cy + 1, cy + 13, cy - 12}, 3);
        }
        // chips
        Subject subj = null;
        for (Subject v : Subject.values()) if (v.label().equals(s.sourceLabel())) subj = v;
        boolean attack = subj != null && subj.isAttack();
        chip(g, ix + 10, iy + 10, phaseName(p), new Color(0, 0, 0, 150), Color.WHITE);
        String clock = String.format(Locale.ROOT, "t = %.1f s", s.elapsedMs() / 1000.0);
        chipRight(g, ix + iw - 10, iy + 10, clock, new Color(0, 0, 0, 150), Color.WHITE);
        chip(g, ix + 10, iy + ih - 34, (attack ? "●  " : "") + "In front of camera: " + s.sourceLabel(),
                attack ? new Color(197, 34, 31, 220) : new Color(0, 0, 0, 150), Color.WHITE);
        if (s.note() != null) chipRight(g, ix + iw - 10, iy + ih - 34, s.note(), new Color(227, 116, 0, 230), Color.WHITE);
        String cap = banner;
        if (cap != null) {
            g.setFont(new Font(Theme.SANS, Font.BOLD, 15));
            FontMetrics fm = g.getFontMetrics();
            int cw = fm.stringWidth(cap) + 28;
            g.setColor(new Color(30, 90, 168, 235));
            g.fill(new RoundRectangle2D.Double(ix + (iw - cw) / 2.0, iy + 44, cw, 30, 15, 15));
            g.setColor(Color.WHITE);
            g.drawString(cap, ix + (iw - cw) / 2 + 14, iy + 64);
        }
        g.setClip(clip);
    }

    private static void chip(Graphics2D g, int x, int y, String text, Color bg, Color fg) {
        g.setFont(Theme.LABEL_BOLD);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 18;
        g.setColor(bg);
        g.fill(new RoundRectangle2D.Double(x, y, w, 24, 12, 12));
        g.setColor(fg);
        g.drawString(text, x + 9, y + 16);
    }

    private static void chipRight(Graphics2D g, int right, int y, String text, Color bg, Color fg) {
        g.setFont(Theme.LABEL_BOLD);
        int w = g.getFontMetrics().stringWidth(text) + 18;
        chip(g, right - w, y, text, bg, fg);
    }

    private static String phaseName(Phase p) {
        return switch (p) {
            case IDLE -> "Idle";
            case DEVICE_CONNECTING -> "Connecting";
            case FACE_ACQUIRING -> "Acquiring face";
            case PASSIVE_EVALUATING -> "Passive check";
            case ACTIVE_CHALLENGE -> "Active challenge";
            case PASSED -> "Passed";
            case FAILED -> "Failed";
            case LOCKED -> "Locked";
            case CANCELLED -> "Cancelled";
        };
    }

    private void paintSession(Graphics2D g, Snapshot s, int x, int y, int w, int h) {
        card(g, x, y, w, h, "Session");
        SessionState st = s.state();
        g.setFont(Theme.LABEL);
        g.setColor(Theme.TEXT);
        String wf = switch (s.workflow()) {
            case RESIDENT -> "Resident registration";
            case OPERATOR -> "Operator authentication";
            case SUPERVISOR -> "Supervisor authentication";
        };
        String attempt = st == null ? "" : "Attempt " + st.attempt() + " of " + st.maxAttempts();
        String frames = st == null ? "" : s.framesAnalyzed() + " frames analysed";
        g.setFont(Theme.LABEL_BOLD);
        g.drawString(wf, x + 14, y + 44);
        g.setFont(Theme.LABEL);
        g.setColor(Theme.MUTED);
        String right = attempt + (frames.isEmpty() ? "" : "   ·   " + frames);
        g.drawString(right, x + w - 14 - g.getFontMetrics().stringWidth(right), y + 44);
        // phase stepper
        String[] steps = {"Connect", "Acquire", "Passive", "Challenge", "Decision"};
        Phase p = st == null ? Phase.IDLE : st.phase();
        int cur = switch (p) {
            case IDLE -> -1;
            case DEVICE_CONNECTING -> 0;
            case FACE_ACQUIRING -> 1;
            case PASSIVE_EVALUATING -> 2;
            case ACTIVE_CHALLENGE -> 3;
            default -> 4;
        };
        int sx = x + 14, sy = y + 60, sw = (w - 28) / steps.length;
        for (int i = 0; i < steps.length; i++) {
            Color c = i < cur ? Theme.ACCENT : i == cur ? (cur == 4 ? decisionColor(s) : Theme.ACCENT) : Theme.FAINT;
            g.setColor(c);
            g.fill(new RoundRectangle2D.Double(sx + i * sw, sy, sw - 6, 20, 10, 10));
            g.setFont(Theme.SMALL);
            g.setColor(i <= cur ? Color.WHITE : Theme.MUTED);
            String t = steps[i];
            g.drawString(t, sx + i * sw + (sw - 6 - g.getFontMetrics().stringWidth(t)) / 2, sy + 14);
        }
    }

    private void paintPrompt(Graphics2D g, Snapshot s, int x, int y, int w, int h) {
        card(g, x, y, w, h, "What the person is asked to do");
        SessionState st = s.state();
        Phase p = st == null ? Phase.IDLE : st.phase();
        Challenge c = s.challenge();
        String head;
        String sub;
        if (p == Phase.ACTIVE_CHALLENGE && c != null) {
            head = UxCopy.prompt(c.type());
            sub = UxCopy.hint(st.hint());
        } else if (p == Phase.FAILED || p == Phase.LOCKED) {
            FailureReason r = s.failure();
            head = p == Phase.LOCKED ? UxCopy.phaseHeadline(p, s.workflow()) : r == null ? UxCopy.phaseHeadline(p, s.workflow()) : UxCopy.failureMessage(r);
            List<String> gl = r == null ? List.of() : UxCopy.guidance(r);
            sub = String.join("  ", gl);
        } else {
            head = UxCopy.phaseHeadline(p, s.workflow());
            sub = st == null ? (s.synthetic() ? "Pick a workflow and a subject, then press Start session." : "Press Start session.") : UxCopy.hint(st.hint());
        }
        g.setColor(p == Phase.ACTIVE_CHALLENGE ? Theme.ACCENT : p == Phase.PASSED ? Theme.GREEN : (p == Phase.FAILED || p == Phase.LOCKED) ? Theme.RED : Theme.TEXT);
        Font f = Theme.PROMPT;
        g.setFont(f);
        while (g.getFontMetrics().stringWidth(head) > w - 28 && f.getSize() > 13) { f = f.deriveFont((float) f.getSize() - 1); g.setFont(f); }
        g.drawString(head, x + 14, y + 56);
        g.setFont(Theme.LABEL);
        g.setColor(Theme.MUTED);
        if (sub != null) g.drawString(sub, x + 14, y + 78);
        // progress: challenge dots + action progress + countdown
        int by = y + 92;
        if (p == Phase.ACTIVE_CHALLENGE && c != null) {
            for (int i = 0; i < c.total(); i++) {
                g.setColor(i < c.index() ? Theme.GREEN : i == c.index() ? Theme.ACCENT : Theme.FAINT);
                g.fillOval(x + 14 + i * 18, by + 2, 12, 12);
            }
            g.setFont(Theme.SMALL);
            g.setColor(Theme.MUTED);
            g.drawString("Challenge " + (c.index() + 1) + " of " + c.total(), x + 18 + c.total() * 18, by + 12);
            int px = x + 170, pw = w - 170 - 90;
            bar(g, px, by + 3, pw, 10, s.actionProgress(), Theme.ACCENT);
            long rem = st.remainingChallengeMillis();
            String t = String.format(Locale.ROOT, "%.1f s left", Math.max(0, rem) / 1000.0);
            g.drawString(t, x + w - 14 - g.getFontMetrics().stringWidth(t), by + 12);
        } else if (st != null) {
            g.setFont(Theme.SMALL);
            g.setColor(Theme.MUTED);
            g.drawString("Session progress", x + 14, by + 12);
            bar(g, x + 120, by + 3, w - 134, 10, st.progress(), p == Phase.PASSED ? Theme.GREEN : (p == Phase.FAILED || p == Phase.LOCKED) ? Theme.RED : Theme.ACCENT);
        }
    }

    private static void bar(Graphics2D g, int x, int y, int w, int h, double v, Color c) {
        g.setColor(Theme.FAINT);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, h, h));
        double f = Math.max(0, Math.min(1, v));
        if (f > 0) {
            g.setColor(c);
            g.fill(new RoundRectangle2D.Double(x, y, Math.max(h, w * f), h, h, h));
        }
    }

    private void paintSignals(Graphics2D g, Snapshot s, int x, int y, int w, int h) {
        card(g, x, y, w, h, "Fused signals  (attack probability, 0 = live)");
        int lx = x + 14, bx = x + 168, bw = w - 168 - 100;
        // fused liveness score
        int yy = y + 40;
        double fused = s.state() == null ? 0 : s.state().passiveScore();
        g.setFont(Theme.LABEL_BOLD);
        g.setColor(Theme.TEXT);
        g.drawString("Fused liveness score", lx, yy + 10);
        boolean ok = fused >= s.passiveThreshold();
        bar(g, bx, yy, bw, 12, fused, s.state() == null ? Theme.FAINT : ok ? Theme.GREEN : Theme.AMBER);
        if (s.passiveThreshold() > 0) {
            int tx = bx + (int) Math.round(bw * s.passiveThreshold());
            g.setColor(Theme.TEXT);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(tx, yy - 4, tx, yy + 16);
            g.setFont(Theme.SMALL);
            g.setColor(Theme.MUTED);
            g.drawString(String.format(Locale.ROOT, "threshold %.2f", s.passiveThreshold()), Math.min(tx - 30, bx + bw - 80), yy + 28);
        }
        g.setFont(Theme.MONO_BOLD);
        g.setColor(Theme.TEXT);
        g.drawString(s.state() == null ? "  –" : String.format(Locale.ROOT, "%.2f", fused), x + w - 52, yy + 10);
        // per-signal rows
        String veto = s.result() == null ? null : s.result().vetoSignal().orElse(null);
        Map<String, Double> scores = s.signalScores();
        List<String> ids = new ArrayList<>();
        for (String id : SIGNAL_ORDER) if (scores.containsKey(id) || s.signalWeights().containsKey(id)) ids.add(id);
        for (String id : scores.keySet()) if (!ids.contains(id)) ids.add(id);
        if (ids.isEmpty()) for (String id : SIGNAL_ORDER) ids.add(id);
        int row = 0;
        for (String id : ids) {
            if (row >= 9) break;
            int ry = y + 76 + row * 17;
            double weight = s.signalWeights().getOrDefault(id, 1.0);
            Double v = scores.get(id);
            boolean off = weight <= 0;
            g.setFont(Theme.LABEL);
            g.setColor(off ? Theme.MUTED : Theme.TEXT);
            g.drawString(UxCopy.signalLabel(id), lx, ry + 10);
            if (off) {
                g.setColor(Theme.FAINT);
                g.fill(new RoundRectangle2D.Double(bx, ry + 2, bw, 8, 8, 8));
                g.setFont(Theme.SMALL);
                g.setColor(Theme.MUTED);
                g.drawString(s.synthetic() ? "off: drawn face has no pulse/depth" : "off by policy", bx + 8, ry + 10);
            } else {
                double val = v == null ? 0 : v;
                Color c = val >= 0.9 ? Theme.RED : val >= 0.5 ? Theme.AMBER : Theme.GREEN;
                bar(g, bx, ry + 2, bw, 8, v == null ? 0 : val, c);
                g.setFont(Theme.MONO);
                g.setColor(Theme.TEXT);
                g.drawString(v == null ? "  –" : String.format(Locale.ROOT, "%.2f", val), x + w - 52, ry + 10);
            }
            if (id.equals(veto)) {
                g.setFont(Theme.SMALL);
                g.setColor(Theme.RED);
                g.drawString("VETO", x + w - 92, ry + 10);
            }
            row++;
        }
    }

    private static Color decisionColor(Snapshot s) {
        String d = decision(s);
        return switch (d) {
            case "LIVE" -> Theme.GREEN;
            case "ATTACK" -> Theme.RED;
            case "LOCKED", "NOT LIVE", "ERROR" -> Theme.AMBER;
            default -> Theme.MUTED;
        };
    }

    /** Banner word for the current outcome. */
    static String decision(Snapshot s) {
        SessionState st = s.state();
        if (st == null) return "READY";
        LivenessResult r = s.result();
        if (st.phase() == Phase.LOCKED) return "LOCKED";
        if (r != null && (st.phase() == Phase.PASSED || st.phase() == Phase.FAILED)) {
            return switch (r.decision()) {
                case LIVE -> "LIVE";
                case ATTACK -> "ATTACK";
                case NOT_LIVE -> "NOT LIVE";
                case ERROR -> "ERROR";
                case CANCELLED -> "CANCELLED";
            };
        }
        if (st.phase() == Phase.PASSED) return "LIVE";
        if (st.phase() == Phase.FAILED) return st.failure().map(f -> f == FailureReason.PAD_DETECTED ? "ATTACK" : "NOT LIVE").orElse("NOT LIVE");
        return "CHECKING";
    }

    private void paintDecision(Graphics2D g, Snapshot s, int x, int y, int w, int h) {
        card(g, x, y, w, h, "Decision");
        String d = decision(s);
        Color fg, bg;
        switch (d) {
            case "LIVE" -> { fg = Theme.GREEN; bg = Theme.GREEN_BG; }
            case "ATTACK" -> { fg = Theme.RED; bg = Theme.RED_BG; }
            case "NOT LIVE", "LOCKED", "ERROR" -> { fg = Theme.AMBER; bg = Theme.AMBER_BG; }
            default -> { fg = Theme.MUTED; bg = Theme.GREY_BG; }
        }
        g.setColor(bg);
        g.fill(new RoundRectangle2D.Double(x + 14, y + 32, 168, 64, 10, 10));
        g.setColor(fg);
        Font bf = Theme.BANNER;
        g.setFont(bf);
        while (g.getFontMetrics().stringWidth(d) > 150) { bf = bf.deriveFont(bf.getSize2D() - 2); g.setFont(bf); }
        g.drawString(d, x + 14 + (168 - g.getFontMetrics().stringWidth(d)) / 2, y + 75);

        SessionState st = s.state();
        LivenessResult r = s.result();
        int tx = x + 196, tw = w - 196 - 14;
        g.setFont(Theme.SMALL);
        g.setColor(Theme.MUTED);
        g.drawString("Shown to the person", tx, y + 42);
        String msg;
        if (st == null) msg = "No session yet.";
        else if (st.phase() == Phase.PASSED) msg = UxCopy.phaseHeadline(Phase.PASSED, s.workflow());
        else if (st.phase() == Phase.LOCKED) msg = UxCopy.phaseHeadline(Phase.LOCKED, s.workflow());
        else if (st.phase() == Phase.FAILED) msg = s.failure() == null ? UxCopy.phaseHeadline(Phase.FAILED, s.workflow()) : UxCopy.failureMessage(s.failure());
        else msg = "Checking face liveness…";
        g.setFont(Theme.LABEL_BOLD);
        g.setColor(Theme.TEXT);
        int ly = y + 58;
        for (String line : wrap(g.getFontMetrics(), msg, tw)) { g.drawString(line, tx, ly); ly += 16; }

        g.setFont(Theme.SMALL);
        g.setColor(Theme.MUTED);
        g.drawString("Audit-only detail (never shown to the person)", x + 14, y + 116);
        g.setFont(Theme.MONO);
        g.setColor(Theme.TEXT);
        String detail;
        if (st == null) detail = "–";
        else {
            StringBuilder b = new StringBuilder();
            b.append("reason=").append(st.failure().map(Enum::name).orElse(st.phase() == Phase.PASSED ? "none" : "–"));
            if (r != null) {
                r.vetoSignal().ifPresent(v -> b.append("  veto=").append(v));
                r.attackClass().ifPresent(v -> b.append("  class=").append(v));
                b.append(String.format(Locale.ROOT, "  fused=%.3f  challenges=%d", r.fusedLivenessScore(), r.challenges().size()));
            }
            detail = b.toString();
        }
        g.drawString(clip(g.getFontMetrics(), detail, w - 28), x + 14, y + 132);
        g.setColor(Theme.MUTED);
        g.setFont(Theme.SMALL);
        g.drawString("Evidence hash (SHA-256 over the decision record)", x + 14, y + 150);
        g.setFont(Theme.MONO_BOLD);
        g.setColor(r == null ? Theme.MUTED : Theme.ACCENT);
        String hash = r == null || r.evidenceHash() == null ? "pending: issued when the attempt ends" : r.evidenceHash();
        g.drawString(clip(g.getFontMetrics(), hash, w - 28), x + 14, y + 165);
    }

    private void paintAudit(Graphics2D g, Snapshot s, int x, int y, int w, int h) {
        card(g, x, y, w, h, "Audit trail  (LIVENESS events, live)");
        List<Snapshot.AuditLine> lines = s.audit();
        int max = 5;
        int from = Math.max(0, lines.size() - max);
        int ly = y + 42;
        g.setFont(Theme.MONO);
        FontMetrics fm = g.getFontMetrics();
        if (lines.isEmpty()) {
            g.setColor(Theme.MUTED);
            g.drawString("Events appear here as the orchestrator emits them.", x + 14, ly);
        }
        for (int i = from; i < lines.size(); i++) {
            Snapshot.AuditLine l = lines.get(i);
            Color c = switch (l.severity()) {
                case "SECURITY" -> Theme.RED;
                case "WARN", "ERROR" -> Theme.AMBER;
                case "DEMO" -> Theme.ACCENT;
                default -> Theme.TEXT;
            };
            String t = String.format(Locale.ROOT, "%6.2fs ", l.elapsedMs() / 1000.0);
            g.setColor(Theme.MUTED);
            g.drawString(t, x + 14, ly);
            int ex = x + 14 + fm.stringWidth(t);
            g.setFont(Theme.MONO_BOLD);
            g.setColor(c);
            g.drawString(l.event(), ex, ly);
            int dx = ex + g.getFontMetrics().stringWidth(l.event()) + 8;
            g.setFont(Theme.MONO);
            g.setColor(Theme.MUTED);
            g.drawString(clip(fm, l.detail(), x + w - 14 - dx), dx, ly);
            ly += 15;
        }
    }

    private static String clip(FontMetrics fm, String s, int w) {
        if (s == null) return "";
        if (fm.stringWidth(s) <= w) return s;
        String e = "…";
        int n = s.length();
        while (n > 0 && fm.stringWidth(s.substring(0, n) + e) > w) n--;
        return s.substring(0, n) + e;
    }

    private static List<String> wrap(FontMetrics fm, String s, int w) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(t) > w && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(word); }
            else { line = new StringBuilder(t); }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
