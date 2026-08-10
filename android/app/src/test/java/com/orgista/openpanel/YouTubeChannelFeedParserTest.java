package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class YouTubeChannelFeedParserTest {
    private static final String CHANNEL_ID = "UCfPyVJEBD7Di1YYjTdS2v8g";

    @Test
    public void parsesValidatedChannelVideos() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<feed xmlns=\"http://www.w3.org/2005/Atom\""
            + " xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\""
            + " xmlns:media=\"http://search.yahoo.com/mrss/\">"
            + "<yt:channelId>" + CHANNEL_ID.substring(2) + "</yt:channelId>"
            + "<title>Homeschool Pop</title>"
            + "<entry><yt:videoId>Rf71QRZtVME</yt:videoId>"
            + "<yt:channelId>" + CHANNEL_ID + "</yt:channelId>"
            + "<title>School Supplies in Spanish for Kids</title>"
            + "<published>2026-07-21T14:01:06+00:00</published>"
            + "<media:group><media:thumbnail url=\"https://i3.ytimg.com/vi/Rf71QRZtVME/hqdefault.jpg\"/>"
            + "</media:group></entry></feed>";

        YouTubeChannelFeedParser.ParsedFeed feed =
            YouTubeChannelFeedParser.parse(xml, CHANNEL_ID);

        assertEquals("Homeschool Pop", feed.channelTitle);
        assertEquals(1, feed.videos.size());
        assertEquals("Rf71QRZtVME", feed.videos.get(0).videoId);
        assertEquals("School Supplies in Spanish for Kids", feed.videos.get(0).title);
        assertEquals("https://i3.ytimg.com/vi/Rf71QRZtVME/hqdefault.jpg", feed.videos.get(0).thumbnailUrl);
        assertEquals("2026-07-21T14:01:06+00:00", feed.videos.get(0).publishedAt);
    }

    @Test
    public void rejectsInvalidChannelIdsAndForeignEntries() {
        assertThrows(
            IllegalArgumentException.class,
            () -> YouTubeChannelFeedParser.feedUrl("@homeschoolpop")
        );

        String xml = "<feed xmlns=\"http://www.w3.org/2005/Atom\""
            + " xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\">"
            + "<yt:channelId>" + CHANNEL_ID.substring(2) + "</yt:channelId><title>Homeschool Pop</title>"
            + "<entry><yt:videoId>Rf71QRZtVME</yt:videoId>"
            + "<yt:channelId>AAAAAAAAAAAAAAAAAAAAAA</yt:channelId>"
            + "<title>Wrong channel</title></entry></feed>";

        assertEquals(0, YouTubeChannelFeedParser.parse(xml, CHANNEL_ID).videos.size());

        String wrongFeed = "<feed xmlns=\"http://www.w3.org/2005/Atom\""
            + " xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\">"
            + "<yt:channelId>UCAAAAAAAAAAAAAAAAAAAAAA</yt:channelId>"
            + "<title>Wrong channel</title></feed>";
        assertThrows(
            IllegalArgumentException.class,
            () -> YouTubeChannelFeedParser.parse(wrongFeed, CHANNEL_ID)
        );
    }

    @Test
    public void replacesUntrustedArtworkWithYouTubesVideoThumbnail() {
        String xml = "<feed xmlns=\"http://www.w3.org/2005/Atom\""
            + " xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\""
            + " xmlns:media=\"http://search.yahoo.com/mrss/\">"
            + "<yt:channelId>" + CHANNEL_ID.substring(2) + "</yt:channelId><title>Homeschool Pop</title>"
            + "<entry><yt:videoId>PI9tlYxluX0</yt:videoId>"
            + "<yt:channelId>" + CHANNEL_ID + "</yt:channelId>"
            + "<title>Cactus Facts for Kids</title>"
            + "<media:thumbnail url=\"https://example.com/untrusted.jpg\"/>"
            + "</entry></feed>";

        YouTubeChannelFeedParser.ParsedFeed feed =
            YouTubeChannelFeedParser.parse(xml, CHANNEL_ID);

        assertEquals(
            "https://i.ytimg.com/vi/PI9tlYxluX0/hqdefault.jpg",
            feed.videos.get(0).thumbnailUrl
        );
    }

    @Test
    public void rejectsDocumentTypeAndEntityDeclarationsBeforeParsing() {
        String doctype = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE feed [<!ENTITY external SYSTEM \"file:///etc/passwd\">]>"
            + "<feed xmlns=\"http://www.w3.org/2005/Atom\""
            + " xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\">"
            + "<yt:channelId>" + CHANNEL_ID.substring(2) + "</yt:channelId>"
            + "<title>&external;</title></feed>";

        assertThrows(
            IllegalArgumentException.class,
            () -> YouTubeChannelFeedParser.parse(doctype, CHANNEL_ID)
        );
    }
}
