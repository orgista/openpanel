package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YouTubeChannelCatalogParserTest {
    private static final String CHANNEL_ID = "UCfPyVJEBD7Di1YYjTdS2v8g";

    @Test
    public void parsesInitialPublicVideosPageAndContinuationConfig() {
        String initialJson = "{\"contents\":{\"richGridRenderer\":{\"contents\":["
            + compactVideo("Rf71QRZtVME", "School Supplies in Spanish for Kids", "6 days ago") + ","
            + compactVideo("PI9tlYxluX0", "Cactus Facts for Kids", "3 weeks ago") + ","
            + continuation("older-page-token")
            + "]}}}";
        String html = "<link rel=\"canonical\" href=\"https://www.youtube.com/channel/"
            + CHANNEL_ID + "/videos\">"
            + "<script>ytcfg.set({\"INNERTUBE_API_KEY\":\"public_bootstrap_key_1234567890\","
            + "\"INNERTUBE_CLIENT_VERSION\":\"2.20260727.01.00\"});</script>"
            + "<script>var ytInitialData = '" + javascriptEscape(initialJson) + "';</script>";

        YouTubeChannelCatalogParser.InitialPage page =
            YouTubeChannelCatalogParser.parseInitialPage(html, CHANNEL_ID);

        assertEquals(2, page.videos.size());
        assertEquals("Rf71QRZtVME", page.videos.get(0).videoId);
        assertEquals("School Supplies in Spanish for Kids", page.videos.get(0).title);
        assertEquals("6 days ago", page.videos.get(0).publishedAt);
        assertEquals("older-page-token", page.continuation);
        assertEquals("public_bootstrap_key_1234567890", page.apiKey);
        assertEquals("2.20260727.01.00", page.clientVersion);
    }

    @Test
    public void parsesOlderVideoWithContextPageAndNextToken() {
        String response = "{\"onResponseReceivedActions\":[{\"appendContinuationItemsAction\":{"
            + "\"continuationItems\":["
            + contextualVideo("jG9804gDDx8", "My Favorite Birthday Present as a Kid!", "6 months ago") + ","
            + contextualVideo("DmAvDdHLL8I", "Learn the U.S. States: The Southeast Region", "6 months ago") + ","
            + continuation("another-older-page-token")
            + "]}}]}";

        YouTubeChannelCatalogParser.CatalogPage page =
            YouTubeChannelCatalogParser.parseContinuation(response);

        assertEquals(2, page.videos.size());
        assertEquals("jG9804gDDx8", page.videos.get(0).videoId);
        assertEquals("My Favorite Birthday Present as a Kid!", page.videos.get(0).title);
        assertEquals("another-older-page-token", page.continuation);
    }

    @Test
    public void decodesEscapedTitlesAndBuildsOnlyFixedYouTubeEndpoints() {
        String response = "{\"videoWithContextRenderer\":{"
            + "\"videoId\":\"5V3ZdTFxoX4\","
            + "\"headline\":{\"runs\":[{\"text\":\"Similes \\\"and\\\" Metaphors \\u0026 More\"}]},"
            + "\"publishedTimeText\":{\"simpleText\":\"7 months ago\"}}}";

        YouTubeChannelCatalogParser.CatalogPage page =
            YouTubeChannelCatalogParser.parseContinuation(response);

        assertEquals("Similes \"and\" Metaphors & More", page.videos.get(0).title);
        assertEquals(
            "https://www.youtube.com/channel/" + CHANNEL_ID + "/videos?view=0&sort=dd&flow=grid",
            YouTubeChannelCatalogParser.pageUrl(CHANNEL_ID)
        );
        assertTrue(
            YouTubeChannelCatalogParser.continuationUrl("public_bootstrap_key_1234567890")
                .startsWith("https://www.youtube.com/youtubei/v1/browse?key=")
        );
    }

    @Test
    public void rejectsWrongChannelsAndUnsafeCursorValues() {
        String html = "<a href=\"/channel/UCAAAAAAAAAAAAAAAAAAAAAA\"></a>"
            + "<script>ytcfg.set({\"INNERTUBE_API_KEY\":\"public_bootstrap_key_1234567890\","
            + "\"INNERTUBE_CLIENT_VERSION\":\"2.20260727.01.00\"});</script>"
            + "<script>var ytInitialData = '" + javascriptEscape("{}") + "';</script>";

        assertThrows(
            IllegalArgumentException.class,
            () -> YouTubeChannelCatalogParser.parseInitialPage(html, CHANNEL_ID)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> YouTubeChannelCatalogParser.continuationUrl("https://example.com")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> YouTubeChannelCatalogParser.validateContinuation("bad\nheader")
        );
    }

    private static String compactVideo(String id, String title, String published) {
        return "{\"richItemRenderer\":{\"content\":{\"compactVideoRenderer\":{"
            + "\"videoId\":\"" + id + "\","
            + "\"title\":{\"runs\":[{\"text\":\"" + title + "\"}]},"
            + "\"publishedTimeText\":{\"runs\":[{\"text\":\"" + published + "\"}]}}}}}";
    }

    private static String contextualVideo(String id, String title, String published) {
        return "{\"richItemRenderer\":{\"content\":{\"videoWithContextRenderer\":{"
            + "\"videoId\":\"" + id + "\","
            + "\"headline\":{\"runs\":[{\"text\":\"" + title + "\"}]},"
            + "\"publishedTimeText\":{\"runs\":[{\"text\":\"" + published + "\"}]}}}}}";
    }

    private static String continuation(String token) {
        return "{\"continuationItemRenderer\":{\"continuationEndpoint\":{"
            + "\"continuationCommand\":{\"token\":\"" + token + "\"}}}}";
    }

    private static String javascriptEscape(String json) {
        return json
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("{", "\\x7b")
            .replace("}", "\\x7d")
            .replace("[", "\\x5b")
            .replace("]", "\\x5d")
            .replace("\"", "\\x22");
    }
}
