package com.orgista.openpanel;

import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Parses YouTube's small public Atom channel feed without using Data API credentials. */
final class YouTubeChannelFeedParser {
    private static final Pattern CHANNEL_ID = Pattern.compile("^UC[A-Za-z0-9_-]{22}$");
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final int MAX_FEED_BYTES = 1_000_000;
    private static final int MAX_VIDEOS = 24;

    private YouTubeChannelFeedParser() {}

    static String feedUrl(String channelId) {
        String value = channelId == null ? "" : channelId.trim();
        if (!CHANNEL_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("This channel must be re-added before its videos can be explored");
        }
        return "https://www.youtube.com/feeds/videos.xml?channel_id=" + value;
    }

    static ParsedFeed parse(String xml, String expectedChannelId) {
        String channelId = expectedChannelId == null ? "" : expectedChannelId.trim();
        if (!CHANNEL_ID.matcher(channelId).matches()) {
            throw new IllegalArgumentException("Use a verified YouTube channel ID");
        }
        String source = xml == null ? "" : xml;
        if (source.isEmpty() || source.length() > MAX_FEED_BYTES) {
            throw new IllegalArgumentException("YouTube returned an invalid channel feed");
        }
        String declarationScan = source.toUpperCase(Locale.ROOT);
        if (declarationScan.contains("<!DOCTYPE") || declarationScan.contains("<!ENTITY")) {
            throw new IllegalArgumentException("YouTube returned an invalid channel feed");
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            // Android TV WebView builds use different XML providers. Some reject
            // one or more Xerces feature URIs before parsing an otherwise valid
            // YouTube feed. Keep the hard declaration scan + empty resolver as
            // the security boundary, and apply provider features when supported.
            setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            Document document = builder.parse(new InputSource(new StringReader(source)));
            Element root = document.getDocumentElement();
            String feedChannelId = firstDirectChildText(root, "channelId");
            // YouTube's feed root currently omits the leading "UC", while each
            // entry carries the complete canonical ID. Accept either form.
            if (!channelId.equals(feedChannelId) && !channelId.substring(2).equals(feedChannelId)) {
                throw new IllegalArgumentException("YouTube returned a different channel feed");
            }
            String channelTitle = firstDirectChildText(root, "title");
            if (channelTitle.isEmpty()) channelTitle = "YouTube Channel";

            NodeList entries = document.getElementsByTagNameNS("*", "entry");
            List<FeedVideo> videos = new ArrayList<>();
            Set<String> seenVideoIds = new HashSet<>();

            for (int index = 0; index < entries.getLength() && videos.size() < MAX_VIDEOS; index++) {
                if (!(entries.item(index) instanceof Element)) continue;
                Element entry = (Element) entries.item(index);
                String entryChannelId = firstText(entry, "channelId");
                String videoId = firstText(entry, "videoId");
                if (!channelId.equals(entryChannelId) || !VIDEO_ID.matcher(videoId).matches()) continue;
                if (!seenVideoIds.add(videoId)) continue;

                String title = firstText(entry, "title");
                String publishedAt = firstText(entry, "published");
                String thumbnailUrl = firstAttribute(entry, "thumbnail", "url");
                if (!isTrustedThumbnail(thumbnailUrl)) {
                    thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
                }
                videos.add(new FeedVideo(
                    videoId,
                    title.isEmpty() ? "YouTube Video" : title,
                    thumbnailUrl,
                    publishedAt
                ));
            }
            return new ParsedFeed(channelTitle, videos);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("YouTube returned an invalid channel feed", error);
        }
    }

    private static void setFeatureIfSupported(
        DocumentBuilderFactory factory,
        String feature,
        boolean enabled
    ) {
        try {
            factory.setFeature(feature, enabled);
        } catch (Exception ignored) {
            // The feed is still protected by the declaration scan, disabled
            // entity expansion, and an entity resolver that returns no data.
        }
    }

    private static String firstDirectChildText(Element parent, String localName) {
        if (parent == null) return "";
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof Element && localName.equals(child.getLocalName())) {
                String text = child.getTextContent();
                return text == null ? "" : text.trim();
            }
            child = child.getNextSibling();
        }
        return "";
    }

    private static String firstText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0 || nodes.item(0) == null) return "";
        String text = nodes.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    private static String firstAttribute(Element parent, String localName, String attribute) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0 || !(nodes.item(0) instanceof Element)) return "";
        return ((Element) nodes.item(0)).getAttribute(attribute).trim();
    }

    private static boolean isTrustedThumbnail(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme())
                && (host.equals("i.ytimg.com") || host.matches("i[0-9]+\\.ytimg\\.com"));
        } catch (Exception ignored) {
            return false;
        }
    }

    static final class FeedVideo {
        final String videoId;
        final String title;
        final String thumbnailUrl;
        final String publishedAt;

        FeedVideo(String videoId, String title, String thumbnailUrl, String publishedAt) {
            this.videoId = videoId;
            this.title = title;
            this.thumbnailUrl = thumbnailUrl;
            this.publishedAt = publishedAt;
        }
    }

    static final class ParsedFeed {
        final String channelTitle;
        final List<FeedVideo> videos;

        ParsedFeed(String channelTitle, List<FeedVideo> videos) {
            this.channelTitle = channelTitle;
            this.videos = videos;
        }
    }
}
