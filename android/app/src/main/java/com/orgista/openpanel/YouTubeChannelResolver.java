package com.orgista.openpanel;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes exact YouTube channel references and extracts verified metadata
 * from the public channel page. This deliberately does not scrape YouTube
 * search results: display names are not unique, while handles and channel IDs
 * are.
 */
final class YouTubeChannelResolver {
    private static final Pattern CHANNEL_ID = Pattern.compile("^UC[A-Za-z0-9_-]{22}$");
    private static final Pattern CHANNEL_URL_IN_PAGE = Pattern.compile(
        "https://www\\.youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})"
    );
    private static final Pattern CHANNEL_PAGE_CANONICAL = Pattern.compile(
        "<link\\s+rel=\\\"canonical\\\"\\s+href=\\\"https://www\\.youtube\\.com/"
            + "(?:@[A-Za-z0-9._-]{3,30}|channel/UC[A-Za-z0-9_-]{22}|user/[A-Za-z0-9._-]+|c/[A-Za-z0-9._-]+)"
            + "(?:/[^\\\"]*)?\\\"",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXTERNAL_CHANNEL_ID = Pattern.compile(
        "(?:\\\"externalId\\\"\\s*:\\s*\\\"|externalId\\\\x22:\\\\x22)(UC[A-Za-z0-9_-]{22})"
    );
    private static final Pattern OG_TITLE = Pattern.compile(
        "<meta\\s+property=\\\"og:title\\\"\\s+content=\\\"([^\\\"]+)\\\"",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern OG_IMAGE = Pattern.compile(
        "<meta\\s+property=\\\"og:image\\\"\\s+content=\\\"([^\\\"]+)\\\"",
        Pattern.CASE_INSENSITIVE
    );

    private YouTubeChannelResolver() {}

    static String lookupUrl(String input) {
        String value = input == null ? "" : input.trim();
        if (value.length() < 2 || value.length() > 160) {
            throw new IllegalArgumentException("Enter a channel name, @handle, channel ID, or YouTube URL");
        }

        if (CHANNEL_ID.matcher(value).matches()) {
            return "https://www.youtube.com/channel/" + value;
        }

        String urlCandidate = value;
        if (value.regionMatches(true, 0, "youtube.com/", 0, "youtube.com/".length())
            || value.regionMatches(true, 0, "www.youtube.com/", 0, "www.youtube.com/".length())) {
            urlCandidate = "https://" + value;
        }

        if (urlCandidate.startsWith("https://") || urlCandidate.startsWith("http://")) {
            return lookupUrlFromYouTubeUrl(urlCandidate);
        }

        String handle = value.startsWith("@") ? value.substring(1) : value.replaceAll("\\s+", "");
        return handleUrl(handle);
    }

    private static String lookupUrlFromYouTubeUrl(String input) {
        try {
            URI uri = URI.create(input);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("youtube.com") || host.endsWith(".youtube.com"))) {
                throw new IllegalArgumentException("Use a YouTube channel URL");
            }

            String[] parts = uri.getPath().split("/");
            String first = parts.length > 1 ? parts[1] : "";
            String second = parts.length > 2 ? parts[2] : "";

            if ("channel".equals(first) && CHANNEL_ID.matcher(second).matches()) {
                return "https://www.youtube.com/channel/" + second;
            }
            if (first.startsWith("@")) {
                return handleUrl(first.substring(1));
            }
            if (("user".equals(first) || "c".equals(first)) && !second.isEmpty()) {
                return legacyUrl(first, second);
            }
            throw new IllegalArgumentException("Use a YouTube channel URL, not a video or playlist URL");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Use a valid YouTube channel URL", error);
        }
    }

    private static String handleUrl(String handle) {
        String value = handle == null ? "" : handle.trim();
        if (value.length() < 3 || value.length() > 30 || value.matches(".*[\\s/?#@].*")) {
            throw new IllegalArgumentException("Use an exact channel name or its unique @handle");
        }
        return "https://www.youtube.com/@" + encodePathSegment(value);
    }

    private static String legacyUrl(String type, String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 100 || value.matches(".*[\\s/?#].*")) {
            throw new IllegalArgumentException("Use a valid YouTube channel URL");
        }
        return "https://www.youtube.com/" + type + "/" + encodePathSegment(value);
    }

    private static String encodePathSegment(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is unavailable", impossible);
        }
    }

    static ResolvedChannel parseVerifiedPage(String html) {
        String page = html == null ? "" : html;
        Matcher channelMatcher = CHANNEL_URL_IN_PAGE.matcher(page);
        String channelId = channelMatcher.find() ? channelMatcher.group(1) : "";

        // YouTube's mobile/tablet response sometimes keeps the channel ID only
        // inside escaped bootstrap data. Only accept that fallback when the
        // document also proves it is a canonical YouTube channel page.
        if (channelId.isEmpty() && CHANNEL_PAGE_CANONICAL.matcher(page).find()) {
            channelId = firstGroup(EXTERNAL_CHANNEL_ID, page);
        }
        if (channelId.isEmpty()) {
            throw new IllegalArgumentException("That exact YouTube channel could not be verified");
        }

        String title = firstGroup(OG_TITLE, page);
        String thumbnailUrl = firstGroup(OG_IMAGE, page);
        if (title.isEmpty()) title = "YouTube Channel";

        return new ResolvedChannel(
            channelId,
            "https://www.youtube.com/channel/" + channelId,
            title,
            thumbnailUrl.isEmpty() ? null : thumbnailUrl
        );
    }

    private static String firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    static final class ResolvedChannel {
        final String channelId;
        final String canonicalUrl;
        final String title;
        final String thumbnailUrl;

        ResolvedChannel(String channelId, String canonicalUrl, String title, String thumbnailUrl) {
            this.channelId = channelId;
            this.canonicalUrl = canonicalUrl;
            this.title = title;
            this.thumbnailUrl = thumbnailUrl;
        }
    }
}
