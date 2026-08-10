package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.InputStream;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OpenPanelInstrumentedTest {
    @Test
    public void applicationIdMatchesCurrentBuildVariant() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.orgista.openpanel.debug", appContext.getPackageName());
    }

    @Test
    public void packagedWebApplicationIsPresent() throws Exception {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (InputStream index = appContext.getAssets().open("public/index.html")) {
            assertTrue(index.available() > 0);
        }
    }

    @Test
    public void tvLauncherAndBannerArePackaged() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent leanbackIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setPackage(appContext.getPackageName());
        ResolveInfo leanbackActivity = appContext.getPackageManager().resolveActivity(leanbackIntent, 0);

        assertNotNull(leanbackActivity);
        assertTrue(appContext.getApplicationInfo().banner != 0);
    }

    @Test
    public void youtubeFeedParserWorksWithAndroidXmlProvider() {
        String channelId = "UCfPyVJEBD7Di1YYjTdS2v8g";
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<feed xmlns=\"http://www.w3.org/2005/Atom\""
                + " xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\""
                + " xmlns:media=\"http://search.yahoo.com/mrss/\">"
                + "<yt:channelId>fPyVJEBD7Di1YYjTdS2v8g</yt:channelId>"
                + "<title>Homeschool Pop</title>"
                + "<entry><yt:videoId>Rf71QRZtVME</yt:videoId>"
                + "<yt:channelId>" + channelId + "</yt:channelId>"
                + "<title>School Supplies in Spanish for Kids</title>"
                + "<published>2026-07-21T14:01:06+00:00</published>"
                + "<media:thumbnail url=\"https://i3.ytimg.com/vi/Rf71QRZtVME/hqdefault.jpg\"/>"
                + "</entry></feed>";

        YouTubeChannelFeedParser.ParsedFeed feed =
                YouTubeChannelFeedParser.parse(xml, channelId);

        assertEquals("Homeschool Pop", feed.channelTitle);
        assertEquals(1, feed.videos.size());
        assertEquals("Rf71QRZtVME", feed.videos.get(0).videoId);
    }

}
