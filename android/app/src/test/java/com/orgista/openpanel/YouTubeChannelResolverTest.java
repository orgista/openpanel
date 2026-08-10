package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class YouTubeChannelResolverTest {
    @Test
    public void turnsAReadableNameIntoAnExactHandleCandidate() {
        assertEquals(
            "https://www.youtube.com/@GoogleDevelopers",
            YouTubeChannelResolver.lookupUrl("Google Developers")
        );
        assertEquals(
            "https://www.youtube.com/@GoogleDevelopers",
            YouTubeChannelResolver.lookupUrl("@GoogleDevelopers")
        );
    }

    @Test
    public void acceptsCanonicalAndLegacyChannelReferences() {
        assertEquals(
            "https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw",
            YouTubeChannelResolver.lookupUrl("UC_x5XG1OV2P6uZZ5FSM9Ttw")
        );
        assertEquals(
            "https://www.youtube.com/@GoogleDevelopers",
            YouTubeChannelResolver.lookupUrl("youtube.com/@GoogleDevelopers/videos")
        );
        assertEquals(
            "https://www.youtube.com/user/GoogleDevelopers",
            YouTubeChannelResolver.lookupUrl("https://www.youtube.com/user/GoogleDevelopers")
        );
    }

    @Test
    public void rejectsLookalikeHostsAndNonChannelUrls() {
        assertThrows(
            IllegalArgumentException.class,
            () -> YouTubeChannelResolver.lookupUrl("https://youtube.example/@GoogleDevelopers")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> YouTubeChannelResolver.lookupUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        );
    }

    @Test
    public void extractsVerifiedCanonicalMetadata() {
        String html = "<html><head>"
            + "<meta property=\"og:title\" content=\"Google for Developers\">"
            + "<meta property=\"og:image\" content=\"https://example.com/avatar.jpg\">"
            + "</head><body>https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw</body></html>";

        YouTubeChannelResolver.ResolvedChannel result = YouTubeChannelResolver.parseVerifiedPage(html);

        assertEquals("UC_x5XG1OV2P6uZZ5FSM9Ttw", result.channelId);
        assertEquals("https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw", result.canonicalUrl);
        assertEquals("Google for Developers", result.title);
        assertEquals("https://example.com/avatar.jpg", result.thumbnailUrl);
    }

    @Test
    public void extractsHomeschoolPopFromEscapedYouTubePageData() {
        String html = "<html><head>"
            + "<link rel=\"canonical\" href=\"https://www.youtube.com/@homeschoolpop\">"
            + "<meta property=\"og:title\" content=\"Homeschool Pop\">"
            + "<meta property=\"og:image\" content=\"https://example.com/homeschool-pop.jpg\">"
            + "</head><body>externalId\\x22:\\x22UCfPyVJEBD7Di1YYjTdS2v8g</body></html>";

        YouTubeChannelResolver.ResolvedChannel result = YouTubeChannelResolver.parseVerifiedPage(html);

        assertEquals("UCfPyVJEBD7Di1YYjTdS2v8g", result.channelId);
        assertEquals("https://www.youtube.com/channel/UCfPyVJEBD7Di1YYjTdS2v8g", result.canonicalUrl);
        assertEquals("Homeschool Pop", result.title);
        assertEquals("https://example.com/homeschool-pop.jpg", result.thumbnailUrl);
    }

    @Test
    public void rejectsPagesWithoutAChannelIdentity() {
        assertThrows(
            IllegalArgumentException.class,
            () -> YouTubeChannelResolver.parseVerifiedPage("<html>Channel unavailable</html>")
        );
    }

    @Test
    public void permitsVerifiedPagesWithoutArtwork() {
        YouTubeChannelResolver.ResolvedChannel result = YouTubeChannelResolver.parseVerifiedPage(
            "<meta property=\"og:title\" content=\"Example Channel\">"
                + "https://www.youtube.com/channel/UCAAAAAAAAAAAAAAAAAAAAAA"
        );

        assertEquals("Example Channel", result.title);
        assertNull(result.thumbnailUrl);
    }
}
