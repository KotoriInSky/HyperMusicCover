package com.os4.musiccover.lyrics;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;

public final class LyricSourcePolicy {
    public static final String DEFAULT_SOURCES = "metadata,local,lyricon,amll,ttmlhub,qq,netease,kugou,kuwo,lrclib";
    public static final String DEFAULT_FORMATS = "ttml,lrc,yrc,krc,lys";
    public static volatile Config current = new Config(false, true, DEFAULT_SOURCES, DEFAULT_FORMATS);

    private LyricSourcePolicy() { }

    public static String normalizeSources(String order) {
        return normalize(order, DEFAULT_SOURCES);
    }

    public static String normalizeFormats(String order) {
        return normalize(order, DEFAULT_FORMATS);
    }

    private static String normalize(String order, String defaults) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>(Arrays.asList(defaults.split(",")));
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (order != null) {
            for (String entry : order.split(",")) {
                String key = entry.trim().toLowerCase(Locale.ROOT);
                if (allowed.contains(key)) result.add(key);
            }
        }
        result.addAll(allowed);
        return String.join(",", result);
    }

    public static boolean network(String source) {
        return !"metadata".equals(source) && !"local".equals(source) && !"lyricon".equals(source);
    }

    public static final class Config {
        public final boolean custom;
        public final boolean allowNetwork;
        public final String sourceOrder;
        public final String formatOrder;

        public Config(boolean custom, boolean allowNetwork, String sourceOrder, String formatOrder) {
            this.custom = custom;
            this.allowNetwork = allowNetwork;
            this.sourceOrder = normalizeSources(sourceOrder);
            this.formatOrder = normalizeFormats(formatOrder);
        }

        public String[] sources() {
            return (custom ? sourceOrder : DEFAULT_SOURCES).split(",");
        }

        public String[] formats() {
            return formatOrder.split(",");
        }

        public boolean permits(String source) {
            return rank(source) != Integer.MAX_VALUE && (!network(source) || allowNetwork);
        }

        public boolean mayReplace(String previous, String candidate) {
            return permits(candidate) && (!permits(previous) || rank(candidate) <= rank(previous));
        }

        private int rank(String source) {
            String[] order = sources();
            for (int index = 0; index < order.length; index++) {
                if (order[index].equals(source)) return index;
            }
            return Integer.MAX_VALUE;
        }
    }
}
