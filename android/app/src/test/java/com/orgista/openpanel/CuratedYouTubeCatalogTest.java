package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CuratedYouTubeCatalogTest {
    @Test
    public void resolvesApprovedNamesHandlesAndCanonicalIds() {
        assertEquals(
            "UCfPyVJEBD7Di1YYjTdS2v8g",
            CuratedYouTubeCatalog.match("Homeschool Pop", "https://www.youtube.com/@HomeschoolPop").channelId
        );
        assertEquals(
            "UCsXVk37bltHxD1rDPwtNM8Q",
            CuratedYouTubeCatalog.match("@kurzgesagt", "https://www.youtube.com/@kurzgesagt").channelId
        );
        assertNull(CuratedYouTubeCatalog.match("Unknown Channel", "https://www.youtube.com/@UnknownChannel"));
    }

    @Test
    public void providesPlayableVerifiedFallbackVideos() {
        CuratedYouTubeCatalog.Channel channel =
            CuratedYouTubeCatalog.forChannelId("UCfPyVJEBD7Di1YYjTdS2v8g");
        assertEquals("Homeschool Pop", channel.title);
        assertTrue(channel.videos.size() >= 3);
        for (CuratedYouTubeCatalog.Video video : channel.videos) {
            assertEquals(11, video.videoId.length());
            assertTrue(video.thumbnailUrl.startsWith("https://i.ytimg.com/vi/"));
        }
    }
}
