package io.mosip.vivid.telemetry.calibration;

import java.math.BigInteger;
import java.util.Random;

/**
 * Exact samplers for the discrete Laplace and discrete Gaussian distributions, after Canonne, Kamath and Steinke,
 * "The Discrete Gaussian for Differential Privacy" (NeurIPS 2020), Algorithms 1 to 3.
 *
 * <p>Every step uses integer or rational arithmetic and uniform integers from the supplied {@link Random} (production:
 * {@link java.security.SecureRandom}); no floating-point value is ever turned into noise. That avoids the
 * floating-point side channels of textbook Laplace sampling (Mironov, CCS 2012), and the output is integer-valued so
 * noisy counts reveal nothing through their low-order bits.</p>
 */
final class ExactSampling {

    private ExactSampling() {}

    /** Uniform integer in [0, bound). */
    static long uniform(Random rnd, long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        if (bound == 1) {
            return 0;
        }
        // Rejection sampling on the top bits: linear congruential generators (java.util.Random, used by the
        // simulation) have weak low-order bits; SecureRandom does not care.
        int bits = 64 - Long.numberOfLeadingZeros(bound - 1);
        while (true) {
            long r = rnd.nextLong() >>> (64 - bits);
            if (r < bound) {
                return r;
            }
        }
    }

    /** Uniform integer in [0, bound) for large bounds. */
    static BigInteger uniform(Random rnd, BigInteger bound) {
        if (bound.bitLength() < 62) {
            return BigInteger.valueOf(uniform(rnd, bound.longValueExact()));
        }
        while (true) {
            BigInteger r = new BigInteger(bound.bitLength(), rnd);
            if (r.compareTo(bound) < 0) {
                return r;
            }
        }
    }

    /** Bernoulli(num / den), 0 <= num <= den. */
    static boolean bernoulli(Random rnd, long num, long den) {
        return uniform(rnd, den) < num;
    }

    static boolean bernoulli(Random rnd, BigInteger num, BigInteger den) {
        return uniform(rnd, den).compareTo(num) < 0;
    }

    /** Bernoulli(exp(-num/den)) for num/den >= 0 (Algorithm 1). */
    static boolean bernoulliExp(Random rnd, long num, long den) {
        if (num < 0 || den <= 0) {
            throw new IllegalArgumentException("need num >= 0 and den > 0");
        }
        while (num > den) {
            // exp(-g) = exp(-1)^floor(g) * exp(-(g - floor(g)))
            if (!bernoulliExp(rnd, 1, 1)) {
                return false;
            }
            num -= den;
        }
        long k = 1;
        while (bernoulli(rnd, num, Math.multiplyExact(den, k))) {
            k++;
        }
        return (k & 1) == 1;
    }

    static boolean bernoulliExp(Random rnd, BigInteger num, BigInteger den) {
        if (num.signum() < 0 || den.signum() <= 0) {
            throw new IllegalArgumentException("need num >= 0 and den > 0");
        }
        BigInteger[] qr = num.divideAndRemainder(den);
        for (BigInteger i = BigInteger.ZERO; i.compareTo(qr[0]) < 0; i = i.add(BigInteger.ONE)) {
            if (!bernoulliExp(rnd, 1, 1)) {
                return false;
            }
        }
        BigInteger rest = qr[1];
        BigInteger k = BigInteger.ONE;
        while (bernoulli(rnd, rest, den.multiply(k))) {
            k = k.add(BigInteger.ONE);
        }
        return k.testBit(0);
    }

    /**
     * Discrete Laplace with scale {@code s/t}: P(x) proportional to exp(-|x| t / s) (Algorithm 2).
     */
    static long discreteLaplace(Random rnd, long s, long t) {
        if (s <= 0 || t <= 0) {
            throw new IllegalArgumentException("scale must be positive");
        }
        while (true) {
            long u = uniform(rnd, s);
            if (!bernoulliExp(rnd, u, s)) {
                continue;
            }
            long v = 0;
            while (bernoulliExp(rnd, 1, 1)) {
                v++;
            }
            long x = u + s * v;
            long y = x / t;
            boolean negative = bernoulli(rnd, 1, 2);
            if (negative && y == 0) {
                continue;
            }
            return negative ? -y : y;
        }
    }

    /**
     * Discrete Gaussian with variance parameter {@code sigma2Num / sigma2Den}: P(x) proportional to
     * exp(-x^2 / (2 sigma^2)) (Algorithm 3).
     */
    static long discreteGaussian(Random rnd, BigInteger sigma2Num, BigInteger sigma2Den) {
        // t = floor(sigma) + 1
        BigInteger floorSigma = sigma2Num.divide(sigma2Den).sqrt();
        while (floorSigma.add(BigInteger.ONE).pow(2).multiply(sigma2Den).compareTo(sigma2Num) <= 0) {
            floorSigma = floorSigma.add(BigInteger.ONE);
        }
        long t = floorSigma.longValueExact() + 1;
        BigInteger bt = BigInteger.valueOf(t);
        while (true) {
            long y = discreteLaplace(rnd, t, 1);
            // gamma = (|y| - sigma^2/t)^2 / (2 sigma^2) = (|y| d t - n)^2 / (2 n d t^2)
            BigInteger a = BigInteger.valueOf(Math.abs(y)).multiply(sigma2Den).multiply(bt).subtract(sigma2Num);
            BigInteger num = a.multiply(a);
            BigInteger den = BigInteger.TWO.multiply(sigma2Num).multiply(sigma2Den).multiply(bt).multiply(bt);
            if (bernoulliExp(rnd, num, den)) {
                return y;
            }
        }
    }
}
