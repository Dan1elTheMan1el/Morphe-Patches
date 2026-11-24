package app.danielthemaniel.patches.googlemaps.extension;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Animates the custom navigation vehicle by flipping between posed copies of it.
 *
 * Maps' map engine draws a glTF vehicle (CustomChevronGltfModel, "the model") as a static mesh. When Maps
 * builds the model, {@link #created} builds one more model per bundled frame with the same constructor,
 * so each frame is its own entity in the engine. The model talks to its entity through one field, a
 * (place: c(), show: k() -> a.d(), hide: e() -> a.b(), destroy: d() -> a.c()). On every placement
 * ({@link #frame}) the field is pointed at the entity of the frame due now and the previous one is
 * hidden; Maps' own placement and show calls then land on that frame.
 *
 * Clips: the renderer picks one of drive, idle, turnleft, turnright, arrive, start and calls j(name),
 * but only among the names the model lists in b(), which is empty for glTF. {@link #clips} lists
 * the bundled clips and {@link #clip} switches to the one asked for.
 *
 * Method and field names (a, b, c, d, e, j, k) are the same in Maps 26.44 and 26.47.
 */
@SuppressWarnings("unused")
public final class Flipbook {
    private Flipbook() {}

    private static final String TAG = "CustomNavatar";
    private static java.io.File frames() {
        java.io.File dir = CustomNavatar.dir();
        return dir == null ? null : new java.io.File(dir, "frames");
    }

    private static final class Clip {
        final Object[] frames; // models
        final float fps;
        final boolean loop;

        Clip(Object[] frames, float fps, boolean loop) {
            this.frames = frames;
            this.fps = fps;
            this.loop = loop;
        }
    }

    private static final class Book {
        final Map<String, Clip> clips;
        final Object originalEntity;
        String clip;
        long clipStart;
        Object shown; // frame model whose entity the main model currently points at; null = original

        Book(Map<String, Clip> clips, Object originalEntity, String clip) {
            this.clips = clips;
            this.originalEntity = originalEntity;
            this.clip = clip;
            this.clipStart = SystemClock.uptimeMillis();
        }
    }

    private static final Map<Object, Book> books = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile List<String> clipNames = Collections.emptyList();

    private static Context context() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] read(java.io.File file) throws Exception {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static Field entityField(Object model) throws Exception {
        Field f = model.getClass().getField("a");
        f.setAccessible(true);
        return f;
    }

    private static void call(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            m.invoke(target);
        } catch (Throwable t) {
            // Entities are interfaces; look on the interfaces' methods too.
            for (Class<?> i : target.getClass().getInterfaces()) {
                try {
                    Method m = i.getMethod(name);
                    m.invoke(target);
                    return;
                } catch (Throwable ignored) {
                }
            }
            Log.w(TAG, "call " + name + " on " + target.getClass().getName() + " failed", t);
        }
    }

    /**
     * Right after Maps builds the model: model, then its constructor arguments
     * (engine, model bytes, texture, unlit blend factor, picker registry, three flags).
     */
    public static void created(Object model, Object engine, Object bytes, android.graphics.Bitmap texture,
                               float blend, Object registry, boolean z1, boolean z2, boolean z3) {
        if (model == null || bytes == null || !CustomNavatar.isDittoLoading() || !CustomNavatar.ready()) return;
        java.io.File dir = frames();
        if (dir == null) return;
        java.util.Properties props = new java.util.Properties();
        try (InputStream in = new java.io.FileInputStream(new java.io.File(dir, "frames.properties"))) {
            props.load(in);
        } catch (Throwable t) {
            return; // no flipbook bundled: the model stays a still
        }
        long t0 = SystemClock.uptimeMillis();
        try {
            Constructor<?> ctor = null;
            for (Constructor<?> c : model.getClass().getDeclaredConstructors()) {
                if (c.getParameterTypes().length == 8) ctor = c;
            }
            if (ctor == null) throw new IllegalStateException("no 8-arg constructor");
            ctor.setAccessible(true);
            Class<?> bytesType = ctor.getParameterTypes()[1];
            Method fromBytes = null;
            for (Method m : bytesType.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == bytesType
                    && Arrays.equals(m.getParameterTypes(), new Class<?>[]{byte[].class})) {
                    fromBytes = m;
                    break;
                }
            }
            if (fromBytes == null) throw new IllegalStateException("no " + bytesType.getName() + "(byte[])");
            fromBytes.setAccessible(true);

            Map<String, Clip> clips = new LinkedHashMap<>();
            int total = 0;
            for (String name : props.stringPropertyNames()) {
                String[] v = props.getProperty(name).split(",");
                int count = Integer.parseInt(v[0].trim());
                float fps = v.length > 1 ? Float.parseFloat(v[1].trim()) : 10f;
                boolean loop = v.length < 3 || !"once".equals(v[2].trim());
                Object[] frames = new Object[count];
                for (int i = 0; i < count; i++) {
                    Object frameBytes = fromBytes.invoke(null, (Object) read(new java.io.File(dir, name + "/" + i + ".glb")));
                    Object frame = ctor.newInstance(engine, frameBytes, texture, blend, registry, z1, z2, z3);
                    call(frame, "e"); // hidden until it is due
                    frames[i] = frame;
                }
                clips.put(name, new Clip(frames, fps, loop));
                total += count;
            }
            if (clips.isEmpty()) return;
            String first = clips.containsKey("drive") ? "drive" : clips.keySet().iterator().next();
            books.put(model, new Book(clips, entityField(model).get(model), first));
            clipNames = Collections.unmodifiableList(new ArrayList<>(clips.keySet()));
            Log.i(TAG, "flipbook: " + total + " frames in " + clips.keySet() + " built in "
                + (SystemClock.uptimeMillis() - t0) + " ms");
        } catch (Throwable t) {
            Log.w(TAG, "flipbook failed", t);
        }
    }

    /** b(): the clip names the model offers; the renderer only asks for clips listed here. */
    public static Collection<?> clips(Object model) {
        return books.containsKey(model) ? clipNames : Collections.emptyList();
    }

    /** j(name): the renderer picks a clip (repeatedly; only a change restarts it). */
    public static void clip(Object model, String name) {
        Book book = books.get(model);
        if (book == null || name == null) return;
        synchronized (book) {
            String pick = book.clips.containsKey(name) ? name : null;
            if (pick == null) {
                for (String c : book.clips.keySet()) {
                    if (name.contains(c) || c.contains(name)) {
                        pick = c;
                        break;
                    }
                }
            }
            if (pick == null || pick.equals(book.clip)) return;
            book.clip = pick;
            book.clipStart = SystemClock.uptimeMillis();
        }
    }

    /** Start of c(): point the model at the frame due now. */
    public static void frame(Object model) {
        Book book = books.get(model);
        if (book == null) return;
        synchronized (book) {
            Clip clip = book.clips.get(book.clip);
            if (clip == null) return;
            long elapsed = SystemClock.uptimeMillis() - book.clipStart;
            int n = clip.frames.length;
            int index = (int) (elapsed * clip.fps / 1000f);
            index = clip.loop ? index % n : Math.min(index, n - 1);
            Object due = clip.frames[index];
            if (due == book.shown) return;
            try {
                if (book.shown == null) {
                    call(model, "e"); // hide the original entity for good
                } else {
                    call(book.shown, "e");
                }
                entityField(model).set(model, entityField(due).get(due));
                book.shown = due;
            } catch (Throwable t) {
                Log.w(TAG, "flipbook frame failed", t);
            }
        }
    }

    /** Start of d(): the model is being destroyed; destroy the frames and the original entity too. */
    public static void destroy(Object model) {
        Book book = books.remove(model);
        if (book == null) return;
        synchronized (book) {
            for (Clip clip : book.clips.values()) {
                for (Object frame : clip.frames) {
                    if (frame != book.shown) call(frame, "d"); // the shown one goes with the model
                }
            }
            if (book.shown != null && book.originalEntity != null) call(book.originalEntity, "c");
        }
    }
}
