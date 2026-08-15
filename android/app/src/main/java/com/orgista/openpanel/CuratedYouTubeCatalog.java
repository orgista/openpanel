package com.orgista.openpanel;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Verified channels explicitly approved for this kiosk, plus a small recent
 * catalog used only when the TV's restricted DNS path cannot reach YouTube's
 * public channel/feed pages. No API key or search endpoint is involved.
 */
final class CuratedYouTubeCatalog {
    private static final Channel HOMESCHOOL_POP = new Channel(
        "UCfPyVJEBD7Di1YYjTdS2v8g",
        "Homeschool Pop",
        "https://yt3.googleusercontent.com/yENC7nbCi2IqwCeDy8T7Kzpcubxt-iz_yR69dlKGXvwufaEJIbk7iYYseVAqMAAoLNrs4hjR=s900-c-k-c0x00ffffff-no-rj",
        Arrays.asList(
            video("pCN-IjSo8j0", "Liquid Measurements For Kids | Cups, Pints, Quarts, & Gallons", "2026-08-10T13:59:18+00:00"),
            video("Rf71QRZtVME", "School Supplies in Spanish for Kids | Spanish Vocabulary Lesson", "2026-07-21T14:01:06+00:00"),
            video("PI9tlYxluX0", "Cactus Facts for Kids | Learn About Cacti", "2026-07-01T15:32:04+00:00"),
            video("UhjYpF9hZJM", "Scorpions for Kids | Scorpion Facts and Anatomy", "2026-06-16T13:54:35+00:00"),
            video("QeN6vIbw9iU", "The Last Fridays Episode of the School Year!", "2026-05-22T13:15:36+00:00"),
            video("VRxXaRx2SXk", "Language Arts Review for Kids | End of School Year Lessons", "2026-05-21T05:00:43+00:00")
        )
    );

    private static final Channel KURZGESAGT = new Channel(
        "UCsXVk37bltHxD1rDPwtNM8Q",
        "Kurzgesagt – In a Nutshell",
        "https://yt3.googleusercontent.com/ytc/AIdro_n1Ribd7LwdP_qKtqWL3ZDfIgv9M1d6g78VwpHGXVR2Ir4=s900-c-k-c0x00ffffff-no-rj",
        Arrays.asList(
            video("Cyl3X88KEgg", "Why Humanity Will Never Leave The Solar System", "2026-08-04T13:59:53+00:00"),
            video("AWUYE6eJO3k", "Building a Secret Moon Base", "2026-07-27T14:00:04+00:00"),
            video("2cK8l5Yg5w8", "The Deadliest Thing in Your Kitchen", "2026-07-23T14:00:17+00:00"),
            video("uVlMWVP5GJk", "The True Cost of War", "2026-07-06T14:00:09+00:00"),
            video("N2FOxjtv5ns", "What is El Niño?", "2026-07-01T14:00:35+00:00"),
            video("Gn-KqiRv0XI", "The World’s Most Average Person", "2026-06-29T14:00:17+00:00")
        )
    );

    private CuratedYouTubeCatalog() {}

    static Channel match(String input, String validatedLookupUrl) {
        String normalized = normalize(input + " " + validatedLookupUrl);
        if (normalized.contains("homeschoolpop")
            || normalized.contains(normalize(HOMESCHOOL_POP.channelId))) {
            return HOMESCHOOL_POP;
        }
        if (normalized.contains("kurzgesagt")
            || normalized.contains(normalize(KURZGESAGT.channelId))) {
            return KURZGESAGT;
        }
        return null;
    }

    static Channel forChannelId(String channelId) {
        if (HOMESCHOOL_POP.channelId.equals(channelId)) return HOMESCHOOL_POP;
        if (KURZGESAGT.channelId.equals(channelId)) return KURZGESAGT;
        return null;
    }

    private static String normalize(String value) {
        return (value == null ? "" : value)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]", "");
    }

    private static Video video(String videoId, String title, String publishedAt) {
        return new Video(
            videoId,
            title,
            "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg",
            publishedAt
        );
    }

    static final class Channel {
        final String channelId;
        final String title;
        final String thumbnailUrl;
        final List<Video> videos;

        Channel(String channelId, String title, String thumbnailUrl, List<Video> videos) {
            this.channelId = channelId;
            this.title = title;
            this.thumbnailUrl = thumbnailUrl;
            this.videos = Collections.unmodifiableList(videos);
        }

        String canonicalUrl() {
            return "https://www.youtube.com/channel/" + channelId;
        }
    }

    static final class Video {
        final String videoId;
        final String title;
        final String thumbnailUrl;
        final String publishedAt;

        Video(String videoId, String title, String thumbnailUrl, String publishedAt) {
            this.videoId = videoId;
            this.title = title;
            this.thumbnailUrl = thumbnailUrl;
            this.publishedAt = publishedAt;
        }
    }
}
