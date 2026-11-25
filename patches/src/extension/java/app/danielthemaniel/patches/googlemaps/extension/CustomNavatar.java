package app.danielthemaniel.patches.googlemaps.extension;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Swaps Maps' 3D navigation vehicle ("navatar") for an animated Ditto.
 *
 * Maps 26.4x draws vehicles as glTF (.glb) through the current map engine, which shows the raw mesh and
 * plays no animations. Ditto is built on the device by {@link DittoBuilder} from the Pokémon X/Y Ditto
 * archive the user downloads once ({@link DittoSetup}), into filesDir/dtm_navatar:
 * model.glb, texture.png, texture_dark.png and frames/ (the flipbook, see {@link Flipbook}).
 *
 * Ditto is added to Maps' vehicle list as his own entry ({@link #manifest}); Maps "downloads" his files
 * through {@link #serve}, shows his picker icon through {@link #icon}, and the flipbook only runs when the
 * loaded vehicle is Ditto ({@link #loading}). Until Ditto is built every hook passes Maps' values through.
 */
@SuppressWarnings("unused")
public final class CustomNavatar {
    private CustomNavatar() {}

    static final String TAG = "CustomNavatar";
    private static final String DIR = "dtm_navatar";
    /** Bump when DittoBuilder's output changes, so existing installs rebuild (DittoSetup asks again). */
    static final String VERSION = "2";
    /** Vehicle name in the manifest; also the folder name in Ditto's (fake) download URLs. */
    static final String NAME = "dtm_ditto";
    /** Google's gltf/car.glb has scale 1900 and Ditto matches its size; he is shown 1.8x that. */
    private static final String MANIFEST_SCALE = "3420.0";
    private static final String SOURCE = "dtm_ditto_source.zip";

    private static volatile File dir;
    private static volatile long lastCheck;

    static Context context() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static File target(Context ctx) {
        return new File(ctx.getFilesDir(), DIR);
    }

    /** The folder with Ditto, once {@link #ready} is true. */
    static File dir() {
        return dir;
    }

    static boolean ready(boolean ignored) {
        return ready();
    }

    /** Whether Ditto has been built (checked on disk at most every 2 s until it has). */
    static boolean ready() {
        if (dir != null) return true;
        long now = SystemClock.uptimeMillis();
        if (now - lastCheck < 2000) return false;
        lastCheck = now;
        Context ctx = context();
        if (ctx == null) return false;
        File out = target(ctx);
        if (built(out)) {
            dir = out;
            Log.i(TAG, "Ditto ready (" + new File(out, "model.glb").length() + " bytes)");
        }
        return dir != null;
    }

    static boolean built(File out) {
        try (InputStream in = new FileInputStream(new File(out, "ditto.version"))) {
            byte[] b = new byte[16];
            int n = in.read(b);
            return n > 0 && new String(b, 0, n, "UTF-8").trim().equals(VERSION)
                && new File(out, "model.glb").isFile() && new File(out, "frames/frames.properties").isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Builds Ditto from the X/Y archive into filesDir/dtm_navatar. Call off the main thread. */
    static String install(Context ctx, byte[] archive) throws Exception {
        long t0 = SystemClock.uptimeMillis();
        File out = target(ctx);
        try (OutputStream o = new FileOutputStream(new File(ctx.getFilesDir(), SOURCE))) {
            o.write(archive); // kept so a newer builder can rebuild without asking again
        }
        File tmp = new File(ctx.getFilesDir(), DIR + ".building");
        deleteTree(tmp);
        String summary = DittoBuilder.build(new ByteArrayInputStream(archive), tmp, new AndroidImages());
        try (OutputStream o = new FileOutputStream(new File(tmp, "ditto.version"))) {
            o.write(VERSION.getBytes("UTF-8"));
        }
        deleteTree(out);
        if (!tmp.renameTo(out)) throw new IllegalStateException("rename " + tmp + " -> " + out);
        lastCheck = 0;
        ready();
        String result = summary + " in " + (SystemClock.uptimeMillis() - t0) + " ms";
        Log.i(TAG, "built Ditto: " + result);
        return result;
    }

    /** Bitmap-backed image codec for DittoBuilder. */
    private static final class AndroidImages implements DittoBuilder.Images {
        @Override
        public int[] decode(byte[] png, int[] sizeOut) throws java.io.IOException {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
            if (b == null) throw new java.io.IOException("undecodable image");
            sizeOut[0] = b.getWidth();
            sizeOut[1] = b.getHeight();
            int[] px = new int[sizeOut[0] * sizeOut[1]];
            b.getPixels(px, 0, sizeOut[0], 0, 0, sizeOut[0], sizeOut[1]);
            b.recycle();
            return px;
        }

        @Override
        public byte[] encodePng(int[] argb, int width, int height) {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(argb, width, height,
                android.graphics.Bitmap.Config.ARGB_8888);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
            b.recycle();
            return o.toByteArray();
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static volatile boolean rebuilding;

    /** Rebuilds from the archive kept by an earlier install (after a builder update). Off the main thread. */
    static boolean rebuildFromCache(Context ctx) {
        File src = new File(ctx.getFilesDir(), SOURCE);
        if (!src.isFile() || rebuilding) return src.isFile();
        rebuilding = true;
        new Thread(() -> {
            try (InputStream in = new FileInputStream(src)) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream((int) src.length());
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                install(ctx.getApplicationContext(), o.toByteArray());
            } catch (Throwable t) {
                Log.w(TAG, "Ditto rebuild failed", t);
            } finally {
                rebuilding = false;
            }
        }, "DittoRebuild").start();
        return true;
    }

    // ---- Hooks ----

    /**
     * Vehicle manifest JSON, before Maps parses it: adds Ditto as one more vehicle once he is built. His
     * files and icon point at a made-up folder under Maps' download base URL, which {@link #serve} and
     * {@link #icon} answer from filesDir/dtm_navatar.
     */
    public static String manifest(String json) {
        Log.i(TAG, "vehicle list read (" + (json == null ? "null" : json.length() + " chars") + ")");
        if (json == null) return json;
        // Unthrottled: Maps reads its vehicle list once, often right after another ready() check.
        if (dir == null) lastCheck = 0;
        if (!ready()) {
            Log.i(TAG, "vehicle list read before Ditto was built");
            return json;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray models = root.optJSONArray("models");
            if (models == null || models.length() == 0) {
                Log.i(TAG, "vehicle list has no models: " + json.substring(0, Math.min(200, json.length())));
                return json;
            }
            for (int i = 0; i < models.length(); i++) {
                if (NAME.equals(models.optJSONObject(i).optString("name"))) return json;
            }
            org.json.JSONObject ditto = new org.json.JSONObject()
                .put("name", NAME)
                .put("displayName", "Ditto")
                .put("filename", NAME + "/model.glb")
                .put("scale", MANIFEST_SCALE)
                .put("unlitBlendFactor", 0.5)
                .put("chevronVeType", 234882)
                .put("buttonVeType", 234930)
                .put("iconSuffixMap", new org.json.JSONObject().put("mdpi", ".png").put("xhdpi", "@2x.png").put("xxhdpi", "@3x.png"))
                .put("textureDir", NAME)
                .put("iconDir", NAME)
                .put("colors", new org.json.JSONArray().put(new org.json.JSONObject()
                    .put("name", "Ditto")
                    .put("colorID", 1)
                    .put("l10nID", 0)
                    .put("light", new org.json.JSONObject().put("color", "#B48CDC").put("texture", "texture.png").put("icon", "icon"))
                    .put("dark", new org.json.JSONObject().put("texture", "texture_dark.png"))));
            // The picker only lists vehicles of the route's travel mode. The list mixes modes (DRIVE,
            // TWO_WHEELER, BICYCLE), so Ditto is always a DRIVE vehicle, like the cars.
            ditto.put("travel_mode", "DRIVE");
            // First in the list, so the picker shows him right after the default blue arrow.
            org.json.JSONArray reordered = new org.json.JSONArray().put(ditto);
            for (int i = 0; i < models.length(); i++) reordered.put(models.get(i));
            root.put("models", reordered);
            Log.i(TAG, "added Ditto to a vehicle list of " + models.length());
            return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "manifest injection failed", t);
            return json;
        }
    }

    /**
     * Start of Maps' downloader for vehicle files (url, file name, folder): Ditto's files are copied from
     * filesDir/dtm_navatar instead. Returns true when served.
     */
    public static boolean serve(String url, String name, File folder) {
        if (url == null || name == null || folder == null || !url.contains("/" + NAME + "/") || !ready()) return false;
        String file = url.substring(url.lastIndexOf('/') + 1);
        File from = new File(dir, file);
        if (!from.isFile()) return false;
        try {
            try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(new File(folder, name))) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Log.i(TAG, "served " + file + " as " + name);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "serve " + file + " failed", t);
            return false;
        }
    }

    /** Picker icon URL of a vehicle: Ditto's points at the icon DittoBuilder rendered. */
    private static final java.util.Set<String> loggedIcons = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static String icon(String url) {
        if (url != null && loggedIcons.size() < 40 && loggedIcons.add(url)) Log.i(TAG, "icon lookup " + url);
        if (url == null || !url.contains("/" + NAME + "/") || !ready()) return url;
        String file = url.substring(url.lastIndexOf('/') + 1); // icon.png, icon@2x.png, icon@3x.png
        File f = new File(dir, file);
        String out = f.isFile() ? android.net.Uri.fromFile(f).toString() : url;
        Log.i(TAG, "icon " + url + " -> " + out + " (" + f.length() + " bytes)");
        return out;
    }

    private static final ThreadLocal<Boolean> dittoLoading = new ThreadLocal<>();

    /** Start of the vehicle loader: remembers whether the vehicle being loaded is Ditto. */
    public static void loading(Object descriptor) {
        boolean ditto = false;
        if (descriptor != null) {
            for (Class<?> c = descriptor.getClass(); c != null && c != Object.class && !ditto; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        if (NAME.equals(f.get(descriptor))) {
                            ditto = true;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        dittoLoading.set(ditto);
    }

    static boolean isDittoLoading() {
        Boolean b = dittoLoading.get();
        return b != null && b;
    }
}
