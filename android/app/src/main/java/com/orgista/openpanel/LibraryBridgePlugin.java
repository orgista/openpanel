package com.orgista.openpanel;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Xml;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "LibraryBridge")
public final class LibraryBridgePlugin extends Plugin {
    private static final long MAX_CATALOG_BYTES = 5L * 1024L * 1024L;
    private static final long MAX_PUBLICATION_BYTES = 1024L * 1024L * 1024L;
    private static final int NETWORK_TIMEOUT_MS = 20_000;
    // Derive the outbound User-Agent from the build version so it never drifts
    // from build.gradle's versionName again.
    private static final String USER_AGENT_OPDS =
        "OpenPanel/" + BuildConfig.VERSION_NAME + " (OPDS reader)";
    private static final String USER_AGENT_DOWNLOAD =
        "OpenPanel/" + BuildConfig.VERSION_NAME + " (institutional reader)";
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private LibraryRepository repository;

    @Override
    public void load() {
        repository = new LibraryRepository(getContext());
    }

    @PluginMethod
    public void listPublications(PluginCall call) {
        JSObject response = new JSObject();
        response.put("publications", publicPublications(repository.publications()));
        call.resolve(response);
    }

    @PluginMethod
    public void importPublication(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
            "application/epub+zip",
            "application/pdf",
            "application/audiobook+zip",
            "application/vnd.readium.lcp.license.v1.0+json",
            "audio/mpeg",
            "audio/mp4",
            "audio/aac"
        });
        try {
            startActivityForResult(call, intent, "publicationImportResult");
        } catch (RuntimeException error) {
            call.reject("The Android file picker could not open.", "LIBRARY_PICKER_FAILED", error);
        }
    }

    @ActivityCallback
    private void publicationImportResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        Uri uri = data == null ? null : data.getData();
        if (result.getResultCode() != Activity.RESULT_OK || uri == null) {
            call.reject("Publication import was cancelled.", "LIBRARY_IMPORT_CANCELLED");
            return;
        }
        ioExecutor.execute(() -> {
            File destination = null;
            try {
                String displayName = displayName(uri);
                String mediaType = getContext().getContentResolver().getType(uri);
                if (mediaType == null) mediaType = mediaTypeForName(displayName);
                if (isLcpLicense(mediaType, displayName)) {
                    throw new IllegalArgumentException("This Readium LCP loan requires the institution's licensed LCP connector.");
                }
                String format = formatFor(mediaType, displayName);
                String extension = extensionFor(format, displayName);
                destination = new File(repository.publicationsDirectory(), UUID.randomUUID() + extension);
                try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalArgumentException("The selected file could not be opened.");
                    copyLimited(input, destination, MAX_PUBLICATION_BYTES);
                }
                JSONObject publication = repository.addPublication(
                    destination,
                    stripExtension(displayName),
                    new JSONArray(),
                    mediaType,
                    format,
                    null,
                    null
                );
                JSObject response = new JSObject();
                response.put("publication", publicPublication(publication));
                call.resolve(response);
            } catch (Exception error) {
                if (destination != null) destination.delete();
                call.reject(userMessage(error, "The publication could not be imported."), "LIBRARY_IMPORT_FAILED", error);
            }
        });
    }

    @PluginMethod
    public void downloadPublication(PluginCall call) {
        String url = call.getString("url", "").trim();
        String title = call.getString("title", "Untitled publication");
        String mediaType = call.getString("mediaType", "application/epub+zip");
        String coverUrl = nullable(call.getString("coverUrl"));
        JSArray authors = call.getArray("authors", new JSArray());
        if (!isSafeHttpsUrl(url)) {
            call.reject("Publication downloads must use HTTPS.", "LIBRARY_HTTPS_REQUIRED");
            return;
        }
        if (isLcpLicense(mediaType, url)) {
            call.reject("This Readium LCP loan requires the institution's licensed LCP connector.", "LIBRARY_LCP_CONNECTOR_REQUIRED");
            return;
        }
        ioExecutor.execute(() -> {
            File destination = null;
            try {
                Download download = download(url, MAX_PUBLICATION_BYTES);
                String actualType = download.contentType == null ? mediaType : download.contentType;
                String format = formatFor(actualType, download.finalUrl);
                destination = new File(repository.publicationsDirectory(), UUID.randomUUID() + extensionFor(format, download.finalUrl));
                if (!download.file.renameTo(destination)) {
                    try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(download.file))) {
                        copyLimited(input, destination, MAX_PUBLICATION_BYTES);
                    }
                    download.file.delete();
                }
                JSONObject publication = repository.addPublication(destination, title, authors, actualType, format, coverUrl, url);
                JSObject response = new JSObject();
                response.put("publication", publicPublication(publication));
                call.resolve(response);
            } catch (Exception error) {
                if (destination != null) destination.delete();
                call.reject(userMessage(error, "The publication could not be downloaded."), "LIBRARY_DOWNLOAD_FAILED", error);
            }
        });
    }

    @PluginMethod
    public void openPublication(PluginCall call) {
        String id = call.getString("id", "");
        JSONObject publication = repository.publication(id);
        File file = repository.publicationFile(publication);
        if (publication == null || file == null) {
            call.reject("This publication is no longer stored on the device.", "LIBRARY_NOT_FOUND");
            return;
        }
        repository.recordOpened(id);
        Intent intent = new Intent(getContext(), LibraryReaderActivity.class);
        intent.putExtra(LibraryReaderActivity.EXTRA_PUBLICATION_ID, id);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void removePublication(PluginCall call) {
        String id = call.getString("id", "");
        JSObject response = new JSObject();
        response.put("removed", repository.removePublication(id));
        call.resolve(response);
    }

    @PluginMethod
    public void listCatalogs(PluginCall call) {
        JSObject response = new JSObject();
        response.put("catalogs", repository.catalogs());
        call.resolve(response);
    }

    @PluginMethod
    public void addCatalog(PluginCall call) {
        String url = call.getString("url", "").trim();
        String requestedTitle = call.getString("title", "").trim();
        if (!isSafeHttpsUrl(url)) {
            call.reject("OPDS catalogs must use HTTPS.", "LIBRARY_HTTPS_REQUIRED");
            return;
        }
        ioExecutor.execute(() -> {
            try {
                JSONObject page = fetchAndParseCatalog(url);
                String title = requestedTitle.isEmpty() ? page.optString("title", hostName(url)) : requestedTitle;
                JSONObject catalog = repository.addCatalog(title, page.optString("url", url));
                JSObject response = new JSObject();
                response.put("catalog", catalog);
                call.resolve(response);
            } catch (Exception error) {
                call.reject(userMessage(error, "That OPDS catalog could not be read."), "LIBRARY_CATALOG_FAILED", error);
            }
        });
    }

    @PluginMethod
    public void removeCatalog(PluginCall call) {
        JSObject response = new JSObject();
        response.put("removed", repository.removeCatalog(call.getString("id", "")));
        call.resolve(response);
    }

    @PluginMethod
    public void fetchCatalog(PluginCall call) {
        String url = call.getString("url", "").trim();
        if (!isSafeHttpsUrl(url)) {
            call.reject("OPDS catalogs must use HTTPS.", "LIBRARY_HTTPS_REQUIRED");
            return;
        }
        ioExecutor.execute(() -> {
            try {
                call.resolve(new JSObject(fetchAndParseCatalog(url).toString()));
            } catch (Exception error) {
                call.reject(userMessage(error, "That OPDS catalog could not be read."), "LIBRARY_CATALOG_FAILED", error);
            }
        });
    }

    private JSONObject fetchAndParseCatalog(String url) throws Exception {
        HttpPayload payload = fetchBytes(url, MAX_CATALOG_BYTES);
        String text = new String(payload.bytes, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{")) return parseOpds2(text, payload.finalUrl);
        if (text.startsWith("<")) return parseOpds1(payload.bytes, payload.finalUrl);
        throw new IllegalArgumentException("The server did not return an OPDS 1 or OPDS 2 feed.");
    }

    private JSONObject parseOpds2(String text, String baseUrl) throws Exception {
        JSONObject feed = new JSONObject(text);
        JSONObject page = emptyPage(feed.optJSONObject("metadata") == null ? hostName(baseUrl) : feed.optJSONObject("metadata").optString("title", hostName(baseUrl)), baseUrl);
        JSONArray entries = page.getJSONArray("entries");
        JSONArray publications = feed.optJSONArray("publications");
        if (publications != null) {
            for (int index = 0; index < publications.length(); index++) {
                JSONObject publication = publications.optJSONObject(index);
                if (publication != null) entries.put(opds2Publication(publication, baseUrl));
            }
        }
        JSONArray navigation = feed.optJSONArray("navigation");
        if (navigation != null) {
            for (int index = 0; index < navigation.length(); index++) {
                JSONObject link = navigation.optJSONObject(index);
                if (link != null) page.getJSONArray("navigation").put(normalizedNavigation(link, baseUrl));
            }
        }
        JSONArray links = feed.optJSONArray("links");
        if (links != null) {
            for (int index = 0; index < links.length(); index++) {
                JSONObject link = links.optJSONObject(index);
                if (link != null && hasRel(link.opt("rel"), "next")) {
                    page.put("nextUrl", resolveUrl(baseUrl, link.optString("href")));
                    break;
                }
            }
        }
        return page;
    }

    private JSONObject opds2Publication(JSONObject source, String baseUrl) throws Exception {
        JSONObject metadata = source.optJSONObject("metadata");
        if (metadata == null) metadata = new JSONObject();
        JSONObject result = new JSONObject();
        result.put("id", metadata.optString("identifier", UUID.randomUUID().toString()));
        result.put("title", metadata.optString("title", "Untitled publication"));
        result.put("authors", contributorNames(metadata.opt("author")));
        result.put("summary", textValue(metadata.opt("description")));
        String cover = null;
        String acquisition = null;
        String mediaType = null;
        String acquisitionType = "unknown";
        JSONArray links = source.optJSONArray("links");
        if (links != null) {
            for (int index = 0; index < links.length(); index++) {
                JSONObject link = links.optJSONObject(index);
                if (link == null) continue;
                Object rel = link.opt("rel");
                if (cover == null && (hasRel(rel, "cover") || hasRel(rel, "image") || hasRel(rel, "thumbnail"))) {
                    cover = resolveUrl(baseUrl, link.optString("href"));
                }
                String relation = rel == null ? "" : rel.toString();
                if (acquisition == null && relation.contains("acquisition")) {
                    String candidate = resolveUrl(baseUrl, link.optString("href"));
                    if (isSafeHttpsUrl(candidate)) {
                        acquisition = candidate;
                        mediaType = link.optString("type", null);
                        acquisitionType = acquisitionType(relation);
                    }
                }
            }
        }
        result.put("coverUrl", cover == null ? JSONObject.NULL : cover);
        result.put("acquisitionUrl", acquisition == null ? JSONObject.NULL : acquisition);
        result.put("mediaType", mediaType == null ? JSONObject.NULL : mediaType);
        result.put("format", mediaType == null ? JSONObject.NULL : formatFor(mediaType, acquisition));
        result.put("acquisitionType", acquisitionType);
        return result;
    }

    private JSONObject parseOpds1(byte[] bytes, String baseUrl) throws Exception {
        JSONObject page = emptyPage(hostName(baseUrl), baseUrl);
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new ByteArrayInputStream(bytes), "UTF-8");
        JSONObject entry = null;
        JSONArray authors = null;
        String currentAuthor = null;
        int authorDepth = -1;
        int entryDepth = -1;
        boolean feedTitleSet = false;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            String name = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("entry".equals(name)) {
                    entry = new JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("title", "Untitled publication")
                        .put("authors", new JSONArray())
                        .put("summary", JSONObject.NULL)
                        .put("coverUrl", JSONObject.NULL)
                        .put("acquisitionUrl", JSONObject.NULL)
                        .put("mediaType", JSONObject.NULL)
                        .put("format", JSONObject.NULL)
                        .put("acquisitionType", "unknown");
                    authors = entry.getJSONArray("authors");
                    entryDepth = parser.getDepth();
                } else if ("author".equals(name)) {
                    currentAuthor = null;
                    authorDepth = parser.getDepth();
                } else if ("title".equals(name)) {
                    String value = parser.nextText().trim();
                    if (entry != null) entry.put("title", value);
                    else if (!feedTitleSet) {
                        page.put("title", value);
                        feedTitleSet = true;
                    }
                } else if ("id".equals(name) && entry != null) {
                    entry.put("id", parser.nextText().trim());
                } else if ("name".equals(name) && authorDepth > 0) {
                    currentAuthor = parser.nextText().trim();
                    if (entry != null && authors != null && !currentAuthor.isEmpty()) authors.put(currentAuthor);
                } else if (("summary".equals(name) || "content".equals(name)) && entry != null) {
                    entry.put("summary", readElementText(parser));
                } else if ("link".equals(name)) {
                    String rel = attribute(parser, "rel");
                    String href = resolveUrl(baseUrl, attribute(parser, "href"));
                    String type = attribute(parser, "type");
                    String title = attribute(parser, "title");
                    if (entry == null) {
                        if (hasRel(rel, "next")) page.put("nextUrl", href);
                    } else if (rel.contains("image") && !href.startsWith("data:")) {
                        if (entry.isNull("coverUrl") || rel.contains("thumbnail")) entry.put("coverUrl", href);
                    } else if (rel.contains("acquisition") && isSafeHttpsUrl(href)) {
                        if (entry.isNull("acquisitionUrl") || isPreferredMediaType(type)) {
                            entry.put("acquisitionUrl", href);
                            entry.put("mediaType", type.isEmpty() ? JSONObject.NULL : type);
                            entry.put("format", formatFor(type, href));
                            entry.put("acquisitionType", acquisitionType(rel));
                        }
                    } else if (hasRel(rel, "subsection") || (type.contains("opds-catalog") && !hasRel(rel, "self"))) {
                        entry.put("navigationUrl", href);
                        entry.put("navigationRel", rel);
                        if (!title.isEmpty() && "Untitled publication".equals(entry.optString("title"))) entry.put("title", title);
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("author".equals(name) && parser.getDepth() == authorDepth) {
                    currentAuthor = null;
                    authorDepth = -1;
                } else if ("entry".equals(name) && entry != null && parser.getDepth() == entryDepth) {
                    if (entry.has("navigationUrl") && entry.isNull("acquisitionUrl")) {
                        page.getJSONArray("navigation").put(new JSONObject()
                            .put("title", entry.optString("title", "Catalog section"))
                            .put("url", entry.optString("navigationUrl"))
                            .put("rel", entry.optString("navigationRel", "subsection")));
                    } else {
                        entry.remove("navigationUrl");
                        entry.remove("navigationRel");
                        page.getJSONArray("entries").put(entry);
                    }
                    entry = null;
                    authors = null;
                    entryDepth = -1;
                }
            }
        }
        return page;
    }

    private JSONObject normalizedNavigation(JSONObject link, String baseUrl) throws JSONException {
        return new JSONObject()
            .put("title", link.optString("title", "Catalog section"))
            .put("url", resolveUrl(baseUrl, link.optString("href")))
            .put("rel", link.opt("rel") == null ? "subsection" : link.opt("rel").toString());
    }

    private JSONObject emptyPage(String title, String url) throws JSONException {
        return new JSONObject()
            .put("title", title)
            .put("url", url)
            .put("entries", new JSONArray())
            .put("navigation", new JSONArray())
            .put("nextUrl", JSONObject.NULL);
    }

    private JSONArray publicPublications(JSONArray stored) {
        JSONArray result = new JSONArray();
        for (int index = 0; index < stored.length(); index++) {
            JSONObject item = stored.optJSONObject(index);
            if (item != null) result.put(publicPublication(item));
        }
        return result;
    }

    private JSONObject publicPublication(JSONObject stored) {
        try {
            JSONObject result = new JSONObject(stored.toString());
            result.remove("fileName");
            result.remove("locator");
            return result;
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private HttpPayload fetchBytes(String requestedUrl, long maxBytes) throws Exception {
        String current = requestedUrl;
        for (int redirect = 0; redirect < 6; redirect++) {
            if (!isSafeHttpsUrl(current)) throw new IllegalArgumentException("Only HTTPS catalog URLs are allowed.");
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setConnectTimeout(NETWORK_TIMEOUT_MS);
            connection.setReadTimeout(NETWORK_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/opds+json, application/atom+xml;profile=opds-catalog, application/atom+xml, application/json;q=0.9");
            connection.setRequestProperty("User-Agent", USER_AGENT_OPDS);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                current = resolveUrl(current, connection.getHeaderField("Location"));
                connection.disconnect();
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IllegalArgumentException("Catalog server returned HTTP " + status + ".");
            }
            long declared = connection.getContentLengthLong();
            if (declared > maxBytes) {
                connection.disconnect();
                throw new IllegalArgumentException("The catalog response is too large.");
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) throw new IllegalArgumentException("The catalog response is too large.");
                    output.write(buffer, 0, count);
                }
                return new HttpPayload(output.toByteArray(), current);
            } finally {
                connection.disconnect();
            }
        }
        throw new IllegalArgumentException("The server redirected too many times.");
    }

    private Download download(String requestedUrl, long maxBytes) throws Exception {
        String current = requestedUrl;
        for (int redirect = 0; redirect < 6; redirect++) {
            if (!isSafeHttpsUrl(current)) throw new IllegalArgumentException("Only HTTPS publication URLs are allowed.");
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setConnectTimeout(NETWORK_TIMEOUT_MS);
            connection.setReadTimeout(NETWORK_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT_DOWNLOAD);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                current = resolveUrl(current, connection.getHeaderField("Location"));
                connection.disconnect();
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IllegalArgumentException("Publication server returned HTTP " + status + ".");
            }
            long declared = connection.getContentLengthLong();
            if (declared > maxBytes) {
                connection.disconnect();
                throw new IllegalArgumentException("The publication is larger than the 1 GB device limit.");
            }
            File temp = File.createTempFile("publication-", ".download", getContext().getCacheDir());
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                copyLimited(input, temp, maxBytes);
                return new Download(temp, current, cleanContentType(connection.getContentType()));
            } catch (Exception error) {
                temp.delete();
                throw error;
            } finally {
                connection.disconnect();
            }
        }
        throw new IllegalArgumentException("The server redirected too many times.");
    }

    private static void copyLimited(InputStream input, File destination, long limit) throws Exception {
        try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IllegalArgumentException("The selected publication is larger than the 1 GB device limit.");
                output.write(buffer, 0, count);
            }
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContext().getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (RuntimeException ignored) {
            // Fall back to the URI path below.
        }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.trim().isEmpty() ? "Imported publication" : segment;
    }

    private static boolean isSafeHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().trim().isEmpty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String resolveUrl(String base, String relative) {
        if (relative == null || relative.trim().isEmpty()) return "";
        try {
            return URI.create(base).resolve(relative.trim()).toString();
        } catch (RuntimeException ignored) {
            return relative.trim();
        }
    }

    private static boolean hasRel(Object rel, String target) {
        if (rel instanceof JSONArray) {
            JSONArray values = (JSONArray) rel;
            for (int index = 0; index < values.length(); index++) if (target.equalsIgnoreCase(values.optString(index))) return true;
            return false;
        }
        if (rel == null) return false;
        String[] values = rel.toString().split("\\s+");
        for (String value : values) if (target.equalsIgnoreCase(value) || value.endsWith("/" + target)) return true;
        return false;
    }

    private static JSONArray contributorNames(Object value) {
        JSONArray result = new JSONArray();
        if (value instanceof JSONArray) {
            JSONArray values = (JSONArray) value;
            for (int index = 0; index < values.length(); index++) appendContributor(result, values.opt(index));
        } else {
            appendContributor(result, value);
        }
        return result;
    }

    private static void appendContributor(JSONArray result, Object value) {
        if (value instanceof JSONObject) {
            String name = ((JSONObject) value).optString("name", "").trim();
            if (!name.isEmpty()) result.put(name);
        } else if (value != null && value != JSONObject.NULL && !value.toString().trim().isEmpty()) {
            result.put(value.toString().trim());
        }
    }

    private static String textValue(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return ((JSONObject) value).optString("value", null);
        return value.toString();
    }

    private static String attribute(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        return value == null ? "" : value.trim();
    }

    /**
     * Atom summary/content elements may contain XHTML children. XmlPullParser's
     * nextText() rejects nested tags, so collect descendant text until the
     * matching end tag and normalize XML formatting whitespace.
     */
    private static String readElementText(XmlPullParser parser) throws Exception {
        int remainingDepth = 1;
        StringBuilder value = new StringBuilder();
        while (remainingDepth > 0) {
            int event = parser.next();
            if (event == XmlPullParser.START_TAG) {
                remainingDepth++;
            } else if (event == XmlPullParser.END_TAG) {
                remainingDepth--;
            } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF) {
                String text = parser.getText();
                if (text != null && !text.trim().isEmpty()) value.append(text).append(' ');
            }
        }
        return value.toString().trim().replaceAll("\\s+", " ");
    }

    private static String acquisitionType(String rel) {
        String value = rel.toLowerCase(Locale.US);
        if (value.contains("borrow")) return "borrow";
        if (value.contains("buy")) return "buy";
        if (value.contains("open-access")) return "open-access";
        if (value.contains("acquisition")) return "download";
        return "unknown";
    }

    private static boolean isPreferredMediaType(String mediaType) {
        String value = mediaType == null ? "" : mediaType.toLowerCase(Locale.US);
        return value.contains("epub") || value.contains("pdf") || value.contains("audiobook") || value.startsWith("audio/");
    }

    private static String cleanContentType(String value) {
        if (value == null) return null;
        int semicolon = value.indexOf(';');
        return (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.US);
    }

    private static String mediaTypeForName(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.US);
        if (lower.endsWith(".epub")) return "application/epub+zip";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".audiobook") || lower.endsWith(".lcpa")) return "application/audiobook+zip";
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".m4b")) return "audio/mp4";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    private static boolean isLcpLicense(String mediaType, String value) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.US);
        String name = value == null ? "" : value.toLowerCase(Locale.US);
        return type.contains("lcp.license") || name.endsWith(".lcpl");
    }

    private static String formatFor(String mediaType, String value) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.US);
        String name = value == null ? "" : value.toLowerCase(Locale.US);
        if (type.contains("pdf") || name.contains(".pdf")) return "pdf";
        if (type.contains("audiobook") || name.contains(".audiobook") || name.contains(".lcpa")) return "audiobook";
        if (type.startsWith("audio/") || name.contains(".mp3") || name.contains(".m4a") || name.contains(".m4b") || name.contains(".aac")) return "audio";
        return "epub";
    }

    private static String extensionFor(String format, String originalName) {
        if ("pdf".equals(format)) return ".pdf";
        if ("audiobook".equals(format)) return ".audiobook";
        if ("audio".equals(format)) {
            String lower = originalName == null ? "" : originalName.toLowerCase(Locale.US);
            if (lower.contains(".m4b")) return ".m4b";
            if (lower.contains(".m4a")) return ".m4a";
            if (lower.contains(".aac")) return ".aac";
            return ".mp3";
        }
        return ".epub";
    }

    private static String stripExtension(String value) {
        if (value == null) return "Imported publication";
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static String hostName(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "OPDS catalog" : host;
        } catch (RuntimeException ignored) {
            return "OPDS catalog";
        }
    }

    private static String nullable(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String userMessage(Exception error, String fallback) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? fallback : message;
    }

    private static final class HttpPayload {
        final byte[] bytes;
        final String finalUrl;
        HttpPayload(byte[] bytes, String finalUrl) {
            this.bytes = bytes;
            this.finalUrl = finalUrl;
        }
    }

    private static final class Download {
        final File file;
        final String finalUrl;
        final String contentType;
        Download(File file, String finalUrl, String contentType) {
            this.file = file;
            this.finalUrl = finalUrl;
            this.contentType = contentType;
        }
    }
}
