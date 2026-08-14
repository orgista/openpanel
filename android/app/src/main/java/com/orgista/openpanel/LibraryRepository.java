package com.orgista.openpanel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.UUID;

/** App-private metadata and files for OpenPanel's offline library. */
public final class LibraryRepository {
    private static final String PREFS = "openpanel.library.v1";
    private static final String PUBLICATIONS = "publications";
    private static final String CATALOGS = "catalogs";
    private final Context context;
    private final SharedPreferences preferences;

    public LibraryRepository(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        publicationsDirectory().mkdirs();
    }

    public File publicationsDirectory() {
        return new File(context.getFilesDir(), "openpanel-library");
    }

    public synchronized JSONArray publications() {
        return readArray(PUBLICATIONS);
    }

    public synchronized JSONObject publication(String id) {
        JSONArray items = readArray(PUBLICATIONS);
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }

    public synchronized JSONObject addPublication(
        File file,
        String title,
        JSONArray authors,
        String mediaType,
        String format,
        String coverUrl,
        String sourceUrl
    ) throws JSONException {
        JSONObject publication = new JSONObject();
        publication.put("id", UUID.randomUUID().toString());
        publication.put("title", title == null || title.trim().isEmpty() ? "Untitled publication" : title.trim());
        publication.put("authors", authors == null ? new JSONArray() : authors);
        publication.put("narrator", JSONObject.NULL);
        publication.put("format", format);
        publication.put("mediaType", mediaType);
        publication.put("coverUrl", coverUrl == null ? JSONObject.NULL : coverUrl);
        publication.put("sourceUrl", sourceUrl == null ? JSONObject.NULL : sourceUrl);
        publication.put("local", true);
        publication.put("bytes", file.length());
        publication.put("addedAt", System.currentTimeMillis());
        publication.put("lastOpenedAt", JSONObject.NULL);
        publication.put("progression", 0.0);
        publication.put("fileName", file.getName());

        JSONArray items = readArray(PUBLICATIONS);
        JSONArray next = new JSONArray();
        next.put(publication);
        for (int index = 0; index < items.length(); index++) next.put(items.opt(index));
        writeArray(PUBLICATIONS, next);
        return publication;
    }

    public synchronized boolean removePublication(String id) {
        JSONArray items = readArray(PUBLICATIONS);
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item != null && id.equals(item.optString("id"))) {
                String fileName = item.optString("fileName", "");
                if (!fileName.isEmpty()) new File(publicationsDirectory(), fileName).delete();
                removed = true;
            } else if (item != null) {
                next.put(item);
            }
        }
        if (removed) writeArray(PUBLICATIONS, next);
        return removed;
    }

    public synchronized void recordOpened(String id) {
        updatePublication(id, item -> item.put("lastOpenedAt", System.currentTimeMillis()));
    }

    public synchronized void saveProgress(String id, String locatorJson, double progression) {
        updatePublication(id, item -> {
            item.put("locator", locatorJson == null ? JSONObject.NULL : locatorJson);
            item.put("progression", Math.max(0.0, Math.min(1.0, progression)));
            item.put("lastOpenedAt", System.currentTimeMillis());
        });
    }

    public File publicationFile(JSONObject publication) {
        if (publication == null) return null;
        String fileName = publication.optString("fileName", "");
        if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\")) return null;
        File file = new File(publicationsDirectory(), fileName);
        return file.isFile() ? file : null;
    }

    public synchronized JSONArray catalogs() {
        JSONArray stored = readArray(CATALOGS);
        JSONArray result = new JSONArray();
        for (int index = 0; index < stored.length(); index++) {
            JSONObject item = stored.optJSONObject(index);
            if (item != null) result.put(item);
        }
        return result;
    }

    public synchronized JSONObject addCatalog(String title, String url) throws JSONException {
        JSONArray stored = readArray(CATALOGS);
        JSONArray next = new JSONArray();
        for (int index = 0; index < stored.length(); index++) {
            JSONObject item = stored.optJSONObject(index);
            if (item != null && !url.equals(item.optString("url"))) next.put(item);
        }
        JSONObject catalog = new JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("title", title)
            .put("url", url)
            .put("builtIn", false);
        next.put(catalog);
        writeArray(CATALOGS, next);
        return catalog;
    }

    public synchronized boolean removeCatalog(String id) {
        JSONArray stored = readArray(CATALOGS);
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < stored.length(); index++) {
            JSONObject item = stored.optJSONObject(index);
            if (item != null && id.equals(item.optString("id"))) removed = true;
            else if (item != null) next.put(item);
        }
        if (removed) writeArray(CATALOGS, next);
        return removed;
    }

    private interface PublicationMutation {
        void apply(JSONObject publication) throws JSONException;
    }

    private void updatePublication(String id, PublicationMutation mutation) {
        JSONArray items = readArray(PUBLICATIONS);
        boolean changed = false;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null || !id.equals(item.optString("id"))) continue;
            try {
                mutation.apply(item);
                changed = true;
            } catch (JSONException ignored) {
                return;
            }
            break;
        }
        if (changed) writeArray(PUBLICATIONS, items);
    }

    private JSONArray readArray(String key) {
        String value = preferences.getString(key, "[]");
        try {
            return new JSONArray(value == null ? "[]" : value);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private void writeArray(String key, JSONArray value) {
        preferences.edit().putString(key, value.toString()).apply();
    }
}
