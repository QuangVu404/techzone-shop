package vn.techzone.infrastructure;

import vn.techzone.domain.TechZoneModels.ProductVariant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Local statistical search and item-item recommendation, trained on the demo catalogue and baskets. */
public final class LocalAiEngine {
    private static final Set<String> STOP = Set.of("va", "voi", "cho", "cua", "toi", "muon", "can", "tim", "mot", "cai",
            "loai", "duoi", "tren", "gia", "trieu", "nghin", "dong", "the", "and", "for", "with", "under", "below");
    // Synthetic examples are labelled fixtures, not records from real customers.
    private static final List<List<String>> SAMPLE_BASKETS = List.of(
            List.of("CPU-14600K", "MB-B760-D5", "RAM-D5-32", "SSD-990", "COOLER-PA120"),
            List.of("CPU-14600K", "MB-B760-D5", "RAM-D5-32", "GPU-4070S", "PSU-750"),
            List.of("CPU-14600K", "MB-B760-D5", "RAM-D5-32", "COOLER-PA120"),
            List.of("CPU-14600K", "MB-B760-D4", "RAM-D4-16", "PSU-550"),
            List.of("CPU-14600K", "MB-B760-D4", "RAM-D4-16", "GPU-4060", "PSU-550"),
            List.of("CPU-14700K", "MB-B760-D5", "RAM-D5-32", "PSU-750", "COOLER-PA120"),
            List.of("CPU-14700K", "MB-B760-D5", "SSD-990", "GPU-4070S", "PSU-750"),
            List.of("CPU-7800X3D", "MB-B650", "RAM-D5-32", "GPU-4070S", "PSU-750"),
            List.of("CPU-7800X3D", "MB-B650", "RAM-D5-32", "COOLER-PA120"),
            List.of("GPU-4060", "PSU-550", "MON-27", "MOUSE-G502"),
            List.of("GPU-4060", "PSU-750", "MON-27"),
            List.of("GPU-4070S", "PSU-750", "MON-27"),
            List.of("PHONE-S24", "CASE-S24"), List.of("PHONE-S24", "CASE-S24", "MOUSE-G502"),
            List.of("PHONE-S24", "CASE-S24"), List.of("PHONE-IP16", "CASE-IP16"),
            List.of("PHONE-IP16", "CASE-IP16"), List.of("MON-27", "MOUSE-G502"));

    private Map<String, Map<String, Double>> vectors = Map.of();
    private Map<String, Double> idf = Map.of();
    private Map<String, Integer> frequency = Map.of();
    private Map<String, Integer> pairCounts = Map.of();
    private int basketCount;

    /** Rebuilds a TF-IDF index and a co-occurrence model from current data. */
    public synchronized void fit(Collection<ProductVariant> products, Collection<List<String>> consentedBaskets) {
        fit(products, consentedBaskets, true);
    }

    /** Evaluation can exclude built-in examples to avoid training on held-out baskets. */
    public synchronized void fit(Collection<ProductVariant> products, Collection<List<String>> baskets, boolean includeSampleBaskets) {
        Map<String, Map<String, Double>> raw = new LinkedHashMap<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (ProductVariant p : products) {
            Map<String, Double> terms = new HashMap<>();
            addFeatures(terms, p.name + " " + p.sku, 3.0);
            addFeatures(terms, p.category + " " + p.brand, 2.0);
            addFeatures(terms, p.description, 1.0);
            p.specs.forEach((key, value) -> addFeatures(terms, key + " " + value, 2.0));
            raw.put(p.id, terms);
            for (String term : terms.keySet()) documentFrequency.merge(term, 1, Integer::sum);
        }
        Map<String, Double> nextIdf = new HashMap<>();
        int count = raw.size();
        documentFrequency.forEach((term, n) -> nextIdf.put(term, Math.log((count + 1.0) / (n + 1.0)) + 1.0));
        Map<String, Map<String, Double>> nextVectors = new LinkedHashMap<>();
        raw.forEach((id, terms) -> nextVectors.put(id, weightedUnitVector(terms, nextIdf)));

        Map<String, Integer> nextFrequency = new HashMap<>();
        Map<String, Integer> nextPairs = new HashMap<>();
        List<List<String>> training = new ArrayList<>();
        if (includeSampleBaskets) training.addAll(SAMPLE_BASKETS);
        if (baskets != null) training.addAll(baskets);
        for (List<String> basket : training) {
            Set<String> unique = new HashSet<>(basket);
            unique.retainAll(raw.keySet());
            for (String id : unique) nextFrequency.merge(id, 1, Integer::sum);
            for (String left : unique) for (String right : unique)
                if (left.compareTo(right) < 0) nextPairs.merge(pairKey(left, right), 1, Integer::sum);
        }
        idf = Map.copyOf(nextIdf); vectors = Map.copyOf(nextVectors);
        frequency = Map.copyOf(nextFrequency); pairCounts = Map.copyOf(nextPairs); basketCount = training.size();
    }

    public static List<List<String>> sampleBaskets() { return SAMPLE_BASKETS; }

    public synchronized Map<String, Double> rankSearch(String query, Collection<ProductVariant> candidates) {
        Map<String, Double> terms = new HashMap<>();
        addFeatures(terms, stripBudget(query), 1.0);
        Map<String, Double> queryVector = weightedUnitVector(terms, idf);
        Map<String, Double> scores = new HashMap<>();
        for (ProductVariant candidate : candidates) scores.put(candidate.id, dot(queryVector, vectors.get(candidate.id)));
        return scores;
    }

    /** Association from basket co-occurrence, optionally informed by the current customer's session. */
    public synchronized double recommendationScore(String primaryId, String candidateId, Set<String> sessionItems) {
        double score = association(primaryId, candidateId);
        if (sessionItems != null) for (String item : sessionItems) {
            if (!item.equals(candidateId) && !item.equals(primaryId)) score = Math.max(score, association(item, candidateId) * 0.8);
        }
        double content = dot(vectors.get(primaryId), vectors.get(candidateId));
        return score + 0.12 * content + (sessionItems != null && sessionItems.contains(candidateId) ? 0.05 : 0.0);
    }

    public synchronized boolean hasBasketEvidence(String primaryId) { return frequency.getOrDefault(primaryId, 0) > 0; }
    public synchronized int basketCount() { return basketCount; }
    public synchronized int vocabularySize() { return idf.size(); }

    private double association(String a, String b) {
        int fa = frequency.getOrDefault(a, 0), fb = frequency.getOrDefault(b, 0);
        if (fa == 0 || fb == 0) return 0.0;
        return pairCounts.getOrDefault(pairKey(a, b), 0) / Math.sqrt((double) fa * fb);
    }

    private static String pairKey(String a, String b) { return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a; }

    private static Map<String, Double> weightedUnitVector(Map<String, Double> terms, Map<String, Double> idf) {
        Map<String, Double> weighted = new HashMap<>();
        double norm = 0;
        for (Map.Entry<String, Double> entry : terms.entrySet()) {
            Double weight = idf.get(entry.getKey());
            if (weight == null) continue;
            double v = Math.log1p(entry.getValue()) * weight;
            weighted.put(entry.getKey(), v); norm += v * v;
        }
        if (norm == 0) return Map.of();
        double length = Math.sqrt(norm);
        weighted.replaceAll((key, value) -> value / length);
        return Map.copyOf(weighted);
    }

    private static double dot(Map<String, Double> a, Map<String, Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        double sum = 0;
        for (Map.Entry<String, Double> term : a.entrySet()) sum += term.getValue() * b.getOrDefault(term.getKey(), 0.0);
        return sum;
    }

    private static void addFeatures(Map<String, Double> terms, String text, double weight) {
        String normalized = canonicalize(text);
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (token.length() < 2 || STOP.contains(token)) continue;
            terms.merge("w:" + token, weight, Double::sum);
            if (token.length() >= 4) for (int i = 0; i <= token.length() - 3; i++)
                terms.merge("c:" + token.substring(i, i + 3), weight * 0.15, Double::sum);
        }
    }

    private static String stripBudget(String text) {
        String normalized = normalize(text);
        return normalized.replaceAll("\\b(?:duoi|under|below|tren)\\s*[0-9][0-9.,]*\\s*(?:trieu|million|nghin|k|m)?\\b", " ");
    }

    private static String canonicalize(String text) {
        String value = normalize(text);
        String[][] aliases = {
                {"bo mach chu|motherboard|mobo", "mainboard"}, {"bo xu ly|processor", "cpu"},
                {"card do hoa|card man hinh|graphics card", "gpu"}, {"nguon may tinh|power supply", "psu"},
                {"tan nhiet|cooling|cooler", "cooler"}, {"o cung|hard drive", "ssd"},
                {"man hinh|screen|display", "monitor"}, {"dien thoai|smartphone", "phone"},
                {"op lung|phone case", "case"}, {"choi game|game", "gaming"}
        };
        for (String[] alias : aliases) value = Pattern.compile("\\b(?:" + alias[0] + ")\\b").matcher(value).replaceAll(alias[1]);
        return value;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT).trim();
    }
}
