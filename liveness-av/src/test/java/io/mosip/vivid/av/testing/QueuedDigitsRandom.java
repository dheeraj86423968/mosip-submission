package io.mosip.vivid.av.testing;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Test-only SecureRandom whose {@code nextInt(10)} returns queued digits, so the code "drawn" by the AV analyzer is
 * one for which a whole-utterance TTS clip already exists. Falls back to real randomness when the queue is empty.
 * Production always uses a plain {@link SecureRandom}.
 */
public final class QueuedDigitsRandom extends SecureRandom {
    private static final long serialVersionUID = 1L;
    private final Deque<Integer> queue = new ArrayDeque<>();

    public synchronized void queue(String digits) {
        for (char c : digits.toCharArray()) queue.add(c - '0');
    }

    @Override
    public synchronized int nextInt(int bound) {
        if (bound == 10 && !queue.isEmpty()) return queue.poll();
        return super.nextInt(bound);
    }
}
