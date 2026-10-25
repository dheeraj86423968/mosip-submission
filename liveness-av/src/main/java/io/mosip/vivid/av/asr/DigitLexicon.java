package io.mosip.vivid.av.asr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spoken forms of the digits 0-9 per language, used both as the recogniser's closed grammar and to map recognised
 * words back to digits. Every word listed here was checked to be in the vocabulary of the matching Vosk small model
 * (Vosk logs "Ignoring word missing in vocabulary" otherwise; see liveness-av/models/README.md).
 *
 * <p>Languages: {@code en} (US and Indian English models), {@code hi} Hindi, {@code te} Telugu, {@code gu} Gujarati.
 * Spoken forms follow common usage; regional variants (for example Hindi "छः"/"छे" for six) are included where the
 * model knows them.</p>
 */
public final class DigitLexicon {
    private static final Map<String, Map<String, Integer>> WORDS = new LinkedHashMap<>();

    static {
        put("en", "zero", 0, "oh", 0, "one", 1, "two", 2, "three", 3, "four", 4, "five", 5, "six", 6, "seven", 7, "eight", 8, "nine", 9);
        put("hi", "शून्य", 0, "एक", 1, "दो", 2, "तीन", 3, "चार", 4, "पांच", 5, "पाँच", 5, "छह", 6, "छः", 6, "छे", 6,
                "सात", 7, "आठ", 8, "नौ", 9);
        put("te", "సున్నా", 0, "ఒకటి", 1, "రెండు", 2, "మూడు", 3, "నాలుగు", 4, "ఐదు", 5, "అయిదు", 5, "ఆరు", 6, "ఏడు", 7,
                "ఎనిమిది", 8, "తొమ్మిది", 9);
        put("gu", "શૂન્ય", 0, "એક", 1, "બે", 2, "ત્રણ", 3, "ચાર", 4, "પાંચ", 5, "છ", 6, "સાત", 7, "આઠ", 8, "નવ", 9);
    }

    private DigitLexicon() {}

    private static void put(String lang, Object... pairs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Integer) pairs[i + 1]);
        WORDS.put(lang, Collections.unmodifiableMap(m));
    }

    /** Languages with a lexicon. */
    public static Set<String> languages() { return Collections.unmodifiableSet(WORDS.keySet()); }

    /** Primary language subtag, lower case: {@code en-IN} becomes {@code en}. */
    public static String base(String languageTag) {
        return Locale.forLanguageTag(languageTag).getLanguage().toLowerCase(Locale.ROOT);
    }

    public static boolean supports(String languageTag) { return WORDS.containsKey(base(languageTag)); }

    public static Map<String, Integer> words(String languageTag) {
        Map<String, Integer> m = WORDS.get(base(languageTag));
        if (m == null) throw new IllegalArgumentException("no digit lexicon for " + languageTag);
        return m;
    }

    /** Digit for a recognised word, or -1. */
    public static int digit(String languageTag, String word) {
        Integer d = words(languageTag).get(word.trim().toLowerCase(Locale.ROOT));
        if (d == null) d = words(languageTag).get(word.trim());
        return d == null ? -1 : d;
    }

    /** Vosk grammar: a JSON array of every digit word plus {@code [unk]}, so other speech maps to an unknown token. */
    public static String grammarJson(String languageTag) {
        List<String> parts = new ArrayList<>();
        for (String w : words(languageTag).keySet()) parts.add('"' + w + '"');
        parts.add("\"[unk]\"");
        return "[" + String.join(",", parts) + "]";
    }

    /** The preferred spoken form of each digit (first listed), for prompts and test synthesis. */
    public static String spoken(String languageTag, int digit) {
        for (Map.Entry<String, Integer> e : words(languageTag).entrySet()) {
            if (e.getValue() == digit && !"oh".equals(e.getKey())) return e.getKey();
        }
        throw new IllegalArgumentException("digit " + digit);
    }
}
