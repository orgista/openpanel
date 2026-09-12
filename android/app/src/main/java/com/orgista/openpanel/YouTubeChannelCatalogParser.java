package com.orgista.openpanel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the public YouTube channel videos page and its browse continuations.
 * This is the API-key-free catalog path used after the small Atom feed runs out.
 */
final class YouTubeChannelCatalogParser {
    private static final Pattern CHANNEL_ID = Pattern.compile("^UC[A-Za-z0-9_-]{22}$");
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern API_KEY = Pattern.compile("^[A-Za-z0-9_-]{20,64}$");
    private static final Pattern CLIENT_VERSION = Pattern.compile("^[A-Za-z0-9._-]{4,64}$");
    private static final Pattern API_KEY_IN_PAGE = Pattern.compile(
        "\\\"INNERTUBE_API_KEY\\\":\\\"([^\\\"]+)\\\""
    );
    private static final Pattern CLIENT_VERSION_IN_PAGE = Pattern.compile(
        "\\\"INNERTUBE_CLIENT_VERSION\\\":\\\"([^\\\"]+)\\\""
    );
    private static final String INITIAL_DATA_MARKER = "var ytInitialData = '";
    private static final String[] VIDEO_RENDERERS = {
        "compactVideoRenderer",
        "videoWithContextRenderer",
        "gridVideoRenderer"
    };
    private static final int MAX_PAGE_CHARS = 2_000_000;
    private static final int MAX_JSON_CHARS = 1_500_000;
    private static final int MAX_VIDEOS_PER_PAGE = 60;
    private static final int MAX_CONTINUATION_CHARS = 12_000;

    private YouTubeChannelCatalogParser() {}

    static String pageUrl(String channelId) {
        String value = normalizedChannelId(channelId);
        return "https://www.youtube.com/channel/" + value + "/videos?view=0&sort=dd&flow=grid";
    }

    static InitialPage parseInitialPage(String html, String expectedChannelId) {
        String channelId = normalizedChannelId(expectedChannelId);
        String page = boundedPage(html);
        if (!page.contains("/channel/" + channelId)) {
            throw new IllegalArgumentException("YouTube returned a different channel page");
        }

        String apiKey = firstGroup(API_KEY_IN_PAGE, page);
        String clientVersion = firstGroup(CLIENT_VERSION_IN_PAGE, page);
        if (!API_KEY.matcher(apiKey).matches() || !CLIENT_VERSION.matcher(clientVersion).matches()) {
            throw new IllegalArgumentException("YouTube channel pagination is unavailable");
        }

        int marker = page.indexOf(INITIAL_DATA_MARKER);
        if (marker < 0) {
            throw new IllegalArgumentException("YouTube channel catalog is unavailable");
        }
        int dataStart = marker + INITIAL_DATA_MARKER.length();
        int dataEnd = page.indexOf("';", dataStart);
        if (dataEnd < 0 || dataEnd - dataStart > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("YouTube returned an invalid channel catalog");
        }

        String json = decodeJavaScriptString(page.substring(dataStart, dataEnd));
        CatalogPage catalog = parseCatalogJson(json);
        if (catalog.videos.isEmpty()) {
            throw new IllegalArgumentException("This channel has no browsable videos");
        }
        return new InitialPage(
            catalog.videos,
            catalog.continuation,
            apiKey,
            clientVersion
        );
    }

    static CatalogPage parseContinuation(String json) {
        return parseCatalogJson(boundedPage(json));
    }

    static String continuationUrl(String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        if (!API_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("YouTube channel pagination expired");
        }
        return "https://www.youtube.com/youtubei/v1/browse?key=" + value;
    }

    static String validateClientVersion(String clientVersion) {
        String value = clientVersion == null ? "" : clientVersion.trim();
        if (!CLIENT_VERSION.matcher(value).matches()) {
            throw new IllegalArgumentException("YouTube channel pagination expired");
        }
        return value;
    }

    static String validateContinuation(String continuation) {
        String value = continuation == null ? "" : continuation.trim();
        if (value.isEmpty() || value.length() > MAX_CONTINUATION_CHARS) {
            throw new IllegalArgumentException("YouTube channel pagination expired");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("YouTube channel pagination expired");
            }
        }
        return value;
    }

    private static CatalogPage parseCatalogJson(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("YouTube returned an invalid channel catalog");
        }

        List<CatalogVideo> videos = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int searchFrom = 0;
        int lastRendererEnd = 0;
        while (videos.size() < MAX_VIDEOS_PER_PAGE) {
            RendererMatch match = nextRenderer(json, searchFrom);
            if (match == null) break;
            int objectStart = skipWhitespace(json, match.markerEnd);
            if (objectStart >= json.length() || json.charAt(objectStart) != '{') {
                searchFrom = match.markerEnd;
                continue;
            }
            int objectEnd = matchingObjectEnd(json, objectStart);
            if (objectEnd < 0) {
                throw new IllegalArgumentException("YouTube returned an invalid channel catalog");
            }
            String renderer = json.substring(objectStart, objectEnd + 1);
            String videoId = jsonStringProperty(renderer, "videoId", 0);
            if (VIDEO_ID.matcher(videoId).matches() && seen.add(videoId)) {
                String titleProperty = "videoWithContextRenderer".equals(match.name)
                    ? "headline"
                    : "title";
                String title = textProperty(renderer, titleProperty);
                String publishedAt = textProperty(renderer, "publishedTimeText");
                videos.add(new CatalogVideo(
                    videoId,
                    title.isEmpty() ? "YouTube Video" : title,
                    "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg",
                    publishedAt
                ));
            }
            lastRendererEnd = objectEnd + 1;
            searchFrom = objectEnd + 1;
        }

        String continuation = continuationAfter(json, lastRendererEnd);
        return new CatalogPage(videos, continuation);
    }

    private static RendererMatch nextRenderer(String json, int from) {
        RendererMatch next = null;
        for (String name : VIDEO_RENDERERS) {
            String marker = "\"" + name + "\":";
            int index = json.indexOf(marker, from);
            if (index >= 0 && (next == null || index < next.index)) {
                next = new RendererMatch(name, index, index + marker.length());
            }
        }
        return next;
    }

    private static String continuationAfter(String json, int from) {
        String marker = "\"continuationCommand\":";
        int command = json.indexOf(marker, Math.max(0, from));
        while (command >= 0) {
            int objectStart = skipWhitespace(json, command + marker.length());
            if (objectStart < json.length() && json.charAt(objectStart) == '{') {
                int objectEnd = matchingObjectEnd(json, objectStart);
                if (objectEnd > objectStart) {
                    String token = jsonStringProperty(
                        json.substring(objectStart, objectEnd + 1),
                        "token",
                        0
                    );
                    if (!token.isEmpty()) return validateContinuation(token);
                }
            }
            command = json.indexOf(marker, command + marker.length());
        }
        return "";
    }

    private static String textProperty(String object, String property) {
        String marker = "\"" + property + "\":";
        int propertyIndex = object.indexOf(marker);
        if (propertyIndex < 0) return "";
        int valueStart = skipWhitespace(object, propertyIndex + marker.length());
        if (valueStart >= object.length()) return "";
        if (object.charAt(valueStart) == '"') {
            return decodedStringAt(object, valueStart).value;
        }
        if (object.charAt(valueStart) != '{') return "";
        int valueEnd = matchingObjectEnd(object, valueStart);
        if (valueEnd < 0) return "";
        String value = object.substring(valueStart, valueEnd + 1);
        String simpleText = jsonStringProperty(value, "simpleText", 0);
        return simpleText.isEmpty() ? jsonStringProperty(value, "text", 0) : simpleText;
    }

    private static String jsonStringProperty(String object, String property, int from) {
        String marker = "\"" + property + "\":";
        int propertyIndex = object.indexOf(marker, from);
        if (propertyIndex < 0) return "";
        int valueStart = skipWhitespace(object, propertyIndex + marker.length());
        if (valueStart >= object.length() || object.charAt(valueStart) != '"') return "";
        return decodedStringAt(object, valueStart).value;
    }

    private static DecodedString decodedStringAt(String source, int quoteIndex) {
        StringBuilder output = new StringBuilder();
        int index = quoteIndex + 1;
        while (index < source.length()) {
            char character = source.charAt(index++);
            if (character == '"') return new DecodedString(output.toString(), index);
            if (character != '\\') {
                output.append(character);
                continue;
            }
            if (index >= source.length()) break;
            char escape = source.charAt(index++);
            switch (escape) {
                case '"': output.append('"'); break;
                case '\\': output.append('\\'); break;
                case '/': output.append('/'); break;
                case 'b': output.append('\b'); break;
                case 'f': output.append('\f'); break;
                case 'n': output.append('\n'); break;
                case 'r': output.append('\r'); break;
                case 't': output.append('\t'); break;
                case 'u':
                    if (index + 4 > source.length()) {
                        throw new IllegalArgumentException("YouTube returned invalid text data");
                    }
                    output.append((char) parseHex(source, index, 4));
                    index += 4;
                    break;
                default: output.append(escape);
            }
        }
        throw new IllegalArgumentException("YouTube returned invalid text data");
    }

    private static String decodeJavaScriptString(String source) {
        StringBuilder output = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char character = source.charAt(index++);
            if (character != '\\') {
                output.append(character);
                continue;
            }
            if (index >= source.length()) break;
            char escape = source.charAt(index++);
            switch (escape) {
                case 'x':
                    if (index + 2 > source.length()) {
                        throw new IllegalArgumentException("YouTube returned invalid catalog data");
                    }
                    output.append((char) parseHex(source, index, 2));
                    index += 2;
                    break;
                case 'u':
                    if (index + 4 > source.length()) {
                        throw new IllegalArgumentException("YouTube returned invalid catalog data");
                    }
                    output.append((char) parseHex(source, index, 4));
                    index += 4;
                    break;
                case 'n': output.append('\n'); break;
                case 'r': output.append('\r'); break;
                case 't': output.append('\t'); break;
                case 'b': output.append('\b'); break;
                case 'f': output.append('\f'); break;
                default: output.append(escape);
            }
        }
        return output.toString();
    }

    private static int parseHex(String source, int start, int length) {
        int value = 0;
        for (int index = start; index < start + length; index++) {
            int digit = Character.digit(source.charAt(index), 16);
            if (digit < 0) throw new IllegalArgumentException("YouTube returned invalid catalog data");
            value = (value << 4) | digit;
        }
        return value;
    }

    private static int matchingObjectEnd(String source, int objectStart) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = objectStart; index < source.length(); index++) {
            char character = source.charAt(index);
            if (inString) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') inString = false;
                continue;
            }
            if (character == '"') inString = true;
            else if (character == '{') depth++;
            else if (character == '}' && --depth == 0) return index;
        }
        return -1;
    }

    private static int skipWhitespace(String source, int start) {
        int index = start;
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        return index;
    }

    private static String normalizedChannelId(String channelId) {
        String value = channelId == null ? "" : channelId.trim();
        if (!CHANNEL_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("This channel must be re-added before its videos can be explored");
        }
        return value;
    }

    private static String boundedPage(String page) {
        String value = page == null ? "" : page;
        if (value.isEmpty() || value.length() > MAX_PAGE_CHARS) {
            throw new IllegalArgumentException("YouTube returned an invalid channel catalog");
        }
        return value;
    }

    private static String firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    static class CatalogPage {
        final List<CatalogVideo> videos;
        final String continuation;

        CatalogPage(List<CatalogVideo> videos, String continuation) {
            this.videos = videos;
            this.continuation = continuation;
        }
    }

    static final class InitialPage extends CatalogPage {
        final String apiKey;
        final String clientVersion;

        InitialPage(
            List<CatalogVideo> videos,
            String continuation,
            String apiKey,
            String clientVersion
        ) {
            super(videos, continuation);
            this.apiKey = apiKey;
            this.clientVersion = clientVersion;
        }
    }

    static final class CatalogVideo {
        final String videoId;
        final String title;
        final String thumbnailUrl;
        final String publishedAt;

        CatalogVideo(String videoId, String title, String thumbnailUrl, String publishedAt) {
            this.videoId = videoId;
            this.title = title;
            this.thumbnailUrl = thumbnailUrl;
            this.publishedAt = publishedAt;
        }
    }

    private static final class RendererMatch {
        final String name;
        final int index;
        final int markerEnd;

        RendererMatch(String name, int index, int markerEnd) {
            this.name = name;
            this.index = index;
            this.markerEnd = markerEnd;
        }
    }

    private static final class DecodedString {
        final String value;
        final int end;

        DecodedString(String value, int end) {
            this.value = value;
            this.end = end;
        }
    }
}
